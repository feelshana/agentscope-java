/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.examples.chatbi.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatModelBase;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

/**
 * Tool that filters Confluence search results using an LLM.
 *
 * <p>Takes the raw JSON search result from {@link ConfluenceTool} and uses an LLM
 * to select the most relevant page IDs (up to 7). The LLM prompt instructs it to return
 * comma-separated page IDs.
 *
 * <p>This mirrors the "正在查看搜索结果" node in the original Dify ChatBI v6 workflow.
 *
 * <p><b>Optimizations vs v1:</b>
 * <ul>
 *   <li>Search result JSON is truncated before LLM input: only id, title, and first 200 chars
 *       of content are kept, max 5 results. This drastically reduces input tokens.</li>
 *   <li>Uses the model's streaming API for faster response delivery.</li>
 *   <li>Timeout reduced from 2000s to 30s.</li>
 * </ul>
 */
public class ConfluenceFilterTool {

    private static final Logger log = LoggerFactory.getLogger(ConfluenceFilterTool.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private static final int FILTER_TIMEOUT_SECONDS = 30;
    private static final int MAX_RESULTS_FOR_FILTER = 5;
    private static final int CONTENT_PREVIEW_CHARS = 200;

    /**
     * Default fallback page ID when no relevant pages are found
     */
    private static final String DEFAULT_PAGE_ID = "437387755";

    private final ChatModelBase filterModel;

    public ConfluenceFilterTool(ChatModelBase filterModel) {
        this.filterModel = filterModel;
    }

    /**
     * Filter Confluence search results and return the most relevant page IDs.
     *
     * @param userQuestion the user's original question
     * @param searchResult the raw JSON search result from search_confluence
     * @return comma-separated page IDs (e.g., "463681344,463693554")
     */
    @Tool(
            name = "filter_confluence_results",
            description =
                    "Filter Confluence search results using AI to select the most relevant page"
                        + " IDs. Inputs: user_question (the original user question) and"
                        + " search_result (raw JSON from search_confluence). Output:"
                        + " comma-separated page IDs (up to 7). If no relevant pages found, returns"
                        + " default ID 437387755. Use this tool after search_confluence to identify"
                        + " the most relevant pages.")
    public Mono<String> filterResults(
            @ToolParam(
                            name = "user_question",
                            description = "The user's original question for relevance matching")
                    String userQuestion,
            @ToolParam(
                            name = "search_result",
                            description = "The raw JSON search result from search_confluence tool")
                    String searchResult) {
        log.info("[filter_confluence_results] filtering search results...");

        if (searchResult == null || searchResult.isBlank() || "[]".equals(searchResult)) {
            log.info("[filter_confluence_results] empty search result, returning default page ID");
            return Mono.just(DEFAULT_PAGE_ID);
        }

        // Truncate search result to reduce LLM input tokens
        String truncated = truncateSearchResult(searchResult);
        log.info(
                "[filter_confluence_results] original length={}, truncated length={}",
                searchResult.length(),
                truncated.length());

        return filterSearchResults(userQuestion, truncated)
                .onErrorResume(
                        e -> {
                            log.warn(
                                    "[filter_confluence_results] Filter failed ({}), using default",
                                    e.getMessage());
                            return Mono.just(DEFAULT_PAGE_ID);
                        });
    }

    /**
     * Truncate search result JSON to only essential fields for the filter LLM:
     * keep only id, title, and first N chars of content.value, limited to top M results.
     */
    String truncateSearchResult(String rawJson) {
        try {
            JsonNode root = JSON.readTree(rawJson);
            if (!root.isArray()) {
                return rawJson;
            }
            ArrayNode array = (ArrayNode) root;
            int limit = Math.min(array.size(), MAX_RESULTS_FOR_FILTER);
            ArrayNode trimmed = JSON.createArrayNode();
            for (int i = 0; i < limit; i++) {
                JsonNode item = array.get(i);
                ObjectNode compact = JSON.createObjectNode();
                compact.put("id", item.path("id").asText(""));
                compact.put("title", item.path("title").asText(""));
                String content = item.path("content").path("value").asText("");
                if (content.length() > CONTENT_PREVIEW_CHARS) {
                    content = content.substring(0, CONTENT_PREVIEW_CHARS) + "...";
                }
                ObjectNode contentNode = JSON.createObjectNode();
                contentNode.put("value", content);
                compact.set("content", contentNode);
                trimmed.add(compact);
            }
            return JSON.writeValueAsString(trimmed);
        } catch (Exception e) {
            log.warn(
                    "[filter_confluence_results] Failed to truncate JSON: {}, returning raw",
                    e.getMessage());
            // Fallback: simple string truncation
            if (rawJson.length() > MAX_RESULTS_FOR_FILTER * 500) {
                return rawJson.substring(0, MAX_RESULTS_FOR_FILTER * 500);
            }
            return rawJson;
        }
    }

    private Mono<String> filterSearchResults(String userQuestion, String searchResult) {
        String filterPrompt = buildFilterPrompt(userQuestion, searchResult);

        List<Msg> messages =
                List.of(
                        Msg.builder()
                                .role(MsgRole.USER)
                                .content(List.of(TextBlock.builder().text(filterPrompt).build()))
                                .build());

        return filterModel.stream(messages, null, null)
                .collectList()
                .timeout(java.time.Duration.ofSeconds(FILTER_TIMEOUT_SECONDS))
                .map(this::extractFilterResult);
    }

    private String buildFilterPrompt(String userQuestion, String searchResult) {
        return """
        **角色说明**
        你是一个搜索结果相关性筛选器。

        **输入说明**
        你将接收一组 **JSON 格式的搜索结果**，其中包含多个页面对象。
        每个页面对象包含以下字段：
        * `id`：页面的唯一标识
        * `title`：页面标题
        * `content.value`：页面的部分正文内容（已截断为前 200 字符）

        **任务目标**
        根据用户问题的语义，从搜索结果中筛选出**最相关的页面**，并返回对应的 `id`。

        **判定规则**
        1. 若所有页面均与用户问题无关，返回固定id: 437387755
        2. 若存在相关页面：
           * 按与用户问题的相关性从高到低排序
           * 返回排序后的页面 `id` 列表，最多返回7个

        **输出要求（必须严格遵守）**
        * 仅输出页面 `id`
        * 使用英文逗号 `,` 分隔多个 `id`
        * 不得包含任何解释、文字说明、空格或换行
        * 示例：
          ```
          463681344,463693554
          ```

        **输入内容**
        * 用户问题：`%s`
        * 搜索结果：`%s`

        **输出内容**
        返回最符合用户问题的页面 id（或 437387755）。
        """
                .formatted(userQuestion, searchResult);
    }

    private String extractFilterResult(List<ChatResponse> responses) {
        if (responses == null || responses.isEmpty()) {
            return DEFAULT_PAGE_ID;
        }
        StringBuilder sb = new StringBuilder();
        for (ChatResponse resp : responses) {
            if (resp.getContent() != null) {
                resp.getContent().stream()
                        .filter(b -> b instanceof TextBlock)
                        .map(b -> ((TextBlock) b).getText())
                        .filter(t -> t != null)
                        .forEach(sb::append);
            }
        }
        String result = sb.toString().strip();
        // Extract IDs from the response (handle potential markdown code blocks)
        Pattern pattern = Pattern.compile("[\\d,]+");
        Matcher matcher = pattern.matcher(result);
        if (matcher.find()) {
            String ids = matcher.group();
            log.info("[filter_confluence_results] Filtered page IDs: {}", ids);
            return ids;
        }
        log.warn("[filter_confluence_results] No valid IDs found in response: {}", result);
        return DEFAULT_PAGE_ID;
    }
}
