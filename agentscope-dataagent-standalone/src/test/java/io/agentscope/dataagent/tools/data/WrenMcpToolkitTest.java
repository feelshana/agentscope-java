package io.agentscope.dataagent.tools.data;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.dataagent.dataset.DatasetGroupService;
import io.agentscope.dataagent.dataset.DatasetScope;
import io.agentscope.dataagent.dataset.WrenProperties;
import io.agentscope.dataagent.dataset.WrenPublishedProject;
import io.agentscope.dataagent.dataset.WrenQueryAccess;
import io.agentscope.dataagent.runtime.wren.WrenQueryGateway;
import io.agentscope.dataagent.web.persistence.jpa.DatasetGroupEntity;
import io.agentscope.dataagent.web.session.ConversationScopeRegistry;
import io.modelcontextprotocol.spec.McpSchema;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WrenMcpToolkitTest {
    @TempDir Path home;

    @Test
    void officialArgumentsPassThroughAndOnlySuccessfulBusinessCallsReceiveReceipts() {
        var groups = mock(DatasetGroupService.class);
        var g = new DatasetGroupEntity("g", "alice", "销售", "");
        g.setMdlVersion(1);
        when(groups.listGroups("alice")).thenReturn(List.of(g));
        var context = mock(RuntimeContext.class);
        when(context.getUserId()).thenReturn("alice");
        when(context.get(DatasetScope.class)).thenReturn(new DatasetScope("alice", List.of("g")));
        var gateway = mock(WrenQueryGateway.class);
        var schema = mock(McpSchema.Tool.class);
        when(schema.name()).thenReturn("run_sql");
        when(gateway.listTools("g")).thenReturn(List.of(schema));
        Map<String, Object> args = Map.of("sql", "SELECT COUNT(*) FROM orders LIMIT 1", "limit", 7);
        when(gateway.call("g", "run_sql", args))
                .thenReturn(
                        new WrenQueryGateway.WrenCallResult(true, "{\"rows\":[{\"count\":2}]}"));
        var tool =
                new WrenMcpToolkit(
                        gateway,
                        new WrenQueryAccess(groups, new ConversationScopeRegistry()),
                        mock(WrenPublishedProject.class),
                        null);
        assertThat(tool.callTool(context, "g", "run_sql", args, null))
                .doesNotContain("WREN_QUERY_RECEIPT");
        assertThat(tool.callTool(context, "g", "run_sql", args, "有多少客户"))
                .contains("WREN_QUERY_RECEIPT", "有多少客户");
        verify(gateway, times(2)).call("g", "run_sql", args);
        assertThat(
                        tool.callTool(
                                context, "g", "run_sql", Map.of("sql", "DELETE FROM orders"), null))
                .startsWith("error:");
        assertThat(tool.callTool(context, "g", "store_query", Map.of(), "问题")).startsWith("error:");
        assertThat(tool.callTool(context, "foreign", "run_sql", args, null)).startsWith("error:");
        when(gateway.call("g", "run_sql", args))
                .thenReturn(new WrenQueryGateway.WrenCallResult(false, "failed"));
        assertThat(tool.callTool(context, "g", "run_sql", args, "有多少客户"))
                .isEqualTo("error: failed");
    }

    @Test
    void nestedPlatformMetadataIsStrippedBeforeOfficialCallAndCreatesReceipt() {
        var groups = mock(DatasetGroupService.class);
        var g = new DatasetGroupEntity("g", "alice", "sales", "");
        g.setMdlVersion(1);
        when(groups.listGroups("alice")).thenReturn(List.of(g));
        var context = mock(RuntimeContext.class);
        when(context.getUserId()).thenReturn("alice");
        when(context.get(DatasetScope.class)).thenReturn(new DatasetScope("alice", List.of("g")));
        var gateway = mock(WrenQueryGateway.class);
        var schema = mock(McpSchema.Tool.class);
        when(schema.name()).thenReturn("run_sql");
        when(gateway.listTools("g")).thenReturn(List.of(schema));
        when(gateway.call("g", "run_sql", Map.of("sql", "SELECT 1", "limit", 10)))
                .thenReturn(new WrenQueryGateway.WrenCallResult(true, "{\"rows\":[{\"n\":1}]}"));
        var tool =
                new WrenMcpToolkit(
                        gateway,
                        new WrenQueryAccess(groups, new ConversationScopeRegistry()),
                        mock(WrenPublishedProject.class),
                        null);
        Map<String, Object> nested =
                Map.of(
                        "answer_question", "2025年营收top10的客户",
                        "query_type", "BUSINESS",
                        "sql", "SELECT 1",
                        "limit", 10);
        assertThat(tool.callTool(context, "g", "run_sql", nested, null, null))
                .contains("WREN_QUERY_RECEIPT", "2025年营收top10的客户");
        verify(gateway).call("g", "run_sql", Map.of("sql", "SELECT 1", "limit", 10));
    }

    @Test
    void knowledgeReadsPublishedSourceAndRejectsTraversal() throws Exception {
        var groups = mock(DatasetGroupService.class);
        var g = new DatasetGroupEntity("g", "alice", "销售", "");
        g.setMdlVersion(1);
        when(groups.listGroups("alice")).thenReturn(List.of(g));
        when(groups.getGroup("alice", "g")).thenReturn(g);
        var props = new WrenProperties("wren", home.toString(), "test", "duckdb", 30);
        Path root = props.groupRoot("g").resolve("published");
        Files.createDirectories(root.resolve("target"));
        Files.writeString(root.resolve("target/mdl.json"), "{}");
        Files.createDirectories(root.resolve("knowledge"));
        Files.writeString(root.resolve("knowledge/metrics.md"), "正式业务口径");
        var ctx = mock(RuntimeContext.class);
        when(ctx.getUserId()).thenReturn("alice");
        var tool =
                new WrenMcpToolkit(
                        mock(WrenQueryGateway.class),
                        new WrenQueryAccess(groups, new ConversationScopeRegistry()),
                        new WrenPublishedProject(props, groups),
                        null);
        assertThat(tool.readKnowledge(ctx, "g", "metrics.md")).isEqualTo("正式业务口径");
        for (String path :
                List.of(
                        "../target/mdl.json",
                        "D:/secret",
                        ".hidden",
                        "metrics.md:stream",
                        "../other/knowledge.md"))
            assertThat(tool.readKnowledge(ctx, "g", path)).startsWith("error:");
    }
}
