package io.agentscope.dataagent.dataset;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

/** Lists and edits the official Wren SQL-example sources for one knowledge base. */
@Service
public class SqlQaExampleService {
    private static final Pattern NL = Pattern.compile("(?m)^nl:\\s*(.*?)\\s*$");

    /** Wren emits folded YAML scalars, so SQL may continue on indented lines. */
    private static final Pattern SQL =
            Pattern.compile("(?ms)^sql:\\s*(.*?)(?=^[A-Za-z_][A-Za-z0-9_-]*:\\s|^---\\s*$)");

    private static final Pattern CODE = Pattern.compile("(?s)(```sql\\s*\\n)(.*?)(\\n```)");
    private final MdlWorkspaceService workspace;
    private final WrenPublishedProject published;

    public SqlQaExampleService(MdlWorkspaceService workspace, WrenPublishedProject published) {
        this.workspace = workspace;
        this.published = published;
    }

    public record Example(
            String id,
            String source,
            String state,
            String file,
            String question,
            String sql,
            boolean editable,
            String message) {}

    public List<Example> list(String owner, String groupId) {
        workspace.ensureWorkspace(groupId);
        List<Example> result = new ArrayList<>();
        collect(
                result,
                "modeling",
                workspace.workspaceRoot(groupId).resolve("knowledge/sql"),
                true);
        collect(
                result,
                "liked",
                published.likedProject(owner, groupId).resolve("knowledge/sql"),
                true);
        return result.stream()
                .sorted(Comparator.comparing(Example::source).thenComparing(Example::file))
                .toList();
    }

    public Example update(
            String owner, String groupId, String source, String file, String question, String sql) {
        if (question == null || question.isBlank() || question.length() > 1000)
            throw new DatasetException("问题不能为空且不能超过 1000 个字符", 400);
        MdlQuestionStore.requireReadSql(sql);
        Path root = sourcePath(owner, groupId, source);
        Path target = confined(root, file);
        if (!Files.isRegularFile(target)) throw new DatasetException("问答对不存在", 404);
        workspace.withWorkspaceLock(
                groupId,
                () -> {
                    try {
                        String original = Files.readString(target, StandardCharsets.UTF_8);
                        String updated = replace(original, NL, "nl: " + yamlScalar(question));
                        updated = replace(updated, SQL, "sql: " + yamlScalar(sql.stripTrailing()));
                        Matcher code = CODE.matcher(updated);
                        updated =
                                code.find()
                                        ? code.replaceFirst(
                                                Matcher.quoteReplacement(
                                                        code.group(1)
                                                                + sql.stripTrailing()
                                                                + code.group(3)))
                                        : updated;
                        Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
                        Files.writeString(tmp, updated, StandardCharsets.UTF_8);
                        Files.move(
                                tmp,
                                target,
                                StandardCopyOption.REPLACE_EXISTING,
                                StandardCopyOption.ATOMIC_MOVE);
                    } catch (IOException e) {
                        throw new DatasetException("更新 SQL 问答对失败：" + e.getMessage(), e);
                    }
                    return null;
                });
        if ("liked".equals(source)) published.indexed(owner, groupId);
        return list(owner, groupId).stream()
                .filter(e -> e.source().equals(source) && e.file().equals(file))
                .findFirst()
                .orElseThrow();
    }

    /** Deletes one source Markdown file and refreshes the liked-example index when necessary. */
    public void delete(String owner, String groupId, String source, String file) {
        Path root = sourcePath(owner, groupId, source);
        Path target = confined(root, file);
        workspace.withWorkspaceLock(
                groupId,
                () -> {
                    try {
                        if (!Files.deleteIfExists(target))
                            throw new DatasetException("问答对不存在", 404);
                    } catch (IOException e) {
                        throw new DatasetException("删除 SQL 问答对失败：" + e.getMessage(), e);
                    }
                    return null;
                });
        if ("liked".equals(source)) published.indexed(owner, groupId);
    }

    private Path sourcePath(String owner, String groupId, String source) {
        if ("modeling".equals(source))
            return workspace
                    .workspaceRoot(groupId)
                    .resolve("knowledge/sql")
                    .toAbsolutePath()
                    .normalize();
        if ("liked".equals(source))
            return published
                    .likedProject(owner, groupId)
                    .resolve("knowledge/sql")
                    .toAbsolutePath()
                    .normalize();
        throw new DatasetException("未知问答对来源", 400);
    }

    private static Path confined(Path root, String file) {
        if (file == null || !file.matches("[A-Za-z0-9._-]{1,128}\\.md"))
            throw new DatasetException("问答对文件名不合法", 400);
        Path value = root.resolve(file).normalize();
        if (!value.startsWith(root)) throw new DatasetException("问答对路径越界", 400);
        return value;
    }

    private static void collect(List<Example> out, String source, Path dir, boolean editable) {
        if (!Files.isDirectory(dir)) return;
        try (var files = Files.list(dir)) {
            for (Path file :
                    files.filter(Files::isRegularFile)
                            .filter(p -> p.toString().endsWith(".md"))
                            .sorted()
                            .toList()) {
                String text = Files.readString(file, StandardCharsets.UTF_8);
                String question = match(NL, text), sql = match(SQL, text);
                if (question == null || sql == null) continue;
                out.add(
                        new Example(
                                source + ":" + file.getFileName(),
                                source,
                                "liked".equals(source) ? "LIKED" : "MODELING",
                                file.getFileName().toString(),
                                question,
                                sql,
                                editable,
                                "modeling".equals(source) ? "修改后需要重新发布" : "修改后自动重建记忆索引"));
            }
        } catch (IOException e) {
            throw new DatasetException("读取 SQL 问答对失败：" + e.getMessage(), e);
        }
    }

    private static String match(Pattern pattern, String text) {
        Matcher m = pattern.matcher(text);
        return m.find() ? m.group(1).replaceAll("\\s+", " ").trim() : null;
    }

    private static String replace(String text, Pattern pattern, String value) {
        Matcher m = pattern.matcher(text);
        return m.find() ? m.replaceFirst(Matcher.quoteReplacement(value)) : text;
    }

    private static String yamlScalar(String value) {
        return value.replace("\\", "\\\\")
                .replace("\n", " ")
                .replace("\r", "")
                .replace("\"", "\\\"");
    }
}
