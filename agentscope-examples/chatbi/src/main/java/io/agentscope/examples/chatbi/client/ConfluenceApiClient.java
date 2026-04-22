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
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
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
    private final boolean mockEnabled;
    // Key: pageId, Value: parsed page content string
    private final JsonNode mockPageData;

    public ConfluenceApiClient(
            @Value("${confluence.api.base-url:http://localhost:8001}") String baseUrl,
            @Value("${confluence.api.api-key:}") String apiKey,
            @Value("${confluence.api.search-limit:8}") int searchLimit,
            @Value("${confluence.api.mock:false}") boolean mockEnabled) {
        this.searchLimit = searchLimit;
        this.mockEnabled = mockEnabled;
        this.mockPageData = loadMockPageData();
        this.webClient =
                WebClient.builder()
                        .baseUrl(baseUrl)
                        .defaultHeader("authorization", "Bearer " + apiKey)
                        .codecs(cfg -> cfg.defaultCodecs().maxInMemorySize(10 * 1024 * 1024))
                        .build();
        if (mockEnabled) {
            log.warn(
                    "[Confluence] Mock mode enabled, data from"
                            + " classpath:json/confluence_search.json & confluence_get_page.json");
        }
    }

    /**
     * Full-text search in Confluence, returns the raw JSON response body.
     *
     * <p>Used by GuAgent to let LLM filter results before fetching page details.
     * <p>When {@code confluence.api.mock=true}, returns data from classpath:json/confluence_search.json.
     * <p>The response format is {@code {"success":true,"data":"[...]"}} and
     * this method extracts the inner data array string.
     */
    public Mono<String> searchRaw(String query) {
        if (mockEnabled) {
            log.info("[Confluence] Mock searchRaw query={}", query);
            return Mono.just(loadMockSearchResult(query));
        }
        String body = "{\"query\": \"" + escapeJson(query) + "\", \"limit\": " + searchLimit + "}";
        return webClient
                .post()
                .uri("/api/confluence/search")
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer pDBP9RmQJe7")
                .bodyValue(body)
                .retrieve()
                .bodyToMono(String.class)
                .timeout(Duration.ofSeconds(20))
                .map(this::extractDataField)
                .onErrorResume(
                        e -> {
                            log.error("[Confluence] search error: {}", e.getMessage());
                            return Mono.just("[]");
                        });
    }

    /**
     * Fetch full page content by page ID.
     * <p>When {@code confluence.api.mock=true}, looks up pageId in classpath:json/confluence_get_page.json.
     * File format: {@code {"pageId": {"status_code":200,"body":"{\"success\":true,\"data\":\"{...}\"}"}}}
     */
    public Mono<String> fetchPage(String pageId) {
        if (mockEnabled) {
            log.info("[Confluence] Mock fetchPage pageId={}", pageId);
            if (mockPageData != null && mockPageData.has(pageId)) {
                try {
                    // Parse: pageEntry["body"] -> {"success":true,"data":"{...}"} -> data content
                    // string
                    String bodyStr = mockPageData.path(pageId).path("body").asText();
                    JsonNode bodyNode = JSON.readTree(bodyStr);
                    String data = bodyNode.path("data").asText();
                    if (data != null && !data.isBlank()) {
                        return Mono.just(data);
                    }
                } catch (Exception e) {
                    log.warn(
                            "[Confluence] Failed to parse mock page {}: {}",
                            pageId,
                            e.getMessage());
                }
            }
            log.warn("[Confluence] Mock page not found for pageId={}", pageId);
            return Mono.just("[页面 " + pageId + " 暂无 mock 数据]");
        }
        String body = "{\"page_id\": \"" + pageId + "\"}";
        return webClient
                .post()
                .uri("/api/confluence/get_page")
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer pDBP9RmQJe7")
                .bodyValue(body)
                .retrieve()
                .bodyToMono(String.class)
                .timeout(Duration.ofSeconds(15))
                .onErrorReturn("[页面 " + pageId + " 加载失败]");
    }

    // ─────────────────── Private helpers ───────────────────

    /**
     * Load and parse mock page data from classpath:json/confluence_get_page.json at startup.
     * File format: {@code {"pageId": {"status_code":200,"body":"..."},  ...}}
     * Returns the root JsonNode (map of pageId -> entry), or null on failure.
     */
    private JsonNode loadMockPageData() {
        try (InputStream is =
                getClass().getClassLoader().getResourceAsStream("json/confluence_get_page.json")) {
            if (is == null) {
                log.warn("[Confluence] Mock file not found: json/confluence_get_page.json");
                return null;
            }
            String fileContent = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            JsonNode root = JSON.readTree(fileContent);
            log.info(
                    "[Confluence] Loaded mock page data from confluence_get_page.json ({} pages)",
                    root.size());
            return root;
        } catch (Exception e) {
            log.error("[Confluence] Failed to load mock page file: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Load and parse mock search result from classpath:json/confluence_search.json.
     * File format: {@code {"status_code":200,"body":"{\"success\":true,\"data\":\"[...]\"}"}}
     * Returns the innermost data array string.
     */
    private String loadMockSearchResult(String query) {
        try (InputStream is =
                getClass().getClassLoader().getResourceAsStream("json/confluence_search.json")) {
            if (is == null) {
                log.warn("[Confluence] Mock file not found: json/confluence_search.json");
                return "[]";
            }
            String fileContent = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            // Parse outer wrapper: {"status_code":200,"body":"..."}
            JsonNode outer = JSON.readTree(fileContent);
            JsonNode resoponse;
            if (query.contains("权限")) {
                resoponse = outer.get("怎么申请权限");
            } else {
                resoponse = outer.get("自助取数任务如何提交");
            }
            String bodyStr = resoponse.path("body").asText();
            // Parse body: {"success":true,"data":"[...]"}
            JsonNode body = JSON.readTree(bodyStr);
            String data = body.path("data").asText();
            if (data != null && !data.isBlank()) {
                log.info("[Confluence] Loaded mock search result from confluence_search.json");
                return data;
            }
            return "[]";
        } catch (Exception e) {
            log.error("[Confluence] Failed to load mock file: {}", e.getMessage());
            return "[]";
        }
    }

    private String extractDataField(String responseBody) {
        try {
            JsonNode root = JSON.readTree(responseBody);
            // Handle {"success":true,"data":"[...]"} wrapper
            if (root.has("data")) {
                String data = root.path("data").asText();
                if (data != null && !data.isBlank()) {
                    return data;
                }
            }
            // Fallback: return raw body if already an array
            return responseBody;
        } catch (Exception e) {
            log.warn("[Confluence] Failed to extract data field: {}", e.getMessage());
            return responseBody;
        }
    }

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
