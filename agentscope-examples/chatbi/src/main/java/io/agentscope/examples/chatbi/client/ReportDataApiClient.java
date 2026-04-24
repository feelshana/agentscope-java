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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

/**
 * HTTP client for fetching report raw data for ChatBI interpretation.
 *
 * <p>Calls {@code POST /report/data/{reportId}/getPureData4ChatBI} with the
 * same parameters as the Dify workflow:
 * <ul>
 *   <li>URL path variable: {@code reportId}</li>
 *   <li>Header: {@code easyBiSession} (session token)</li>
 *   <li>Body: {@code chatParms} (JSON string from frontend {@code param})</li>
 * </ul>
 *
 * <p>Configuration properties:
 * <ul>
 *   <li>{@code report.data.api.base-url} – base URL of the report data service
 *       (default: {@code http://10.194.142.14:7999})</li>
 *   <li>{@code report.data.api.timeout-seconds} – request timeout (default: 15)</li>
 * </ul>
 */
@Component
public class ReportDataApiClient {

    private static final Logger log = LoggerFactory.getLogger(ReportDataApiClient.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private final WebClient webClient;
    private final int timeoutSeconds;
    private final boolean mockEnabled;

    public ReportDataApiClient(
            @Value("${report.data.api.base-url:http://10.194.142.14:7999}") String baseUrl,
            @Value("${report.data.api.timeout-seconds:15}") int timeoutSeconds,
            @Value("${report.data.api.mock-enabled:true}") boolean mockEnabled) {
        this.timeoutSeconds = timeoutSeconds;
        this.mockEnabled = mockEnabled;
        this.webClient =
                WebClient.builder()
                        .baseUrl(baseUrl)
                        .codecs(cfg -> cfg.defaultCodecs().maxInMemorySize(5 * 1024 * 1024))
                        .build();
        log.info(
                "ReportDataApiClient initialized, baseUrl={}, timeoutSeconds={}",
                baseUrl,
                timeoutSeconds);
    }

    /**
     * Fetch raw data for a report via the getPureData4ChatBI endpoint.
     *
     * <p>Mirrors the Dify HTTP request node exactly:
     * <pre>
     *   POST /report/data/{reportId}/getPureData4ChatBI
     *   Header: easyBiSession: {easyBiSession}
     *   Header: Content-Type: application/json
     *   Body:   {chatParms}  (raw JSON string from frontend param)
     * </pre>
     *
     * @param reportId      report ID (path variable)
     * @param easyBiSession EasyBI session token (header)
     * @param chatParms     chart data JSON string from frontend (request body)
     * @return raw report data as JSON string, or an error message
     */
    public Mono<String> interpretChartData(
            String reportId, String easyBiSession, String chatParms) {
        if (mockEnabled) {
            return Mono.just(loadMockSearchResult(reportId));
        }

        if (reportId == null || reportId.isBlank()) {
            log.warn("[ReportDataApiClient] No reportId provided");
            return Mono.just("未提供报表ID，无法查询报表数据。");
        }
        log.info(
                "[ReportDataApiClient] getReportData reportId={}, easyBiSession={},"
                        + " chatParmsLen={}",
                reportId,
                easyBiSession != null ? "***" : "null",
                chatParms != null ? chatParms.length() : 0);

        WebClient.RequestBodySpec spec =
                webClient
                        .post()
                        .uri("/report/data/" + reportId + "/getPureData4ChatBI")
                        .header("Content-Type", "application/json");

        if (easyBiSession != null && !easyBiSession.isBlank()) {
            spec.header("easyBiSession", easyBiSession);
        }

        // Send chatParms as raw JSON body (mirrors Dify body.data[0].value = {{param}})
        String body = (chatParms != null && !chatParms.isBlank()) ? chatParms : "{}";
        return spec.bodyValue(body)
                .retrieve()
                .bodyToMono(String.class)
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .onErrorResume(
                        e -> {
                            log.error(
                                    "[ReportDataApiClient] getReportData error reportId={}: {}",
                                    reportId,
                                    e.getMessage());
                            return Mono.just("查询报表数据接口异常：" + e.getMessage());
                        });
    }

    private String loadMockSearchResult(String reportId) {
        try (InputStream is =
                getClass()
                        .getClassLoader()
                        .getResourceAsStream("json/report_Interpretation.json")) {
            if (is == null) {
                log.warn("[report_search] Mock file not found: json/report_Interpretation.json");
                return "[]";
            }
            String fileContent = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            JsonNode outer = JSON.readTree(fileContent);
            JsonNode reportNode = outer.get(reportId);
            if (reportNode == null) {
                log.warn("[report_search] No mock data for reportId: {}", reportId);
                return "[]";
            }
            String bodyStr = reportNode.path("body").asText();
            JsonNode body = JSON.readTree(bodyStr);
            JsonNode data = body.path("data");

            if (!data.isMissingNode()) {
                log.info(
                        "[report_search] Loaded mock search result from"
                                + " report_Interpretation.json");
                return data.toString();
            }
            return "[]";
        } catch (Exception e) {
            log.error("[report_search] Failed to load mock file: {}", e.getMessage());
            return "[]";
        }
    }
}
