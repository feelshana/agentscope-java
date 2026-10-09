package io.agentscope.dataagent.dataset;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/** File-backed requirements and platform-owned execution receipts; workspace locks serialize changes. */
@Component
public class MdlQuestionStore {
    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern ID = Pattern.compile("[A-Za-z0-9_-]{1,64}");
    private static final Pattern LIMIT = Pattern.compile("(?i)\\b(limit|offset)\\b");
    private final MdlWorkspaceService workspace;

    public MdlQuestionStore(MdlWorkspaceService workspace) {
        this.workspace = workspace;
    }

    @com.fasterxml.jackson.annotation.JsonInclude(
            com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    public record Question(
            String id, String question, String definition, String sql, ModelingPlan modeling) {
        public Question(String id, String question, String definition, String sql) {
            this(id, question, definition, sql, null);
        }
    }

    public record AssetRef(String kind, String name) {}

    public record ModelingPlan(String strategy, String reason, List<AssetRef> assets) {}

    public record Coverage(String strategy, boolean ready, String message, List<AssetRef> assets) {}

    public record Publication(boolean available, boolean current) {}

    public record Receipt(
            String validationId,
            String questionHash,
            String modelHash,
            String status,
            String executedAt,
            Map<String, Object> result,
            String error,
            boolean truncated,
            String confirmedBy,
            String confirmedAt,
            Evidence evidence) {}

    public record Evidence(String sql, List<MdlWorkspaceReader.WorkspaceView> views) {}

    public record Review(
            Question question,
            String status,
            Receipt validation,
            Coverage coverage,
            Publication published,
            String revision) {
        public Review(Question question, String status, Receipt validation) {
            this(question, status, validation, null, null, questionHash(question));
        }

        public Review(
                Question question,
                String status,
                Receipt validation,
                Coverage coverage,
                Publication published) {
            this(question, status, validation, coverage, published, questionHash(question));
        }
    }

    public String revision(String groupId, String id) {
        return workspace.withWorkspaceLock(
                groupId, () -> questionHash(requireQuestion(groupId, id)));
    }

    /** Human corrections update the example only, never its referenced semantic assets. */
    public Review correctSql(String groupId, String id, String expectedRevision, String sql) {
        if (sql == null || sql.isBlank() || sql.length() > 16000)
            throw new DatasetException("请提供不超过 16000 字符的查询 SQL", 400);
        requireReadSql(sql);
        return workspace.withWorkspaceLock(
                groupId,
                () -> {
                    Question current = requireUnchanged(groupId, id, expectedRevision);
                    Path source =
                            workspace
                                    .workspaceRoot(groupId)
                                    .resolve("knowledge/questions/" + id + ".yml");
                    try {
                        var node =
                                (com.fasterxml.jackson.databind.node.ObjectNode)
                                        YAML.readTree(
                                                Files.readString(source, StandardCharsets.UTF_8));
                        node.put("sql", sql.trim());
                        var plan = node.putObject("modeling");
                        plan.put("strategy", "EXAMPLE");
                        plan.put("reason", "用户修正本题查询；复用已有语义模型，不反向修改资产");
                        plan.putArray("assets");
                        if (current.sql().equals(sql.trim()))
                            return listLocked(groupId).stream()
                                    .filter(r -> r.question().id().equals(id))
                                    .findFirst()
                                    .orElseThrow();
                        write(source, YAML.writeValueAsString(node));
                        Files.deleteIfExists(
                                workspace
                                        .workspaceRoot(groupId)
                                        .resolve("knowledge/sql/" + id + ".md"));
                        return listLocked(groupId).stream()
                                .filter(r -> r.question().id().equals(id))
                                .findFirst()
                                .orElseThrow();
                    } catch (IOException e) {
                        throw new DatasetException("保存问题查询失败：" + e.getMessage(), e);
                    }
                });
    }

    public void archive(String groupId, String id, String expectedRevision) {
        workspace.withWorkspaceLock(
                groupId,
                () -> {
                    requireUnchanged(groupId, id, expectedRevision);
                    Path source =
                            workspace
                                    .workspaceRoot(groupId)
                                    .resolve("knowledge/questions/" + id + ".yml");
                    try {
                        var node =
                                (com.fasterxml.jackson.databind.node.ObjectNode)
                                        YAML.readTree(
                                                Files.readString(source, StandardCharsets.UTF_8));
                        node.put("archived", true);
                        write(source, YAML.writeValueAsString(node));
                        Files.deleteIfExists(
                                workspace
                                        .workspaceRoot(groupId)
                                        .resolve("knowledge/sql/" + id + ".md"));
                    } catch (IOException e) {
                        throw new DatasetException("移出验收失败：" + e.getMessage(), e);
                    }
                    return null;
                });
    }

    private Question requireUnchanged(String groupId, String id, String revision) {
        Question question = requireQuestion(groupId, id);
        if (revision == null || !questionHash(question).equals(revision))
            throw new DatasetException("问题已变化，请刷新后再操作", 409);
        return question;
    }

    public List<Review> list(String groupId) {
        return workspace.withWorkspaceLock(groupId, () -> listLocked(groupId));
    }

    private List<Review> listLocked(String groupId) {
        Path root = workspace.workspaceRoot(groupId);
        String modelHash = modelHash(root);
        List<Review> out = new ArrayList<>();
        for (Question question : readQuestions(root)) {
            Receipt receipt = readReceipt(groupId, question.id());
            String status = receipt == null ? "DRAFT" : receipt.status();
            if (receipt != null
                    && (!matchesQuestionHash(question, receipt.questionHash())
                            || !modelHash.equals(receipt.modelHash()))) {
                status = "STALE";
            }
            out.add(review(groupId, question, status, receipt));
        }
        return out;
    }

    public Question requireQuestion(String groupId, String id) {
        checkId(id);
        return readQuestions(workspace.workspaceRoot(groupId)).stream()
                .filter(q -> q.id().equals(id))
                .findFirst()
                .orElseThrow(() -> new DatasetException("常用问题不存在", 404));
    }

    public List<Question> readQuestions(Path root) {
        Path directory = root.resolve("knowledge/questions");
        if (!Files.isDirectory(directory)) return List.of();
        try (var files = Files.list(directory)) {
            List<Path> paths =
                    files.filter(p -> p.getFileName().toString().endsWith(".yml"))
                            .sorted()
                            .toList();
            if (paths.size() > 50) throw new DatasetException("每个知识库最多设置 50 个常用问题", 400);
            List<Question> out = new ArrayList<>();
            for (Path path : paths) {
                if (Files.isSymbolicLink(path) || Files.size(path) > 65536) {
                    throw new DatasetException("常用问题文件不可为链接或超过 64KB", 400);
                }
                String id = path.getFileName().toString().replaceFirst("\\.yml$", "");
                checkId(id);
                JsonNode node = YAML.readTree(Files.readString(path, StandardCharsets.UTF_8));
                if (node == null || !node.isObject())
                    throw new DatasetException("常用问题 YAML 必须是对象", 400);
                if (node.path("archived").asBoolean(false)) continue;
                String question = node.path("question").asText("").trim();
                String definition = node.path("definition").asText("").trim();
                String sql = node.path("sql").asText("").trim();
                if (question.isBlank()
                        || question.length() > 1000
                        || definition.length() > 8000
                        || sql.length() > 16000) {
                    throw new DatasetException("常用问题不能为空，且问题/口径/SQL 不能超过长度上限：" + id, 400);
                }
                if (!sql.isBlank()) requireReadSql(sql);
                out.add(
                        new Question(
                                id,
                                question,
                                definition,
                                sql,
                                readPlan(node.path("modeling"), id)));
            }
            return out;
        } catch (IOException e) {
            throw new DatasetException("读取常用问题失败：" + e.getMessage(), e);
        }
    }

    public static void requireReadSql(String sql) {
        String value = sql == null ? "" : sql.trim();
        if (!value.matches("(?is)^(select|with)\\b.*")
                || value.contains(";")
                || LIMIT.matcher(value).find()
                || Pattern.compile(
                                "(?i)\\b(insert|update|delete|drop|alter|create|merge|call|into|outfile|load_file|sleep|benchmark)\\b")
                        .matcher(value)
                        .find()) {
            throw new DatasetException("验证 SQL 仅允许单条 SELECT/WITH，禁止写操作及显式 LIMIT/OFFSET", 400);
        }
    }

    private static ModelingPlan readPlan(JsonNode node, String id) {
        if (node.isMissingNode() || node.isNull()) return null;
        String strategy = node.path("strategy").asText("");
        String reason = node.path("reason").asText("").trim();
        if (!node.isObject()
                || !List.of("MODEL", "VIEW", "CUBE", "EXAMPLE").contains(strategy)
                || reason.isBlank()
                || reason.length() > 4000) {
            throw new DatasetException(
                    "问题须声明 modeling.strategy（MODEL/VIEW/CUBE/EXAMPLE）和业务理由：" + id, 400);
        }
        List<AssetRef> assets = new ArrayList<>();
        JsonNode refs = node.path("assets");
        if (!refs.isMissingNode() && !refs.isArray())
            throw new DatasetException("modeling.assets 必须为数组：" + id, 400);
        for (JsonNode ref : refs) {
            String kind = ref.path("kind").asText("");
            String name = ref.path("name").asText("");
            if (!List.of("MODEL", "VIEW", "CUBE").contains(kind)
                    || !name.matches("[\\p{L}_][\\p{L}\\p{N}_]{0,63}")) {
                throw new DatasetException("问题资产须使用 MODEL/VIEW/CUBE 和安全的逻辑名称：" + id, 400);
            }
            assets.add(new AssetRef(kind, name));
        }
        if (!"EXAMPLE".equals(strategy)
                && assets.stream().noneMatch(a -> strategy.equals(a.kind()))) {
            throw new DatasetException("问题落点须至少包含一个对应类型的资产：" + id, 400);
        }
        return new ModelingPlan(strategy, reason, List.copyOf(assets));
    }

    /** New agent writes must state a decision; historic files remain readable without migration. */
    public void requireModelingPlan(Path root, String id) {
        readQuestions(root).stream()
                .filter(q -> q.id().equals(id))
                .findFirst()
                .ifPresent(
                        q -> {
                            if (q.modeling() == null)
                                throw new DatasetException(
                                        "请先声明问题的 modeling.strategy、reason、assets；一次性查询可选 EXAMPLE"
                                                + " 并说明理由",
                                        400);
                        });
    }

    public Coverage coverage(Path root, Question question) {
        ModelingPlan plan = question.modeling();
        if (plan == null)
            return new Coverage("LEGACY_EXAMPLE", true, "历史 SQL 示例；尚未声明模型资产落点", List.of());
        if ("EXAMPLE".equals(plan.strategy()))
            return new Coverage("EXAMPLE", true, "SQL 示例：" + plan.reason(), plan.assets());
        try {
            String references =
                    question.sql().replaceAll("(?s)/\\*.*?\\*/|--[^\\r\\n]*|'(?:''|[^'])*'", " ");
            for (AssetRef asset : plan.assets()) {
                String directory =
                        switch (asset.kind()) {
                            case "VIEW" -> "views";
                            case "CUBE" -> "cubes";
                            default -> "models";
                        };
                Path metadata = root.resolve(directory + "/" + asset.name() + "/metadata.yml");
                if (!Files.isRegularFile(metadata, LinkOption.NOFOLLOW_LINKS))
                    return new Coverage(
                            plan.strategy(), false, "尚未建立资产：" + asset.name(), plan.assets());
                JsonNode node = YAML.readTree(Files.readString(metadata, StandardCharsets.UTF_8));
                if (node == null || !asset.name().equals(node.path("name").asText()))
                    return new Coverage(
                            plan.strategy(), false, "资产名称与定义不一致：" + asset.name(), plan.assets());
                String reference =
                        "CUBE".equals(asset.kind())
                                ? node.path("base_object").asText("")
                                : asset.name();
                if (reference.isBlank()
                        || !Pattern.compile(
                                        "(?i)\\b(?:FROM|JOIN)\\s+[\\\"`]?"
                                                + Pattern.quote(reference)
                                                + "[\\\"`]?(?![\\p{L}\\p{N}_])")
                                .matcher(references)
                                .find()) {
                    return new Coverage(
                            plan.strategy(),
                            false,
                            "问题 SQL 尚未引用资产"
                                    + ("CUBE".equals(asset.kind()) ? "的基础对象" : "")
                                    + "："
                                    + asset.name(),
                            plan.assets());
                }
                if ("CUBE".equals(asset.kind())
                        && !Files.isRegularFile(
                                root.resolve("models/" + reference + "/metadata.yml"))
                        && !Files.isRegularFile(
                                root.resolve("views/" + reference + "/metadata.yml"))) {
                    return new Coverage(
                            plan.strategy(), false, "Cube 基础对象尚未建立：" + reference, plan.assets());
                }
            }
            return new Coverage(
                    plan.strategy(), true, "资产已建立且查询已引用；请通过 Wren 验证业务结果", plan.assets());
        } catch (IOException e) {
            throw new DatasetException("读取问题资产失败：" + e.getMessage(), e);
        }
    }

    public void requireCoverage(String groupId, Question question) {
        Coverage value = coverage(workspace.workspaceRoot(groupId), question);
        if (!value.ready()) throw new DatasetException(value.message() + "，请先完成对话建模", 409);
    }

    Review review(String groupId, Question question, String status, Receipt receipt) {
        Path published = workspace.workspaceRoot(groupId).getParent().resolve("published");
        Path pair = published.resolve("knowledge/sql/" + question.id() + ".md");
        boolean available = false;
        try {
            available =
                    Files.isRegularFile(pair, LinkOption.NOFOLLOW_LINKS)
                            && Files.readString(pair, StandardCharsets.UTF_8)
                                    .contains("\nverified: true\n");
        } catch (IOException e) {
            throw new DatasetException("读取已发布问题失败", e);
        }
        boolean current =
                available
                        && "CONFIRMED".equals(status)
                        && readQuestions(published).stream()
                                .anyMatch(
                                        q ->
                                                q.id().equals(question.id())
                                                        && questionHash(q)
                                                                .equals(questionHash(question)));
        return new Review(
                question,
                status,
                receipt,
                coverage(workspace.workspaceRoot(groupId), question),
                new Publication(available, current));
    }

    public Receipt saveExecution(
            String groupId,
            Question question,
            String modelHash,
            JsonNode result,
            String error,
            boolean truncated) {
        return saveExecution(groupId, question, modelHash, result, error, truncated, null);
    }

    public Receipt saveExecution(
            String groupId,
            Question question,
            String modelHash,
            JsonNode result,
            String error,
            boolean truncated,
            Evidence evidence) {
        Receipt receipt =
                new Receipt(
                        UUID.randomUUID().toString(),
                        questionHash(question),
                        modelHash,
                        error == null ? "EXECUTED" : "FAILED",
                        Instant.now().toString(),
                        result == null
                                ? null
                                : JSON.convertValue(
                                        result, new TypeReference<Map<String, Object>>() {}),
                        error,
                        truncated,
                        null,
                        null,
                        evidence);
        writeReceipt(groupId, question.id(), receipt);
        return receipt;
    }

    public Review decide(
            String groupId, String id, String validationId, boolean accepted, String ownerId) {
        return workspace.withWorkspaceLock(
                groupId,
                () -> {
                    Question question = requireQuestion(groupId, id);
                    Receipt receipt = readReceipt(groupId, id);
                    if (receipt == null
                            || !receipt.validationId().equals(validationId)
                            || !matchesQuestionHash(question, receipt.questionHash())
                            || !receipt.modelHash()
                                    .equals(modelHash(workspace.workspaceRoot(groupId)))) {
                        throw new DatasetException("问题或模型已变化，请重新执行验证后再确认", 409);
                    }
                    if (!"EXECUTED".equals(receipt.status())
                            && !"CONFIRMED".equals(receipt.status())) {
                        throw new DatasetException("只能确认成功执行的验证结果", 409);
                    }
                    if (accepted && receipt.truncated()) {
                        throw new DatasetException("结果已截断，请改为完整聚合或缩小范围后验证", 409);
                    }
                    if (accepted) requireCoverage(groupId, question);
                    Receipt decided =
                            new Receipt(
                                    receipt.validationId(),
                                    receipt.questionHash(),
                                    receipt.modelHash(),
                                    accepted ? "CONFIRMED" : "REJECTED",
                                    receipt.executedAt(),
                                    receipt.result(),
                                    receipt.error(),
                                    receipt.truncated(),
                                    accepted ? ownerId : null,
                                    accepted ? Instant.now().toString() : null,
                                    receipt.evidence());
                    Path pair =
                            workspace.workspaceRoot(groupId).resolve("knowledge/sql/" + id + ".md");
                    if (accepted) {
                        write(pair, exampleMarkdown(question, decided));
                    } else {
                        try {
                            Files.deleteIfExists(pair);
                        } catch (IOException e) {
                            throw new DatasetException("移除示例失败：" + e.getMessage(), e);
                        }
                    }
                    writeReceipt(groupId, id, decided);
                    return review(groupId, question, decided.status(), decided);
                });
    }

    public List<MdlPublishService.MdlIssue> publishIssues(String groupId) {
        List<MdlPublishService.MdlIssue> issues = new ArrayList<>();
        for (Review review : list(groupId)) {
            if (!review.coverage().ready())
                issues.add(
                        new MdlPublishService.MdlIssue(
                                "error", review.coverage().message(), "questions"));
            if (!"CONFIRMED".equals(review.status())) {
                issues.add(
                        new MdlPublishService.MdlIssue(
                                "error",
                                "常用问题「"
                                        + review.question().question()
                                        + "」尚未有效确认（"
                                        + review.status()
                                        + "），请在常用问题页验证并确认",
                                "questions"));
            }
        }
        return issues;
    }

    /** Only currently confirmed pairs are shipped; stale requirements never leak examples. */
    public void filterPublishedExamples(String groupId, Path preparedDir) {
        java.util.Set<String> confirmed =
                list(groupId).stream()
                        .filter(r -> "CONFIRMED".equals(r.status()))
                        .map(r -> r.question().id() + ".md")
                        .collect(java.util.stream.Collectors.toSet());
        Path directory = preparedDir.resolve("knowledge/sql");
        if (!Files.isDirectory(directory)) return;
        try (var files = Files.list(directory)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".md")).toList()) {
                if (!confirmed.contains(file.getFileName().toString())) Files.delete(file);
            }
        } catch (IOException e) {
            throw new DatasetException("筛选发布示例失败：" + e.getMessage(), e);
        }
    }

    /** Excludes receipts and query examples, which do not change the modeled query semantics. */
    public String modelHash(Path root) {
        if (!Files.isDirectory(root)) return digest("");
        try (var walk = Files.walk(root)) {
            StringBuilder content = new StringBuilder();
            for (Path path :
                    walk.filter(p -> Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS))
                            .sorted()
                            .toList()) {
                String relative = root.relativize(path).toString().replace('\\', '/');
                if (relative.startsWith(".platform/")
                        || relative.startsWith("target/")
                        || relative.equals("wren-source.properties")
                        || relative.startsWith(".wren/")
                        || relative.startsWith("knowledge/questions/")
                        || relative.startsWith("knowledge/sql/")) continue;
                content.append(relative)
                        .append('\0')
                        .append(digest(Files.readString(path, StandardCharsets.UTF_8)))
                        .append('\n');
            }
            return digest(content.toString());
        } catch (IOException e) {
            throw new DatasetException("计算模型验证指纹失败：" + e.getMessage(), e);
        }
    }

    private Receipt readReceipt(String groupId, String id) {
        Path path = receiptPath(groupId, id);
        if (!Files.isRegularFile(path)) return null;
        try {
            return JSON.readValue(Files.readString(path, StandardCharsets.UTF_8), Receipt.class);
        } catch (IOException e) {
            throw new DatasetException("读取验证凭据失败：" + e.getMessage(), e);
        }
    }

    private Path receiptPath(String groupId, String id) {
        checkId(id);
        return workspace.workspaceRoot(groupId).resolve(".platform/questions/" + id + ".json");
    }

    private void writeReceipt(String groupId, String id, Receipt receipt) {
        try {
            write(receiptPath(groupId, id), JSON.writeValueAsString(receipt));
        } catch (IOException e) {
            throw new DatasetException("保存验证凭据失败：" + e.getMessage(), e);
        }
    }

    private static String questionHash(Question question) {
        try {
            return digest(JSON.writeValueAsString(question));
        } catch (IOException e) {
            throw new DatasetException("计算问题验证指纹失败", e);
        }
    }

    /** Accepts old receipt hashes without making a retired classification change the semantics. */
    private static boolean matchesQuestionHash(Question question, String expected) {
        if (questionHash(question).equals(expected)) return true;
        com.fasterxml.jackson.databind.node.ObjectNode legacy = JSON.valueToTree(question);
        for (boolean required : new boolean[] {true, false}) {
            legacy.put("required", required);
            try {
                if (digest(JSON.writeValueAsString(legacy)).equals(expected)) return true;
            } catch (IOException e) {
                throw new DatasetException("计算历史问题验证指纹失败", e);
            }
        }
        return false;
    }

    private static void checkId(String id) {
        if (id == null || !ID.matcher(id).matches())
            throw new DatasetException("问题 ID 只允许字母、数字、下划线和横线，最长 64 字符", 400);
    }

    private static String digest(String value) {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void write(Path path, String content) {
        try {
            Files.createDirectories(path.getParent());
            Path temp = Files.createTempFile(path.getParent(), ".question-", ".tmp");
            try {
                Files.writeString(temp, content, StandardCharsets.UTF_8);
                Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING);
            } finally {
                Files.deleteIfExists(temp);
            }
        } catch (IOException e) {
            throw new DatasetException("保存常用问题状态失败：" + e.getMessage(), e);
        }
    }

    private static String exampleMarkdown(Question question, Receipt receipt) {
        return "---\nnl: "
                + MdlPublishService.yamlScalar(question.question())
                + "\nsql: "
                + MdlPublishService.yamlScalar(question.sql())
                + "\nsource: user"
                + "\nverified: true\nvalidation_id: "
                + receipt.validationId()
                + "\nmodel_hash: "
                + receipt.modelHash()
                + "\nconfirmed_by: "
                + MdlPublishService.yamlScalar(receipt.confirmedBy())
                + "\n---\n\n# 业务口径\n\n"
                + question.definition()
                + "\n\n# SQL\n\n```sql\n"
                + question.sql()
                + "\n```\n";
    }
}
