package io.agentscope.dataagent.runtime.wren;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.dataagent.dataset.ProcessWrenCli;
import io.agentscope.dataagent.dataset.WrenProfileHome;
import io.agentscope.dataagent.dataset.WrenProperties;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Opt-in read-only check against the reported MySQL knowledge base; no publish or writes. */
class WrenMysqlLimitIntegrationTest {
    @Test
    void cliAndMcpAcceptSqlLimitsAndPreserveTruncation() throws Exception {
        String executable = System.getProperty("dataagent.test.wren", "");
        String root = System.getProperty("dataagent.test.mysql.mdlRoot", "");
        String group = System.getProperty("dataagent.test.mysql.group", "");
        assumeTrue(
                !executable.isBlank() && !root.isBlank() && !group.isBlank(),
                "Provide explicit Wren executable, MySQL mdlRoot and group for the read-only"
                        + " fixture");
        var props = new WrenProperties(executable, root, "dataagent", "mysql", 60);
        var project = props.groupRoot(group).resolve("published");
        assertThat(Files.isRegularFile(project.resolve("target/mdl.json"))).isTrue();
        var cli = new ProcessWrenCli(props);
        var home = mock(WrenProfileHome.class);
        when(home.ensure()).thenReturn(props.wrenHome());
        var registry = new WrenInstanceRegistry(props, home);
        var json = new ObjectMapper();
        try {
            // The fixture has at least two valid orders; keep the probe small and read-only.
            String base = "SELECT order_id FROM valid_orders ORDER BY order_id";
            var baseline = registry.call(group, "run_sql", Map.of("sql", base, "limit", 10));
            assertThat(baseline.ok()).as(baseline.payload()).isTrue();
            JsonNode rows = json.readTree(baseline.payload()).path("rows");
            assertThat(rows.size()).isGreaterThanOrEqualTo(2);
            for (String suffix :
                    List.of(" LIMIT 1", " LIMIT 50", " LIMIT 1 OFFSET 1", " LIMIT 0")) {
                String sql = base + suffix;
                var result =
                        cli.run(
                                project,
                                props.timeout(),
                                List.of(
                                        "query",
                                        "--mdl",
                                        project.resolve("target/mdl.json").toString(),
                                        "--sql",
                                        sql,
                                        "--limit",
                                        "1",
                                        "-o",
                                        "json"));
                assertThat(result.exitCode()).as(result.output()).isZero();
                var mcp = registry.call(group, "run_sql", Map.of("sql", sql, "limit", 1));
                assertThat(mcp.ok()).as(mcp.payload()).isTrue();
                var payload = json.readTree(mcp.payload());
                assertThat(payload.path("row_count").asInt())
                        .isEqualTo(suffix.equals(" LIMIT 0") ? 0 : 1);
                assertThat(payload.path("truncated").asBoolean())
                        .isEqualTo(suffix.equals(" LIMIT 50"));
                if (!suffix.equals(" LIMIT 0")) {
                    JsonNode expected = rows.get(suffix.contains("OFFSET") ? 1 : 0);
                    assertThat(payload.path("rows").get(0)).isEqualTo(expected);
                    var cliRows =
                            result.output()
                                    .lines()
                                    .filter(line -> line.strip().startsWith("{"))
                                    .map(
                                            line -> {
                                                try {
                                                    return json.readTree(line);
                                                } catch (Exception e) {
                                                    throw new IllegalStateException(e);
                                                }
                                            })
                                    .toList();
                    assertThat(cliRows).containsExactly(expected);
                }
            }
            String nested =
                    "SELECT COUNT(*) AS n FROM (SELECT order_id FROM valid_orders ORDER BY order_id"
                            + " LIMIT 1) t LIMIT 50";
            var nestedResult = registry.call(group, "run_sql", Map.of("sql", nested, "limit", 1));
            assertThat(nestedResult.ok()).as(nestedResult.payload()).isTrue();
            assertThat(json.readTree(nestedResult.payload()).path("rows").get(0).path("n").asInt())
                    .isEqualTo(1);
        } finally {
            registry.close();
        }
    }
}
