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
 * HTTP client for the report search API.
 *
 * <p>Calls {@code POST /api/v1/search} to search for reports, dashboards,
 * and data screens based on a natural-language query.
 *
 * <p>Configuration properties:
 * <ul>
 *   <li>{@code report.search.api.base-url} – base URL of the search service</li>
 *   <li>{@code report.search.api.timeout-seconds} – request timeout in seconds (default: 15)</li>
 * </ul>
 */
@Component
public class ReportSearchApiClient {

    private static final Logger log = LoggerFactory.getLogger(ReportSearchApiClient.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final WebClient webClient;
    private final int timeoutSeconds;
    private final boolean mockEnable;

    public ReportSearchApiClient(
            @Value("${report.search.api.base-url:http://localhost:8006}") String baseUrl,
            @Value("${report.search.api.timeout-seconds:15}") int timeoutSeconds,
            @Value("${report.search.api.mock-enable:true}") boolean mockEnable) {
        this.timeoutSeconds = timeoutSeconds;
        this.mockEnable = mockEnable;
        this.webClient =
                WebClient.builder()
                        .baseUrl(baseUrl)
                        .codecs(cfg -> cfg.defaultCodecs().maxInMemorySize(5 * 1024 * 1024))
                        .build();
        log.info(
                "ReportSearchApiClient initialized, baseUrl={}, timeoutSeconds={}",
                baseUrl,
                timeoutSeconds);
    }

    /**
     * Search for reports and dashboards matching the user's query.
     *
     * @param query         the user's question or search term
     * @param projectId     project / tenant ID
     * @param reportName    current report name from session context (may be null)
     * @param dashboardName current dashboard name from session context (may be null)
     * @param memory        JSON string of previous conversation history (may be null)
     * @param topN          number of results to return (default 10)
     * @return search result as JSON string, or an error message
     */
    public Mono<String> searchReports(
            String query,
            String projectId,
            String reportName,
            String dashboardName,
            String memory,
            int topN) {

        if (mockEnable) {
            return Mono.just(loadMockSearchResult(query));
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("query", query);
        body.put("project_id", projectId);
        body.put("top_n", topN);
        body.put("include_metadata", true);
        if (reportName != null && !reportName.isBlank()) {
            body.put("reportName", reportName);
        }
        if (dashboardName != null && !dashboardName.isBlank()) {
            body.put("dashboardName", dashboardName);
        }
        body.put("question_type", "re");
        if (memory != null && !memory.isBlank()) {
            body.put("memory", memory);
        }

        String bodyJson;
        try {
            bodyJson = JSON.writeValueAsString(body);
        } catch (Exception e) {
            log.error("[ReportSearchApiClient] Failed to serialize request body", e);
            return Mono.just("报表搜索请求构建失败：" + e.getMessage());
        }

        log.info(
                "[ReportSearchApiClient] searchReports query={}, projectId={}, reportName={},"
                        + " dashboardName={}",
                query,
                projectId,
                reportName,
                dashboardName);

        return webClient
                .post()
                .uri("/api/v1/search")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve()
                .bodyToMono(String.class)
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .onErrorResume(
                        e -> {
                            log.error(
                                    "[ReportSearchApiClient] searchReports error query={}: {}",
                                    query,
                                    e.getMessage());
                            return Mono.just("报表推荐接口异常：" + e.getMessage());
                        });
    }

    private String loadMockSearchResult(String query) {
        try (InputStream is =
                getClass().getClassLoader().getResourceAsStream("json/report_search.json")) {
            if (is == null) {
                log.warn("[report_search] Mock file not found: json/report_search.json");
                return "[]";
            }
            String fileContent = new String(is.readAllBytes(), StandardCharsets.UTF_8);

            JsonNode outer = JSON.readTree(fileContent);
            JsonNode resoponse;
            if (query.contains("经分")) {
                resoponse = outer.get("二级经分空间");
            } else {
                resoponse = outer.get("视讯二级");
            }

            return resoponse.path("body").asText();
        } catch (Exception e) {
            log.error("[report_search] Failed to load mock file: {}", e.getMessage());
            return "[]";
        }
    }
}
