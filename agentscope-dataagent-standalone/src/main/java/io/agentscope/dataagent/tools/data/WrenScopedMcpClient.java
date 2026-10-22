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
                                String payload = result.payload();
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
                                    receipt.put("queryType", "BUSINESS");
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
