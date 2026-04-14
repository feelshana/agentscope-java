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
import io.agentscope.examples.chatbi.dto.DatasetInfo;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

/**
 * HTTP client for SuperSonic NLP data query API.
 *
 * <p>Provides two core operations:
 * <ul>
 *   <li>{@code listDatasets(agentId, token)} – fetches available datasets for a given agent</li>
 *   <li>{@code queryDataset(agentId, query, token)} – sends a natural-language query to SuperSonic</li>
 *   <li>{@code queryAgentInfo(agentId, token)} – fetches agent dataset schema info</li>
 *   <li>{@code queryReportSchedule(reportId, token)} – fetches report data schedule info</li>
 * </ul>
 */
@Component
public class SupersonicApiClient {

    private static final Logger log = LoggerFactory.getLogger(SupersonicApiClient.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final WebClient webClient;

    @Value("${supersonic.api.nlp-agent-ids:}")
    private String defaultAgentIdsStr;

    /** datasetName → agentId for multi-agent routing */
    private final Map<String, String> datasetAgentIdMap = new ConcurrentHashMap<>();

    /** datasetName → chatId for per-dataset conversation isolation */
    private final Map<String, String> datasetChatIdMap = new ConcurrentHashMap<>();

    public SupersonicApiClient(
            @Value("${supersonic.api.base-url:http://localhost:9082}") String baseUrl) {
        this.webClient =
                WebClient.builder()
                        .baseUrl(baseUrl)
                        .codecs(cfg -> cfg.defaultCodecs().maxInMemorySize(10 * 1024 * 1024))
                        .build();
    }

    /**
     * List available datasets for the given agent ID.
     *
     * @param agentId        SuperSonic agent ID
     * @param authorization  SuperSonic auth token (Bearer)
     * @return list of DatasetInfo
     */
    public Mono<List<DatasetInfo>> listDatasets(String agentId, String authorization) {
        if (agentId == null || agentId.isBlank()) {
            // Fall back to configured default agent IDs
            List<String> ids =
                    defaultAgentIdsStr.isBlank()
                            ? new ArrayList<>()
                            : Arrays.stream(defaultAgentIdsStr.split(","))
                                    .map(String::trim)
                                    .filter(s -> !s.isBlank())
                                    .collect(Collectors.toList());
            if (ids.isEmpty()) {
                return Mono.just(Collections.emptyList());
            }
            agentId = ids.get(0);
        }
        String finalAgentId = agentId;
        return webClient
                .get()
                .uri(
                        uriBuilder ->
                                uriBuilder
                                        .path("/api/chat/agent/getAgentDataSetInfo")
                                        .queryParam("agentId", finalAgentId)
                                        .queryParam("queryText", "")
                                        .build())
                .header("authorization", authorization)
                .retrieve()
                .bodyToMono(String.class)
                .timeout(Duration.ofSeconds(30))
                .map(body -> parseDatasets(body, finalAgentId))
                .doOnNext(
                        list ->
                                list.forEach(
                                        ds -> datasetAgentIdMap.put(ds.getName(), finalAgentId)))
                .onErrorResume(
                        e -> {
                            log.error("[SuperSonic] listDatasets error: {}", e.getMessage());
                            return Mono.just(Collections.emptyList());
                        });
    }

    /**
     * Send a natural-language query to SuperSonic for a specific dataset.
     *
     * @param agentId       agent ID
     * @param queryText     natural-language query text
     * @param authorization SuperSonic auth token
     * @return JSON string of query result
     */
    public Mono<String> queryDataset(String agentId, String queryText, String authorization) {
        // Build chat request body
        String chatId = datasetChatIdMap.getOrDefault(agentId, "0");
        String body =
                "{\"agentId\":"
                        + agentId
                        + ",\"queryText\":\""
                        + escapeJson(queryText)
                        + "\",\"chatId\":"
                        + chatId
                        + "}";

        return webClient
                .post()
                .uri("/api/chat/query/execute")
                .header("authorization", authorization)
                .header("Content-Type", "application/json")
                .bodyValue(body)
                .retrieve()
                .bodyToMono(String.class)
                .timeout(Duration.ofSeconds(120))
                .map(
                        resp -> {
                            // Extract chatId for session continuity
                            try {
                                JsonNode root = JSON.readTree(resp);
                                JsonNode data = root.path("data");
                                if (data.has("chatId")) {
                                    datasetChatIdMap.put(
                                            agentId, String.valueOf(data.get("chatId").asLong()));
                                }
                            } catch (Exception ignored) {
                            }
                            return resp;
                        })
                .onErrorResume(
                        e -> {
                            log.error(
                                    "[SuperSonic] queryDataset error agentId={}: {}",
                                    agentId,
                                    e.getMessage());
                            return Mono.just("Error querying SuperSonic: " + e.getMessage());
                        });
    }

    /**
     * Query the report data schedule (出数时间) for a report or dashboard.
     *
     * @param reportId      report ID (may be null if dashboardId is used)
     * @param dashboardId   dashboard ID (fallback)
     * @param authorization SuperSonic auth token
     * @return schedule info as string
     */
    public Mono<String> queryReportSchedule(
            String reportId, String dashboardId, String authorization) {
        String id = (reportId != null && !reportId.isBlank()) ? reportId : dashboardId;
        if (id == null || id.isBlank()) {
            return Mono.just("未提供报表ID或仪表盘ID，无法查询出数时间。");
        }
        return webClient
                .get()
                .uri(
                        uriBuilder ->
                                uriBuilder
                                        .path("/api/report/schedule")
                                        .queryParam("reportId", id)
                                        .build())
                .header("authorization", authorization)
                .retrieve()
                .bodyToMono(String.class)
                .timeout(Duration.ofSeconds(15))
                .onErrorResume(
                        e -> {
                            log.error("[SuperSonic] queryReportSchedule error: {}", e.getMessage());
                            return Mono.just("查询出数时间接口异常：" + e.getMessage());
                        });
    }

    /**
     * Query data lineage for a given table, metric or field name.
     *
     * @param target        table/metric/field name to query lineage for
     * @param authorization SuperSonic auth token
     * @return lineage information as string
     */
    public Mono<String> queryLineage(String target, String authorization) {
        return webClient
                .get()
                .uri(
                        uriBuilder ->
                                uriBuilder
                                        .path("/api/data/lineage")
                                        .queryParam("target", target)
                                        .build())
                .header("authorization", authorization)
                .retrieve()
                .bodyToMono(String.class)
                .timeout(Duration.ofSeconds(30))
                .onErrorResume(
                        e -> {
                            log.error(
                                    "[SuperSonic] queryLineage error target={}: {}",
                                    target,
                                    e.getMessage());
                            return Mono.just("数据血缘查询接口异常：" + e.getMessage());
                        });
    }

    /**
     * Look up the agent ID registered for a given dataset name.
     */
    public String getAgentIdForDataset(String datasetName) {
        return datasetAgentIdMap.get(datasetName);
    }

    // ─────────────────── Private helpers ───────────────────

    private List<DatasetInfo> parseDatasets(String body, String agentId) {
        List<DatasetInfo> result = new ArrayList<>();
        try {
            JsonNode root = JSON.readTree(body);
            JsonNode data = root.path("data");
            if (data.isArray()) {
                for (JsonNode item : data) {
                    String name = item.path("name").asText();
                    String desc = item.path("description").asText();
                    String id = item.path("id").asText(agentId);
                    result.add(new DatasetInfo(id, name, desc, agentId));
                }
            }
        } catch (Exception e) {
            log.warn("[SuperSonic] Failed to parse dataset list: {}", e.getMessage());
        }
        return result;
    }

    private static String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
    }
}
