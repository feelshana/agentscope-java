package io.agentscope.dataagent.tools.data;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.tool.mcp.McpClientWrapper;
import io.agentscope.dataagent.dataset.DatasetException;
import io.agentscope.dataagent.dataset.FactQueryWindowPolicy;
import io.agentscope.dataagent.dataset.MdlCatalog;
import io.agentscope.dataagent.dataset.WrenPublishedProject;
import io.agentscope.dataagent.dataset.WrenQueryAccess;
import io.agentscope.dataagent.runtime.wren.WrenQueryGateway;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/** Invocation-owned MCP adapter. Never register this client on a shared agent toolkit. */
public final class WrenScopedMcpClient extends McpClientWrapper {
    private static final Logger log = LoggerFactory.getLogger(WrenScopedMcpClient.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    /** The official query's own ceiling; analysis turns always ask for all of it. */
    static final int ANALYSIS_QUERY_LIMIT = 10000;

    /** Analysis results above this reach the model as a summary, never smaller queries. */
    static final int ANALYSIS_INLINE_ROWS = 500;

    private static final int ANALYSIS_SAMPLE_ROWS = 5;

    /** A cut-off result would silently misattribute, so it never becomes an analysis input. */
    private static final String ANALYSIS_TRUNCATED_NOTE =
            "\n*(结果超过 "
                    + ANALYSIS_QUERY_LIMIT
                    + " 行已被截断，不完整的数据不能用于分析，未生成数据文件。"
                    + "请减少维度或缩小时间范围后重新取数，不要用这份结果计算。)*\n";

    public static final Set<String> READ_TOOLS =
            Set.of(
                    "run_sql",
                    "query_cube",
                    "dry_run",
                    "dry_plan",
                    "get_mdl",
                    "list_models",
                    "describe_model",
                    "get_data_source",
                    "list_cubes",
                    "describe_cube",
                    "list_functions",
                    "get_instructions",
                    "recall_queries",
                    "get_context",
                    "describe_schema",
                    "list_stored_queries",
                    "list_knowledge");
    private final RuntimeContext context;
    private final String groupId;
    private final WrenQueryAccess access;
    private final WrenQueryGateway gateway;
    private final WrenPublishedProject projects;
    private final FactQueryWindowPolicy windowPolicy;
    private volatile boolean closed;
    private QueryDataFiles dataFiles;

    /** Attaches the CSV handoff for {@code run_python}; without it results carry no data file. */
    public WrenScopedMcpClient withDataFiles(QueryDataFiles files) {
        this.dataFiles = files;
        return this;
    }

    private final Map<String, McpSchema.CallToolResult> successfulQueries =
            new java.util.HashMap<>();

    public WrenScopedMcpClient(
            RuntimeContext context,
            String groupId,
            WrenQueryAccess access,
            WrenQueryGateway gateway,
            WrenPublishedProject projects,
            MdlCatalog mdlCatalog,
            FactQueryWindowPolicy windowPolicy) {
        super("wren-" + Objects.requireNonNull(groupId));
        this.context = Objects.requireNonNull(context);
        this.groupId = groupId;
        this.access = Objects.requireNonNull(access);
        this.gateway = Objects.requireNonNull(gateway);
        this.projects = Objects.requireNonNull(projects);
        this.windowPolicy = windowPolicy;
    }

    public WrenScopedMcpClient(
            RuntimeContext context,
            String groupId,
            WrenQueryAccess access,
            WrenQueryGateway gateway,
            WrenPublishedProject projects,
            MdlCatalog mdlCatalog) {
        this(context, groupId, access, gateway, projects, mdlCatalog, null);
    }

    /** Compatibility constructor for unit fixtures without a published catalog. */
    public WrenScopedMcpClient(
            RuntimeContext context,
            String groupId,
            WrenQueryAccess access,
            WrenQueryGateway gateway,
            WrenPublishedProject projects) {
        this(
                context,
                groupId,
                access,
                gateway,
                projects,
                new MdlCatalog(new io.agentscope.dataagent.dataset.WrenProperties()),
                null);
    }

    private <T> T measured(String phase, java.util.function.Supplier<T> action) {
        long started = System.nanoTime();
        try {
            T result = action.get();
            io.agentscope.dataagent.web.middleware.ExecutionTimingMiddleware.record(
                    context, phase, "", started, "SUCCESS");
            return result;
        } catch (RuntimeException failure) {
            io.agentscope.dataagent.web.middleware.ExecutionTimingMiddleware.record(
                    context, phase, "", started, "ERROR");
            throw failure;
        }
    }

    private void authorize() {
        if (closed) throw new DatasetException("本轮 Wren 工具已关闭", 409);
        access.resolve(context, groupId);
    }

    @Override
    public Mono<Void> initialize() {
        return listTools().doOnSuccess(ignored -> initialized = true).then();
    }

    @Override
    public Mono<List<McpSchema.Tool>> listTools() {
        return Mono.fromCallable(
                        () -> {
                            authorize();
                            List<McpSchema.Tool> tools =
                                    gateway.listToolsAuthorized(groupId, this::authorize).stream()
                                            .filter(tool -> READ_TOOLS.contains(tool.name()))
                                            .toList();
                            // Discovery is re-authorized; cached definitions are not an
                            // authorization decision.
                            cachedTools.clear();
                            tools.forEach(tool -> cachedTools.put(tool.name(), tool));
                            return tools;
                        })
                .subscribeOn(Schedulers.boundedElastic());
    }

    @Override
    public Mono<McpSchema.CallToolResult> callTool(String tool, Map<String, Object> arguments) {
        return callTool(tool, arguments, Map.of());
    }

    @Override
    public Mono<McpSchema.CallToolResult> callTool(
            String tool, Map<String, Object> arguments, Map<String, Object> meta) {
        return Mono.fromCallable(
                        () -> {
                            measured(
                                    "tool.authorization",
                                    () -> {
                                        authorize();
                                        return null;
                                    });
                            if (!READ_TOOLS.contains(tool))
                                throw new DatasetException("问数仅开放只读官方工具；样例写入需要最终答案点赞", 403);
                            // spec102: an analysis turn queries only after the user confirmed
                            // the parameter card.
                            AnalysisTurn analysis = context.get(AnalysisTurn.class);
                            if (analysis != null
                                    && !analysis.confirmed()
                                    && Set.of("run_sql", "query_cube").contains(tool))
                                throw new DatasetException(
                                        "本轮为《"
                                                + analysis.name()
                                                + "》参数确认，用户确认参数前不执行查询；请调用 propose_analysis"
                                                + " 提交预填参数后结束本轮。",
                                        409);
                            if (measured(
                                            "tool.discovery",
                                            () ->
                                                    gateway.listToolsAuthorized(
                                                            groupId, this::authorize))
                                    .stream()
                                    .noneMatch(schema -> tool.equals(schema.name())))
                                throw new DatasetException("当前工程未提供该官方工具，请刷新工具定义", 400);
                            Map<String, Object> args =
                                    arguments == null
                                            ? Map.of()
                                            : new java.util.LinkedHashMap<>(arguments);
                            if (Set.of("run_sql", "dry_run", "dry_plan").contains(tool)) {
                                if (!(args.get("sql") instanceof String sql))
                                    throw new DatasetException("官方 sql 参数不能为空", 400);
                                OfficialWrenToolkit.requireReadOnly(sql);
                                validateQueryBoundary(tool, sql);
                            }
                            // Analysis scripts need the whole result, so the row limit is not
                            // left to the model's guess.
                            if (analysis != null && "run_sql".equals(tool))
                                args.put("limit", ANALYSIS_QUERY_LIMIT);

                            if (Set.of("recall_queries", "get_context").contains(tool))
                                measured(
                                        "index.prepare",
                                        () ->
                                                projects.indexed(
                                                        context.getUserId(), groupId, context));
                            // Client metadata is not trusted for user/project selection and is
                            // never forwarded.
                            synchronized (successfulQueries) {
                                authorize();
                                int version = access.resolve(context, groupId).getMdlVersion();
                                boolean query = Set.of("run_sql", "query_cube").contains(tool);
                                String cacheKey =
                                        version
                                                + ":"
                                                + tool
                                                + ":"
                                                + JSON.writeValueAsString(
                                                        new java.util.TreeMap<>(args));
                                if (query && successfulQueries.containsKey(cacheKey))
                                    return successfulQueries.get(cacheKey);
                                long started = System.nanoTime();
                                var result =
                                        gateway.callAuthorized(
                                                groupId, tool, args, this::authorize);
                                io.agentscope.dataagent.web.middleware.ExecutionTimingMiddleware
                                        .record(
                                                context,
                                                "wren." + tool,
                                                "",
                                                started,
                                                result.ok() ? "SUCCESS" : "ERROR");
                                String payload =
                                        query && result.ok()
                                                ? compactRows(result.payload())
                                                : result.payload();
                                boolean partial =
                                        analysis != null
                                                && query
                                                && result.ok()
                                                && truncated(result.payload());
                                if (analysis != null && query && result.ok())
                                    payload = summarizeForAnalysis(payload);
                                // spec096: hand the rows to run_python as a session CSV; the
                                // note is best-effort and never turns a successful query into
                                // an error.
                                if (partial) payload += ANALYSIS_TRUNCATED_NOTE;
                                else if (query && result.ok() && dataFiles != null)
                                    payload += dataFiles.note(context, result.payload());
                                if (!partial && query && result.ok())
                                    payload +=
                                            columnTotals(
                                                    compactRows(result.payload()),
                                                    String.valueOf(args.getOrDefault("sql", "")));
                                if (query && result.ok())
                                    successfulQueries.put(
                                            cacheKey,
                                            McpSchema.CallToolResult.builder()
                                                    .content(
                                                            List.of(
                                                                    new McpSchema.TextContent(
                                                                            payload)))
                                                    .isError(false)
                                                    .build());
                                if (result.ok() && query) {
                                    Map<String, Object> receipt = new java.util.LinkedHashMap<>();
                                    receipt.put("groupId", groupId);
                                    receipt.put("tool", tool);
                                    receipt.put("sql", args.getOrDefault("sql", ""));
                                    receipt.put("limit", args.getOrDefault("limit", 1000));
                                    // Analysis answers are feedback only, never recalled examples.
                                    receipt.put(
                                            "queryType",
                                            analysis != null ? "ANALYSIS" : "BUSINESS");
                                    payload =
                                            "[WREN_QUERY_RECEIPT]"
                                                    + JSON.writeValueAsString(receipt)
                                                    + "\n"
                                                    + payload;
                                }
                                return McpSchema.CallToolResult.builder()
                                        .content(List.of(new McpSchema.TextContent(payload)))
                                        .isError(!result.ok())
                                        .build();
                            }
                        })
                .subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * Large analysis results stay out of the conversation: scripts read the whole data file, and
     * the model only needs the shape of the result. Small ones arrive whole.
     */
    static String summarizeForAnalysis(String payload) {
        try {
            com.fasterxml.jackson.databind.JsonNode root = JSON.readTree(payload);
            com.fasterxml.jackson.databind.JsonNode rows = root.path("rows");
            if (!root.isObject() || !rows.isArray() || rows.size() <= ANALYSIS_INLINE_ROWS)
                return payload;
            com.fasterxml.jackson.databind.node.ArrayNode sample = JSON.createArrayNode();
            for (int i = 0; i < ANALYSIS_SAMPLE_ROWS; i++) sample.add(rows.get(i));
            com.fasterxml.jackson.databind.node.ObjectNode summary =
                    ((com.fasterxml.jackson.databind.node.ObjectNode) root).deepCopy();
            summary.set("rows", sample);
            summary.put("row_count", rows.size());
            summary.put(
                    "note",
                    "结果共 "
                            + rows.size()
                            + " 行，这里只显示前 "
                            + ANALYSIS_SAMPLE_ROWS
                            + " 行，完整数据在下方数据文件中，由脚本读取计算。");
            return JSON.writeValueAsString(summary);
        } catch (Exception e) {
            return payload;
        }
    }

    /**
     * Sums every all-numeric column of a complete multi-row result, so an answer quoting a total
     * copies it instead of adding rows in prose (2026-11-02/03 logs: 98,806 written as 93,806, and
     * 98,819 as 98,941, despite the prompt forbidding hand totals). Truncated, single-row and
     * non-tabular results get nothing. Columns the SQL computes as distinct counts, averages,
     * extremes or ratios are left out (2026-11-03: daily distinct visitors were totalled as 2673).
     */
    static String columnTotals(String payload) {
        return columnTotals(payload, null);
    }

    static String columnTotals(String payload, String sql) {
        java.util.Set<String> skipped = nonAdditiveColumns(sql);
        try {
            com.fasterxml.jackson.databind.JsonNode root = JSON.readTree(payload);
            com.fasterxml.jackson.databind.JsonNode rows = root.path("rows");
            com.fasterxml.jackson.databind.JsonNode columns = root.path("columns");
            if (!root.isObject()
                    || root.path("truncated").asBoolean(false)
                    || !rows.isArray()
                    || rows.size() < 2
                    || !columns.isArray()) return "";
            List<String> totals = new java.util.ArrayList<>();
            for (int i = 0; i < columns.size(); i++) {
                if (skipped.contains(columns.get(i).asText().toLowerCase(java.util.Locale.ROOT)))
                    continue;
                java.math.BigDecimal sum = java.math.BigDecimal.ZERO;
                boolean numeric = true;
                int values = 0;
                for (com.fasterxml.jackson.databind.JsonNode row : rows) {
                    com.fasterxml.jackson.databind.JsonNode cell = row.path(i);
                    if (cell.isNull() || cell.isMissingNode()) continue;
                    if (!cell.isNumber()) {
                        numeric = false;
                        break;
                    }
                    sum = sum.add(cell.decimalValue());
                    values++;
                }
                if (numeric && values > 0)
                    totals.add(
                            columns.get(i).asText()
                                    + "="
                                    + sum.stripTrailingZeros().toPlainString());
            }
            if (totals.isEmpty()) return "";
            return "\n**返回行逐列合计（平台按本次返回的 "
                    + rows.size()
                    + " 行计算）：** "
                    + String.join("，", totals)
                    + "\n只有计数、金额等可相加的列才能引用此合计；去重数、比率、平均值、编号、年份等列相加没有意义。"
                    + "答案中的合计只能引用这里或聚合 SQL 返回的数值，不要自己相加。\n";
        } catch (Exception e) {
            return "";
        }
    }

    private static final java.util.regex.Pattern NON_ADDITIVE =
            java.util.regex.Pattern.compile(
                    "(?is).*(count\\s*\\(\\s*distinct\\b|\\b(avg|min|max|stddev\\w*|variance)\\s*\\(|/).*");
    private static final java.util.regex.Pattern ALIAS =
            java.util.regex.Pattern.compile(
                    "(?is)^(.*?)\\s+(?:as\\s+)?[`\"]?([\\p{L}\\p{N}_]+)[`\"]?\\s*$");

    /**
     * Lower-cased output names of the outermost SELECT whose expression is a distinct count, an
     * average, an extreme or a ratio, which do not add up across rows. Unparseable SQL yields none.
     */
    static java.util.Set<String> nonAdditiveColumns(String sql) {
        java.util.Set<String> out = new java.util.HashSet<>();
        if (sql == null || sql.isBlank()) return out;
        String text = sql.replaceAll("(?s)/\\*.*?\\*/|--[^\\r\\n]*", " ");
        String lower = text.toLowerCase(java.util.Locale.ROOT);
        int depth = 0, select = -1, from = -1;
        boolean quoted = false;
        for (int i = 0; i < lower.length(); i++) {
            char c = lower.charAt(i);
            if (c == '\'') quoted = !quoted;
            if (quoted) continue;
            if (c == '(') depth++;
            else if (c == ')') depth--;
            else if (depth == 0 && word(lower, i, "select")) {
                select = i + 6;
                from = -1;
            } else if (depth == 0 && select >= 0 && from < 0 && word(lower, i, "from")) from = i;
        }
        if (select < 0 || from < 0) return out;
        String list = text.substring(select, from);
        depth = 0;
        int start = 0;
        quoted = false;
        for (int i = 0; i <= list.length(); i++) {
            char c = i < list.length() ? list.charAt(i) : ',';
            if (c == '\'') quoted = !quoted;
            if (quoted) continue;
            if (c == '(') depth++;
            else if (c == ')') depth--;
            else if (c == ',' && depth == 0) {
                java.util.regex.Matcher item = ALIAS.matcher(list.substring(start, i).strip());
                if (item.matches() && NON_ADDITIVE.matcher(item.group(1)).matches())
                    out.add(item.group(2).toLowerCase(java.util.Locale.ROOT));
                start = i + 1;
            }
        }
        return out;
    }

    private static boolean word(String text, int at, String word) {
        if (!text.startsWith(word, at)) return false;
        boolean before = at == 0 || !Character.isLetterOrDigit(text.charAt(at - 1));
        int end = at + word.length();
        boolean after = end >= text.length() || !Character.isLetterOrDigit(text.charAt(end));
        return before && after;
    }

    private static boolean truncated(String payload) {
        try {
            return JSON.readTree(payload).path("truncated").asBoolean(false);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Rewrites a query result's object rows as arrays in column order, without indentation. The
     * values and row count are unchanged; only repeated keys and whitespace go, which were most of
     * what the model read. Anything that is not a plain {@code {columns, rows}} object is kept.
     */
    static String compactRows(String payload) {
        if (payload == null || !payload.stripLeading().startsWith("{")) return payload;
        try {
            com.fasterxml.jackson.databind.JsonNode root =
                    JSON.reader()
                            .with(
                                    com.fasterxml.jackson.databind.DeserializationFeature
                                            .FAIL_ON_TRAILING_TOKENS)
                            .readTree(payload);
            com.fasterxml.jackson.databind.JsonNode rows = root.path("rows");
            if (!root.isObject() || !rows.isArray() || rows.isEmpty()) return payload;
            List<String> columns = new java.util.ArrayList<>();
            for (com.fasterxml.jackson.databind.JsonNode column : root.path("columns"))
                columns.add(column.isTextual() ? column.asText() : column.path("name").asText());
            if (columns.isEmpty()) rows.get(0).fieldNames().forEachRemaining(columns::add);
            com.fasterxml.jackson.databind.node.ArrayNode arrays = JSON.createArrayNode();
            for (com.fasterxml.jackson.databind.JsonNode row : rows) {
                if (!row.isObject()) return payload;
                com.fasterxml.jackson.databind.node.ArrayNode values = arrays.addArray();
                for (String column : columns) values.add(row.get(column));
            }
            com.fasterxml.jackson.databind.node.ObjectNode compact =
                    ((com.fasterxml.jackson.databind.node.ObjectNode) root).deepCopy();
            compact.set("columns", JSON.valueToTree(columns));
            compact.set("rows", arrays);
            return JSON.writeValueAsString(compact);
        } catch (Exception e) {
            // A result followed by a free-text note is not plain JSON; keep it as returned.
            return payload;
        }
    }

    private void validateQueryBoundary(String tool, String sql) {
        if (windowPolicy != null) windowPolicy.requireAllowed(context, groupId, sql, tool);
    }

    @Override
    public void close() {
        closed = true;
        initialized = false;
        cachedTools.clear();
        // The shared engine lifecycle belongs to WrenInstanceRegistry, not this invocation.
    }
}
