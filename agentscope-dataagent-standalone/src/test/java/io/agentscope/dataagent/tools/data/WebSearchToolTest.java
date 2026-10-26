package io.agentscope.dataagent.tools.data;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.agentscope.core.tool.Toolkit;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/** spec094: DeepSeek web search for the query agent, against a local stand-in server. */
class WebSearchToolTest {
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
        String text = tool(Duration.ofSeconds(5)).webSearch("最近 体育赛事 开赛 after:2026-10-19", 2);
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
        tool(Duration.ofSeconds(5)).webSearch("赛".repeat(300), null);
        var sent = new ObjectMapper().readTree(body.get());
        assertThat(sent.path("query").asText()).hasSize(WebSearchTool.MAX_QUERY_CHARS);
        assertThat(sent.path("count").asInt()).isEqualTo(5);
        body.set(null);
        assertThat(tool(Duration.ofSeconds(5)).webSearch("  ", 3)).contains("搜索词为空");
        assertThat(body.get()).isNull();
    }

    @Test
    void failuresBecomeChineseNotesInsteadOfErrors() {
        assertThat(tool(Duration.ofSeconds(5)).webSearch("赛事", 3)).isEqualTo("没有搜到相关结果。");
        status = 402;
        assertThat(tool(Duration.ofSeconds(5)).webSearch("赛事", 3)).contains("余额不足");
        status = 401;
        assertThat(tool(Duration.ofSeconds(5)).webSearch("赛事", 3)).contains("未授权");
        status = 429;
        assertThat(tool(Duration.ofSeconds(5)).webSearch("赛事", 3)).contains("过于频繁");
        status = 500;
        assertThat(tool(Duration.ofSeconds(5)).webSearch("赛事", 3)).contains("暂时不可用");
        status = 200;
        delayMillis = 1500;
        assertThat(tool(Duration.ofMillis(300)).webSearch("赛事", 3)).contains("暂时不可用");
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
