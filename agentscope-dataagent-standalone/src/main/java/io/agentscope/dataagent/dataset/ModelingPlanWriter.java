package io.agentscope.dataagent.dataset;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Validates one complete file plan before human review and commits it under the workspace lock. */
public final class ModelingPlanWriter {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());
    private final MdlWorkspaceService workspace;
    private final WrenCli cli;
    private final WrenProperties properties;

    public ModelingPlanWriter(
            MdlWorkspaceService workspace, WrenCli cli, WrenProperties properties) {
        this.workspace = workspace;
        this.cli = cli;
        this.properties = properties;
    }

    public String revision(String groupId) {
        return workspace.withWorkspaceLock(
                groupId,
                () -> {
                    try {
                        Path root = workspace.workspaceRoot(groupId).toAbsolutePath().normalize();
                        MessageDigest digest = MessageDigest.getInstance("SHA-256");
                        if (Files.isDirectory(root))
                            try (var files = Files.walk(root)) {
                                for (Path file :
                                        files.filter(
                                                        p ->
                                                                Files.isRegularFile(
                                                                        p,
                                                                        LinkOption.NOFOLLOW_LINKS))
                                                .sorted()
                                                .toList()) {
                                    String name =
                                            root.relativize(file).toString().replace('\\', '/');
                                    if (name.startsWith("target/")
                                            || name.startsWith(".platform/")
                                            || name.startsWith(".git/")
                                            || name.startsWith(".wren/")) continue;
                                    digest.update(name.getBytes(StandardCharsets.UTF_8));
                                    digest.update((byte) 0);
                                    digest.update(Files.readAllBytes(file));
                                    digest.update((byte) 0);
                                }
                            }
                        return HexFormat.of().formatHex(digest.digest());
                    } catch (Exception e) {
                        throw new DatasetException("无法计算工程基准：" + e.getMessage(), e);
                    }
                });
    }

    public String apply(String groupId, String filesJson, String expectedRevision, boolean commit) {
        return workspace.withWorkspaceLock(
                groupId,
                () -> {
                    Path scratch = null;
                    try {
                        if (expectedRevision == null || !expectedRevision.equals(revision(groupId)))
                            return "error: 工程基准已变化，请重新 list_files 并按最新内容准备完整方案";
                        var array = JSON.readTree(filesJson == null ? "null" : filesJson);
                        if (!array.isArray() || array.isEmpty() || array.size() > 100)
                            return "error: files_json 必须包含 1–100 个文件对象";
                        Map<String, String> proposed = new LinkedHashMap<>();
                        java.util.Set<Path> targets = new java.util.HashSet<>();
                        int size = 0;
                        Path root = workspace.workspaceRoot(groupId).toAbsolutePath().normalize();
                        if (!Files.isDirectory(root)) return "error: 工作区尚未初始化，请先完成数据准备";
                        try (var paths = Files.walk(root)) {
                            if (paths.anyMatch(Files::isSymbolicLink))
                                return "error: 工作区包含符号链接，禁止批量写入";
                        }
                        for (var entry : array) {
                            if (!entry.isObject()
                                    || !entry.path("path").isTextual()
                                    || !entry.path("content").isTextual())
                                return "error: 每个文件必须提供 path/content 字符串";
                            String path = entry.path("path").asText();
                            String content = entry.path("content").asText();
                            if (Path.of(path).isAbsolute()) return "error: 文件路径必须相对工程根目录";
                            Path target = workspace.resolveWritable(groupId, path);
                            if (!targets.add(target)) return "error: 方案包含重复文件路径";
                            String relative = root.relativize(target).toString().replace('\\', '/');
                            if (proposed.putIfAbsent(relative, content) != null)
                                return "error: 方案包含重复文件路径";
                            size += content.getBytes(StandardCharsets.UTF_8).length;
                            if (content.isBlank() || size > 1048576)
                                return "error: 文件内容不能为空，总内容不得超过 1MB";
                            if (relative.endsWith(".yml") || relative.endsWith(".yaml")) {
                                if (YAML.readTree(content) == null) return "error: YAML 文件不能是空文档";
                            }
                            if (relative.startsWith("knowledge/questions/")
                                    && !relative.matches(
                                            "knowledge/questions/[A-Za-z0-9_-]{1,64}\\.yml"))
                                return "error: 问题路径必须为 knowledge/questions/<英文ID>.yml";
                        }
                        scratch = workspace.copyToScratch(groupId);
                        for (var entry : proposed.entrySet()) {
                            Path file = scratch.resolve(entry.getKey());
                            Files.createDirectories(file.getParent());
                            Files.writeString(file, entry.getValue(), StandardCharsets.UTF_8);
                        }
                        MdlQuestionStore store = new MdlQuestionStore(workspace);
                        var questions = store.readQuestions(scratch);
                        for (var question : questions) {
                            if (!proposed.containsKey(
                                    "knowledge/questions/" + question.id() + ".yml")) continue;
                            store.requireModelingPlan(scratch, question.id());
                            if (question.definition().isBlank() || question.sql().isBlank())
                                return "error: 完整方案中的问题必须包含业务口径和可执行 SQL，不要提交空 SQL 等待回填";
                            var coverage = store.coverage(scratch, question);
                            if (!coverage.ready()) return "error: " + coverage.message();
                        }
                        for (List<String> args :
                                List.of(
                                        List.of("context", "validate", "--strict"),
                                        List.of("context", "build"),
                                        List.of(
                                                "dry-plan",
                                                "--sql",
                                                "SELECT 1",
                                                "-d",
                                                properties.dataSource()))) {
                            var result = cli.run(scratch, properties.timeout(), args);
                            if (!result.ok())
                                return "error: 技术预检失败（"
                                        + String.join(" ", args)
                                        + "）：\n"
                                        + result.output();
                        }
                        for (var entry : proposed.entrySet()) {
                            if (entry.getKey().matches("views/[^/]+/sql\\.yml")) {
                                String statement =
                                        YAML.readTree(entry.getValue()).path("statement").asText();
                                MdlSuggestionService.requireViewSql(statement);
                                String error = checkSql(scratch, statement);
                                if (error != null) return error;
                                String viewName = entry.getKey().split("/")[1];
                                error = checkSql(scratch, "SELECT * FROM \"" + viewName + "\"");
                                if (error != null) return error;
                            }
                        }
                        for (var question : questions) {
                            if (proposed.containsKey(
                                    "knowledge/questions/" + question.id() + ".yml")) {
                                String error = checkSql(scratch, question.sql());
                                if (error != null) return error;
                            }
                        }
                        if (commit) commit(groupId, proposed);
                        return null;
                    } catch (Exception e) {
                        return "error: 业务方案预检/写入失败：" + e.getMessage();
                    } finally {
                        if (scratch != null) workspace.deleteScratchQuietly(groupId);
                    }
                });
    }

    private String checkSql(Path scratch, String sql) {
        var result =
                cli.run(
                        scratch,
                        properties.timeout(),
                        List.of("dry-plan", "--sql", sql, "-d", properties.dataSource()));
        return result.ok() ? null : "error: 查询编译预检失败：\n" + result.output();
    }

    private void commit(String groupId, Map<String, String> proposed) throws IOException {
        Map<Path, byte[]> originals = new LinkedHashMap<>();
        try {
            for (var entry : proposed.entrySet()) {
                Path target = workspace.resolveWritable(groupId, entry.getKey());
                originals.put(target, Files.exists(target) ? Files.readAllBytes(target) : null);
                Files.createDirectories(target.getParent());
                Files.writeString(target, entry.getValue(), StandardCharsets.UTF_8);
            }
        } catch (IOException | RuntimeException failure) {
            for (var original : originals.entrySet())
                try {
                    if (original.getValue() == null) Files.deleteIfExists(original.getKey());
                    else Files.write(original.getKey(), original.getValue());
                } catch (IOException rollback) {
                    failure.addSuppressed(rollback);
                }
            throw failure;
        }
    }
}
