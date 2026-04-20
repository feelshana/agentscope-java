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
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.time.Duration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

/**
 * HTTP client for the internal knowledge search service (DocRepo + Report Search).
 *
 * <p>Wraps two endpoints on the same backend:
 * <ul>
 *   <li>{@code POST /api/v1/search} – report/dashboard search (intent: {@code re})</li>
 *   <li>{@code POST /api/v1/docrepo/query} – knowledge base query (intents: {@code bu}, {@code in})</li>
 * </ul>
 *
 * <p>Base URL and auth are configured via {@code application.yml}:
 * <pre>
 * knowledge.search.base-url: http://10.194.2.66:8006
 * knowledge.search.api-key: mk_mQ636UhMnx8brb9Grcy
 * </pre>
 */
@Component
public class KnowledgeSearchClient {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeSearchClient.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final WebClient webClient;
    private final String apiKey;
    private final int defaultTopN;

    public KnowledgeSearchClient(
            @Value("${knowledge.search.base-url:http://10.194.2.66:8006}") String baseUrl,
            @Value("${knowledge.search.api-key:mk_mQ636UhMnx8brb9Grcy}") String apiKey,
            @Value("${knowledge.search.top-n:10}") int defaultTopN) {
        this.apiKey = apiKey;
        this.defaultTopN = defaultTopN;
        this.webClient = WebClient.builder()
                .baseUrl(baseUrl)
                .defaultHeader("Authorization", "Bearer " + apiKey)
                .codecs(cfg -> cfg.defaultCodecs().maxInMemorySize(10 * 1024 * 1024))
                .build();
    }

    /**
     * Search for reports/dashboards by keyword (intent: {@code re}).
     *
     * @param query the user's search query
     * @param topN  max number of results to return
     * @return the {@code final_prompt} extracted from the response, or an error message
     */
    public Mono<String> searchReports(String query, int topN) {
        ObjectNode body = JSON.createObjectNode();
        body.put("query", query);
        body.put("top_n", topN > 0 ? topN : defaultTopN);
        body.put("question_type", "re");
        body.put("include_metadata", true);

        return doSearch("/api/v1/search", body);
    }

    /**
     * Query the knowledge base for metric definitions or business knowledge
     * (intents: {@code bu}, {@code in}).
     *
     * @param query         the user's rewritten query
     * @param questionType  "bu" (business knowledge) or "in" (metric definition)
     * @param projectId     project identifier
     * @param reportName    current report name (optional, can be null)
     * @param dashboardName current dashboard name (optional, can be null)
     * @param memoryJson    conversation history as JSON string (optional, can be null)
     * @return the {@code final_prompt} extracted from the response, or an error message
     */
    public Mono<String> queryDocRepo(String query,
                                     String questionType,
                                     String projectId,
                                     String reportName,
                                     String dashboardName,
                                     String memoryJson) {
        ObjectNode body = JSON.createObjectNode();
        body.put("query", query);
        body.put("project_id", projectId != null ? projectId : "");
        body.put("top_n", defaultTopN);
        body.put("include_metadata", true);
        body.put("question_type", questionType);
        if (reportName != null && !reportName.isBlank()) {
            body.put("reportName", reportName);
        }
        if (dashboardName != null && !dashboardName.isBlank()) {
            body.put("dashboardName", dashboardName);
        }
        if (memoryJson != null && !memoryJson.isBlank()) {
            body.put("memory", memoryJson);
        }

        return doSearch("/api/v1/docrepo/query", body);
    }

    private Mono<String> doSearch(String uri, ObjectNode body) {
        String bodyJson;
        try {
            bodyJson = JSON.writeValueAsString(body);
        } catch (Exception e) {
            log.error("[KnowledgeSearch] Failed to serialize request body: {}", e.getMessage());
            return Mono.just(buildErrorResult("请求参数序列化失败"));
        }

        return webClient
                .post()
                .uri(uri)
                .header("Content-Type", "application/json")
                .bodyValue(bodyJson)
                .retrieve()
                .bodyToMono(String.class)
                .timeout(Duration.ofSeconds(30))
                .retryWhen(Retry.backoff(2, Duration.ofMillis(100)))
                .map(this::extractFinalPrompt)
                .onErrorResume(e -> {
                    log.error("[KnowledgeSearch] request error at {}: {}", uri, e.getMessage());
                    return Mono.just(buildErrorResult(
                            "抱歉，我暂时无法为您查询到相关知识，请稍后再试。"));
                });
    }

    /**
     * Extract {@code final_prompt} from the API response.
     * Response format: {@code {"final_prompt": "...", ...}}
     * or wrapped as {@code {"success": true, "data": {"final_prompt": "..."}}}
     */
    private String extractFinalPrompt(String responseBody) {
        try {
            JsonNode root = JSON.readTree(responseBody);
            // Direct field
            if (root.has("final_prompt")) {
                String prompt = root.path("final_prompt").asText("").trim();
                if (!prompt.isBlank()) return prompt;
            }
            // Nested in data
            if (root.has("data")) {
                JsonNode data = root.path("data");
                if (data.isObject() && data.has("final_prompt")) {
                    String prompt = data.path("final_prompt").asText("").trim();
                    if (!prompt.isBlank()) return prompt;
                }
                // data might be a string containing JSON
                if (data.isTextual()) {
                    String dataStr = data.asText();
                    JsonNode dataNode = JSON.readTree(dataStr);
                    if (dataNode.has("final_prompt")) {
                        String prompt = dataNode.path("final_prompt").asText("").trim();
                        if (!prompt.isBlank()) return prompt;
                    }
                }
            }
            // Fallback: return the entire response if it looks like a text result
            return responseBody.length() > 2000 ? responseBody.substring(0, 2000) + "..." : responseBody;
        } catch (Exception e) {
            log.warn("[KnowledgeSearch] Failed to extract final_prompt: {}", e.getMessage());
            return responseBody;
        }
    }

    private String buildErrorResult(String message) {
        return message;
    }
}