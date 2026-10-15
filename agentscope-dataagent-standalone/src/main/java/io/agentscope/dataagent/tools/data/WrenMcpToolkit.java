package io.agentscope.dataagent.tools.data;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import io.agentscope.dataagent.dataset.DatasetException;
import io.agentscope.dataagent.dataset.WrenPublishedProject;
import io.agentscope.dataagent.dataset.WrenQueryAccess;
import io.agentscope.dataagent.runtime.wren.WrenQueryGateway;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Scope-only official MCP dispatch plus generic result provenance and knowledge-file access. */
public final class WrenMcpToolkit {
    private static final Logger log = LoggerFactory.getLogger(WrenMcpToolkit.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private final WrenQueryGateway gateway;
    private final WrenQueryAccess access;
    private final WrenPublishedProject projects;
    private final WrenToolkit artifacts;

    public WrenMcpToolkit(
            WrenQueryGateway gateway,
            WrenQueryAccess access,
            WrenPublishedProject projects,
            WrenToolkit artifacts) {
        this.gateway = gateway;
        this.access = access;
        this.projects = projects;
        this.artifacts = artifacts;
    }

    @Tool(
            name = "wren_list_tools",
            description =
                    "发现当前授权已发布 Wren 工程的官方 MCP 工具名称与原始参数 schema。会话已有同工程有效 schema"
                            + " 时直接复用，无需每轮重复发现；不会读取草稿。")
    public String listTools(
            RuntimeContext context,
            @ToolParam(
                            name = "group_id",
                            description = "当前知识库名称或 ID，只有一个可用知识库时可省略",
                            required = false)
                    String group) {
        try {
            var selected = access.resolve(context, group);
            return JSON.writeValueAsString(gateway.listTools(selected.getId()));
        } catch (Exception e) {
            return "error: " + e.getMessage();
        }
    }

    /** Backward-compatible Java entry point for callers that do not provide platform metadata. */
    public String callTool(
            RuntimeContext context,
            String group,
            String tool,
            Map<String, Object> arguments,
            String question) {
        return callTool(context, group, tool, arguments, question, null);
    }

    @Tool(
            name = "wren_call_tool",
            description =
                    "调用当前授权已发布 Wren 工程的官方 MCP 工具。tool_name 与 arguments 必须遵循 wren_list_tools 的官方"
                        + " schema；arguments 原样传给官方服务，不增加平台字段。返回官方原始 JSON，执行的数据可交给"
                        + " Python。answer_question 是可选答案来源标注，仅业务查询使用，诊断查询省略；只在最终答案点赞后保存样例。run_sql"
                        + " 只能使用逻辑模型，并遵循官方 Wren SQL 语法（包括 LIMIT/OFFSET）；query_cube 使用官方"
                        + " limit/offset 参数。")
    public String callTool(
            RuntimeContext context,
            @ToolParam(
                            name = "group_id",
                            description = "当前知识库名称或 ID，只有一个可用知识库时可省略",
                            required = false)
                    String group,
            @ToolParam(name = "tool_name", description = "发现的官方 MCP 工具名称") String tool,
            @ToolParam(name = "arguments", description = "官方工具原始参数 JSON 对象")
                    Map<String, Object> arguments,
            @ToolParam(
                            name = "answer_question",
                            description = "可选：此查询实际用于最终答案的业务问题；诊断查询省略，不传入 Wren",
                            required = false)
                    String question,
            @ToolParam(
                            name = "query_type",
                            description = "查询类型：BUSINESS 业务查询，DIAGNOSTIC 诊断查询；不传给 Wren",
                            required = false)
                    String queryType) {
        try {
            var selected = access.resolve(context, group);
            if (tool == null || tool.equals("store_query"))
                throw new DatasetException("问数 MCP 为只读；样例写入由最终答案点赞授权", 403);
            if (gateway.listTools(selected.getId()).stream().noneMatch(t -> tool.equals(t.name())))
                throw new DatasetException("该官方工具未在当前工程提供，请先发现工具", 400);
            if (Set.of("recall_queries", "get_context").contains(tool))
                projects.indexed(context.getUserId(), selected.getId());
            Map<String, Object> args =
                    arguments == null ? new LinkedHashMap<>() : new LinkedHashMap<>(arguments);
            // Platform metadata may be nested by a model; never forward it to official Wren.
            String effectiveQuestion = question;
            if ((effectiveQuestion == null || effectiveQuestion.isBlank())
                    && args.get("answer_question") instanceof String nestedQuestion) {
                effectiveQuestion = nestedQuestion;
            }
            String nestedQueryType =
                    args.get("query_type") instanceof String nestedType ? nestedType : null;
            args.remove("answer_question");
            args.remove("query_type");
            if (effectiveQuestion != null && !effectiveQuestion.isBlank()) {
                log.info(
                        "[wren-mcp] metadata group={} tool={} question={} queryType={} argsKeys={}",
                        selected.getId(),
                        tool,
                        effectiveQuestion.strip(),
                        queryType == null ? nestedQueryType : queryType,
                        args.keySet());
            }
            if (Set.of("run_sql", "dry_run", "dry_plan").contains(tool)) {
                Object sql = args.get("sql");
                if (!(sql instanceof String value))
                    throw new DatasetException("官方 sql 参数不能为空", 400);
                OfficialWrenToolkit.requireReadOnly(value);
            }
            var result = gateway.call(selected.getId(), tool, args);
            if (!result.ok()) return "error: " + result.payload();
            String output = result.payload();
            if (Set.of("run_sql", "query_cube").contains(tool)
                    && !Boolean.TRUE.equals(args.get("sql_only"))) {
                if (artifacts != null) output += artifacts.dataFileNote(context, result.payload());
                if (effectiveQuestion != null && !effectiveQuestion.isBlank()) {
                    String sql =
                            tool.equals("run_sql")
                                    ? String.valueOf(args.get("sql"))
                                    : cubeSql(selected.getId(), args);
                    if (sql != null && !sql.isBlank()) {
                        int limit = queryLimit(tool, args);
                        Map<String, Object> receipt =
                                Map.of(
                                        "groupId",
                                        selected.getId(),
                                        "question",
                                        effectiveQuestion.strip(),
                                        "sql",
                                        sql,
                                        "limit",
                                        limit,
                                        "tool",
                                        tool,
                                        "queryType",
                                        (queryType == null || queryType.isBlank())
                                                ? (nestedQueryType == null
                                                        ? "BUSINESS"
                                                        : nestedQueryType)
                                                : queryType);
                        output =
                                "[WREN_QUERY_RECEIPT]"
                                        + JSON.writeValueAsString(receipt)
                                        + "\n"
                                        + output;
                        log.info(
                                "[wren-mcp] receipt group={} tool={} question={} limit={}"
                                        + " queryType={}",
                                selected.getId(),
                                tool,
                                effectiveQuestion.strip(),
                                limit,
                                (queryType == null || queryType.isBlank())
                                        ? (nestedQueryType == null ? "BUSINESS" : nestedQueryType)
                                        : queryType);
                    }
                }
            }
            return output;
        } catch (Exception e) {
            return "error: " + e.getMessage();
        }
    }

    private String cubeSql(String group, Map<String, Object> arguments) {
        try {
            Map<String, Object> args = new LinkedHashMap<>(arguments);
            args.put("sql_only", true);
            var plan = gateway.call(group, "query_cube", args);
            return plan.ok() ? JSON.readTree(plan.payload()).path("sql").asText("") : null;
        } catch (Exception e) {
            return null;
        }
    }

    private int queryLimit(String tool, Map<String, Object> arguments) {
        Object configured = arguments.get("limit");
        if (configured instanceof Number number) return number.intValue();
        if (!"run_sql".equals(tool)) return 1000;
        Object sql = arguments.get("sql");
        if (sql instanceof String value) {
            var matcher =
                    java.util.regex.Pattern.compile(
                                    "(?i)\\blimit\\s+(\\d+)\\s*$", java.util.regex.Pattern.DOTALL)
                            .matcher(value.strip());
            if (matcher.find()) return Integer.parseInt(matcher.group(1));
        }
        return 1000;
    }
}
