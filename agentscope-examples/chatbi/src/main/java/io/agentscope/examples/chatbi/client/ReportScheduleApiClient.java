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

import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

/**
 * HTTP client for querying report data schedule (出数时间).
 *
 * <p>Dedicated client for {@code ReportScheduleTool}, decoupled from
 * {@link SupersonicApiClient}.
 *
 * <p>Calls {@code GET /api/v1/readiness/report?reportId={id}} to fetch
 * the data refresh schedule for a given report or dashboard.
 *
 * <p>Configuration properties:
 * <ul>
 *   <li>{@code report.schedule.api.base-url} – base URL of the schedule service
 *       (defaults to {@code supersonic.api.base-url} value)</li>
 *   <li>{@code report.schedule.api.timeout-seconds} – request timeout in seconds
 *       (default: 15)</li>
 * </ul>
 */
@Component
public class ReportScheduleApiClient {

    private static final Logger log = LoggerFactory.getLogger(ReportScheduleApiClient.class);

    private final WebClient webClient;

    private final int timeoutSeconds;

    public ReportScheduleApiClient(
            @Value("${report.schedule.api.base-url:http://localhost:9080}")
                    String baseUrl,
            @Value("${report.schedule.api.timeout-seconds:15}") int timeoutSeconds) {
        this.timeoutSeconds = timeoutSeconds;
        this.webClient =
                WebClient.builder()
                        .baseUrl(baseUrl)
                        .codecs(cfg -> cfg.defaultCodecs().maxInMemorySize(2 * 1024 * 1024))
                        .build();
        log.info(
                "ReportScheduleApiClient initialized, baseUrl={}, timeoutSeconds={}",
                baseUrl,
                timeoutSeconds);
    }

    /**
     * Query the data refresh schedule (出数时间) for a report or dashboard.
     *
     * <p>Preference order: {@code reportId} first, {@code dashboardId} as fallback.
     *
     * @param reportId      report ID (may be null or blank)
     * @param dashboardId   dashboard ID used when reportId is absent
     * @param authorization SuperSonic auth token (Bearer …)
     * @return schedule info as raw JSON/text string, or an error message
     */
    public Mono<String> queryReportSchedule(
            String reportId, String dashboardId, String authorization) {
        String id = (reportId != null && !reportId.isBlank()) ? reportId : dashboardId;
        if (id == null || id.isBlank()) {
            log.warn("[ReportScheduleApiClient] No reportId or dashboardId provided");
            return Mono.just("未提供报表ID或仪表盘ID，无法查询出数时间。");
        }
        log.debug(
                "[ReportScheduleApiClient] queryReportSchedule id={}", id);
        return webClient
                .get()
                .uri(
                        uriBuilder ->
                                uriBuilder
                                        .path("/api/v1/readiness/report")
                                        .queryParam("reportId", id)
                                        .build())
                .header("authorization", authorization)
                .retrieve()
                .bodyToMono(String.class)
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .onErrorResume(
                        e -> {
                            log.error(
                                    "[ReportScheduleApiClient] queryReportSchedule error id={}: {}",
                                    id,
                                    e.getMessage());
                            return Mono.just("查询出数时间接口异常：" + e.getMessage());
                        });
    }
}
