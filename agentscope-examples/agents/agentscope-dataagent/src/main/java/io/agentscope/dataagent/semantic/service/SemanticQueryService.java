package io.agentscope.dataagent.semantic.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.dataagent.dataset.DatasetGroupService;
import io.agentscope.dataagent.dataset.DatasetScope;
import io.agentscope.dataagent.dataset.parser.ColumnSchema;
import io.agentscope.dataagent.semantic.model.SemanticModel;
import io.agentscope.dataagent.semantic.model.SemanticModelTable;
import io.agentscope.dataagent.tools.data.DataSource;
import io.agentscope.dataagent.tools.data.DataSourceRegistry;
import io.agentscope.dataagent.tools.data.SqlConnector;
import io.agentscope.dataagent.web.persistence.jpa.DatasetEntity;
import io.agentscope.dataagent.web.persistence.jpa.DatasetGroupEntity;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

@Service
public class SemanticQueryService {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String WORKFLOW =
            "使用逻辑模型名编写 SQL；先读取规则和字段，再 dry_plan，随后将原始语义 SQL 交给 query_semantic。Cube"
                    + " 可选；只有未配置模型时才使用物理查询工具。";
    private final DatasetGroupService groups;
    private final SemanticModelService models;
    private final DataSourceRegistry registry;
    private final SemanticSqlPlanner planner;
    private final SqlConnector connector;
    private final QueryHistoryService history;

    public SemanticQueryService(
            DatasetGroupService groups,
            SemanticModelService models,
            DataSourceRegistry registry,
            SemanticSqlPlanner planner,
            SqlConnector connector,
            QueryHistoryService history) {
        this.groups = groups;
        this.models = models;
        this.registry = registry;
        this.planner = planner;
        this.connector = connector;
        this.history = history;
    }

    private record Bound(
            Map<String, SemanticSqlPlanner.TableBinding> tables,
            Map<String, DataSource> sources,
            Map<String, String> errors) {}

    public DatasetGroupEntity resolveGroup(DatasetScope scope, String groupId) {
        requireOwner(scope);
        if (groupId != null && !groupId.isBlank()) {
            if (scope.hasGroupFilter() && !scope.groupIds().contains(groupId))
                throw new IllegalArgumentException("知识库不在当前选择范围内");
            return groups.getGroup(scope.ownerId(), groupId);
        }
        List<DatasetGroupEntity> allowed = allowedGroups(scope);
        if (allowed.size() != 1)
            throw new IllegalArgumentException(
                    "请指定 group_id，允许的知识库: "
                            + allowed.stream()
                                    .map(g -> g.getId() + " (" + g.getName() + ")")
                                    .collect(Collectors.joining(", ")));
        return allowed.get(0);
    }

    private List<DatasetGroupEntity> allowedGroups(DatasetScope scope) {
        requireOwner(scope);
        if (!scope.hasGroupFilter()) return groups.listGroups(scope.ownerId());
        return scope.groupIds().stream()
                .distinct()
                .map(id -> groups.getGroup(scope.ownerId(), id))
                .toList();
    }

    private void requireOwner(DatasetScope scope) {
        if (scope == null || scope.ownerId() == null || scope.ownerId().isBlank())
            throw new IllegalArgumentException("缺少用户身份，无法访问语义模型");
    }

    private SemanticModel model(DatasetScope scope, String groupId) {
        return models.getModel(scope.ownerId(), groupId)
                .orElseThrow(() -> new IllegalArgumentException("当前知识库没有语义模型，请先生成模型或使用物理查询工具"));
    }

    public String listModels(
            DatasetScope scope, String groupId, String query, int offset, int limit) {
        return respond(
                "models",
                groupId,
                () -> {
                    List<DatasetGroupEntity> allowed =
                            groupId == null || groupId.isBlank()
                                    ? allowedGroups(scope)
                                    : List.of(resolveGroup(scope, groupId));
                    List<Map<String, Object>> entries = new ArrayList<>();
                    List<Map<String, Object>> libraries = new ArrayList<>();
                    List<String> tokens =
                            query == null
                                    ? List.of()
                                    : java.util.Arrays.stream(
                                                    query.toLowerCase(Locale.ROOT)
                                                            .split("[\\s,，、;；]+"))
                                            .filter(t -> !t.isBlank())
                                            .toList();
                    for (var group : allowed) {
                        libraries.add(
                                Map.of("groupId", group.getId(), "groupName", group.getName()));
                        var optional = models.getModel(scope.ownerId(), group.getId());
                        if (optional.isEmpty()) continue;
                        var mdl = optional.get();
                        var bound = bind(scope, group.getId(), mdl);
                        for (var table : mdl.getModels()) {
                            Map<String, Object> item = modelSummary(table, mdl, bound);
                            String haystack =
                                    (table.getName()
                                                    + " "
                                                    + table.getLabel()
                                                    + " "
                                                    + table.getDescription())
                                            .toLowerCase(Locale.ROOT);
                            if (!tokens.isEmpty() && tokens.stream().noneMatch(haystack::contains))
                                continue;
                            item.put("groupId", group.getId());
                            item.put("groupName", group.getName());
                            entries.add(item);
                        }
                    }
                    var data = page("models", entries, offset, limit);
                    data.put("knowledgeBases", libraries);
                    data.put("nextStep", WORKFLOW);
                    return success("models", groupId, "发现语义模型 · " + entries.size() + " 个模型", data);
                });
    }

    private Map<String, Object> modelSummary(
            SemanticModelTable table, SemanticModel mdl, Bound bound) {
        var item = new LinkedHashMap<String, Object>();
        item.put("name", table.getName());
        item.put("label", table.getLabel());
        item.put("description", table.getDescription());
        item.put("kind", table.getKind());
        item.put("columnCount", table.getColumns().size());
        item.put(
                "cubes",
                mdl.getCubes().stream()
                        .filter(c -> Objects.equals(c.getBaseObject(), table.getName()))
                        .map(
                                c ->
                                        Map.of(
                                                "name",
                                                c.getName(),
                                                "measureCount",
                                                c.getMeasures().size()))
                        .toList());
        item.put("bound", bound.tables().containsKey(table.getName()));
        item.put("bindingError", bound.errors().get(table.getName()));
        return item;
    }

    public String describeModel(
            DatasetScope scope, String groupId, String name, int offset, int limit) {
        return respond(
                "model",
                groupId,
                () -> {
                    var group = resolveGroup(scope, groupId);
                    var mdl = model(scope, group.getId());
                    var table = mdl.findModel(name);
                    if (table == null)
                        throw new IllegalArgumentException("未知逻辑模型，请先调用 list_models");
                    var bound = bind(scope, group.getId(), mdl);
                    List<Map<String, Object>> columns = new ArrayList<>();
                    for (var col : table.getColumns()) {
                        var item =
                                JSON.convertValue(
                                        col, new TypeReference<LinkedHashMap<String, Object>>() {});
                        String reason = null;
                        try {
                            planner.compile(
                                    "SELECT "
                                            + identifier(col.getName())
                                            + " FROM "
                                            + identifier(name),
                                    mdl,
                                    bound.tables());
                        } catch (IllegalArgumentException e) {
                            reason = e.getMessage();
                        }
                        item.put("queryable", reason == null);
                        item.put("unavailableReason", reason);
                        item.put("handle", col.getRelationship() != null);
                        columns.add(item);
                    }
                    var data = page("columns", columns, offset, limit);
                    data.putAll(modelSummary(table, mdl, bound));
                    data.put(
                            "relationships",
                            mdl.getRelationships().stream()
                                    .filter(r -> r.getModels().contains(name))
                                    .toList());
                    data.put(
                            "cubes",
                            mdl.getCubes().stream()
                                    .filter(c -> Objects.equals(c.getBaseObject(), name))
                                    .toList());
                    data.put(
                            "nextStep",
                            "使用 get_semantic_context 补读"
                                    + " rules、limitations、instructions；不可查询的字段会给出原因。");
                    return success(
                            "model",
                            group.getId(),
                            "读取模型 · " + name + " · " + columns.size() + " 个字段",
                            data);
                });
    }

    public String context(
            DatasetScope scope, String groupId, String section, int offset, int limit) {
        return respond(
                "context",
                groupId,
                () -> {
                    var group = resolveGroup(scope, groupId);
                    var mdl = model(scope, group.getId());
                    String instructions =
                            models.getInstructionsText(scope.ownerId(), group.getId());
                    String part = section == null || section.isBlank() ? "overview" : section;
                    Map<String, Object> data;
                    switch (part) {
                        case "rules" -> data = page("rules", mdl.getRules(), offset, limit);
                        case "limitations" ->
                                data = page("limitations", mdl.getLimitations(), offset, limit);
                        case "relationships" ->
                                data = page("relationships", mdl.getRelationships(), offset, limit);
                        case "cubes" -> data = page("cubes", mdl.getCubes(), offset, limit);
                        case "instructions" ->
                                data =
                                        page(
                                                "instructions",
                                                paragraphs(instructions),
                                                offset,
                                                limit);
                        case "overview" -> {
                            var bound = bind(scope, group.getId(), mdl);
                            data =
                                    page(
                                            "models",
                                            mdl.getModels().stream()
                                                    .map(t -> modelSummary(t, mdl, bound))
                                                    .toList(),
                                            offset,
                                            limit);
                            data.put("mode", "summary");
                            data.put(
                                    "sectionTotals",
                                    Map.of(
                                            "rules",
                                            mdl.getRules().size(),
                                            "limitations",
                                            mdl.getLimitations().size(),
                                            "instructions",
                                            paragraphs(instructions).size(),
                                            "relationships",
                                            mdl.getRelationships().size(),
                                            "cubes",
                                            mdl.getCubes().size()));
                            var full = new LinkedHashMap<>(data);
                            full.put(
                                    "schemas",
                                    mdl.getModels().stream()
                                            .map(
                                                    t -> {
                                                        var schema =
                                                                new LinkedHashMap<String, Object>();
                                                        schema.put("name", t.getName());
                                                        schema.put("columns", t.getColumns());
                                                        return schema;
                                                    })
                                            .toList());
                            full.put("rules", mdl.getRules());
                            full.put("limitations", mdl.getLimitations());
                            full.put("relationships", mdl.getRelationships());
                            full.put("cubes", mdl.getCubes());
                            full.put("instructions", paragraphs(instructions));
                            if (offset == 0
                                    && Boolean.TRUE.equals(data.get("contextComplete"))
                                    && json(full).length() < 40000) {
                                data = full;
                                data.put("mode", "full");
                            } else {
                                data.put("contextComplete", false);
                            }
                        }
                        default ->
                                throw new IllegalArgumentException(
                                        "section 支持"
                                            + " overview/rules/limitations/instructions/relationships/cubes");
                    }
                    data.put("section", part);
                    data.put(
                            "nextStep",
                            WORKFLOW
                                    + " 若 contextComplete=false，按 nextOffset 继续读取当前章节，并补读"
                                    + " sectionTotals 中非空的规则/局限/说明；字段使用 describe_model。");
                    return success("context", group.getId(), "读取语义上下文 · " + group.getName(), data);
                });
    }

    public String dryPlan(DatasetScope scope, String groupId, String sql) {
        return respond(
                "plan",
                groupId,
                () -> {
                    var group = resolveGroup(scope, groupId);
                    var mdl = model(scope, group.getId());
                    var bound = bind(scope, group.getId(), mdl);
                    var plan = planner.compile(sql, mdl, bound.tables());
                    source(plan, bound);
                    var data = planData(plan);
                    data.put("validationLevel", "semantic");
                    data.put("databaseValidated", false);
                    data.put(
                            "nextStep",
                            "语义编译通过，尚未执行数据库；使用原始 semanticSql 调用 query_semantic，不要传 compiledSql。");
                    return success("plan", group.getId(), "校验语义 SQL · 编译通过", data);
                });
    }

    public String query(
            DatasetScope scope, String groupId, String sql, String question, int rowLimit) {
        return respond(
                "query",
                groupId,
                () -> {
                    var group = resolveGroup(scope, groupId);
                    var mdl = model(scope, group.getId());
                    var bound = bind(scope, group.getId(), mdl);
                    var plan = planner.compile(sql, mdl, bound.tables());
                    var source = source(plan, bound);
                    var data = planData(plan);
                    data.put("groupName", group.getName());
                    data.put("sourceId", source.id());
                    if (json(data).length() > 35000)
                        throw new IllegalArgumentException("SQL 或编译计划过长，请简化查询");
                    var result = connector.query(source, plan.compiledSql(), rowLimit);
                    data.put("columns", result.columns());
                    data.put("rows", result.rows());
                    data.put("returnedRowCount", result.returnedRowCount());
                    data.put("truncated", result.truncated());
                    data.put("truncationReason", result.truncationReason());
                    data.put("elapsedMs", result.elapsedMs());
                    boolean stored = false;
                    try {
                        history.storeSemantic(
                                group.getId(),
                                question == null ? sql : question,
                                sql,
                                plan.compiledSql(),
                                version(scope, group.getId(), mdl, bound));
                        stored = true;
                    } catch (RuntimeException ignored) {
                        data.put("memoryWarning", "查询成功，但查询记忆未保存。");
                    }
                    data.put("memoryStored", stored);
                    return success(
                            "query",
                            group.getId(),
                            "执行语义查询 · 返回 "
                                    + result.returnedRowCount()
                                    + " 行"
                                    + (result.truncated() ? " · 已截断" : ""),
                            data);
                });
    }

    public String recall(DatasetScope scope, String groupId, String question) {
        return respond(
                "recall",
                groupId,
                () -> {
                    var group = resolveGroup(scope, groupId);
                    var mdl = model(scope, group.getId());
                    var bound = bind(scope, group.getId(), mdl);
                    var queries =
                            new ArrayList<>(
                                    history.recallSemantic(
                                            group.getId(),
                                            question,
                                            version(scope, group.getId(), mdl, bound),
                                            5));
                    if (queries.isEmpty()) {
                        for (var table : mdl.getModels()) {
                            String sql =
                                    "SELECT * FROM " + identifier(table.getName()) + " LIMIT 20";
                            try {
                                planner.compile(sql, mdl, bound.tables());
                            } catch (IllegalArgumentException e) {
                                continue;
                            }
                            queries.add(
                                    Map.of(
                                            "question",
                                            "查看" + table.getName() + "的明细",
                                            "semanticSql",
                                            sql,
                                            "source",
                                            "seed_unexecuted"));
                            if (queries.size() == 3) break;
                        }
                    }
                    return success(
                            "recall",
                            group.getId(),
                            "召回参考查询 · " + queries.size() + " 条",
                            Map.of(
                                    "queries",
                                    queries,
                                    "total",
                                    queries.size(),
                                    "nextStep",
                                    "以上来自知识库历史成功查询（非当前对话记忆）。seed_unexecuted"
                                            + " 是未执行参考；其他是当前版本下执行成功的语义 SQL。仍需核对规则并"
                                            + " dry_plan。"));
                });
    }

    private Bound bind(DatasetScope scope, String groupId, SemanticModel mdl) {
        List<DatasetEntity> datasets = groups.listDatasets(scope.ownerId(), groupId);
        Map<String, SemanticSqlPlanner.TableBinding> tables = new LinkedHashMap<>();
        Map<String, DataSource> sources = new LinkedHashMap<>();
        Map<String, String> errors = new LinkedHashMap<>();
        for (var table : mdl.getModels()) {
            try {
                if (table.getRefSql() != null || table.getBaseObject() != null)
                    throw new IllegalArgumentException("暂不支持 refSql/baseObject");
                String physical = table.getTableName();
                if (physical == null) throw new IllegalArgumentException("模型缺少物理映射");
                String[] parts = physical.replace("`", "").replace("\"", "").split("\\.", -1);
                if (parts.length > 2) throw new IllegalArgumentException("不支持跨 catalog 物理映射");
                String schema = parts.length == 2 ? parts[0] : null;
                if (table.getTableReference() != null) {
                    String declared = table.getTableReference().get("schema");
                    String catalog = table.getTableReference().get("catalog");
                    if (declared == null) declared = catalog;
                    else if (catalog != null && !catalog.equals(declared))
                        throw new IllegalArgumentException("不支持此 catalog/schema 组合");
                    if (schema != null && declared != null && !schema.equals(declared))
                        throw new IllegalArgumentException("物理 schema 定义冲突");
                    if (declared != null) schema = declared;
                }
                final String expectedSchema = schema;
                List<DatasetEntity> matches =
                        datasets.stream()
                                .filter(
                                        d ->
                                                Objects.equals(
                                                                d.getTableName(),
                                                                parts[parts.length - 1])
                                                        && (expectedSchema == null
                                                                || expectedSchema.equals(
                                                                        d.getSchemaName())))
                                .toList();
                if (matches.size() != 1)
                    throw new IllegalArgumentException("物理表映射缺失或有歧义，请登记唯一的数据集及 schema");
                DatasetEntity dataset = matches.get(0);
                DataSource source =
                        registry.findById(dataset.getId())
                                .orElseThrow(() -> new IllegalArgumentException("数据集连接尚未注册"));
                if (!Objects.equals(source.properties().get("ownerId"), scope.ownerId())
                        || !Objects.equals(source.properties().get("groupId"), groupId)
                        || !Objects.equals(
                                source.properties().get("tableName"), dataset.getTableName()))
                    throw new IllegalArgumentException("数据源绑定与知识库归属不一致");
                String url = source.properties().get("jdbcUrl");
                if (url == null || !(url.startsWith("jdbc:mysql:") || url.startsWith("jdbc:h2:")))
                    throw new IllegalArgumentException("当前语义 SQL 仅支持 MySQL（H2 用于测试）");
                List<ColumnSchema> columns =
                        JSON.readValue(
                                dataset.getColumnSchemaJson(),
                                new TypeReference<List<ColumnSchema>>() {});
                tables.put(
                        table.getName(),
                        new SemanticSqlPlanner.TableBinding(
                                dataset.getSchemaName(),
                                dataset.getTableName(),
                                columns.stream()
                                        .map(ColumnSchema::name)
                                        .collect(Collectors.toCollection(LinkedHashSet::new))));
                sources.put(table.getName(), source);
            } catch (Exception e) {
                errors.put(
                        table.getName(),
                        e instanceof IllegalArgumentException ? e.getMessage() : "数据集列绑定无效");
            }
        }
        return new Bound(tables, sources, errors);
    }

    private DataSource source(SemanticSqlPlanner.Plan plan, Bound bound) {
        DataSource chosen = null;
        String schema = null;
        for (String name : plan.models()) {
            var current = bound.sources().get(name);
            String currentSchema = bound.tables().get(name).schema();
            if (chosen != null
                    && (!Objects.equals(connectionKey(chosen), connectionKey(current))
                            || !Objects.equals(schema, currentSchema)))
                throw new IllegalArgumentException("一次查询只支持一个实际连接与 schema，不支持跨连接 JOIN");
            chosen = current;
            schema = currentSchema;
        }
        if (chosen == null || !connector.supports(chosen))
            throw new IllegalArgumentException("当前连接器不支持此数据源");
        return chosen;
    }

    private List<String> connectionKey(DataSource source) {
        return java.util.Arrays.asList(
                source.properties().get("jdbcUrl"),
                source.properties().get("username"),
                source.properties().get("password"),
                source.properties().get("externalDataSourceId"));
    }

    private String version(DatasetScope scope, String groupId, SemanticModel mdl, Bound bound) {
        var data = new LinkedHashMap<String, Object>();
        data.put("model", mdl);
        data.put("instructions", models.getInstructionsText(scope.ownerId(), groupId));
        data.put("tables", bound.tables());
        data.put(
                "connections",
                bound.sources().entrySet().stream()
                        .sorted(Map.Entry.comparingByKey())
                        .map(
                                e ->
                                        List.of(
                                                e.getKey(),
                                                e.getValue().id(),
                                                connectionKey(e.getValue())))
                        .toList());
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(json(data).getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private Map<String, Object> planData(SemanticSqlPlanner.Plan plan) {
        return JSON.convertValue(plan, new TypeReference<LinkedHashMap<String, Object>>() {});
    }

    private List<String> paragraphs(String text) {
        return text == null || text.isBlank() ? List.of() : List.of(text.split("\\R\\s*\\R"));
    }

    private Map<String, Object> page(String key, List<?> all, int offset, int limit) {
        if (offset < 0 || offset > all.size()) throw new IllegalArgumentException("offset 超出范围");
        int end = Math.min(all.size(), offset + (limit <= 0 ? 20 : Math.min(limit, 100)));
        List<Object> items = new ArrayList<>();
        int size = 0;
        int cursor = offset;
        while (cursor < end) {
            Object item = all.get(cursor);
            int cost = json(item).length();
            if (size + cost > 35000) {
                if (items.isEmpty()) throw new IllegalArgumentException("单项上下文过长，请缩短该字段或拆分说明段落");
                break;
            }
            items.add(item);
            size += cost;
            cursor++;
        }
        var data = new LinkedHashMap<String, Object>();
        data.put(key, items);
        data.put("total", all.size());
        data.put("nextOffset", cursor < all.size() ? cursor : null);
        data.put("contextComplete", cursor == all.size());
        return data;
    }

    private String identifier(String value) {
        if (value == null || !value.matches("[\\p{L}\\p{N}_$]{1,128}"))
            throw new IllegalArgumentException("不支持的标识符");
        return "`" + value + "`";
    }

    private Map<String, Object> success(String stage, String groupId, String summary, Object data) {
        var out = new LinkedHashMap<String, Object>();
        out.put("type", "semantic_tool");
        out.put("stage", stage);
        out.put("status", "success");
        out.put("groupId", groupId);
        out.put("summary", summary);
        out.put("data", data);
        out.put("diagnostics", List.of());
        return out;
    }

    private String respond(String stage, String groupId, Supplier<Map<String, Object>> action) {
        try {
            String result = json(action.get());
            if (result.length() > 59000)
                throw new IllegalArgumentException("工具输出超出预算，请减少分页数量或简化 SQL");
            return result;
        } catch (RuntimeException e) {
            return error(stage, groupId, e.getMessage());
        }
    }

    public static String error(String stage, String groupId, String message) {
        String safe = message == null ? "语义工具执行失败" : message;
        if (safe.length() > 1000) safe = "错误详情过长，请简化查询后重试";
        var out = new LinkedHashMap<String, Object>();
        out.put("type", "semantic_tool");
        out.put("stage", stage);
        out.put("status", "error");
        out.put("groupId", groupId);
        out.put("summary", "语义工具未完成");
        out.put("data", Map.of());
        out.put(
                "diagnostics",
                List.of(
                        Map.of(
                                "code",
                                "SEMANTIC_" + stage.toUpperCase(Locale.ROOT) + "_ERROR",
                                "message",
                                safe,
                                "hint",
                                "检查当前知识库、模型/字段支持范围与规则，修正后重试；不要回退到未授权物理 SQL。")));
        return json(out);
    }

    private static String json(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("无法序列化语义工具结果", e);
        }
    }
}
