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

    /** spec102: no query before the user confirms the card; analysis receipts are not examples. */
    @Test
    void analysisTurnsQueryOnlyAfterConfirmationAndAreReceiptedAsAnalysis() {
        grant("alice", "a");
        var args = Map.<String, Object>of("sql", "SELECT 1");
        when(gateway.call("a", "run_sql", Map.of("sql", "SELECT 1", "limit", 10000)))
                .thenReturn(new WrenQueryGateway.WrenCallResult(true, "{\"rows\":[[1]]}"));

        RuntimeContext pending = mock(RuntimeContext.class);
        when(pending.getUserId()).thenReturn("alice");
        when(pending.get(DatasetScope.class)).thenReturn(new DatasetScope("alice", List.of("a")));
        when(pending.get(AnalysisTurn.class)).thenReturn(new AnalysisTurn("attribution", null));
        var refused = new WrenScopedMcpClient(pending, "a", access, gateway, projects);
        assertThatThrownBy(() -> refused.callTool("run_sql", args).block())
                .isInstanceOf(DatasetException.class)
                .hasMessageContaining("用户确认参数前不执行查询");
        verify(gateway, never()).call("a", "run_sql", Map.of("sql", "SELECT 1", "limit", 10000));

        RuntimeContext confirmed = mock(RuntimeContext.class);
        when(confirmed.getUserId()).thenReturn("alice");
        when(confirmed.get(DatasetScope.class)).thenReturn(new DatasetScope("alice", List.of("a")));
        when(confirmed.get(AnalysisTurn.class))
                .thenReturn(new AnalysisTurn("attribution", "{\"metric\":\"x\"}"));
        String text =
                ((McpSchema.TextContent)
                                new WrenScopedMcpClient(confirmed, "a", access, gateway, projects)
                                        .callTool("run_sql", args)
                                        .block()
                                        .content()
                                        .get(0))
                        .text();
        assertThat(text).contains("\"queryType\":\"ANALYSIS\"");
        when(gateway.call("a", "run_sql", Map.of("sql", "SELECT 2")))
                .thenReturn(new WrenQueryGateway.WrenCallResult(true, "{\"rows\":[[2]]}"));
        assertThat(
                        ((McpSchema.TextContent)
                                        client("alice", "a")
                                                .callTool("run_sql", Map.of("sql", "SELECT 2"))
                                                .block()
                                                .content()
                                                .get(0))
                                .text())
                .doesNotContain("ANALYSIS");
    }

    private WrenScopedMcpClient analysisClient(
            io.agentscope.harness.agent.filesystem.sandbox.AbstractSandboxFilesystem fs) {
        grant("alice", "a");
        RuntimeContext context = mock(RuntimeContext.class);
        when(context.getUserId()).thenReturn("alice");
        when(context.getSessionId()).thenReturn("s-1");
        when(context.get(DatasetScope.class)).thenReturn(new DatasetScope("alice", List.of("a")));
        when(context.get(AnalysisTurn.class))
                .thenReturn(new AnalysisTurn("attribution", "{\"metric\":\"x\"}"));
        return new WrenScopedMcpClient(context, "a", access, gateway, projects)
                .withDataFiles(new QueryDataFiles(fs, null));
    }

    private io.agentscope.harness.agent.filesystem.sandbox.AbstractSandboxFilesystem acceptingFs() {
        var fs =
                mock(
                        io.agentscope.harness.agent.filesystem.sandbox.AbstractSandboxFilesystem
                                .class);
        when(fs.uploadFiles(any(), any()))
                .thenReturn(
                        List.of(
                                io.agentscope.harness.agent.filesystem.model.FileUploadResponse
                                        .success("uploaded")));
        return fs;
    }

    private static String rows(int count, boolean truncated) {
        StringBuilder rows = new StringBuilder();
        for (int i = 0; i < count; i++)
            rows.append(i == 0 ? "" : ",").append("{\"项目\":\"p" + i + "\"}");
        return "{\"columns\":[\"项目\"],\"rows\":["
                + rows
                + "],\"row_count\":"
                + count
                + ",\"truncated\":"
                + truncated
                + "}";
    }

    private String run(WrenScopedMcpClient client, String sql, String result) {
        when(gateway.call("a", "run_sql", Map.of("sql", sql, "limit", 10000)))
                .thenReturn(new WrenQueryGateway.WrenCallResult(true, result));
        return ((McpSchema.TextContent)
                        client.callTool("run_sql", Map.of("sql", sql, "limit", 1000))
                                .block()
                                .content()
                                .get(0))
                .text();
    }

    /** The model's own limit is replaced: 2026-10-31 it guessed 1000, then 2000, of 3806 rows. */
    @Test
    void analysisQueriesAlwaysAskForTheFullLimit() {
        var client = analysisClient(acceptingFs());
        assertThat(run(client, "SELECT 1", rows(3, false))).contains("\"limit\":10000");
    }

    @Test
    void largeAnalysisResultsReachTheModelAsASummaryAndTheFileStaysComplete() {
        var fs = acceptingFs();
        List<String> files = new java.util.ArrayList<>();
        when(fs.uploadFiles(any(), any()))
                .thenAnswer(
                        invocation -> {
                            for (Object entry : (List<?>) invocation.getArgument(1)) {
                                @SuppressWarnings("unchecked")
                                Map.Entry<String, byte[]> file = (Map.Entry<String, byte[]>) entry;
                                files.add(
                                        new String(
                                                file.getValue(),
                                                java.nio.charset.StandardCharsets.UTF_8));
                            }
                            return List.of(
                                    io.agentscope.harness.agent.filesystem.model.FileUploadResponse
                                            .success("uploaded"));
                        });
        String text = run(analysisClient(fs), "SELECT 2", rows(501, false));
        assertThat(text)
                .contains("\"row_count\":501", "p4", "**数据文件：** data/", "只显示前 5 行")
                .doesNotContain("p5");
        assertThat(files).singleElement().satisfies(csv -> assertThat(csv).contains("p500"));
    }

    @Test
    void smallAnalysisResultsReachTheModelWhole() {
        String text = run(analysisClient(acceptingFs()), "SELECT 3", rows(500, false));
        assertThat(text).contains("p499", "**数据文件：** data/").doesNotContain("只显示前");
    }

    /** A cut-off result must not become script input, or attribution is silently wrong. */
    @Test
    void truncatedAnalysisResultsProduceNoDataFile() {
        var fs = acceptingFs();
        String text = run(analysisClient(fs), "SELECT 4", rows(10000, true));
        assertThat(text).contains("已被截断", "未生成数据文件").doesNotContain("**数据文件：**");
        verify(fs, never()).uploadFiles(any(), any());
    }

    @Test
    void ordinaryQueriesKeepWhatTheModelAskedForAndAllRows() {
        grant("alice", "a");
        var args = Map.<String, Object>of("sql", "SELECT 5", "limit", 1000);
        when(gateway.call("a", "run_sql", args))
                .thenReturn(new WrenQueryGateway.WrenCallResult(true, rows(600, true)));
        String text =
                ((McpSchema.TextContent)
                                client("alice", "a")
                                        .callTool("run_sql", args)
                                        .block()
                                        .content()
                                        .get(0))
                        .text();
        assertThat(text).contains("p599").doesNotContain("已被截断", "只显示前");
    }

    /** spec096: official query rows reach run_python as a session CSV; the receipt stays first. */
    @Test
    void successfulQueryHandsRowsToPythonAsADataFile() {
        grant("alice", "a");
        var fs =
                mock(
                        io.agentscope.harness.agent.filesystem.sandbox.AbstractSandboxFilesystem
                                .class);
        List<String> paths = new java.util.ArrayList<>();
        List<String> contents = new java.util.ArrayList<>();
        when(fs.uploadFiles(any(), any()))
                .thenAnswer(
                        invocation -> {
                            for (Object entry : (List<?>) invocation.getArgument(1)) {
                                @SuppressWarnings("unchecked")
                                Map.Entry<String, byte[]> file = (Map.Entry<String, byte[]>) entry;
                                paths.add(file.getKey());
                                contents.add(
                                        new String(
                                                file.getValue(),
                                                java.nio.charset.StandardCharsets.UTF_8));
                            }
                            return List.of(
                                    io.agentscope.harness.agent.filesystem.model.FileUploadResponse
                                            .success("uploaded"));
                        });
        RuntimeContext context = mock(RuntimeContext.class);
        when(context.getUserId()).thenReturn("alice");
        when(context.getSessionId()).thenReturn("s-1");
        when(context.get(DatasetScope.class)).thenReturn(new DatasetScope("alice", List.of("a")));
        var client =
                new WrenScopedMcpClient(context, "a", access, gateway, projects)
                        .withDataFiles(new QueryDataFiles(fs, null));
        var args = Map.<String, Object>of("sql", "SELECT 部门, 次数 FROM t");
        when(gateway.call("a", "run_sql", args))
                .thenReturn(
                        new WrenQueryGateway.WrenCallResult(
                                true,
                                "{\"columns\":[\"部门\",\"次数\"],\"rows\":[{\"部门\":\"市场,经营部\",\"次数\":3}]}"));

        String first =
                ((McpSchema.TextContent) client.callTool("run_sql", args).block().content().get(0))
                        .text();
        assertThat(first).startsWith("[WREN_QUERY_RECEIPT]").contains("**数据文件：** data/");
        assertThat(paths).singleElement().asString().startsWith("/workspace/runpython/s-1/data/");
        assertThat(contents).containsExactly("部门,次数\n\"市场,经营部\",3\n");
        // A repeated identical query is served from the cache and still names the file.
        String second =
                ((McpSchema.TextContent) client.callTool("run_sql", args).block().content().get(0))
                        .text();
        assertThat(second).contains("**数据文件：** data/").doesNotContain("WREN_QUERY_RECEIPT");

        var failed = Map.<String, Object>of("sql", "SELECT 2");
        when(gateway.call("a", "run_sql", failed))
                .thenReturn(new WrenQueryGateway.WrenCallResult(false, "boom"));
        assertThat(
                        ((McpSchema.TextContent)
                                        client.callTool("run_sql", failed).block().content().get(0))
                                .text())
                .doesNotContain("数据文件");
    }

    @Test
    void queryRowsReachTheModelAsCompactArraysWithTheSameValues() {
        String official =
                """
                {
                  "columns": ["department", "user_count"],
                  "rows": [
                    {"department": "市场经营部", "user_count": 12},
                    {"department": null, "user_count": 1}
                  ],
                  "row_count": 2,
                  "truncated": false
                }\
                """;
        assertThat(WrenScopedMcpClient.compactRows(official))
                .isEqualTo(
                        "{\"columns\":[\"department\",\"user_count\"],"
                                + "\"rows\":[[\"市场经营部\",12],[null,1]],"
                                + "\"row_count\":2,\"truncated\":false}");
        assertThat(WrenScopedMcpClient.compactRows("{\"rows\": []}")).isEqualTo("{\"rows\": []}");
        assertThat(WrenScopedMcpClient.compactRows("{\"rows\":[{\"a\":1}]}\n备注")).endsWith("备注");
        assertThat(WrenScopedMcpClient.compactRows("error: x")).isEqualTo("error: x");
    }
}
