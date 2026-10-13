package io.agentscope.dataagent.dataset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SqlExampleRecallTest {
    @TempDir Path home;

    @Test
    void modelingAgentDoesNotExposeCubeTools() {
        var names =
                java.util.Arrays.stream(
                                io.agentscope.dataagent.tools.data.ModelingToolkit.class
                                        .getMethods())
                        .map(method -> method.getAnnotation(io.agentscope.core.tool.Tool.class))
                        .filter(java.util.Objects::nonNull)
                        .map(io.agentscope.core.tool.Tool::name)
                        .toList();
        assertTrue(names.stream().noneMatch(name -> name.contains("cube")));
    }

    private SqlExampleRecall.Example example(String question) {
        return new SqlExampleRecall.Example(question, "SELECT 1", question);
    }

    @Test
    void officialResultsPreserveOrderAndFields() {
        var result =
                SqlExampleRecall.results("[{\"nl_query\":\"营收排名\",\"sql_query\":\"SELECT 1\"}]");
        assertEquals("营收排名", result.get(0).question());
        assertEquals("SELECT 1", result.get(0).sql());
        assertTrue(SqlExampleRecall.results("No results found.").isEmpty());
    }

    @Test
    void publishedMetadataRetainsDefinitionAndSupportsMultilineSql() {
        String markdown =
                "---\n"
                        + "nl: 每月营收\n"
                        + "sql: |\n"
                        + "  SELECT month, SUM(amount)\n"
                        + "  FROM orders GROUP BY month\n"
                        + "---\n"
                        + "按支付时间，单位元";
        var examples = SqlExampleRecall.published(List.of(markdown, "invalid"));
        assertEquals(1, examples.size());
        assertTrue(examples.get(0).content().contains("单位元"));
        assertTrue(examples.get(0).sql().contains("GROUP BY month"));
    }

    @Test
    void canonicalCubePathsUseOfficialWorkspace() {
        var cli = (WrenCli) (cwd, timeout, args) -> new WrenCli.Result(0, "");
        var props = new WrenProperties("wren", home.toString(), "test", "mysql", 30);
        var workspace = new MdlWorkspaceService(props, cli);
        assertEquals(
                workspace.workspaceRoot("g1").resolve("cubes/revenue/metadata.yml"),
                workspace.resolveWritable("g1", "cubes/revenue/metadata.yml"));
    }
}
