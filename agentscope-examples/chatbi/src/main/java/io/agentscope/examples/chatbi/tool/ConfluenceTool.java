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

import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import io.agentscope.examples.chatbi.client.ConfluenceApiClient;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Confluence knowledge base tools for GuAgent.
 *
 * <p>Provides two tools:
 * <ul>
 *   <li>{@code search_confluence} - Search and return raw JSON results for LLM filtering</li>
 *   <li>{@code get_confluence_page} - Fetch page content by ID(s)</li>
 * </ul>
 */
public class ConfluenceTool {

    private static final Logger log = LoggerFactory.getLogger(ConfluenceTool.class);

    private final ConfluenceApiClient confluenceClient;

    public ConfluenceTool(ConfluenceApiClient confluenceClient) {
        this.confluenceClient = confluenceClient;
    }

    /**
     * Search Confluence and return raw JSON results for LLM filtering.
     *
     * @param query the search query
     * @return raw JSON string containing search results
     */
    @Tool(
            name = "search_confluence",
            description =
                    "Search the Confluence knowledge base and return raw search results. The result"
                            + " is a JSON array containing page objects with 'id', 'title', and"
                            + " 'content.value' fields. Use this tool first to find relevant"
                            + " documentation pages.")
    public Mono<String> search(
            @ToolParam(
                            name = "query",
                            description = "The search query extracted from the user's question.")
                    String query) {
        log.info("[search_confluence] query={}", query);
        return confluenceClient
                .searchRaw(query)
                .doOnNext(r -> log.debug("[search_confluence] result length={}", r.length()))
                .onErrorResume(
                        e -> {
                            log.error("[search_confluence] Error: {}", e.getMessage());
                            return Mono.just("[]");
                        });
    }

    /**
     * Fetch Confluence page content by page ID(s).
     *
     * @param pageIds a single page ID or comma-separated multiple page IDs
     * @return concatenated page content
     */
    @Tool(
            name = "get_confluence_page",
            description =
                    "Fetch the full content of Confluence page(s) by page ID. Accepts a single page"
                            + " ID or comma-separated multiple IDs. Use this tool after filtering"
                            + " search results to get detailed page content.")
    public Mono<String> getPage(
            @ToolParam(
                            name = "page_ids",
                            description =
                                    "A single page ID or comma-separated multiple page IDs, e.g.,"
                                            + " '463681344' or '463681344,463693554'")
                    String pageIds) {
        log.info("[get_confluence_page] pageIds={}", pageIds);

        if (pageIds == null || pageIds.isBlank()) {
            return Mono.just("未提供页面ID。");
        }

        List<String> ids =
                Arrays.stream(pageIds.split(","))
                        .map(String::trim)
                        .filter(id -> !id.isBlank())
                        .collect(Collectors.toList());

        if (ids.isEmpty()) {
            return Mono.just("未提供有效的页面ID。");
        }

        // Fetch all pages in parallel
        List<Mono<String>> fetchMonos =
                ids.stream().map(confluenceClient::fetchPage).collect(Collectors.toList());

        return Flux.merge(fetchMonos)
                .filter(content -> content != null && !content.isBlank())
                .collectList()
                .map(
                        pages -> {
                            if (pages.isEmpty()) {
                                return "未能获取页面内容。";
                            }
                            return String.join("\n\n---\n\n", pages);
                        })
                .doOnNext(r -> log.debug("[get_confluence_page] result length={}", r.length()))
                .onErrorResume(
                        e -> {
                            log.error("[get_confluence_page] Error: {}", e.getMessage());
                            return Mono.just("获取页面内容失败：" + e.getMessage());
                        });
    }
}
