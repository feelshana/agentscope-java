package io.agentscope.dataagent.web.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.dataagent.dataset.FactQueryWindowPolicy;
import io.agentscope.dataagent.dataset.SqlWindowGate;
import io.agentscope.dataagent.dataset.WrenQueryAccess;
import io.agentscope.dataagent.runtime.session.WrenQueryContextMiddleware;
import io.agentscope.dataagent.web.persistence.jpa.DatasetGroupEntity;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/** Paid native-agent behavior evaluation with synthetic metadata and an isolated empty database. */
@EnabledIfSystemProperty(named = "dataagent.test.liveQueryDate", matches = "true")
class QueryDateBehaviorLiveTest {
    record Scenario(
            String name,
            String table,
            String timeColumn,
            String valueColumn,
            String aggregate,
            String question,
            String metricName) {}

    static Stream<Scenario> scenarios() {
        return Stream.of(
                new Scenario("orders", "orders", "order_time", "amount", "sum", "营收分析", "营收"),
                new Scenario(
                        "stock",
                        "stock_movements",
                        "recorded_at",
                        "quantity",
                        "sum",
                        "库存变动分析",
                        "库存变动量"),
                new Scenario(
                        "observations",
                        "observations",
                        "observed_at",
                        "reading",
                        "avg",
                        "温度分析",
                        "平均温度"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("scenarios")
    void asksBeforeSqlResumesFromHistoryAndAcceptsEmptyResults(Scenario scenario) throws Exception {
        var setup = WrenLiveQueryEvaluationTest.configuredModel();
        var tools = new SyntheticQueryTools(scenario);
        var toolkit = new Toolkit();
        toolkit.registerTool(tools);
        var access = mock(WrenQueryAccess.class);
        var policy = mock(FactQueryWindowPolicy.class);
        var context =
                RuntimeContext.builder()
                        .userId("fixture")
                        .sessionId("date-evaluation-" + scenario.name())
                        .build();
        var group = new DatasetGroupEntity("fixture", "fixture", "隔离行为测试库", "");
        when(access.visible(context)).thenReturn(List.of(group));
        when(policy.rules("fixture"))
                .thenReturn(
                        List.of(
                                new SqlWindowGate.Rule(
                                        scenario.table(), List.of(scenario.timeColumn()), 90)));
        String prompt =
                new WrenQueryContextMiddleware(access, policy)
                        .onSystemPrompt(
                                null,
                                context,
                                DataAgentConfig.QUERY_EFFICIENCY_RULES
                                        + "\n"
                                        + DataAgentConfig.DEFAULT_AGENT_SYS_PROMPT)
                        .block();
        var agent =
                ReActAgent.builder()
                        .name("date-evaluation")
                        .model(setup.model())
                        .toolkit(toolkit)
                        .sysPrompt(
                                prompt
                                        + "\n本会话已加载usage：使用已提供的官方工具查询。以下是隔离测试的已发布模型元数据："
                                        + tools.instructions())
                        .maxIters(6)
                        .build();
        var report = new ArrayList<Map<String, Object>>();
        try {
            String clarification = ask(agent, context, scenario.question(), tools, report);
            assertThat(tools.sql).isEmpty();
            assertThat(tools.recalls).isZero();
            assertThat(clarification).containsAnyOf("日期", "时间范围", "时间段", "哪段时间");

            ask(agent, context, "最近30天", tools, report);
            assertThat(tools.sql).isNotEmpty();
            for (String sql : tools.sql)
                assertThat(sql.toLowerCase(Locale.ROOT))
                        .contains(scenario.valueColumn(), scenario.timeColumn(), "where")
                        .containsPattern(scenario.aggregate() + "\\s*\\(")
                        .doesNotContainPattern(
                                "(?:max|min)\\s*\\(\\s*(?:[\\w`\"]+\\.)?[`\"]?"
                                        + Pattern.quote(scenario.timeColumn())
                                        + "[`\"]?\\s*\\)");
            int queries = tools.sql.size();
            ask(agent, context, "解释一下这个指标的业务口径，不需要查询数据", tools, report);
            assertThat(tools.sql).hasSize(queries);

            int beforeDaily = tools.sql.size();
            ask(agent, context, "请重新查询最近7天的每日" + scenario.metricName(), tools, report);
            assertThat(tools.sql.size()).isGreaterThan(beforeDaily);
            assertThat(tools.emptySql).isNotEmpty();
        } finally {
            Path output = Path.of("target/spec088-live-query-date-" + scenario.name() + ".json");
            Files.createDirectories(output.getParent());
            new ObjectMapper()
                    .writerWithDefaultPrettyPrinter()
                    .writeValue(
                            output.toFile(),
                            Map.of(
                                    "cases",
                                    report,
                                    "sql",
                                    tools.sql,
                                    "trace",
                                    agent.getAgentState(context).getContext().stream()
                                            .map(
                                                    msg ->
                                                            Map.of(
                                                                    "role",
                                                                    msg.getRole().name(),
                                                                    "text",
                                                                    msg.getTextContent(),
                                                                    "calls",
                                                                    msg.getContentBlocks(
                                                                            ToolUseBlock.class)))
                                            .toList()));
        }
    }

    private static String ask(
            ReActAgent agent,
            RuntimeContext context,
            String question,
            SyntheticQueryTools tools,
            List<Map<String, Object>> report) {
        int before = tools.sql.size();
        int beforeMessages = agent.getAgentState(context).getContext().size();
        long start = System.nanoTime();
        var answer = agent.call(question, context).block(Duration.ofSeconds(90));
        String text = answer == null ? "" : answer.getTextContent();
        report.add(
                Map.of(
                        "question",
                        question,
                        "answer",
                        text,
                        "sqlCalls",
                        tools.sql.size() - before,
                        "elapsedMs",
                        (System.nanoTime() - start) / 1_000_000));
        boolean sawEmptyBatch = false;
        // An aggregate returning one 0/NULL row is not an empty result. A later grouped
        // query can still be necessary, but no new SQL batch may follow actual empty rows.
        for (var message :
                agent.getAgentState(context)
                        .getContext()
                        .subList(
                                beforeMessages, agent.getAgentState(context).getContext().size())) {
            var calls =
                    message.getContentBlocks(ToolUseBlock.class).stream()
                            .filter(call -> "run_sql".equals(call.getName()))
                            .toList();
            if (calls.isEmpty()) continue;
            assertThat(sawEmptyBatch)
                    .as("no new SQL batch after empty rows: %s", question)
                    .isFalse();
            sawEmptyBatch =
                    calls.stream()
                            .anyMatch(
                                    call ->
                                            tools.emptySql.contains(
                                                    String.valueOf(call.getInput().get("sql"))));
        }
        return text;
    }

    public static class SyntheticQueryTools {
        private final Scenario scenario;
        final List<String> sql = new ArrayList<>();
        final Set<String> emptySql = new HashSet<>();
        int recalls;

        SyntheticQueryTools(Scenario scenario) {
            this.scenario = scenario;
        }

        @Tool(name = "recall_queries", description = "召回当前知识库的相关问题与SQL示例")
        public String recall(@ToolParam(name = "question", description = "当前问题") String question) {
            recalls++;
            return "{\"matches\":[]}";
        }

        @Tool(name = "get_instructions", description = "读取当前知识库的已发布业务规则和模型说明")
        public String instructions() {
            return "模型"
                    + scenario.table()
                    + "包含"
                    + scenario.timeColumn()
                    + "（日期）、"
                    + scenario.valueColumn()
                    + "（数值）；"
                    + scenario.metricName()
                    + "口径为"
                    + scenario.aggregate().toUpperCase(Locale.ROOT)
                    + "("
                    + scenario.valueColumn()
                    + ")，无额外过滤。";
        }

        @Tool(name = "run_sql", description = "执行当前知识库的只读SQL，返回结果行")
        public String query(@ToolParam(name = "sql", description = "只读查询SQL") String statement)
                throws Exception {
            sql.add(statement);
            var rows = EmptyOrdersQueryFixture.query(statement);
            if (rows.isEmpty()) emptySql.add(statement);
            return new ObjectMapper()
                    .writeValueAsString(
                            Map.of(
                                    "success",
                                    true,
                                    "rows",
                                    rows,
                                    "row_count",
                                    rows.size(),
                                    "truncated",
                                    false));
        }
    }
}
