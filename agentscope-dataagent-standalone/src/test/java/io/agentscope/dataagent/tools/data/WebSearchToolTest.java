package io.agentscope.dataagent.tools.data;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.dataagent.dataset.MdlCatalog;
import io.agentscope.dataagent.dataset.WrenPublishedProject;
import io.agentscope.dataagent.dataset.WrenQueryAccess;
import io.agentscope.dataagent.runtime.DataAgentBootstrap;
import io.agentscope.dataagent.runtime.config.AgentscopeConfig;
import io.agentscope.dataagent.runtime.session.WrenScopedToolkitMiddleware;
import io.agentscope.dataagent.runtime.wren.WrenQueryGateway;
import io.agentscope.harness.agent.HarnessAgent;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import reactor.core.publisher.Flux;

/** spec095: DeepSeek web search for the query agent, against a local stand-in server. */
class WebSearchToolTest {
    private final RuntimeContext allowedTurn =
            RuntimeContext.builder().put(WebSearchTool.ALLOWED_CONTEXT_KEY, true).build();
    private HttpServer server;
    private final AtomicReference<String> body = new AtomicReference<>();
    private final AtomicReference<String> auth = new AtomicReference<>();
    private volatile int status = 200;
    private volatile String reply = "{\"results\":[]}";
    private volatile long delayMillis;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/v1/tools/search",
                exchange -> {
                    body.set(
                            new String(
                                    exchange.getRequestBody().readAllBytes(),
                                    StandardCharsets.UTF_8));
                    auth.set(exchange.getRequestHeaders().getFirst("Authorization"));
                    try {
                        Thread.sleep(delayMillis);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    byte[] out = reply.getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(status, out.length);
                    exchange.getResponseBody().write(out);
                    exchange.close();
                });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private WebSearchTool tool(Duration timeout) {
        return new WebSearchTool(
                "http://127.0.0.1:" + server.getAddress().getPort() + "/", "test-key", timeout, 5);
    }

    @Test
    void sendsOnlyTheSearchWordsAndFormatsResults() throws Exception {
        reply =
                """
                {"results":[
                  {"title":"2026 年全国赛事日程","url":"https://example.cn/a","site_name":"体育总局","date":"2026-10-20","summary":"十月下旬开赛"},
                  {"title":"联赛开幕","url":"https://example.cn/b","snippet":"只有片段","summary":null},
                  {"title":"第三条","url":"https://example.cn/c","summary":"不该出现"}
                ]}\
                """;
        String text =
                tool(Duration.ofSeconds(5))
                        .webSearch(allowedTurn, "最近 体育赛事 开赛 after:2026-10-19", 2);
        var sent = new ObjectMapper().readTree(body.get());
        assertThat(sent.fieldNames())
                .toIterable()
                .containsExactlyInAnyOrder("query", "count", "need_content");
        assertThat(sent.path("count").asInt()).isEqualTo(2);
        assertThat(sent.path("need_content").asBoolean()).isFalse();
        assertThat(auth.get()).isEqualTo("Bearer test-key");
        assertThat(text)
                .contains(
                        "1. 2026 年全国赛事日程",
                        "网址：https://example.cn/a",
                        "体育总局  2026-10-20",
                        "摘要：十月下旬开赛")
                .contains("摘要：只有片段")
                .doesNotContain("第三条");
    }

    @Test
    void longQueriesAreCutAndEmptyOnesNeverLeave() throws Exception {
        tool(Duration.ofSeconds(5)).webSearch(allowedTurn, "赛".repeat(300), null);
        var sent = new ObjectMapper().readTree(body.get());
        assertThat(sent.path("query").asText()).hasSize(WebSearchTool.MAX_QUERY_CHARS);
        assertThat(sent.path("count").asInt()).isEqualTo(5);
        body.set(null);
        assertThat(tool(Duration.ofSeconds(5)).webSearch(allowedTurn, "  ", 3)).contains("搜索词为空");
        assertThat(body.get()).isNull();
    }

    @Test
    void failuresBecomeChineseNotesInsteadOfErrors() {
        assertThat(tool(Duration.ofSeconds(5)).webSearch(allowedTurn, "赛事", 3))
                .isEqualTo("没有搜到相关结果。");
        status = 402;
        assertThat(tool(Duration.ofSeconds(5)).webSearch(allowedTurn, "赛事", 3)).contains("余额不足");
        status = 401;
        assertThat(tool(Duration.ofSeconds(5)).webSearch(allowedTurn, "赛事", 3)).contains("未授权");
        status = 429;
        assertThat(tool(Duration.ofSeconds(5)).webSearch(allowedTurn, "赛事", 3)).contains("过于频繁");
        status = 500;
        assertThat(tool(Duration.ofSeconds(5)).webSearch(allowedTurn, "赛事", 3)).contains("暂时不可用");
        status = 200;
        delayMillis = 1500;
        assertThat(tool(Duration.ofMillis(300)).webSearch(allowedTurn, "赛事", 3)).contains("暂时不可用");
    }

    @Test
    void neverSendsSearchWithoutExplicitTurnPermission() {
        var tool = tool(Duration.ofSeconds(5));
        assertThat(tool.webSearch(RuntimeContext.empty(), "公开政策", 3)).contains("本轮未开启");
        assertThat(tool.webSearch(null, "公开政策", 3)).contains("本轮未开启");
        assertThat(body.get()).isNull();
    }

    @Test
    void realAgentCallsTheSearchToolForAnAuthorizedTurnWithoutAKnowledgeBase() {
        reply =
                """
                {"results":[{"title":"公开新闻","url":"https://example.cn/news","summary":"公开消息"}]}
                """;
        var toolkit = new Toolkit();
        DataToolkitRegistrar.configureQueryWebTools(toolkit, tool(Duration.ofSeconds(5)));
        var model = mock(Model.class);
        when(model.getModelName()).thenReturn("search-fixture");
        when(model.stream(anyList(), anyList(), any()))
                .thenAnswer(
                        invocation -> {
                            List<Msg> messages = invocation.getArgument(0);
                            List<ToolSchema> schemas = invocation.getArgument(1);
                            boolean searchOffered =
                                    schemas.stream()
                                            .anyMatch(
                                                    schema ->
                                                            WebSearchTool.NAME.equals(
                                                                    schema.getName()));
                            if (!searchOffered) return Flux.just(textResponse("本轮没有联网"));
                            if (messages.stream().anyMatch(msg -> msg.getRole() == MsgRole.TOOL)) {
                                assertThat(
                                                messages.stream()
                                                        .filter(
                                                                msg ->
                                                                        msg.getRole()
                                                                                == MsgRole.TOOL)
                                                        .flatMap(msg -> msg.getContent().stream())
                                                        .filter(
                                                                io.agentscope.core.message
                                                                                .ToolResultBlock
                                                                                .class
                                                                        ::isInstance)
                                                        .map(
                                                                io.agentscope.core.message
                                                                                .ToolResultBlock
                                                                                .class
                                                                        ::cast)
                                                        .flatMap(
                                                                result ->
                                                                        result.getOutput().stream())
                                                        .filter(TextBlock.class::isInstance)
                                                        .map(TextBlock.class::cast)
                                                        .map(TextBlock::getText))
                                        .anyMatch(
                                                result ->
                                                        result.contains("https://example.cn/news"));
                                return Flux.just(textResponse("找到公开新闻"));
                            }
                            assertThat(messages)
                                    .anyMatch(
                                            msg ->
                                                    msg.getTextContent()
                                                            .contains("当前 web_search 工具可用"));
                            return Flux.just(
                                    ChatResponse.builder()
                                            .content(
                                                    List.of(
                                                            ToolUseBlock.builder()
                                                                    .id("fixture-search")
                                                                    .name(WebSearchTool.NAME)
                                                                    .content(
                                                                            "{\"query\":\"今天的公开新闻\"}")
                                                                    .input(
                                                                            Map.of(
                                                                                    "query",
                                                                                    "今天的公开新闻"))
                                                                    .build()))
                                            .build());
                        });
        var agent =
                ReActAgent.builder()
                        .name("search-test")
                        .model(model)
                        .toolkit(toolkit)
                        .middleware(
                                new WrenScopedToolkitMiddleware(
                                        mock(WrenQueryAccess.class),
                                        mock(WrenQueryGateway.class),
                                        mock(WrenPublishedProject.class),
                                        mock(MdlCatalog.class)))
                        .build();
        var question = List.of(Msg.builder().role(MsgRole.USER).textContent("你搜下今天的新闻").build());
        assertThat(
                        agent.call(question, RuntimeContext.empty())
                                .block(Duration.ofSeconds(5))
                                .getTextContent())
                .isEqualTo("本轮没有联网");
        assertThat(body.get()).isNull();
        assertThat(agent.call(question, allowedTurn).block(Duration.ofSeconds(5)).getTextContent())
                .isEqualTo("找到公开新闻");
        assertThat(body.get()).contains("今天的公开新闻");
    }

    private static ChatResponse textResponse(String text) {
        return ChatResponse.builder()
                .content(List.of(TextBlock.builder().text(text).build()))
                .build();
    }

    @Test
    void configuredSearchIsReportedAvailableOnlyWhenMountedOnTheQueryAgent() {
        var config = new MockEnvironment().withProperty("dataagent.web-search.api-key", "test-key");
        var bootstrap = mock(DataAgentBootstrap.class);
        var loaded = mock(AgentscopeConfig.class);
        var otherMain = mock(HarnessAgent.class);
        when(loaded.getMain()).thenReturn("other");
        when(bootstrap.loadedConfig()).thenReturn(loaded);
        when(bootstrap.agents()).thenReturn(Map.of("other", otherMain));
        var registrar =
                new DataToolkitRegistrar(
                        bootstrap, null, null, null, null, null, null, null, null, null, config);
        registrar.registerDataToolkit();
        assertThat(registrar.isWebSearchAvailable()).isFalse();

        var query = mock(HarnessAgent.class);
        var delegate = mock(ReActAgent.class);
        var toolkit = new Toolkit();
        when(query.getDelegate()).thenReturn(delegate);
        when(delegate.getToolkit()).thenReturn(toolkit);
        when(bootstrap.agents()).thenReturn(Map.of("other", otherMain, "data-agent", query));
        registrar.registerDataToolkit();
        assertThat(registrar.isWebSearchAvailable()).isTrue();
        toolkit.removeTool(WebSearchTool.NAME);
        assertThat(registrar.isWebSearchAvailable()).isFalse();
    }

    @Test
    void onlyTheQueryAgentSearchesAndOnlyWithAKey() {
        var properties = new MockEnvironment();
        assertThat(DataToolkitRegistrar.webSearch(properties)).isNull();
        properties.setProperty("dataagent.web-search.api-key", "test-key");
        assertThat(DataToolkitRegistrar.webSearch(properties)).isNotNull();
        properties.setProperty("dataagent.web-search.enabled", "false");
        assertThat(DataToolkitRegistrar.webSearch(properties)).isNull();

        // The harness tools every agent receives are dropped; the query agent gets ours.
        Toolkit query = new Toolkit();
        query.registerTool(new io.agentscope.harness.agent.tool.WebTools.WebSearchTool());
        query.registerTool(new io.agentscope.harness.agent.tool.WebTools.WebFetchTool());
        DataToolkitRegistrar.configureQueryWebTools(query, null);
        assertThat(query.getToolNames()).doesNotContain("web_search", "web_fetch");
        DataToolkitRegistrar.configureQueryWebTools(query, tool(Duration.ofSeconds(5)));
        assertThat(query.getToolNames()).contains("web_search").doesNotContain("web_fetch");
        assertThat(query.getTool("web_search").getDescription()).contains("联网搜索公开信息");
    }
}
