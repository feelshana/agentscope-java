package io.agentscope.dataagent.web.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.model.Model;
import io.agentscope.dataagent.dataset.DatasetException;
import io.agentscope.dataagent.dataset.DatasetScope;
import io.agentscope.dataagent.dataset.FactQueryWindowPolicy;
import io.agentscope.dataagent.dataset.MdlCatalog;
import io.agentscope.dataagent.dataset.MdlPublishService;
import io.agentscope.dataagent.dataset.SqlWindowGate;
import io.agentscope.dataagent.dataset.WrenCli;
import io.agentscope.dataagent.dataset.WrenProjectAccess;
import io.agentscope.dataagent.dataset.WrenProperties;
import io.agentscope.dataagent.dataset.WrenPublishedProject;
import io.agentscope.dataagent.dataset.WrenQueryAccess;
import io.agentscope.dataagent.runtime.wren.WrenQueryGateway;
import io.agentscope.dataagent.tools.data.OfficialWrenToolkit;
import io.agentscope.dataagent.tools.data.WrenScopedMcpClient;
import io.agentscope.dataagent.web.persistence.jpa.DatasetEntity;
import io.agentscope.dataagent.web.persistence.jpa.DatasetGroupEntity;
import io.agentscope.dataagent.web.persistence.jpa.DatasetRepository;
import io.agentscope.dataagent.web.persistence.jpa.FactQueryWindowEntity;
import io.agentscope.dataagent.web.persistence.jpa.FactQueryWindowRepository;
import io.modelcontextprotocol.spec.McpSchema;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;

/** Paid model verification through real adapters with synthetic policies and no database execution. */
@EnabledIfSystemProperty(named = "dataagent.test.liveSqlWindowGate", matches = "true")
class SqlWindowLiveBoundaryTest {
    @TempDir Path root;
    private static final ObjectMapper JSON = new ObjectMapper();

    record Scenario(String name, String sql, boolean allowed, int matched, boolean unlimitedOnly) {}

    @Test
    void realModelAcceptsAndRejectsBeforeBothExecutionAdapters() throws Exception {
        Model model = WrenLiveQueryEvaluationTest.configuredModel().model();
        ObjectProvider<Model> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(model);
        var gate = new SqlWindowGate(provider);
        var scenarios =
                List.of(
                        new Scenario(
                                "independent_windows",
                                "SELECT a.user_id, SUM(a.amount) FROM a JOIN b ON a.id=b.a_id WHERE"
                                    + " a.time1 >= '2025-01-01' AND a.time1 < '2025-01-20' AND"
                                    + " b.time2 >= '2025-01-01' AND b.time2 < '2025-01-15' GROUP BY"
                                    + " a.user_id",
                                true,
                                2,
                                false),
                        new Scenario(
                                "missing_date", "SELECT SUM(x.amount) FROM a x", false, 1, false),
                        new Scenario(
                                "over_window",
                                "SELECT SUM(x.amount) FROM a x WHERE x.time1 >= '2025-01-01' AND"
                                        + " x.time1 < '2025-03-01'",
                                false,
                                1,
                                false),
                        new Scenario(
                                "missing_joined_date",
                                "SELECT SUM(x.amount) FROM a x JOIN b y ON x.id=y.a_id WHERE"
                                        + " x.time1 >= '2025-01-01' AND x.time1 < '2025-01-20'",
                                false,
                                2,
                                false),
                        new Scenario(
                                "joined_over_window",
                                "SELECT SUM(x.amount) FROM a x JOIN b y ON x.id=y.a_id WHERE"
                                        + " x.time1 >= '2025-01-01' AND x.time1 < '2025-01-20' AND"
                                        + " y.time2 >= '2025-01-01' AND y.time2 < '2025-01-25'",
                                false,
                                2,
                                false),
                        new Scenario(
                                "one_sided_date",
                                "SELECT SUM(amount) FROM a WHERE time1 >= '2025-01-01'",
                                false,
                                1,
                                false),
                        new Scenario(
                                "chinese_alias",
                                "SELECT SUM(t.amount) FROM `交易明细` t WHERE t.`业务日期` >= '2025-01-01'"
                                        + " AND t.`业务日期` < '2025-01-20'",
                                true,
                                1,
                                false),
                        new Scenario(
                                "unregistered",
                                "SELECT COUNT(*) FROM unregistered_view",
                                true,
                                0,
                                false),
                        new Scenario("unlimited", "SELECT COUNT(*) FROM a", true, 0, true));
        var logger = (Logger) LoggerFactory.getLogger("SQL_WINDOW_GATE");
        var audit = new ListAppender<ILoggingEvent>();
        audit.start();
        logger.addAppender(audit);
        var results = new ArrayList<Map<String, Object>>();
        try {
            int index = 0;
            for (var scenario : scenarios) {
                String groupId = "gate-evaluation-" + index++;
                var policy = policy(gate, groupId, scenario.unlimitedOnly());
                for (String adapter : List.of("CLI", "MCP")) {
                    var ctx =
                            RuntimeContext.builder()
                                    .userId("gate-evaluation")
                                    .sessionId(groupId + adapter)
                                    .put(
                                            DatasetScope.class,
                                            new DatasetScope("gate-evaluation", List.of(groupId)))
                                    .build();
                    AtomicInteger executions = new AtomicInteger();
                    int before = audit.list.size();
                    long started = System.nanoTime();
                    if (adapter.equals("CLI")) {
                        var tools = cli(policy, ctx, groupId, executions);
                        String result =
                                tools.execute(ctx, List.of("query", "--sql", scenario.sql()));
                        assertThat(result.contains("SQL_TIME_WINDOW_REJECTED"))
                                .as(scenario.name() + adapter)
                                .isEqualTo(!scenario.allowed());
                        if (!scenario.allowed()) {
                            assertThat(tools.execute(ctx, List.of("query", "--sql", "SELECT 1")))
                                    .contains("SQL_TIME_WINDOW_REJECTED");
                        }
                    } else {
                        var client = mcp(policy, ctx, groupId, executions);
                        if (scenario.allowed())
                            assertThat(
                                            client.callTool(
                                                            "run_sql",
                                                            Map.of("sql", scenario.sql()))
                                                    .block()
                                                    .isError())
                                    .isFalse();
                        else {
                            assertThatThrownBy(
                                            () ->
                                                    client.callTool(
                                                                    "run_sql",
                                                                    Map.of("sql", scenario.sql()))
                                                            .block())
                                    .isInstanceOf(DatasetException.class)
                                    .hasMessageContaining("SQL_TIME_WINDOW_REJECTED");
                            assertThatThrownBy(
                                            () ->
                                                    client.callTool(
                                                                    "run_sql",
                                                                    Map.of("sql", "SELECT 1"))
                                                            .block())
                                    .hasMessageContaining("SQL_TIME_WINDOW_REJECTED");
                        }
                    }
                    var event = JSON.readTree(audit.list.get(before).getFormattedMessage());
                    results.add(
                            Map.of(
                                    "case",
                                    scenario.name(),
                                    "adapter",
                                    adapter,
                                    "status",
                                    event.path("status").asText(),
                                    "decision",
                                    event.path("decision"),
                                    "durationMs",
                                    (System.nanoTime() - started) / 1_000_000,
                                    "executionCalls",
                                    executions.get()));
                    writeResults(results);
                    assertThat(executions.get())
                            .as(scenario.name() + adapter)
                            .isEqualTo(scenario.allowed() ? 1 : 0);
                    assertThat(event.path("decision").path("tables").size())
                            .as(scenario.name() + adapter)
                            .isEqualTo(scenario.matched());
                    if (scenario.name().equals("independent_windows")) {
                        assertThat(
                                        event.path("decision")
                                                .path("tables")
                                                .get(0)
                                                .path("windowDays")
                                                .asDouble())
                                .isEqualTo(19);
                        assertThat(
                                        event.path("decision")
                                                .path("tables")
                                                .get(1)
                                                .path("windowDays")
                                                .asDouble())
                                .isEqualTo(14);
                    }
                }
            }
        } finally {
            writeResults(results);
            logger.detachAppender(audit);
            audit.stop();
        }
        assertThat(results).hasSize(scenarios.size() * 2);
    }

    private static void writeResults(List<Map<String, Object>> results) throws Exception {
        JSON.writerWithDefaultPrettyPrinter()
                .writeValue(Path.of("target/spec085-live-window-results.json").toFile(), results);
    }

    private FactQueryWindowPolicy policy(SqlWindowGate gate, String group, boolean unlimitedOnly) {
        var windows = mock(FactQueryWindowRepository.class);
        var datasets = mock(DatasetRepository.class);
        var rows = new ArrayList<FactQueryWindowEntity>();
        for (var item :
                List.of(
                        new String[] {"a", "time1", "30"},
                        new String[] {"b", "time2", "20"},
                        new String[] {"c", "time3", "7"},
                        new String[] {"交易明细", "业务日期", "30"})) {
            var dataset = new DatasetEntity();
            dataset.setId(item[0]);
            dataset.setGroupId(group);
            dataset.setName(item[0]);
            when(datasets.findById(item[0])).thenReturn(Optional.of(dataset));
            rows.add(
                    new FactQueryWindowEntity(
                            item[0],
                            group,
                            item[0],
                            item[0],
                            "[\"" + item[1] + "\"]",
                            unlimitedOnly ? "UNLIMITED" : "LIMITED",
                            unlimitedOnly ? null : Integer.valueOf(item[2]),
                            "gate-evaluation"));
        }
        when(windows.findByGroupId(group)).thenReturn(rows);
        return new FactQueryWindowPolicy(windows, datasets, gate);
    }

    private OfficialWrenToolkit cli(
            FactQueryWindowPolicy policy,
            RuntimeContext ctx,
            String group,
            AtomicInteger executions)
            throws Exception {
        Path directory = root.resolve(group);
        Files.createDirectories(directory);
        Files.writeString(directory.resolve("wren_project.yml"), "name: evaluation\n");
        var access = mock(WrenProjectAccess.class);
        var project =
                new WrenProjectAccess.Project("gate-evaluation", group, "evaluation", directory);
        when(access.resolve(ctx)).thenReturn(project);
        when(access.<String>locked(eq(project), any()))
                .thenAnswer(call -> ((Supplier<String>) call.getArgument(1)).get());
        var publish = mock(MdlPublishService.class);
        when(publish.draftCliProfile(anyString(), anyString())).thenReturn("evaluation");
        WrenCli command =
                (cwd, timeout, args) -> {
                    if (args.get(0).equals("query")) executions.incrementAndGet();
                    return new WrenCli.Result(0, "synthetic execution sink");
                };
        return new OfficialWrenToolkit(
                access, command, new WrenProperties(), publish, null, policy);
    }

    private WrenScopedMcpClient mcp(
            FactQueryWindowPolicy policy,
            RuntimeContext ctx,
            String groupId,
            AtomicInteger executions) {
        var access = mock(WrenQueryAccess.class);
        var group = new DatasetGroupEntity(groupId, "gate-evaluation", "evaluation", "");
        group.setMdlVersion(1);
        when(access.resolve(ctx, groupId)).thenReturn(group);
        var gateway = mock(WrenQueryGateway.class, CALLS_REAL_METHODS);
        var schema =
                new McpSchema.Tool(
                        "run_sql",
                        null,
                        "只读合成测试",
                        new McpSchema.JsonSchema(
                                "object",
                                Map.of("sql", Map.of("type", "string")),
                                List.of("sql"),
                                false,
                                null,
                                null),
                        null,
                        null,
                        null);
        org.mockito.Mockito.doReturn(List.of(schema)).when(gateway).listTools(groupId);
        org.mockito.Mockito.doAnswer(
                        call -> {
                            executions.incrementAndGet();
                            return new WrenQueryGateway.WrenCallResult(true, "{}");
                        })
                .when(gateway)
                .call(eq(groupId), eq("run_sql"), any());
        return new WrenScopedMcpClient(
                ctx,
                groupId,
                access,
                gateway,
                mock(WrenPublishedProject.class),
                mock(MdlCatalog.class),
                policy);
    }
}
