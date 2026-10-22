package io.agentscope.dataagent.tools.data;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.dataagent.dataset.DatasetException;
import io.agentscope.dataagent.dataset.DatasetGroupService;
import io.agentscope.dataagent.dataset.DatasetScope;
import io.agentscope.dataagent.dataset.WrenPublishedProject;
import io.agentscope.dataagent.dataset.WrenQueryAccess;
import io.agentscope.dataagent.runtime.wren.WrenQueryGateway;
import io.agentscope.dataagent.web.persistence.jpa.DatasetGroupEntity;
import io.agentscope.dataagent.web.session.ConversationScopeRegistry;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class WrenScopedMcpClientTest {
    @Test
    void databaseWindowRefusalPreventsMcpExecutionWithoutReadingMdl() {
        grant("alice", "a");
        var ctx =
                RuntimeContext.builder()
                        .userId("alice")
                        .put(DatasetScope.class, new DatasetScope("alice", List.of("a")))
                        .build();
        var catalog = mock(io.agentscope.dataagent.dataset.MdlCatalog.class);
        var policy = mock(io.agentscope.dataagent.dataset.FactQueryWindowPolicy.class);
        org.mockito.Mockito.doThrow(new DatasetException("SQL_TIME_WINDOW_REJECTED", 422))
                .when(policy)
                .requireAllowed(ctx, "a", "SELECT SUM(o.amount) FROM orders o", "run_sql");
        var client = new WrenScopedMcpClient(ctx, "a", access, gateway, projects, catalog, policy);
        assertThatThrownBy(
                        () ->
                                client.callTool(
                                                "run_sql",
                                                Map.of("sql", "SELECT SUM(o.amount) FROM orders o"))
                                        .block())
                .hasMessageContaining("SQL_TIME_WINDOW_REJECTED");
        verify(gateway, never()).call(anyString(), anyString(), anyMap());
        org.mockito.Mockito.verifyNoInteractions(catalog);
    }

    @Test
    void duplicateSuccessfulQueryReusesPayloadWithoutAnotherReceiptAndStillChecksAccess() {
        grant("alice", "a");
        var client = client("alice", "a");
        var args = Map.<String, Object>of("sql", "SELECT 1");
        when(gateway.call("a", "run_sql", args))
                .thenReturn(new WrenQueryGateway.WrenCallResult(true, "{\"rows\":[[1]]}"));
        var first = client.callTool("run_sql", args).block();
        var second = client.callTool("run_sql", args).block();
        assertThat(((McpSchema.TextContent) first.content().get(0)).text())
                .contains("WREN_QUERY_RECEIPT");
        assertThat(((McpSchema.TextContent) second.content().get(0)).text())
                .isEqualTo("{\"rows\":[[1]]}");
        verify(gateway, org.mockito.Mockito.times(1)).call("a", "run_sql", args);
        when(groups.queryGroups("alice", List.of("a"))).thenReturn(List.of());
        assertThatThrownBy(() -> client.callTool("run_sql", args).block())
                .isInstanceOf(DatasetException.class);
    }

    @Test
    void failedQueriesAreNotCached() {
        grant("alice", "a");
        var client = client("alice", "a");
        var args = Map.<String, Object>of("sql", "SELECT 1");
        when(gateway.call("a", "run_sql", args))
                .thenReturn(
                        new WrenQueryGateway.WrenCallResult(false, "error"),
                        new WrenQueryGateway.WrenCallResult(true, "{\"rows\":[[1]]}"));
        assertThat(client.callTool("run_sql", args).block().isError()).isTrue();
        assertThat(client.callTool("run_sql", args).block().isError()).isFalse();
        verify(gateway, org.mockito.Mockito.times(2)).call("a", "run_sql", args);
    }

    private final DatasetGroupService groups = mock(DatasetGroupService.class);
    private final WrenQueryAccess access =
            new WrenQueryAccess(groups, new ConversationScopeRegistry());
    private final WrenQueryGateway gateway = mock(WrenQueryGateway.class, CALLS_REAL_METHODS);
    private final WrenPublishedProject projects = mock(WrenPublishedProject.class);
    private final McpSchema.Tool sqlTool =
            new McpSchema.Tool(
                    "run_sql",
                    null,
                    "执行官方只读查询",
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

    private WrenScopedMcpClient client(String user, String group) {
        RuntimeContext context = mock(RuntimeContext.class);
        when(context.getUserId()).thenReturn(user);
        when(context.get(DatasetScope.class)).thenReturn(new DatasetScope(user, List.of(group)));
        return new WrenScopedMcpClient(context, group, access, gateway, projects);
    }

    private void grant(String user, String group) {
        var entity = new DatasetGroupEntity(group, user, group, "");
        entity.setMdlVersion(1);
        when(groups.queryGroups(user, List.of(group))).thenReturn(List.of(entity));
        doReturn(List.of(sqlTool)).when(gateway).listTools(group);
    }

    @Test
    void nativeRegistrationExposesOfficialSchemaAndExecutesWithoutEnvelope() {
        grant("alice", "a");
        var client = client("alice", "a");
        var toolkit = new Toolkit();
        toolkit.registerMcpClient(client).block();
        assertThat(toolkit.getToolSchemas()).hasSize(1);
        assertThat(toolkit.getToolSchemas().get(0).getName()).isEqualTo("run_sql");
        assertThat(toolkit.getToolSchemas().get(0).getParameters())
                .containsEntry("required", List.of("sql"));
        var args = Map.<String, Object>of("sql", "SELECT 1 LIMIT 1");
        when(gateway.call("a", "run_sql", args))
                .thenReturn(new WrenQueryGateway.WrenCallResult(true, "{\"rows\":[[1]]}"));
        var result =
                toolkit.callTool(
                                ToolCallParam.builder()
                                        .toolUseBlock(
                                                ToolUseBlock.builder()
                                                        .id("q1")
                                                        .name("run_sql")
                                                        .content("{\"sql\":\"SELECT 1 LIMIT 1\"}")
                                                        .input(args)
                                                        .build())
                                        .input(args)
                                        .build())
                        .block();
        assertThat(result).isNotNull();
        verify(gateway).call("a", "run_sql", args);
    }

    @Test
    void foreignScopeCannotDiscoverAndRevocationInvalidatesExistingClient() {
        grant("alice", "a");
        when(groups.queryGroups("bob", List.of("a"))).thenReturn(List.of());
        assertThatThrownBy(() -> client("bob", "a").listTools().block())
                .isInstanceOf(DatasetException.class);
        var client = client("alice", "a");
        client.initialize().block();
        when(groups.queryGroups("alice", List.of("a"))).thenReturn(List.of());
        assertThatThrownBy(() -> client.callTool("run_sql", Map.of("sql", "SELECT 1")).block())
                .isInstanceOf(DatasetException.class);
        verify(gateway, never()).call(anyString(), anyString(), anyMap());
    }

    @Test
    void queuedCallRechecksRevocationBeforeExecution() {
        grant("alice", "a");
        doAnswer(
                        invocation -> {
                            when(groups.queryGroups("alice", List.of("a"))).thenReturn(List.of());
                            invocation.<Runnable>getArgument(3).run();
                            throw new AssertionError("Revoked call reached execution");
                        })
                .when(gateway)
                .callAuthorized(eq("a"), eq("run_sql"), anyMap(), any());
        assertThatThrownBy(
                        () ->
                                client("alice", "a")
                                        .callTool("run_sql", Map.of("sql", "SELECT 1"))
                                        .block())
                .isInstanceOf(DatasetException.class);
    }

    @Test
    void queuedQueryCannotRunAfterAnotherDatasetRequiresModeling() {
        grant("alice", "a");
        doAnswer(
                        invocation -> {
                            var pending = new DatasetGroupEntity("a", "alice", "a", "");
                            pending.setMdlVersion(1);
                            org.springframework.test.util.ReflectionTestUtils.setField(
                                    pending, "modelingRevision", 1L);
                            when(groups.queryGroups("alice", List.of("a")))
                                    .thenReturn(List.of(pending));
                            invocation.<Runnable>getArgument(3).run();
                            throw new AssertionError("Unpublished data reached execution");
                        })
                .when(gateway)
                .callAuthorized(eq("a"), eq("run_sql"), anyMap(), any());
        assertThatThrownBy(
                        () ->
                                client("alice", "a")
                                        .callTool("run_sql", Map.of("sql", "SELECT 1"))
                                        .block())
                .isInstanceOf(DatasetException.class)
                .hasMessageContaining("发布");
    }

    @Test
    void writeToolsAndMutatingSqlAreDeniedAndCloseDoesNotStopSharedEngine() {
        grant("alice", "a");
        var write =
                new McpSchema.Tool(
                        "store_query", null, "write", sqlTool.inputSchema(), null, null, null);
        doReturn(List.of(sqlTool, write)).when(gateway).listTools("a");
        var client = client("alice", "a");
        assertThat(client.listTools().block()).containsExactly(sqlTool);
        assertThatThrownBy(() -> client.callTool("store_query", Map.of()).block())
                .isInstanceOf(DatasetException.class);
        assertThatThrownBy(
                        () ->
                                client.callTool("run_sql", Map.of("sql", "DELETE FROM orders"))
                                        .block())
                .isInstanceOf(RuntimeException.class);
        client.close();
        assertThatThrownBy(() -> client.listTools().block()).isInstanceOf(DatasetException.class);
        verify(gateway, never()).invalidate(anyString());
        verify(gateway, never()).call(anyString(), anyString(), anyMap());
    }

    @Test
    void separateClientsKeepProjectBindingsAndPreserveOfficialErrors() {
        grant("alice", "a");
        grant("bob", "b");
        when(gateway.call(eq("a"), eq("run_sql"), anyMap()))
                .thenReturn(new WrenQueryGateway.WrenCallResult(true, "A"));
        when(gateway.call(eq("b"), eq("run_sql"), anyMap()))
                .thenReturn(new WrenQueryGateway.WrenCallResult(false, "B error"));
        var results =
                reactor.core.publisher.Mono.zip(
                                client("alice", "a")
                                        .callTool(
                                                "run_sql",
                                                Map.of("sql", "SELECT 1"),
                                                Map.of("group_id", "b")),
                                client("bob", "b").callTool("run_sql", Map.of("sql", "SELECT 2")))
                        .block();
        assertThat(results.getT1().isError()).isFalse();
        assertThat(results.getT2().isError()).isTrue();
        assertThat(((McpSchema.TextContent) results.getT1().content().get(0)).text())
                .contains("\"groupId\":\"a\"", "A");
        assertThat(((McpSchema.TextContent) results.getT2().content().get(0)).text())
                .isEqualTo("B error");
    }
}
