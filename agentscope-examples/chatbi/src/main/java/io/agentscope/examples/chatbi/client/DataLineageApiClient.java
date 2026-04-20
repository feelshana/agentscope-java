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

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

/**
 * Dedicated HTTP client for the data lineage API (血缘查询).
 *
 * <p>Decoupled from {@link SupersonicApiClient}; uses its own base-url and api-key
 * so the lineage service can be deployed independently.
 *
 * <p>Configuration properties (set in {@code application-dev.yml} / environment):
 * <ul>
 *   <li>{@code data.lineage.api.base-url}  – base URL of the lineage service</li>
 *   <li>{@code data.lineage.api.api-key}   – authentication key / token</li>
 *   <li>{@code data.lineage.api.timeout-seconds} – request timeout (default: 60)</li>
 * </ul>
 *
 * <p>The API contract (POST {@code /api/data/lineage}):
 * <pre>
 * Request body:
 * {
 *   "query":      "用户的问题",
 *   "project_id": "前端传入的projectId",
 *   "memory":     "[{...}]"   // optional – previous conversation rounds
 * }
 *
 * Response body (JSON):
 * {
 *   "final_prompt": "..."   // extracted and returned by this client
 * }
 * </pre>
 */
@Component
public class DataLineageApiClient {

    private static final Logger log = LoggerFactory.getLogger(DataLineageApiClient.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    /** Fallback message when the lineage API is unreachable or returns invalid data. */
    static final String ERROR_MSG =
            "你是红海chatBI问答小助手，你当前遇到了网络问题，请直接回复用户：" +
            "抱歉，我遇到了一个网络问题，暂时无法处理您的请求。请稍后再试，或联系分析云团队获取帮助。感谢您的理解！";

    private final WebClient webClient;
    private final String apiKey;
    private final int timeoutSeconds;

    public DataLineageApiClient(
            @Value("${data.lineage.api.base-url:http://localhost:9080}") String baseUrl,
            @Value("${data.lineage.api.api-key:}") String apiKey,
            @Value("${data.lineage.api.timeout-seconds:60}") int timeoutSeconds) {
        this.apiKey = apiKey;
        this.timeoutSeconds = timeoutSeconds;
        this.webClient = WebClient.builder()
                .baseUrl(baseUrl)
                .codecs(cfg -> cfg.defaultCodecs().maxInMemorySize(10 * 1024 * 1024))
                .build();
        log.info("DataLineageApiClient initialized, baseUrl={}, timeoutSeconds={}",
                baseUrl, timeoutSeconds);
    }

    /**
     * Query data lineage and return the extracted {@code final_prompt}.
     *
     * <p>Mirrors the Dify post-processing script:
     * <pre>
     *   body_data = json.loads(response)
     *   final_prompt = body_data.get("final_prompt", "").strip()
     *   return final_prompt if final_prompt else error_msg
     * </pre>
     *
     * @param query     user's question / rewritten question
     * @param projectId project ID passed from frontend
     * @param memory    JSON string of previous conversation rounds (may be null/empty)
     * @return extracted {@code final_prompt}, or {@link #ERROR_MSG} on failure
     */
    public Mono<String> queryLineage(String query, String projectId, String memory) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("query", query);
        body.put("project_id", projectId);
        if (memory != null && !memory.isBlank()) {
            body.put("memory", memory);
        }
        log.info("[DataLineageApiClient] queryLineage query={}, projectId={}, memoryLen={}",
                query, projectId, memory == null ? 0 : memory.length());


        if (true){
            return Mono.just(loadMockSearchResult());
        }

        return webClient
                .post()
                .uri("/api/data/lineage")
                .header("Authorization", apiKey)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve()
                .bodyToMono(String.class)
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .map(this::extractFinalPrompt)
                .onErrorResume(e -> {
                    log.error("[DataLineageApiClient] queryLineage error query={}: {}",
                            query, e.getMessage());
                    return Mono.just(ERROR_MSG);
                });
    }

    /**
     * Extract the {@code final_prompt} field from the API JSON response.
     *
     * @param responseBody raw JSON string
     * @return trimmed {@code final_prompt}, or {@link #ERROR_MSG} if absent / blank
     */
    private String extractFinalPrompt(String responseBody) {
        if (responseBody == null || responseBody.isBlank()) {
            log.warn("[DataLineageApiClient] Empty response body");
            return ERROR_MSG;
        }
        try {
            Map<String, Object> parsed = JSON.readValue(responseBody, MAP_TYPE);
            Object fp = parsed.get("final_prompt");
            if (fp instanceof String finalPrompt && !finalPrompt.isBlank()) {
                log.debug("[DataLineageApiClient] Extracted final_prompt length={}",
                        finalPrompt.length());
                return finalPrompt.strip();
            }
            log.warn("[DataLineageApiClient] final_prompt missing or blank, snippet={}",
                    responseBody.length() > 200
                            ? responseBody.substring(0, 200) + "..." : responseBody);
            return ERROR_MSG;
        } catch (Exception e) {
            log.error("[DataLineageApiClient] Failed to parse response: {}", e.getMessage());
            return ERROR_MSG;
        }
    }


    private String loadMockSearchResult() {
        try (InputStream is =
                     getClass().getClassLoader().getResourceAsStream("json/data_lineage.json")) {
            if (is == null) {
                log.warn("[DataLineage] Mock file not found: json/data_lineage.json");
                return "[]";
            }
            String fileContent = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            // Parse outer wrapper: {"status_code":200,"body":"..."}
            JsonNode outer = JSON.readTree(fileContent);

            String bodyStr = outer.path("body").asText();
            // Parse body: {"success":true,"data":"[...]"}
            JsonNode body = JSON.readTree(bodyStr);
            String data = body.path("final_prompt").asText();
            if (data != null && !data.isBlank()) {
                log.info("[DataLineage] Loaded mock search result from data_lineage.json");
                return data;
            }
            return "[]";
        } catch (Exception e) {
            log.error("[DataLineage] Failed to load mock file: {}", e.getMessage());
            return "[]";
        }
    }
}
