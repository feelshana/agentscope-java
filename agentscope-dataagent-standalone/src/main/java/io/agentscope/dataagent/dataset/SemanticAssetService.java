package io.agentscope.dataagent.dataset;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.dataagent.runtime.wren.WrenQueryGateway;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;

/**
 * Review, edit and question verification of Views and ref_sql models (ADR0123, spec107). The
 * assistant drafts these files; everything here is driven by a person holding MANAGE.
 */
@Service
public class SemanticAssetService {
    static final int MAX_CHECKS = 20;
    private static final int SHOWN_ROWS = 20;
    private static final String EXPECTED = "EXPECTED";
    private static final String PROBLEM = "PROBLEM";
    private static final String NAME = "[\\p{L}\\p{N}_]+";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

    private final DatasetGroupService groups;
    private final AssetReviewStore reviews;
    private final MdlPublishService publisher;
    private final MdlWorkspaceService workspace;
    private final MdlWorkspaceReader reader;
    private final FactQueryWindowPolicy windows;
    private final WrenQueryGateway gateway;
    private final AssetSqlGenerator generator;
    private final MdlQuestionService questions;
    private final AssetProposalStore proposals;

    /** spec109: what deleting an asset would touch — rule entries naming it, questions using it. */
    public record Impact(List<String> rules, List<String> questions) {}

    public List<AssetProposalStore.Proposal> proposals(String actor, String groupId) {
        groups.getGroup(actor, groupId);
        return proposals.list(groupId);
    }

    public AssetProposalStore.Proposal decideProposal(
            String actor, String groupId, String id, String status) {
        groups.getGroup(actor, groupId);
        return proposals.decide(groupId, id, status, actor);
    }

    public Impact impact(String actor, String groupId, String kind, String name) {
        groups.getGroup(actor, groupId);
        location(kind, name);
        reviews.require(groupId, kind, name);
        List<String> rules = new ArrayList<>();
        for (var line : ruleLines(workspace.workspaceRoot(groupId), name))
            rules.add(line.getValue());
        List<String> used = new ArrayList<>();
        if (questions != null)
            for (var review : questions.list(new DatasetScope(actor, List.of(groupId)), groupId))
                if (PublishedAssetWindows.names(review.question().sql())
                        .contains(name.toLowerCase(java.util.Locale.ROOT)))
                    used.add(review.question().question());
        return new Impact(rules, used);
    }

    /**
     * Deletes an asset from the draft together with the rule entries naming it, so the query
     * assistant is never pointed at a missing model. Questions using it then need review
     * (spec108). Nothing changes online until the draft is published.
     */
    public void delete(String actor, String groupId, String kind, String name) {
        groups.getGroup(actor, groupId);
        String directory = location(kind, name);
        workspace.withWorkspaceLock(
                groupId,
                () -> {
                    reviews.require(groupId, kind, name);
                    Path root = workspace.workspaceRoot(groupId);
                    Path target = root.resolve(directory).normalize();
                    if (!target.startsWith(
                            root.resolve(
                                    kind.equals(AssetReviewStore.KIND_VIEW) ? "views" : "models")))
                        throw new DatasetException("工程路径越界", 403);
                    try {
                        List<Path> files;
                        try (var walk = Files.walk(target)) {
                            files = walk.sorted(java.util.Comparator.reverseOrder()).toList();
                        }
                        for (Path file : files) {
                            if (Files.isRegularFile(file))
                                DraftChangeLog.record(
                                        root,
                                        root.relativize(file).toString().replace('\\', '/'),
                                        ModelingTurnPurpose.EDIT,
                                        actor);
                            Files.delete(file);
                        }
                        Map<Path, List<String>> keep = new LinkedHashMap<>();
                        for (var line : ruleLines(root, name))
                            keep.putIfAbsent(line.getKey(), null);
                        for (Path file : keep.keySet()) {
                            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
                            lines.removeIf(l -> isRuleEntry(l) && l.contains(name));
                            Files.writeString(
                                    file, String.join("\n", lines) + "\n", StandardCharsets.UTF_8);
                            DraftChangeLog.record(
                                    root,
                                    root.relativize(file).toString().replace('\\', '/'),
                                    ModelingTurnPurpose.EDIT,
                                    actor);
                        }
                    } catch (IOException e) {
                        throw new DatasetException("删除语义资产失败：" + e.getMessage(), e);
                    }
                    groups.markMdlDirty(actor, groupId);
                    return null;
                });
    }

    /** Rule entries (bullets under knowledge/rules/) that name the asset, with their file. */
    private static List<Map.Entry<Path, String>> ruleLines(Path root, String name) {
        List<Map.Entry<Path, String>> out = new ArrayList<>();
        Path dir = root.resolve("knowledge/rules");
        if (!Files.isDirectory(dir)) return out;
        try (var list = Files.list(dir)) {
            for (Path file : list.filter(p -> p.toString().endsWith(".md")).sorted().toList())
                for (String line : Files.readAllLines(file, StandardCharsets.UTF_8))
                    if (isRuleEntry(line) && line.contains(name))
                        out.add(Map.entry(file, line.strip()));
        } catch (IOException e) {
            throw new DatasetException("读取业务规则失败：" + e.getMessage(), e);
        }
        return out;
    }

    private static boolean isRuleEntry(String line) {
        String s = line.strip();
        return s.startsWith("- ") || s.startsWith("* ");
    }

    public SemanticAssetService(
            DatasetGroupService groups,
            AssetReviewStore reviews,
            MdlPublishService publisher,
            MdlWorkspaceService workspace,
            MdlWorkspaceReader reader,
            FactQueryWindowPolicy windows,
            WrenQueryGateway gateway,
            AssetSqlGenerator generator) {
        this(groups, reviews, publisher, workspace, reader, windows, gateway, generator, null);
    }

    public SemanticAssetService(
            DatasetGroupService groups,
            AssetReviewStore reviews,
            MdlPublishService publisher,
            MdlWorkspaceService workspace,
            MdlWorkspaceReader reader,
            FactQueryWindowPolicy windows,
            WrenQueryGateway gateway,
            AssetSqlGenerator generator,
            MdlQuestionService questions) {
        this(
                groups,
                reviews,
                publisher,
                workspace,
                reader,
                windows,
                gateway,
                generator,
                questions,
                new AssetProposalStore(workspace));
    }

    @org.springframework.beans.factory.annotation.Autowired
    public SemanticAssetService(
            DatasetGroupService groups,
            AssetReviewStore reviews,
            MdlPublishService publisher,
            MdlWorkspaceService workspace,
            MdlWorkspaceReader reader,
            FactQueryWindowPolicy windows,
            WrenQueryGateway gateway,
            AssetSqlGenerator generator,
            MdlQuestionService questions,
            AssetProposalStore proposals) {
        this.proposals = proposals;
        this.groups = groups;
        this.reviews = reviews;
        this.publisher = publisher;
        this.workspace = workspace;
        this.reader = reader;
        this.windows = windows;
        this.gateway = gateway;
        this.generator = generator;
        this.questions = questions;
    }

    public record SourceFile(String path, String content) {}

    public record OutputColumn(String name, String type) {}

    public record Detail(
            AssetReviewStore.Asset asset,
            List<SourceFile> files,
            boolean needsTimeColumn,
            List<String> fixedDates,
            List<OutputColumn> outputColumns,
            // spec110: risks the reviewer must see, e.g. window or aggregation over a limited
            // table.
            List<String> warnings) {}

    private static final java.util.regex.Pattern WINDOW_OR_AGGREGATE =
            java.util.regex.Pattern.compile(
                    "(?is)\\bover\\s*\\(|\\bgroup\\s+by\\b|\\b(count|sum|avg|min|max)\\s*\\(");

    /**
     * A query's outer date filter cannot reach inside a window or aggregation, so such an asset
     * over a limited fact table computes over all of it (ADR0127: allowed after a warning).
     */
    static List<String> warnings(String sql, boolean limited) {
        if (!limited || sql == null || !WINDOW_OR_AGGREGATE.matcher(sql).find()) return List.of();
        return List.of(
                "这个资产依赖有查询时间限制的事实表，并且含窗口函数或聚合：问数时加在外层的日期条件推不进内部计算，"
                        + "数据库会先计算全部数据，窗口或聚合的统计范围是全部数据而不是查询的日期范围。"
                        + "请确认描述中写明了统计范围，再决定是否发布。");
    }

    /** One verification: the business question and the SQL that answers it from the asset. */
    public record VerifyCase(String question, String sql) {}

    public record Table(List<Object> columns, List<Object> rows, int rowCount, boolean truncated) {}

    /**
     * One verification: {@code expanded} is the SQL Wren would send after expanding the asset
     * (spec110: nothing is executed, so {@code draft}/{@code published} stay null).
     */
    public record Check(
            String key,
            String label,
            String sql,
            Table draft,
            Table published,
            String error,
            String expanded) {}

    public List<AssetReviewStore.Asset> list(String actor, String groupId) {
        groups.getGroup(actor, groupId);
        return workspace.withWorkspaceLock(groupId, () -> reviews.assets(groupId));
    }

    public Detail get(String actor, String groupId, String kind, String name) {
        groups.getGroup(actor, groupId);
        return workspace.withWorkspaceLock(groupId, () -> detail(groupId, kind, name));
    }

    private Detail detail(String groupId, String kind, String name) {
        var asset = reviews.require(groupId, kind, name);
        Path root = workspace.workspaceRoot(groupId);
        List<SourceFile> files = new ArrayList<>();
        for (String file : List.of("metadata.yml", "sql.yml", "ref_sql.sql")) {
            String path = asset.directory() + "/" + file;
            if (!Files.isRegularFile(root.resolve(path))) continue;
            try {
                files.add(new SourceFile(path, Files.readString(root.resolve(path))));
            } catch (IOException e) {
                throw new DatasetException("读取语义资产失败：" + e.getMessage(), e);
            }
        }
        boolean limited = needsTimeColumn(groupId, name);
        return new Detail(
                asset,
                files,
                limited,
                AssetReviewStore.fixedDates(asset.sql()),
                outputColumns(root, asset),
                warnings(asset.sql(), limited));
    }

    /** The columns the asset declares; a time column must be one a query can actually filter. */
    private static List<OutputColumn> outputColumns(Path root, AssetReviewStore.Asset asset) {
        List<OutputColumn> columns = new ArrayList<>();
        Path metadata = root.resolve(asset.directory()).resolve("metadata.yml");
        if (!Files.isRegularFile(metadata)) return columns;
        try {
            for (JsonNode column : YAML.readTree(metadata.toFile()).path("columns")) {
                String name = column.path("name").asText("");
                if (!name.isBlank())
                    columns.add(new OutputColumn(name, column.path("type").asText("")));
            }
        } catch (IOException e) {
            throw new DatasetException("读取语义资产失败：" + e.getMessage(), e);
        }
        return columns;
    }

    /** Hand edits land in the shared draft and void any earlier conclusion on the asset. */
    public Detail save(
            String actor,
            String groupId,
            String kind,
            String name,
            String file,
            String content,
            String expectedHash) {
        groups.getGroup(actor, groupId);
        Path relative = Path.of(location(kind, name), file);
        if (!allowedFile(kind, file)) throw new DatasetException("不支持编辑该文件：" + file, 400);
        if (file.endsWith(".yml")) requireYaml(content);
        return workspace.withWorkspaceLock(
                groupId,
                () -> {
                    Path root = workspace.workspaceRoot(groupId);
                    Path target = root.resolve(relative).normalize();
                    if (!target.startsWith(root)) throw new DatasetException("工程路径越界", 403);
                    String current = AssetReviewStore.hash(target.getParent());
                    if (expectedHash != null && !expectedHash.equals(current))
                        throw new DatasetException("语义资产已被其他修改更新，请刷新后重试", 409);
                    // Without metadata.yml the asset is skipped, leaving a half-written folder.
                    if (!"metadata.yml".equals(file)
                            && !Files.isRegularFile(target.resolveSibling("metadata.yml")))
                        throw new DatasetException("请先保存 metadata.yml，再保存 " + file, 400);
                    try {
                        Files.createDirectories(target.getParent());
                        Files.writeString(target, content, StandardCharsets.UTF_8);
                    } catch (IOException e) {
                        throw new DatasetException("保存语义资产失败：" + e.getMessage(), e);
                    }
                    DraftChangeLog.record(
                            root,
                            relative.toString().replace('\\', '/'),
                            ModelingTurnPurpose.EDIT,
                            actor);
                    groups.markMdlDirty(actor, groupId);
                    return detail(groupId, kind, name);
                });
    }

    public Detail review(
            String actor, String groupId, String kind, String name, List<String> timeColumns) {
        groups.getGroup(actor, groupId);
        return workspace.withWorkspaceLock(
                groupId,
                () -> {
                    var asset = reviews.require(groupId, kind, name);
                    List<String> columns =
                            timeColumns == null
                                    ? List.of()
                                    : timeColumns.stream()
                                            .filter(c -> c != null && !c.isBlank())
                                            .map(String::strip)
                                            .distinct()
                                            .toList();
                    if (columns.isEmpty() && needsTimeColumn(groupId, name))
                        throw new DatasetException("该资产依赖受限事实表，请先确认时间列", 422);
                    var known =
                            outputColumns(workspace.workspaceRoot(groupId), asset).stream()
                                    .map(OutputColumn::name)
                                    .toList();
                    if (!known.isEmpty() && !known.containsAll(columns))
                        throw new DatasetException("时间列必须是资产的输出列", 422);
                    reviews.save(
                            groupId,
                            asset,
                            new AssetReviewStore.Review(
                                    asset.hash(),
                                    actor,
                                    Instant.now().toString(),
                                    columns,
                                    List.of(),
                                    Map.of(),
                                    null,
                                    null,
                                    false));
                    return detail(groupId, kind, name);
                });
    }

    /** Drafts SQL for a question from this asset alone; nothing runs until the user says so. */
    public String generate(
            String actor, String groupId, String kind, String name, String question) {
        groups.getGroup(actor, groupId);
        if (question == null || question.isBlank()) throw new DatasetException("请输入业务问题", 400);
        var prompt =
                workspace.withWorkspaceLock(
                        groupId,
                        () -> {
                            var asset = requireReviewed(groupId, kind, name);
                            Integer days =
                                    draftWindows(groupId).rules().stream()
                                            .filter(r -> r.table().equals(asset.name()))
                                            .map(SqlWindowGate.Rule::maxWindowDays)
                                            .findFirst()
                                            .orElse(null);
                            var columns =
                                    outputColumns(workspace.workspaceRoot(groupId), asset).stream()
                                            .map(OutputColumn::name)
                                            .toList();
                            return new Prompt(asset, columns, days);
                        });
        String sql =
                generator.generate(
                        RuntimeContext.builder().userId(actor).build(),
                        prompt.asset().name(),
                        prompt.asset().description(),
                        prompt.columns(),
                        prompt.asset().review().timeColumns(),
                        prompt.maxDays(),
                        question.strip());
        requireUsesAsset(sql, name);
        return sql;
    }

    private record Prompt(AssetReviewStore.Asset asset, List<String> columns, Integer maxDays) {}

    /** A query that never touches the asset says nothing about it, so it cannot pass it. */
    private static void requireUsesAsset(String sql, String name) {
        if (!PublishedAssetWindows.names(sql).contains(name.toLowerCase(java.util.Locale.ROOT)))
            throw new DatasetException("这条 SQL 没有用到资产「" + name + "」，不能作为验证", 422);
    }

    /** Runs each case on the draft and the published version; only outcome keys persist. */
    public List<Check> verify(
            String actor, String groupId, String kind, String name, List<VerifyCase> cases) {
        groups.getGroup(actor, groupId);
        var asset =
                workspace.withWorkspaceLock(groupId, () -> requireReviewed(groupId, kind, name));
        Map<String, Check> checks = new LinkedHashMap<>();
        for (VerifyCase item : cases == null ? List.<VerifyCase>of() : cases) {
            if (item == null || item.sql() == null || item.sql().isBlank()) continue;
            String sql = item.sql().strip();
            String key = "sql:" + sha(sql);
            String question = item.question() == null ? "" : item.question().strip();
            checks.put(key, check(key, question.isBlank() ? sql : question, sql));
        }
        if (checks.isEmpty()) throw new DatasetException("请至少添加一个验证问题", 400);
        if (checks.size() > MAX_CHECKS)
            throw new DatasetException("单次最多验证 " + MAX_CHECKS + " 条", 400);

        PublishedAssetWindows.Derived draft = draftWindows(groupId);
        List<Check> runnable = new ArrayList<>();
        for (Check check : List.copyOf(checks.values())) {
            try {
                requireUsesAsset(check.sql(), name);
                MdlQuestionStore.requireReadSql(check.sql());
                // One context per check: a refusal must not stop the remaining questions.
                windows.requireAllowed(
                        RuntimeContext.builder().userId(actor).build(),
                        groupId,
                        check.sql(),
                        "asset verify",
                        draft);
                runnable.add(check);
            } catch (DatasetException e) {
                checks.put(check.key(), failed(check, e.getMessage()));
            }
        }
        if (!runnable.isEmpty()) {
            // spec110 / ADR0127: verification reads SQL only; the plan never reaches the database.
            groups.getGroup(actor, groupId);
            var plans = publisher.planDraft(groupId, runnable.stream().map(Check::sql).toList());
            for (int i = 0; i < runnable.size(); i++) {
                Check check = runnable.get(i);
                var plan = plans.get(i);
                checks.put(
                        check.key(),
                        plan.error() != null
                                ? failed(check, plan.error())
                                : new Check(
                                        check.key(),
                                        check.label(),
                                        check.sql(),
                                        null,
                                        null,
                                        null,
                                        plan.expanded()));
            }
        }
        List<Check> done = List.copyOf(checks.values());
        boolean allRan = done.stream().allMatch(c -> c.error() == null);
        return workspace.withWorkspaceLock(
                groupId,
                () -> {
                    var current = requireReviewed(groupId, kind, name);
                    if (!current.hash().equals(asset.hash()))
                        throw new DatasetException("语义资产在验证期间被修改，请重新验证", 409);
                    reviews.save(
                            groupId,
                            current,
                            current.review()
                                    .withRun(
                                            allRan
                                                    ? done.stream().map(Check::key).toList()
                                                    : List.of()));
                    return done;
                });
    }

    /** Only a person's verdict on every question of the latest run can pass the asset. */
    public Detail verdict(
            String actor, String groupId, String kind, String name, Map<String, String> verdicts) {
        return verdict(actor, groupId, kind, name, verdicts, List.of());
    }

    /**
     * Records the verdicts, then (spec108) saves each listed case the user judged as expected as
     * a confirmed question. A listed case must be one of this run's SQL, so a client cannot store
     * SQL that never ran against the asset. Saving failures leave the verdicts recorded.
     */
    public Detail verdict(
            String actor,
            String groupId,
            String kind,
            String name,
            Map<String, String> verdicts,
            List<VerifyCase> save) {
        groups.getGroup(actor, groupId);
        List<VerifyCase> keep = save == null ? List.of() : save;
        for (VerifyCase item : keep) {
            String key = item == null || item.sql() == null ? "" : "sql:" + sha(item.sql());
            if (verdicts == null || !EXPECTED.equals(verdicts.get(key)))
                throw new DatasetException("只能保存本次运行且判定为符合预期的问题", 400);
            if (item.question() == null || item.question().isBlank())
                throw new DatasetException("保存为问答时需要问题文字", 400);
        }
        Detail detail = recordVerdicts(actor, groupId, kind, name, verdicts);
        if (keep.isEmpty() || questions == null) return detail;
        String label = "view".equals(kind) ? "查询模板" : "加工表";
        List<String> failed = new ArrayList<>();
        for (VerifyCase item : keep) {
            try {
                questions.confirmVerified(
                        new DatasetScope(actor, List.of(groupId)),
                        groupId,
                        item.question(),
                        "来源："
                                + label
                                + "「"
                                + name
                                + "」的问题验证，结果符合预期\n口径：按"
                                + label
                                + "「"
                                + name
                                + "」统计",
                        item.sql());
            } catch (DatasetException e) {
                failed.add("「" + item.question() + "」：" + e.getMessage());
            }
        }
        if (!failed.isEmpty())
            throw new DatasetException("判定已保存，但以下问题未能保存为问答：" + String.join("；", failed), 409);
        return detail;
    }

    private Detail recordVerdicts(
            String actor, String groupId, String kind, String name, Map<String, String> verdicts) {
        groups.getGroup(actor, groupId);
        return workspace.withWorkspaceLock(
                groupId,
                () -> {
                    var asset = requireReviewed(groupId, kind, name);
                    var review = asset.review();
                    Set<String> ran = new LinkedHashSet<>(review.ran());
                    if (ran.isEmpty()) throw new DatasetException("请先运行问题验证，且全部执行成功", 409);
                    if (verdicts == null || !verdicts.keySet().equals(ran))
                        throw new DatasetException("请对本次运行的每个问题给出判定", 400);
                    if (!Set.of(EXPECTED, PROBLEM).containsAll(verdicts.values()))
                        throw new DatasetException("判定只能是符合预期或有问题", 400);
                    boolean passed = !verdicts.containsValue(PROBLEM);
                    reviews.save(
                            groupId,
                            asset,
                            review.withVerdicts(
                                    Map.copyOf(verdicts), actor, Instant.now().toString(), passed));
                    return detail(groupId, kind, name);
                });
    }

    private AssetReviewStore.Asset requireReviewed(String groupId, String kind, String name) {
        var asset = reviews.require(groupId, kind, name);
        if (asset.review() == null) throw new DatasetException("请先完成核查，再用问题验证", 409);
        return asset;
    }

    private boolean needsTimeColumn(String groupId, String name) {
        return PublishedAssetWindows.derive(manifest(groupId), windows.facts(groupId), Map.of())
                .unconfigured()
                .contains(name);
    }

    private PublishedAssetWindows.Derived draftWindows(String groupId) {
        Map<String, List<String>> columns = new LinkedHashMap<>();
        for (var asset : reviews.assets(groupId))
            if (asset.review() != null) columns.put(asset.name(), asset.review().timeColumns());
        return PublishedAssetWindows.derive(manifest(groupId), windows.facts(groupId), columns);
    }

    /** The draft in the shape of the published manifest, so both share dependency logic. */
    private JsonNode manifest(String groupId) {
        var snapshot = reader.read(groupId);
        ObjectNode root = JSON.createObjectNode();
        ArrayNode models = root.putArray("models");
        for (var model : snapshot.models()) {
            ObjectNode node = models.addObject().put("name", model.name());
            if (model.refSql() != null) node.put("refSql", model.refSql());
            if (model.tableName() != null)
                node.putObject("tableReference").put("table", model.tableName());
        }
        ArrayNode views = root.putArray("views");
        for (var view : snapshot.views())
            views.addObject().put("name", view.name()).put("statement", view.sql());
        return root;
    }

    private static Check check(String key, String label, String sql) {
        return new Check(key, label, sql, null, null, null, null);
    }

    private static Check failed(Check check, String error) {
        return new Check(check.key(), check.label(), check.sql(), null, null, error, null);
    }

    private static Table table(String payload) {
        if (payload == null) return null;
        try {
            JsonNode node = JSON.readTree(payload);
            if (node != null && node.isTextual()) node = JSON.readTree(node.asText());
            if (node == null || !node.path("rows").isArray())
                throw new DatasetException("Wren 未返回结构化查询结果");
            List<Object> rows = new ArrayList<>();
            for (JsonNode row : node.path("rows")) {
                if (rows.size() >= SHOWN_ROWS) break;
                rows.add(JSON.convertValue(row, Object.class));
            }
            return new Table(
                    JSON.convertValue(node.path("columns"), JSON.constructType(List.class)),
                    rows,
                    node.path("rows").size(),
                    node.path("truncated").asBoolean(false) || node.path("rows").size() >= 1000);
        } catch (IOException e) {
            throw new DatasetException("Wren 未返回结构化查询结果", e);
        }
    }

    private static String location(String kind, String name) {
        if (name == null || !name.matches(NAME)) throw new DatasetException("资产名称无效", 400);
        if (AssetReviewStore.KIND_VIEW.equals(kind)) return "views/" + name;
        if (AssetReviewStore.KIND_REF_SQL.equals(kind)) return "models/" + name;
        throw new DatasetException("不支持的资产类型：" + kind, 400);
    }

    private static boolean allowedFile(String kind, String file) {
        return AssetReviewStore.KIND_VIEW.equals(kind)
                ? List.of("metadata.yml", "sql.yml").contains(file)
                : List.of("metadata.yml", "ref_sql.sql").contains(file);
    }

    private static void requireYaml(String content) {
        try {
            YAML.readTree(content);
        } catch (IOException e) {
            throw new DatasetException("YAML 格式错误：" + e.getMessage(), 422);
        }
    }

    private static String sha(String text) {
        try {
            return HexFormat.of()
                    .formatHex(
                            java.security.MessageDigest.getInstance("SHA-256")
                                    .digest(text.getBytes(StandardCharsets.UTF_8)))
                    .substring(0, 16);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
