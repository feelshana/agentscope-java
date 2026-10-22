package io.agentscope.dataagent.dataset;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.stereotype.Service;

/**
 * Review of a knowledge base's draft before publication (ADR0111, spec092). The change list is
 * always the diff between the draft workspace and the published snapshot; {@link DraftChangeLog}
 * only adds who changed a file and why. Reverting restores the published version of one file;
 * discarding restores all of them.
 */
@Service
public class DraftReviewService {
    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

    private final DatasetGroupService groups;
    private final MdlWorkspaceService workspace;
    private final MdlPublishService publisher;
    private final ModelingActivity activity;

    public DraftReviewService(
            DatasetGroupService groups,
            MdlWorkspaceService workspace,
            MdlPublishService publisher,
            ModelingActivity activity) {
        this.groups = groups;
        this.workspace = workspace;
        this.publisher = publisher;
        this.activity = activity;
    }

    public record Change(
            String path,
            String category,
            String kind,
            String summary,
            List<String> sources,
            List<String> authors,
            String lastChangedAt,
            boolean userContentChanged,
            boolean revertible) {}

    public record Review(
            List<Change> changes,
            String currentUser,
            int changesByOthers,
            boolean assistantRunning) {}

    public Review review(String userId, String groupId) {
        groups.getGroup(userId, groupId);
        return workspace.withWorkspaceLock(
                groupId,
                () -> {
                    List<Change> changes = changes(groupId);
                    int others =
                            (int)
                                    changes.stream()
                                            .filter(
                                                    c ->
                                                            c.authors().stream()
                                                                    .anyMatch(
                                                                            a -> !a.equals(userId)))
                                            .count();
                    return new Review(changes, userId, others, activity.running(groupId));
                });
    }

    /** Restores the published version of one file, or removes a file that was only drafted. */
    public Review revert(String userId, String groupId, String path) {
        groups.getGroup(userId, groupId);
        activity.requireIdle(groupId);
        workspace.withWorkspaceLock(
                groupId,
                () -> {
                    Change change =
                            changes(groupId).stream()
                                    .filter(c -> c.path().equals(path))
                                    .findFirst()
                                    .orElseThrow(() -> new DatasetException("该文件没有未发布的修改", 404));
                    if (!change.revertible()) throw new DatasetException("该文件由平台维护，不能单独撤销", 400);
                    restore(groupId, path);
                    DraftChangeLog.forget(workspace.workspaceRoot(groupId), path);
                    return null;
                });
        groups.markMdlDirty(userId, groupId);
        return review(userId, groupId);
    }

    /**
     * The user's own edit of one drafted text file. Same boundary as the assistant's writes: the
     * path must be a writable draft file and YAML must parse; the file is not confirmed again.
     */
    public Review edit(String userId, String groupId, String path, String content) {
        groups.getGroup(userId, groupId);
        activity.requireIdle(groupId);
        if (content == null || content.length() > 200_000)
            throw new DatasetException("文件内容为空或过大", 400);
        String lower = path == null ? "" : path.toLowerCase(java.util.Locale.ROOT);
        if (!(lower.startsWith("models/")
                        || lower.startsWith("knowledge/rules/")
                        || lower.startsWith("knowledge/caveats/")
                        || lower.startsWith("knowledge/glossary/")
                        || lower.startsWith("knowledge/metrics/")
                        || lower.equals("relationships.yml"))
                || workspace.isPlatformOwned(groupId, lower))
            throw new DatasetException("这个文件不能在审阅页编辑，请在对话中让助手修改", 400);
        String invalid = yamlError(path, content);
        if (invalid != null) throw new DatasetException(invalid, 400);
        workspace.withWorkspaceLock(
                groupId,
                () -> {
                    try {
                        Files.writeString(
                                workspace.resolveWritable(groupId, path),
                                content,
                                java.nio.charset.StandardCharsets.UTF_8);
                    } catch (IOException e) {
                        throw new DatasetException("保存修改失败：" + e.getMessage(), e);
                    }
                    DraftChangeLog.record(
                            workspace.workspaceRoot(groupId),
                            path,
                            ModelingTurnPurpose.EDIT,
                            userId);
                    return null;
                });
        groups.markMdlDirty(userId, groupId);
        return review(userId, groupId);
    }

    /** Line-precise YAML syntax error for .yml/.yaml content, or null. */
    static String yamlError(String path, String content) {
        String lower = path.toLowerCase(java.util.Locale.ROOT);
        if (!(lower.endsWith(".yml") || lower.endsWith(".yaml"))) return null;
        try {
            for (Object ignored : new org.yaml.snakeyaml.Yaml().loadAll(content)) {
                // Parsing every document is the check itself.
            }
            return null;
        } catch (org.yaml.snakeyaml.error.MarkedYAMLException e) {
            var mark = e.getProblemMark();
            return "YAML 格式错误，未保存："
                    + (mark == null
                            ? ""
                            : "第 " + (mark.getLine() + 1) + " 行第 " + (mark.getColumn() + 1) + " 列，")
                    + e.getProblem();
        } catch (RuntimeException e) {
            return "YAML 格式错误，未保存：" + e.getMessage();
        }
    }

    /** Makes the draft equal to the published snapshot again. */
    public Review discard(String userId, String groupId) {
        groups.getGroup(userId, groupId);
        activity.requireIdle(groupId);
        workspace.withWorkspaceLock(
                groupId,
                () -> {
                    for (Change change : changes(groupId)) restore(groupId, change.path());
                    DraftChangeLog.clear(workspace.workspaceRoot(groupId));
                    return null;
                });
        groups.markMdlDirty(userId, groupId);
        return review(userId, groupId);
    }

    private List<Change> changes(String groupId) {
        var preview = publisher.preview(groupId);
        Map<String, String> before = new LinkedHashMap<>();
        preview.publishedFiles().forEach(f -> before.put(f.path(), f.content()));
        Map<String, String> after = new LinkedHashMap<>();
        preview.files().forEach(f -> after.put(f.path(), f.content()));
        Set<String> paths = new LinkedHashSet<>(after.keySet());
        paths.addAll(before.keySet());
        Map<String, List<DraftChangeLog.Entry>> log = new LinkedHashMap<>();
        for (DraftChangeLog.Entry entry : DraftChangeLog.read(workspace.workspaceRoot(groupId)))
            log.computeIfAbsent(entry.path(), k -> new ArrayList<>()).add(entry);
        List<Change> out = new ArrayList<>();
        for (String path : paths) {
            String old = before.get(path);
            String now = after.get(path);
            if (Objects.equals(old, now)) continue;
            List<DraftChangeLog.Entry> entries = log.getOrDefault(path, List.of());
            out.add(
                    new Change(
                            path,
                            category(path),
                            old == null ? "ADDED" : now == null ? "REMOVED" : "MODIFIED",
                            summary(path, old, now),
                            entries.isEmpty()
                                    ? List.of("platform")
                                    : entries.stream()
                                            .map(DraftChangeLog.Entry::source)
                                            .distinct()
                                            .toList(),
                            entries.stream()
                                    .map(DraftChangeLog.Entry::userId)
                                    .filter(Objects::nonNull)
                                    .distinct()
                                    .toList(),
                            entries.isEmpty() ? null : entries.get(entries.size() - 1).at(),
                            userContentChanged(entries),
                            revertible(path)));
        }
        return out;
    }

    /** A user-directed write later rewritten by an automatic or document turn. */
    static boolean userContentChanged(List<DraftChangeLog.Entry> entries) {
        boolean userWrote = false;
        for (DraftChangeLog.Entry entry : entries) {
            if (List.of(
                            ModelingTurnPurpose.CHAT,
                            ModelingTurnPurpose.CONFIRM,
                            ModelingTurnPurpose.EDIT)
                    .contains(entry.source())) userWrote = true;
            else if (userWrote
                    && (ModelingTurnPurpose.AUTO.equals(entry.source())
                            || ModelingTurnPurpose.DOCUMENT.equals(entry.source()))) return true;
        }
        return false;
    }

    static String category(String path) {
        if (path.startsWith("models/")) return "MODEL";
        if (path.startsWith("relationships")) return "RELATIONSHIP";
        if (path.startsWith("knowledge/rules/")) return "RULE";
        if (path.startsWith("knowledge/caveats/")) return "CAVEAT";
        if (path.startsWith(BusinessDocumentService.DIRECTORY + "/")) return "DOCUMENT";
        if (path.startsWith("knowledge/questions/") || path.startsWith("knowledge/sql/"))
            return "EXAMPLE";
        if (path.startsWith("raw/")) return "SAMPLE";
        return "OTHER";
    }

    /** Confirmed pairs and sampling material follow their own flows, not a file revert. */
    private boolean revertible(String path) {
        return !path.startsWith("knowledge/sql/")
                && !path.startsWith("knowledge/questions/")
                && !workspace.isPlatformOwned(null, path);
    }

    /** Deterministic summary: changed columns for model files, line counts otherwise. */
    static String summary(String path, String old, String now) {
        if (path.startsWith("models/") && path.endsWith("metadata.yml")) {
            String columns = columnSummary(old, now);
            if (columns != null) return columns;
        }
        List<String> a = old == null ? List.of() : List.of(old.split("\n", -1));
        List<String> b = now == null ? List.of() : List.of(now.split("\n", -1));
        Set<String> removed = new LinkedHashSet<>(a);
        b.forEach(removed::remove);
        Set<String> added = new LinkedHashSet<>(b);
        a.forEach(added::remove);
        return "+" + added.size() + " / -" + removed.size() + " 行";
    }

    private static String columnSummary(String old, String now) {
        try {
            Map<String, String> before = columns(old);
            Map<String, String> after = columns(now);
            List<String> parts = new ArrayList<>();
            List<String> changed = new ArrayList<>();
            List<String> added = new ArrayList<>();
            for (var entry : after.entrySet()) {
                if (!before.containsKey(entry.getKey())) added.add(entry.getKey());
                else if (!Objects.equals(before.get(entry.getKey()), entry.getValue()))
                    changed.add(entry.getKey());
            }
            List<String> removed =
                    before.keySet().stream().filter(k -> !after.containsKey(k)).toList();
            if (!changed.isEmpty()) parts.add("修改列：" + String.join("、", changed));
            if (!added.isEmpty()) parts.add("新增列：" + String.join("、", added));
            if (!removed.isEmpty()) parts.add("删除列：" + String.join("、", removed));
            if (!Objects.equals(modelDescription(old), modelDescription(now))) parts.add(0, "模型描述");
            return parts.isEmpty() ? null : String.join("；", parts);
        } catch (IOException e) {
            return null;
        }
    }

    private static Map<String, String> columns(String yaml) throws IOException {
        Map<String, String> out = new LinkedHashMap<>();
        if (yaml == null) return out;
        JsonNode node = YAML.readTree(yaml);
        if (node == null) return out;
        for (JsonNode column : node.path("columns"))
            out.put(column.path("name").asText(""), column.toString());
        return out;
    }

    private static String modelDescription(String yaml) throws IOException {
        if (yaml == null) return null;
        JsonNode node = YAML.readTree(yaml);
        return node == null ? null : node.path("properties").path("description").asText(null);
    }

    private void restore(String groupId, String path) {
        Path root = workspace.workspaceRoot(groupId);
        Path target = root.resolve(path).normalize();
        Path source = root.getParent().resolve("published").resolve(path).normalize();
        if (!target.startsWith(root)) throw new DatasetException("工作区路径越界", 400);
        try {
            if (Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS)) {
                Files.createDirectories(target.getParent());
                Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
            } else {
                Files.deleteIfExists(target);
            }
        } catch (IOException e) {
            throw new DatasetException("撤销修改失败：" + e.getMessage(), e);
        }
    }
}
