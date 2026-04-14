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
package io.agentscope.examples.chatbi.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * HTTP client for the internal Confluence knowledge base API.
 *
 * <p>Two endpoints are used:
 * <ul>
 *   <li>{@code POST /api/confluence/search} – full-text search, returns page summaries with IDs</li>
 *   <li>{@code POST /api/confluence/get_page} – fetch full page content by page ID</li>
 * </ul>
 */
@Component
public class ConfluenceApiClient {

    private static final Logger log = LoggerFactory.getLogger(ConfluenceApiClient.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final WebClient webClient;
    private final int searchLimit;

    public ConfluenceApiClient(
            @Value("${confluence.api.base-url:http://localhost:8001}") String baseUrl,
            @Value("${confluence.api.api-key:}") String apiKey,
            @Value("${confluence.api.search-limit:8}") int searchLimit) {
        this.searchLimit = searchLimit;
        this.webClient =
                WebClient.builder()
                        .baseUrl(baseUrl)
                        .defaultHeader("authorization", "Bearer " + apiKey)
                        .codecs(cfg -> cfg.defaultCodecs().maxInMemorySize(10 * 1024 * 1024))
                        .build();
    }

    /**
     * Search the knowledge base and return the top relevant page contents.
     *
     * <p>Process: search → LLM-like relevance filter (done by caller/tool) → fetch full pages.
     * This method does search + fetch all returned pages in parallel.
     *
     * @param query search query text
     * @return concatenated page contents (markdown-friendly)
     */
    public Mono<String> searchAndFetch(String query) {
        return search(query)
                .flatMap(
                        ids -> {
                            if (ids.isEmpty()) {
                                return Mono.just("未找到相关知识库内容。");
                            }
                            // Fetch all relevant pages in parallel
                            List<Mono<String>> fetchMonos = new ArrayList<>();
                            for (String id : ids) {
                                fetchMonos.add(fetchPage(id));
                            }
                            return Flux.merge(fetchMonos)
                                    .filter(content -> content != null && !content.isBlank())
                                    .collectList()
                                    .map(pages -> String.join("\n\n---\n\n", pages));
                        })
                .onErrorResume(
                        e -> {
                            log.error("[Confluence] searchAndFetch error: {}", e.getMessage());
                            return Mono.just("知识库检索异常：" + e.getMessage());
                        });
    }

    /**
     * Full-text search in Confluence, returns a list of relevant page IDs.
     */
    public Mono<List<String>> search(String query) {
        String body = "{\"query\": \"" + escapeJson(query) + "\", \"limit\": " + searchLimit + "}";
        return webClient
                .post()
                .uri("/api/confluence/search")
                .header("Content-Type", "application/json")
                .bodyValue(body)
                .retrieve()
                .bodyToMono(String.class)
                .timeout(Duration.ofSeconds(20))
                .map(this::extractPageIds)
                .onErrorResume(
                        e -> {
                            log.error("[Confluence] search error: {}", e.getMessage());
                            return Mono.just(new ArrayList<>());
                        });
    }

    /**
     * Fetch full page content by page ID.
     */
    public Mono<String> fetchPage(String pageId) {
        String body = "{\"page_id\": \"" + pageId + "\"}";
        return webClient
                .post()
                .uri("/api/confluence/get_page")
                .header("Content-Type", "application/json")
                .bodyValue(body)
                .retrieve()
                .bodyToMono(String.class)
                .timeout(Duration.ofSeconds(15))
                .onErrorReturn("[页面 " + pageId + " 加载失败]");
    }

    // ─────────────────── Private helpers ───────────────────

    private List<String> extractPageIds(String body) {
        List<String> ids = new ArrayList<>();
        try {
            JsonNode root = JSON.readTree(body);
            // Support both array root and {"data": [...]} wrapper
            JsonNode items = root.isArray() ? root : root.path("data");
            if (items.isArray()) {
                for (JsonNode item : items) {
                    String id = item.path("id").asText(null);
                    if (id != null && !id.isBlank()) {
                        ids.add(id);
                    }
                }
            }
        } catch (Exception e) {
            log.warn("[Confluence] Failed to parse search result: {}", e.getMessage());
        }
        return ids;
    }

    private static String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
    }
}
