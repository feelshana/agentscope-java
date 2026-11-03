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
            Evidence evidence,
            // spec112: what the confirmed SQL depended on, and where the confirmation came from.
            Map<String, String> dependencies,
            String origin) {
        Receipt(
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
                Evidence evidence) {
            this(
                    validationId,
                    questionHash,
                    modelHash,
                    status,
                    executedAt,
                    result,
                    error,
                    truncated,
                    confirmedBy,
                    confirmedAt,
                    evidence,
                    null,
                    null);
        }
    }

    /** One line of a question's analysis: something reusable ({@code ok}) or a gap. */
    public record Finding(boolean ok, String text) {}

    static final int MAX_FINDINGS = 5;

    /** spec112: a confirmation that came from a semantic asset's question verification. */
    public static final String ORIGIN_ASSET_VERIFY = "asset-verify";

    public record Evidence(String sql, List<MdlWorkspaceReader.WorkspaceView> views) {}

    public record Review(
            Question question,
            String status,
            Receipt validation,
            Coverage coverage,
            Publication published,
            String revision,
            // spec112: why a confirmed question needs review (status REVIEW); empty otherwise.
            List<String> reviewReasons,
            // spec114 §8: the assistant's per-question analysis; kept out of Question so that
            // re-analysing never invalidates a confirmation.
            List<Finding> analysis) {
        public Review(
                Question question,
                String status,
                Receipt validation,
                Coverage coverage,
                Publication published,
                String revision,
                List<String> reviewReasons) {
            this(
                    question,
                    status,
                    validation,
                    coverage,
                    published,
                    revision,
                    reviewReasons,
                    List.of());
        }

        public Review(Question question, String status, Receipt validation) {
            this(question, status, validation, null, null, questionHash(question), List.of());
        }

        public Review(
                Question question,
                String status,
                Receipt validation,
                Coverage coverage,
                Publication published) {
            this(
                    question,
                    status,
                    validation,
                    coverage,
                    published,
                    questionHash(question),
                    List.of());
        }

        public Review(
                Question question,
                String status,
                Receipt validation,
                Coverage coverage,
                Publication published,
                String revision) {
            this(question, status, validation, coverage, published, revision, List.of());
        }
    }

    /**
     * Runs a question change and its modeling-revision bump under one workspace lock, so a
     * publish can never snapshot the change without also covering its revision.
     */
    public <T> T locked(String groupId, java.util.function.Supplier<T> action) {
        return workspace.withWorkspaceLock(groupId, action);
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
        List<Review> out = new ArrayList<>();
        QuestionDependencies.Project draft = null;
        QuestionDependencies.Project published = null;
        for (Question question : readQuestions(root)) {
            Receipt receipt = readReceipt(groupId, question.id());
            String status = receipt == null ? "DRAFT" : receipt.status();
            List<String> reasons = List.of();
            if (receipt != null && !matchesQuestionHash(question, receipt.questionHash())) {
                status = "STALE";
            } else if ("CONFIRMED".equals(status)) {
                // spec112: a confirmation also binds to what its SQL depends on; when that
                // changed in the draft, the question needs review before it is an example again.
                if (draft == null) draft = QuestionDependencies.read(root);
                Map<String, String> before = receipt.dependencies();
                if (before == null) {
                    // Confirmed before spec112: the published version is the baseline.
                    if (published == null)
                        published =
                                QuestionDependencies.read(root.getParent().resolve("published"));
                    before =
                            published.nodes().isEmpty()
                                    ? null
                                    : QuestionDependencies.snapshot(published, question.sql());
                }
                if (before != null) {
                    reasons =
                            QuestionDependencies.changes(
                                    before, QuestionDependencies.snapshot(draft, question.sql()));
                    if (!reasons.isEmpty()) status = "REVIEW";
                }
            }
            Review review = review(groupId, question, status, receipt);
            out.add(
                    new Review(
                            review.question(),
                            review.status(),
                            review.validation(),
                            review.coverage(),
                            review.published(),
                            review.revision(),
                            reasons,
                            readAnalysis(root, question.id())));
        }
        return out;
    }

    /**
     * Reads the optional {@code analysis} list the coach writes into a question file. The page
     * only shows it, so anything malformed is dropped instead of failing the question list.
     */
    static List<Finding> readAnalysis(Path root, String id) {
        Path file = root.resolve("knowledge/questions/" + id + ".yml");
        try {
            JsonNode items =
                    YAML.readTree(Files.readString(file, StandardCharsets.UTF_8)).path("analysis");
            if (!items.isArray()) return List.of();
            List<Finding> out = new ArrayList<>();
            for (JsonNode item : items) {
                if (out.size() >= MAX_FINDINGS) break;
                String text = item.path("text").asText("").strip();
                if (!item.path("ok").isBoolean() || text.isEmpty() || text.length() > 200) continue;
                out.add(new Finding(item.path("ok").asBoolean(), text));
            }
            return List.copyOf(out);
        } catch (IOException | RuntimeException e) {
            return List.of();
        }
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
                // SQL policy is enforced on confirm, trial run and correction, so one invalid
                // agent draft cannot hide the rest of the list.
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
                || Pattern.compile(
                                "(?i)\\b(insert|update|delete|drop|alter|create|merge|call|into|outfile|load_file|sleep|benchmark)\\b")
                        .matcher(value)
                        .find()) {
            throw new DatasetException("验证 SQL 仅允许单条 SELECT/WITH，禁止写操作", 400);
        }
        // Same lexical policy as Wren CLI/MCP: read-only and no cartesian joins. Official
        // LIMIT/OFFSET is allowed (Top-N questions); truncated results still cannot be confirmed.
        io.agentscope.dataagent.tools.data.OfficialWrenToolkit.requireReadOnly(value);
    }

    private static ModelingPlan readPlan(JsonNode node, String id) {
        if (node.isMissingNode() || node.isNull()) return null;
        String strategy = node.path("strategy").asText("");
        String reason = node.path("reason").asText("").trim();
        if (!node.isObject()
                || !List.of("MODEL", "VIEW", "EXAMPLE").contains(strategy)
                || reason.isBlank()
                || reason.length() > 4000) {
            throw new DatasetException(
                    "问题须声明 modeling.strategy（MODEL/VIEW/EXAMPLE）和业务理由：" + id, 400);
        }
        List<AssetRef> assets = new ArrayList<>();
        JsonNode refs = node.path("assets");
        if (!refs.isMissingNode() && !refs.isArray())
            throw new DatasetException("modeling.assets 必须为数组：" + id, 400);
        for (JsonNode ref : refs) {
            String kind = ref.path("kind").asText("");
            String name = ref.path("name").asText("");
            if (!List.of("MODEL", "VIEW").contains(kind)
                    || !name.matches("[\\p{L}_][\\p{L}\\p{N}_]{0,63}")) {
                throw new DatasetException("问题资产须使用 MODEL/VIEW 和安全的逻辑名称：" + id, 400);
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

    /**
     * Confirms or returns a question directly (spec091): no prior execution is required. Accepting
     * re-checks the SQL policy and asset coverage; time-window gating is the caller's job.
     */
    public Review decide(String groupId, String id, boolean accepted, String ownerId) {
        return decide(groupId, id, accepted, ownerId, null);
    }

    /** {@code origin} marks where an accepted confirmation came from; null for the trial panel. */
    public Review decide(
            String groupId, String id, boolean accepted, String ownerId, String origin) {
        return workspace.withWorkspaceLock(
                groupId,
                () -> {
                    Question question = requireQuestion(groupId, id);
                    if (accepted) {
                        if (question.sql().isBlank())
                            throw new DatasetException("本题尚未生成 SQL，不能确认", 409);
                        requireReadSql(question.sql());
                        requireCoverage(groupId, question);
                    }
                    Receipt previous = readReceipt(groupId, id);
                    boolean sameQuestion =
                            previous != null
                                    && matchesQuestionHash(question, previous.questionHash());
                    Receipt decided =
                            new Receipt(
                                    sameQuestion
                                            ? previous.validationId()
                                            : UUID.randomUUID().toString(),
                                    questionHash(question),
                                    modelHash(workspace.workspaceRoot(groupId)),
                                    accepted ? "CONFIRMED" : "REJECTED",
                                    sameQuestion ? previous.executedAt() : null,
                                    sameQuestion ? previous.result() : null,
                                    null,
                                    sameQuestion && previous.truncated(),
                                    accepted ? ownerId : null,
                                    accepted ? Instant.now().toString() : null,
                                    sameQuestion ? previous.evidence() : null,
                                    accepted
                                            ? QuestionDependencies.snapshot(
                                                    QuestionDependencies.read(
                                                            workspace.workspaceRoot(groupId)),
                                                    question.sql())
                                            : null,
                                    // Re-confirming after a review keeps where it came from.
                                    !accepted
                                            ? null
                                            : origin != null
                                                    ? origin
                                                    : previous == null ? null : previous.origin());
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

    /**
     * spec112: writes a question verified against a semantic asset, ready to be confirmed. A
     * question with the same text is updated in place instead of duplicated; returns its id.
     */
    public String saveVerifiedQuestion(
            String groupId, String question, String definition, String sql) {
        String text = question == null ? "" : question.strip();
        if (text.isEmpty() || text.length() > 1000)
            throw new DatasetException("验证问题不能为空且不超过 1000 字", 400);
        requireReadSql(sql);
        return workspace.withWorkspaceLock(
                groupId,
                () -> {
                    Path root = workspace.workspaceRoot(groupId);
                    List<Question> existing = readQuestions(root);
                    String id =
                            existing.stream()
                                    .filter(q -> q.question().equals(text))
                                    .map(Question::id)
                                    .findFirst()
                                    .orElse(null);
                    if (id == null) {
                        if (existing.size() >= 50)
                            throw new DatasetException("每个知识库最多设置 50 个常用问题", 400);
                        String stamp = Long.toString(System.currentTimeMillis(), 36);
                        int n = 1;
                        while (Files.exists(
                                root.resolve(
                                        "knowledge/questions/q-asset-" + stamp + "-" + n + ".yml")))
                            n++;
                        id = "q-asset-" + stamp + "-" + n;
                    }
                    Path source = root.resolve("knowledge/questions/" + id + ".yml");
                    try {
                        var node =
                                Files.isRegularFile(source)
                                        ? (com.fasterxml.jackson.databind.node.ObjectNode)
                                                YAML.readTree(
                                                        Files.readString(
                                                                source, StandardCharsets.UTF_8))
                                        : YAML.createObjectNode();
                        node.put("question", text);
                        node.put("definition", definition == null ? "" : definition.strip());
                        node.put("sql", sql.strip());
                        var plan = node.putObject("modeling");
                        plan.put("strategy", "EXAMPLE");
                        plan.put("reason", "语义资产问题验证符合预期，由用户确认保存");
                        plan.putArray("assets");
                        write(source, YAML.writeValueAsString(node));
                    } catch (IOException e) {
                        throw new DatasetException("保存验证问题失败：" + e.getMessage(), e);
                    }
                    return id;
                });
    }

    /** Ids of currently confirmed questions with their SQL, for the publish-time dry-plan. */
    public Map<String, String> confirmedSql(String groupId) {
        Map<String, String> out = new java.util.LinkedHashMap<>();
        for (Review review : list(groupId)) {
            if ("CONFIRMED".equals(review.status()))
                out.put(review.question().id(), review.question().sql());
        }
        return out;
    }

    /**
     * Returns a confirmed question to the unconfirmed state after its SQL failed the publish
     * check; the reason stays visible on the question until the user confirms again.
     */
    public void revokeConfirmation(String groupId, String id, String reason) {
        workspace.withWorkspaceLock(
                groupId,
                () -> {
                    Question question = requireQuestion(groupId, id);
                    try {
                        Files.deleteIfExists(
                                workspace
                                        .workspaceRoot(groupId)
                                        .resolve("knowledge/sql/" + id + ".md"));
                    } catch (IOException e) {
                        throw new DatasetException("移除示例失败：" + e.getMessage(), e);
                    }
                    writeReceipt(
                            groupId,
                            id,
                            new Receipt(
                                    UUID.randomUUID().toString(),
                                    questionHash(question),
                                    modelHash(workspace.workspaceRoot(groupId)),
                                    "FAILED",
                                    null,
                                    null,
                                    reason,
                                    false,
                                    null,
                                    null,
                                    null));
                    return null;
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

    /**
     * A question's pair is shipped only while the question is confirmed: a changed SQL (STALE) or
     * a changed dependency (REVIEW, spec112) keeps the old pair out of the snapshot. Pairs that
     * belong to no question (standalone SQL examples) are left alone. Returns the questions held
     * back for review.
     */
    public List<Review> filterPublishedExamples(String groupId, Path preparedDir) {
        List<Review> reviews = list(groupId);
        Path directory = preparedDir.resolve("knowledge/sql");
        try {
            for (Review review : reviews) {
                if ("CONFIRMED".equals(review.status())) continue;
                Files.deleteIfExists(directory.resolve(review.question().id() + ".md"));
            }
        } catch (IOException e) {
            throw new DatasetException("筛选发布示例失败：" + e.getMessage(), e);
        }
        return reviews.stream().filter(r -> "REVIEW".equals(r.status())).toList();
    }

    /**
     * Excludes receipts, query examples and raw source material, which do not change the modeled
     * query semantics.
     */
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
                        || relative.equals(PublishedAssetWindows.FILE)
                        || relative.startsWith(".wren/")
                        // Source material (documents, samples) never compiles into the MDL.
                        || relative.startsWith("raw/")
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
                // The official markdown sync indexes only `source` (as tag `source:<value>`) and
                // drops the `tags` list, so provenance must live in `source` to reach recall.
                + "\nsource: user-confirmed"
                + "\ntags:\n- source:user-confirmed"
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
