package io.agentscope.dataagent.tools.data;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;

/**
 * Web search for the query agent through the DeepSeek Search API (spec095). Replaces the harness
 * Tavily tool of the same name. Only the agent's own search words leave the platform: the user's
 * original question ({@code ori_question}) and page bodies are never requested. Failures come back
 * as Chinese notes so a question never breaks on a search problem.
 */
public final class WebSearchTool {
    static final int MAX_QUERY_CHARS = 128;
    private static final int RESULT_CAP = 10;
    private static final ObjectMapper JSON = new ObjectMapper();

    private final URI endpoint;
    private final String apiKey;
    private final Duration timeout;
    private final int defaultResults;
    private final HttpClient client;

    public WebSearchTool(String baseUrl, String apiKey, Duration timeout, int defaultResults) {
        String base = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.endpoint = URI.create(base + "/v1/tools/search");
        this.apiKey = apiKey;
        this.timeout = timeout;
        this.defaultResults = Math.max(1, Math.min(defaultResults, RESULT_CAP));
        this.client = HttpClient.newBuilder().connectTimeout(timeout).build();
    }

    @Tool(
            name = "web_search",
            readOnly = true,
            description =
                    "联网搜索公开信息，返回标题、网址、站点、日期和摘要。用于知识库数据以外的外部信息：新闻、赛事、活动、政策法规、节假日、行业概念、公开数据等。"
                            + "不要用它查询知识库里的业务数据或统计值（这些只能来自 SQL）。"
                            + "搜索词只写公开概念，不写内部数据、查询结果、客户或员工名称、知识库名称；"
                            + "\"最近\"\"本周\"等先按当前日期换算，可在搜索词中使用 after:YYYY-MM-DD、site:域名。")
    public String webSearch(
            @ToolParam(name = "query", description = "搜索词，不超过 128 个字") String query,
            @ToolParam(name = "count", description = "返回条数，默认 5，最多 10", required = false)
                    Integer count) {
        if (query == null || query.isBlank()) return "联网搜索未执行：搜索词为空。";
        String words = query.strip();
        if (words.length() > MAX_QUERY_CHARS) words = words.substring(0, MAX_QUERY_CHARS);
        int limit = count == null || count < 1 ? defaultResults : Math.min(count, RESULT_CAP);
        ObjectNode body = JSON.createObjectNode();
        body.put("query", words);
        body.put("count", limit);
        body.put("need_content", false);
        try {
            HttpResponse<String> response =
                    client.send(
                            HttpRequest.newBuilder(endpoint)
                                    .timeout(timeout)
                                    .header("Content-Type", "application/json")
                                    .header("Authorization", "Bearer " + apiKey)
                                    .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                                    .build(),
                            HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) return failure(response.statusCode());
            return format(response.body(), limit);
        } catch (HttpTimeoutException e) {
            return "联网搜索暂时不可用（请求超时），本次未搜索；请按已有信息回答并说明。";
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "联网搜索已中断，本次未搜索。";
        } catch (Exception e) {
            return "联网搜索暂时不可用，本次未搜索；请按已有信息回答并说明。";
        }
    }

    static String failure(int status) {
        return switch (status) {
            case 401, 403 -> "联网搜索未授权（搜索服务的 Key 无效或无权限），本次未搜索；请告诉用户联网搜索暂不可用。";
            case 402 -> "联网搜索余额不足，本次未搜索；请告诉用户联网搜索暂不可用。";
            case 429 -> "联网搜索请求过于频繁，本次未搜索；请稍后再试或按已有信息回答。";
            case 400, 413 -> "联网搜索词无效，本次未搜索；请缩短或改写搜索词后再试一次。";
            default -> "联网搜索暂时不可用（服务返回 " + status + "），本次未搜索；请按已有信息回答并说明。";
        };
    }

    static String format(String responseBody, int limit) {
        JsonNode results;
        try {
            results = JSON.readTree(responseBody).path("results");
        } catch (Exception e) {
            return "联网搜索返回的结果无法解析，本次未得到结果。";
        }
        if (!results.isArray() || results.isEmpty()) return "没有搜到相关结果。";
        StringBuilder out = new StringBuilder();
        int n = 0;
        for (JsonNode r : results) {
            if (n == limit) break;
            String summary = text(r, "summary");
            if (summary.isEmpty()) summary = text(r, "snippet");
            out.append(++n).append(". ").append(text(r, "title")).append('\n');
            out.append("   网址：").append(text(r, "url")).append('\n');
            String site = text(r, "site_name");
            String date = text(r, "date");
            if (!site.isEmpty() || !date.isEmpty())
                out.append("   来源：")
                        .append(site)
                        .append(date.isEmpty() ? "" : "  " + date)
                        .append('\n');
            if (!summary.isEmpty()) out.append("   摘要：").append(summary).append('\n');
        }
        return out.toString().strip();
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() ? "" : value.asText("").strip();
    }
}
