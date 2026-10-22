package io.agentscope.dataagent.dataset;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.springframework.stereotype.Service;

/**
 * Business documents of a knowledge base (spec092): several Markdown files under {@code
 * raw/docs/} of the draft workspace, published with the snapshot. Adding, replacing and deleting
 * are draft changes like any other; replaced versions are kept under {@code .platform/}. Only
 * users upload documents; the modeling agent reads them.
 */
@Service
public class BusinessDocumentService {
    public static final String DIRECTORY = "raw/docs";
    public static final int MAX_DOCUMENTS = 20;
    public static final int MAX_CHARS = 20000;
    private static final String HISTORY = ".platform/docs-history";
    private static final Pattern NAME = Pattern.compile("[\\p{L}\\p{N}_\\-. ]{1,80}");

    private final MdlWorkspaceService workspace;
    private final DatasetGroupService groups;

    public BusinessDocumentService(MdlWorkspaceService workspace, DatasetGroupService groups) {
        this.workspace = workspace;
        this.groups = groups;
    }

    /** ADDED since publish, MODIFIED (replaced), UNCHANGED, or DELETED in the draft. */
    public record Document(
            String name, String status, int chars, String updatedAt, int previousVersions) {}

    public static boolean hasDocuments(Path root) {
        Path directory = root.resolve(DIRECTORY);
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) return false;
        try (Stream<Path> files = Files.list(directory)) {
            return files.anyMatch(f -> f.getFileName().toString().endsWith(".md"));
        } catch (IOException e) {
            return false;
        }
    }

    public List<Document> list(String userId, String groupId) {
        groups.getGroup(userId, groupId);
        return workspace.withWorkspaceLock(
                groupId,
                () -> {
                    Path root = workspace.workspaceRoot(groupId);
                    Path draft = root.resolve(DIRECTORY);
                    Path published = root.getParent().resolve("published").resolve(DIRECTORY);
                    Set<String> names = new LinkedHashSet<>(names(draft));
                    names.addAll(names(published));
                    List<Document> out = new ArrayList<>();
                    for (String name : names) {
                        String now = read(draft.resolve(name));
                        String before = read(published.resolve(name));
                        String status =
                                now == null
                                        ? "DELETED"
                                        : before == null
                                                ? "ADDED"
                                                : before.equals(now) ? "UNCHANGED" : "MODIFIED";
                        out.add(
                                new Document(
                                        name,
                                        status,
                                        now == null ? 0 : now.length(),
                                        modified(now == null ? null : draft.resolve(name)),
                                        history(root, name)));
                    }
                    return out;
                });
    }

    /** Adds a document or replaces the draft version of one with the same name. */
    public Document put(String userId, String groupId, String fileName, String text) {
        groups.getGroup(userId, groupId);
        String name = fileName(fileName);
        if (text == null || text.isBlank()) throw new DatasetException("业务文档内容为空", 400);
        String content = text.length() > MAX_CHARS ? text.substring(0, MAX_CHARS) : text;
        return workspace.withWorkspaceLock(
                groupId,
                () -> {
                    Path root = workspace.workspaceRoot(groupId);
                    Path file = root.resolve(DIRECTORY).resolve(name);
                    try {
                        Files.createDirectories(file.getParent());
                        String previous = read(file);
                        if (previous == null && names(file.getParent()).size() >= MAX_DOCUMENTS)
                            throw new DatasetException("每个知识库最多 " + MAX_DOCUMENTS + " 份业务文档", 400);
                        if (previous != null && !previous.equals(content))
                            keepVersion(root, name, previous);
                        Files.writeString(file, content, StandardCharsets.UTF_8);
                    } catch (IOException e) {
                        throw new DatasetException("保存业务文档失败：" + e.getMessage(), e);
                    }
                    DraftChangeLog.record(
                            root, DIRECTORY + "/" + name, ModelingTurnPurpose.UPLOAD, userId);
                    return list(userId, groupId).stream()
                            .filter(d -> d.name().equals(name))
                            .findFirst()
                            .orElseThrow();
                });
    }

    public void delete(String userId, String groupId, String fileName) {
        groups.getGroup(userId, groupId);
        String name = fileName(fileName);
        workspace.withWorkspaceLock(
                groupId,
                () -> {
                    Path root = workspace.workspaceRoot(groupId);
                    Path file = root.resolve(DIRECTORY).resolve(name);
                    String previous = read(file);
                    if (previous == null) throw new DatasetException("业务文档不存在", 404);
                    try {
                        keepVersion(root, name, previous);
                        Files.delete(file);
                    } catch (IOException e) {
                        throw new DatasetException("删除业务文档失败：" + e.getMessage(), e);
                    }
                    DraftChangeLog.record(
                            root, DIRECTORY + "/" + name, ModelingTurnPurpose.UPLOAD, userId);
                    return null;
                });
    }

    /** Uploaded names become safe Markdown file names; the extension is always {@code .md}. */
    static String fileName(String original) {
        String base = original == null ? "" : original.replace('\\', '/');
        base = base.substring(base.lastIndexOf('/') + 1).replaceFirst("\\.[^.]*$", "").trim();
        if (base.isEmpty() || !NAME.matcher(base).matches() || base.startsWith("."))
            throw new DatasetException("文档名只允许中英文、数字、空格、下划线、横线和点，最长 80 个字符", 400);
        return base + ".md";
    }

    private static void keepVersion(Path root, String name, String content) throws IOException {
        Path directory = root.resolve(HISTORY).resolve(name);
        Files.createDirectories(directory);
        Files.writeString(
                directory.resolve(Instant.now().toString().replace(':', '-') + ".md"),
                content,
                StandardCharsets.UTF_8);
    }

    private static int history(Path root, String name) {
        Path directory = root.resolve(HISTORY).resolve(name);
        if (!Files.isDirectory(directory)) return 0;
        try (Stream<Path> files = Files.list(directory)) {
            return (int) files.count();
        } catch (IOException e) {
            return 0;
        }
    }

    private static List<String> names(Path directory) {
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) return List.of();
        try (Stream<Path> files = Files.list(directory)) {
            return files.filter(f -> Files.isRegularFile(f, LinkOption.NOFOLLOW_LINKS))
                    .map(f -> f.getFileName().toString())
                    .filter(n -> n.endsWith(".md"))
                    .sorted()
                    .toList();
        } catch (IOException e) {
            throw new DatasetException("读取业务文档目录失败：" + e.getMessage(), e);
        }
    }

    private static String read(Path file) {
        try {
            return Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)
                    ? Files.readString(file, StandardCharsets.UTF_8)
                    : null;
        } catch (IOException e) {
            throw new DatasetException("读取业务文档失败：" + e.getMessage(), e);
        }
    }

    private static String modified(Path file) {
        try {
            return file == null ? null : Files.getLastModifiedTime(file).toInstant().toString();
        } catch (IOException e) {
            return null;
        }
    }
}
