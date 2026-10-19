package io.agentscope.dataagent.dataset;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Supplier;
import org.springframework.stereotype.Service;

/** Lists and edits the official Wren SQL-example sources for one knowledge base. */
@Service
public class SqlQaExampleService {
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
        for (String source : List.of("modeling", "liked")) {
            withSourceLock(
                    groupId,
                    source,
                    () -> {
                        collect(result, source, sourcePath(owner, groupId, source));
                        return null;
                    });
        }
        return result.stream()
                .sorted(Comparator.comparing(Example::source).thenComparing(Example::file))
                .toList();
    }

    public Example update(
            String owner, String groupId, String source, String file, String question, String sql) {
        if (question == null || question.isBlank() || question.length() > 1000)
            throw new DatasetException("问题不能为空且不能超过 1000 个字符", 400);
        if (sql == null || sql.length() > 16000)
            throw new DatasetException("请提供不超过 16000 字符的查询 SQL", 400);
        MdlQuestionStore.requireReadSql(sql);
        return withSourceLock(
                groupId,
                source,
                () -> {
                    Path target = confined(sourcePath(owner, groupId, source), file);
                    if (!Files.isRegularFile(target)) throw new DatasetException("问答对不存在", 404);
                    try {
                        byte[] original = Files.readAllBytes(target);
                        var document =
                                SqlExampleDocument.parse(
                                        new String(original, StandardCharsets.UTF_8));
                        String updated = document.update(question, sql);
                        // Parsing and exact round-trip validation happen before any source
                        // mutation.
                        var checked = SqlExampleDocument.parse(updated);
                        mutate(
                                owner,
                                groupId,
                                source,
                                target,
                                original,
                                updated.getBytes(StandardCharsets.UTF_8));
                        return example(source, file, checked);
                    } catch (IOException e) {
                        throw new DatasetException("更新SQL问答对失败，未完成保存", e);
                    }
                });
    }

    /** Shares mutation/index coordination with editing, including rollback on index failure. */
    public void delete(String owner, String groupId, String source, String file) {
        withSourceLock(
                groupId,
                source,
                () -> {
                    Path target = confined(sourcePath(owner, groupId, source), file);
                    if (!Files.isRegularFile(target)) throw new DatasetException("问答对不存在", 404);
                    try {
                        mutate(owner, groupId, source, target, Files.readAllBytes(target), null);
                    } catch (IOException e) {
                        throw new DatasetException("删除SQL问答对失败", e);
                    }
                    return null;
                });
    }

    private <T> T withSourceLock(String group, String source, Supplier<T> action) {
        return workspace.withWorkspaceLock(
                group,
                () -> "liked".equals(source) ? published.locked(group, action) : action.get());
    }

    private void mutate(
            String owner, String group, String source, Path target, byte[] original, byte[] updated)
            throws IOException {
        if (updated == null) Files.delete(target);
        else atomicWrite(target, updated);
        if (!"liked".equals(source)) return;
        try {
            published.indexed(owner, group);
        } catch (RuntimeException failed) {
            try {
                atomicWrite(target, original);
            } catch (IOException restoreFailed) {
                restoreFailed.addSuppressed(failed);
                throw new DatasetException("索引更新失败且原文件恢复失败，请保留现场并人工检查", 502, restoreFailed);
            }
            try {
                published.restoreIndex(owner, group);
            } catch (RuntimeException recoveryFailed) {
                failed.addSuppressed(recoveryFailed);
                throw new DatasetException("保存失败，原文件已恢复；记忆索引待恢复，请稍后重试", 502, failed);
            }
            throw new DatasetException("保存失败，原文件及记忆索引已恢复，请重试", 502, failed);
        }
    }

    private static void atomicWrite(Path target, byte[] content) throws IOException {
        Path tmp = Files.createTempFile(target.getParent(), ".sql-example-", ".tmp");
        try {
            Files.write(tmp, content);
            Files.move(
                    tmp,
                    target,
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    private Path sourcePath(String owner, String groupId, String source) {
        Path root;
        if ("modeling".equals(source))
            root = workspace.workspaceRoot(groupId).resolve("knowledge/sql");
        else if ("liked".equals(source))
            root = published.likedProject(owner, groupId).resolve("knowledge/sql");
        else throw new DatasetException("未知问答对来源", 400);
        root = root.toAbsolutePath().normalize();
        WrenPublishedProject.check(root);
        return root;
    }

    private static Path confined(Path root, String file) {
        if (file == null || !file.matches("[A-Za-z0-9._-]{1,128}\\.md"))
            throw new DatasetException("问答对文件名不合法", 400);
        Path value = root.resolve(file).normalize();
        if (!value.startsWith(root)) throw new DatasetException("问答对路径越界", 400);
        WrenPublishedProject.check(value);
        return value;
    }

    private static Example example(String source, String file, SqlExampleDocument doc) {
        return new Example(
                source + ":" + file,
                source,
                "liked".equals(source) ? "LIKED" : "MODELING",
                file,
                doc.question(),
                doc.sql(),
                true,
                "modeling".equals(source) ? "修改后需要重新发布" : "修改后自动重建记忆索引");
    }

    private static void collect(List<Example> out, String source, Path dir) {
        if (!Files.isDirectory(dir)) return;
        try (var files = Files.list(dir)) {
            for (Path file :
                    files.filter(Files::isRegularFile)
                            .filter(p -> p.toString().endsWith(".md"))
                            .sorted()
                            .toList()) {
                WrenPublishedProject.check(file);
                String text = Files.readString(file, StandardCharsets.UTF_8);
                try {
                    out.add(
                            example(
                                    source,
                                    file.getFileName().toString(),
                                    SqlExampleDocument.parse(text)));
                } catch (DatasetException malformed) {
                    out.add(
                            new Example(
                                    source + ":" + file.getFileName(),
                                    source,
                                    "INVALID",
                                    file.getFileName().toString(),
                                    file.getFileName().toString(),
                                    "",
                                    false,
                                    "文件格式异常，保留原文件待核对；暂不可编辑"));
                }
            }
        } catch (IOException e) {
            throw new DatasetException("读取SQL问答对失败", e);
        }
    }
}
