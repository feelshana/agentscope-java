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
    // spec149: one document per table (or a few) instead of a single long, truncated document.
    public static final int MAX_DOCUMENTS = 100;
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

    private static final Pattern DECLARED =
            Pattern.compile("^[\\s>*\\-#]*适用(?:模型|表)[^:：\\n]{0,8}[:：](.+)$", Pattern.MULTILINE);
    private static final Pattern QUOTED = Pattern.compile("`([\\p{L}\\p{N}_]+)`");
    private static final Pattern MODEL_NAME = Pattern.compile("[\\p{L}\\p{N}_]+");
    private static final int DECLARATION_LINES = 40;

    /**
     * Models a document declares in its opening lines ("适用模型：`a`、`b`", spec149). A document
     * without the line applies to the whole knowledge base.
     */
    public static List<String> declaredModels(String text) {
        if (text == null) return List.of();
        String head = String.join("\n", text.lines().limit(DECLARATION_LINES).toList());
        Set<String> out = new LinkedHashSet<>();
        var line = DECLARED.matcher(head);
        while (line.find()) {
            String value = line.group(1);
            // Backticked names are exact; otherwise names run to the end of the first sentence.
            var quoted = QUOTED.matcher(value);
            boolean any = false;
            while (quoted.find()) {
                out.add(quoted.group(1));
                any = true;
            }
            if (any) continue;
            var name = MODEL_NAME.matcher(value.split("[。；;]", 2)[0]);
            while (name.find()) out.add(name.group());
        }
        return List.copyOf(out);
    }

    /**
     * Whether a declared name means this model. An uploaded table's model is named {@code
     * ds_<owner>_<upload>_<original name>}; people write the original name they know (twice a
     * document said "hhweb_video_vip_more_dimension_m" for the "ds_..._" model and got no list).
     */
    public static boolean declares(String declared, String model) {
        if (declared == null || model == null) return false;
        if (declared.equalsIgnoreCase(model)) return true;
        return model.toLowerCase(java.util.Locale.ROOT)
                .matches(
                        "ds_[0-9a-f]{8}_[0-9a-f]{8}_"
                                + Pattern.quote(declared.toLowerCase(java.util.Locale.ROOT)));
    }

    /** Declared names, by document, that mean none of the given models. */
    public static java.util.Map<String, List<String>> unmatchedDeclarations(
            Path root, java.util.Collection<String> models) {
        java.util.Map<String, List<String>> out = new java.util.TreeMap<>();
        Path directory = root.resolve(DIRECTORY);
        for (String name : names(directory)) {
            List<String> unmatched =
                    declaredModels(read(directory.resolve(name))).stream()
                            .filter(d -> models.stream().noneMatch(m -> declares(d, m)))
                            .toList();
            if (!unmatched.isEmpty()) out.put(name, unmatched);
        }
        return out;
    }

    /** spec149 P7-3: the text of every document declaring a model, for checking a draft copies it. */
    public static String declaringText(Path root, String model) {
        StringBuilder out = new StringBuilder();
        Path directory = root.resolve(DIRECTORY);
        for (String name : names(directory)) {
            String text = read(directory.resolve(name));
            if (text != null && declaredModels(text).stream().anyMatch(m -> declares(m, model)))
                out.append(text).append('\n');
        }
        return out.toString();
    }

    /** Documents declaring a model, with a hash of their current content, by file name. */
    public static java.util.Map<String, String> documentsFor(Path root, String model) {
        java.util.Map<String, String> out = new java.util.TreeMap<>();
        Path directory = root.resolve(DIRECTORY);
        for (String name : names(directory)) {
            String text = read(directory.resolve(name));
            if (text == null) continue;
            if (declaredModels(text).stream().anyMatch(m -> declares(m, model)))
                out.put(name, CaliberSources.sha(text));
        }
        return out;
    }

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

    /** The converted text of one draft document. */
    public record DocumentText(String name, String content) {}

    /**
     * spec117: the draft text of one document, so the workbench can show the passages behind a
     * model's rules. Only names listed in the directory are readable (no path from the caller).
     */
    public DocumentText content(String userId, String groupId, String name) {
        groups.getGroup(userId, groupId);
        return workspace.withWorkspaceLock(
                groupId,
                () -> {
                    Path draft = workspace.workspaceRoot(groupId).resolve(DIRECTORY);
                    if (name == null || !names(draft).contains(name))
                        throw new DatasetException("文档不存在", 404);
                    return new DocumentText(name, read(draft.resolve(name)));
                });
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
