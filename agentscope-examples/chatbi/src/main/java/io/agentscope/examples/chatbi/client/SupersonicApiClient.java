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

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.examples.chatbi.dto.DatasetInfo;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

/**
 * HTTP client for SuperSonic NLP data query API.
 *
 * <p>Core operations:
 * <ul>
 *   <li>{@code listDatasets()} – fetches available datasets via
 *       {@code GET /api/chat/agent/getRedSeaDataSetInfo?queryType=brief}</li>
 *   <li>{@code queryDataset(dataSetName, question)} – NLP query via
 *       {@code POST /api/chat/query/parseAndExecute}; auto-manages chatId per dataset</li>
 *   <li>{@code fetchDatasetDetail(agentIds)} – fetches detailed dataset metadata</li>
 * </ul>
 *
 * <p>Report schedule queries have been moved to
 * {@link io.agentscope.examples.chatbi.client.ReportScheduleApiClient}.
 */
@Component
public class SupersonicApiClient {

    private static final Logger log = LoggerFactory.getLogger(SupersonicApiClient.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final WebClient webClient;

    private final boolean mockEnabled;

    /** NLP queryType sent in parseAndExecute requests (e.g. "super_simple", "RULE", "LLMTEXT") */
    private final String nlpQueryType;

    /** datasetName → agentId for multi-agent routing */
    private final Map<String, String> datasetAgentIdMap = new ConcurrentHashMap<>();

    /** datasetName → chatId for per-dataset conversation isolation */
    private final Map<String, String> datasetChatIdMap = new ConcurrentHashMap<>();

    public SupersonicApiClient(
            @Value("${supersonic.api.base-url:http://localhost:9080}") String baseUrl,
            @Value("${supersonic.api.mock:false}") boolean mockEnabled,
            @Value("${supersonic.api.nlp-query-type:super_simple}") String nlpQueryType) {
        this.mockEnabled = mockEnabled;
        this.nlpQueryType = nlpQueryType;
        this.webClient =
                WebClient.builder()
                        .baseUrl(baseUrl)
                        .codecs(cfg -> cfg.defaultCodecs().maxInMemorySize(10 * 1024 * 1024))
                        .build();
        log.info(
                "SupersonicApiClient initialized, baseUrl={}, mock={}, nlpQueryType={}",
                baseUrl,
                mockEnabled,
                nlpQueryType);
    }

    private Mono<List<DatasetInfo>> mockListDatasets() {
        log.debug("Using mock dataset list");
        return Mono.just(
                List.of(
                        new DatasetInfo(
                                "xx视频APP日指数",
                                "xx视频APP日指数",
                                "xx视频APP日指数，基于高质量日活跃样本用户计算的活跃指数，用于反映核心活跃用户的变化趋势。"
                                        + "包含每日活跃指数值、环比变化率、同比变化率。",
                                "84"),
                        new DatasetInfo(
                                "社区化xx号专项运营数据报表",
                                "社区化xx号专项运营数据报表",
                                "xx视频APP社区化相关指标，包含："
                                        + "社区场景月累计活跃用户规模、"
                                        + "社区化日活跃用户规模、"
                                        + "社区内容消费用户次日留存率、"
                                        + "全量创作者规模、"
                                        + "B级及以上创作者规模、"
                                        + "B级及以上创作者日人均涨粉数、"
                                        + "社区内容日总赞数量、"
                                        + "B级及以上创作者TOP1视频点赞量。",
                                "86"),
                        new DatasetInfo(
                                "ds_kpi",
                                "2025关键考核指标日表",
                                "##xx各产品的考核指标情况### 元数据（字段顺序）：产品名称, 指标名称, 日期, 指标值, 目标值, 完成进度, 上月同期值,"
                                    + " 环比上月变化情况\n"
                                    + "### 示例数据：\n"
                                    + "#### 全量, RFE高价值活跃规模, 20260318, 45911864, 3.808E+7, 20.57,"
                                    + " 45377751, 1.18\n"
                                    + "#### 元宇宙, AI数智人使用用户, 20260318, 1963914, 5000000, 39.28,"
                                    + " 1353245, 45.13\n"
                                    + "#### xx视频, AI+xx视频自研产品使用用户数, 20260318, 3721628, 6000000,"
                                    + " 62.03, 2792373, 33.28\n",
                                "85"),
                        new DatasetInfo(
                                "ds_content_play",
                                "驾驶舱热门内容日榜",
                                "内容播放情况，包含xx视频各类型的内容播放数据，"
                                        + "分类：动漫、少儿、电视剧、电影、纪实、体育、综艺、总榜。"
                                        + "包含热门内容名称、热门内容热度指数、热度排名。",
                                "87")));
    }

    /**
     * List all available datasets for a specific agent.
     *
     * <p>Uses the agentId from the chat request to fetch datasets directly.
     *
     * @param agentId the SuperSonic agent ID (from chat request)
     * @return Mono emitting list of DatasetInfo
     */
    public Mono<List<DatasetInfo>> listDatasets(String agentId) {
        if (mockEnabled) {
            return mockListDatasets();
        }
        log.info("[listDatasets] Fetching datasets for agentId={}", agentId);
        return fetchDatasetInfoByAgentId(agentId)
                .flatMap(
                        datasets -> {
                            if (datasets.isEmpty()) {
                                log.warn(
                                        "[listDatasets] fetchDatasetInfoByAgentIds returned empty"
                                                + " list, falling back to mock datasets");
                                return mockListDatasets();
                            }
                            return Mono.just(datasets);
                        })
                .onErrorResume(
                        e -> {
                            log.error(
                                    "[listDatasets] fetchDatasetInfoByAgentIds failed, falling back"
                                            + " to mock datasets",
                                    e);
                            return mockListDatasets();
                        });
    }

    /**
     * Fetch dataset info for given agentIds.
     *
     * @param agentId comma-separated agentId
     * @return Mono emitting list of DatasetInfo
     */
    private Mono<List<DatasetInfo>> fetchDatasetInfoByAgentId(String agentId) {
        return webClient
                .get()
                .uri(
                        uriBuilder ->
                                uriBuilder
                                        .path("/api/chat/agent/getRedSeaDataSetInfo")
                                        .queryParam("agentIds", agentId)
                                        .queryParam("queryType", "brief")
                                        .build())
                .retrieve()
                .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {})
                .timeout(Duration.ofSeconds(60))
                .map(
                        body -> {
                            Object data = body.get("data");
                            if (!(data instanceof List<?>)) {
                                log.warn(
                                        "[fetchDatasetInfoByAgentIds] Unexpected response"
                                                + " structure: {}",
                                        body);
                                return new ArrayList<DatasetInfo>();
                            }
                            List<?> items = (List<?>) data;
                            List<DatasetInfo> result = new ArrayList<>();
                            for (Object item : items) {
                                if (!(item instanceof Map<?, ?> itemMap)) {
                                    continue;
                                }
                                Object resultAgentId = itemMap.get("agentId");
                                Object description = itemMap.get("description");
                                Object agentName = itemMap.get("agentName");
                                if (resultAgentId == null) {
                                    continue;
                                }
                                String id = "ds_" + resultAgentId;
                                String descStr = description != null ? description.toString() : "";
                                result.add(
                                        new DatasetInfo(
                                                id,
                                                agentName != null ? agentName.toString() : id,
                                                descStr,
                                                resultAgentId.toString()));
                                log.debug(
                                        "[fetchDatasetInfoByAgentIds] Loaded dataset: id={},"
                                                + " agentId={} name={}",
                                        id,
                                        resultAgentId,
                                        agentName);
                            }
                            log.info(
                                    "[fetchDatasetInfoByAgentIds] Loaded {} datasets",
                                    result.size());
                            return result;
                        })
                .onErrorResume(
                        e -> {
                            log.error(
                                    "[fetchDatasetInfoByAgentIds] Failed to fetch datasets from NLP"
                                            + " service",
                                    e);
                            return Mono.just(new ArrayList<>());
                        });
    }

    /**
     * Query a specific dataset using a natural-language question.
     *
     * <p>When mock is disabled, calls SuperSonic's NLP service via
     * {@code POST /api/chat/query/parseAndExecute} with the agentId registered for the dataset.
     * Auto-manages a per-dataset chatId for conversation continuity.
     *
     * <p>Response processing rules (same as DataApiClient):
     * <ol>
     *   <li>If {@code queryResults} is non-empty → convert to compact CSV table (header + rows)</li>
     *   <li>If {@code queryResults} is empty and {@code errorMsg} is non-blank → return error hint</li>
     *   <li>If both empty → return {@code textResult} or generic "no data" message</li>
     * </ol>
     *
     * @param datasetName dataset display name (from system prompt catalogue)
     * @param question    natural-language question
     * @return query result as compact CSV table, or an error/hint message
     */
    public Mono<String> queryDataset(String datasetName, String question) {
        if (mockEnabled) {
            return mockQueryDataset(datasetName, question);
        }
        String agentId = datasetAgentIdMap.get(datasetName);
        if (agentId == null) {
            log.warn(
                    "[queryDataset] No agentId registered for datasetName={},"
                            + " please call listDatasets() first",
                    datasetName);
            return Mono.just("未找到数据集 '" + datasetName + "' 的agentId，请先调用listDatasets初始化。");
        }
        return getOrCreateChatId(datasetName)
                .flatMap(chatId -> queryByNlp(agentId, chatId, question))
                .switchIfEmpty(
                        Mono.fromSupplier(
                                () -> {
                                    log.warn(
                                            "[queryDataset] queryByNlp returned empty Mono,"
                                                    + " datasetName={}",
                                            datasetName);
                                    return "Query returned no result.";
                                }))
                .doOnError(
                        e ->
                                log.error(
                                        "[queryDataset] Failed, datasetName={}, question={}",
                                        datasetName,
                                        question,
                                        e))
                .onErrorResume(e -> Mono.just("Query failed: " + e.getMessage()));
    }

    // ==================== Private: NLP Service ====================

    /**
     * Get existing chatId for the dataset, or create a new one via
     * {@code POST /api/chat/manage/save}.
     */
    private Mono<String> getOrCreateChatId(String datasetName) {
        String existingChatId = datasetChatIdMap.get(datasetName);
        if (existingChatId != null) {
            log.debug(
                    "[getOrCreateChatId] Reusing chatId={} for datasetName={}",
                    existingChatId,
                    datasetName);
            return Mono.just(existingChatId);
        }
        String agentId = datasetAgentIdMap.get(datasetName);
        return createChat(agentId)
                .doOnNext(
                        chatId -> {
                            datasetChatIdMap.put(datasetName, chatId);
                            log.info(
                                    "[getOrCreateChatId] Created chatId={} for datasetName={}",
                                    chatId,
                                    datasetName);
                        });
    }

    /**
     * Call {@code POST /api/chat/manage/save} to create a new chat session.
     * Parameters are passed as RequestParam: chatName and agentId.
     *
     * @param agentId the agentId to associate with this chat session
     * @return Mono emitting the new chatId as a String
     */
    private Mono<String> createChat(String agentId) {
        log.debug("[createChat] Creating new chat session, agentId={}", agentId);
        return webClient
                .post()
                .uri(
                        uriBuilder ->
                                uriBuilder
                                        .path("/api/chat/manage/save")
                                        .queryParam("chatName", "新问答对话")
                                        .queryParam("agentId", agentId)
                                        .build())
                .retrieve()
                .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {})
                .timeout(Duration.ofSeconds(10))
                .map(
                        body -> {
                            Object data = body.get("data");
                            if (data == null) {
                                throw new RuntimeException(
                                        "Received null data from /api/chat/manage/save, body="
                                                + body);
                            }
                            return data.toString();
                        })
                .doOnNext(chatId -> log.debug("[createChat] Got chatId={}", chatId))
                .doOnError(
                        e ->
                                log.error(
                                        "[createChat] Failed to create chat session, agentId={}",
                                        agentId,
                                        e));
    }

    /**
     * Call {@code POST /api/chat/query/parseAndExecute} and extract the result.
     *
     * <ol>
     *   <li>If {@code queryResults} is non-empty → convert to compact CSV table to save tokens.</li>
     *   <li>If {@code queryResults} is empty and {@code errorMsg} non-blank → return error hint.</li>
     *   <li>Otherwise → return {@code textResult} or generic "no data" message.</li>
     * </ol>
     *
     * @param agentId  SuperSonic agent ID
     * @param chatId   chat session ID (from createChat)
     * @param question natural-language question
     * @return result string for the calling agent
     */
    private Mono<String> queryByNlp(String agentId, String chatId, String question) {
        Map<String, Object> requestBody =
                Map.of(
                        "agentId",
                        Long.parseLong(agentId),
                        "chatId",
                        Long.parseLong(chatId),
                        "queryText",
                        question,
                        "queryType",
                        this.nlpQueryType);
        log.info(
                "[queryByNlp] agentId={}, chatId={}, question={}, queryType={}",
                agentId,
                chatId,
                question,
                this.nlpQueryType);
        return webClient
                .post()
                .uri("/api/chat/query/parseAndExecute")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(requestBody)
                .retrieve()
                .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {})
                .timeout(Duration.ofSeconds(180))
                .switchIfEmpty(
                        Mono.fromSupplier(
                                () -> {
                                    log.warn(
                                            "[queryByNlp] parseAndExecute returned empty body,"
                                                    + " agentId={}, question={}",
                                            agentId,
                                            question);
                                    return Map.<String, Object>of();
                                }))
                .map(
                        body -> {
                            Object data = body.get("data");
                            if (!(data instanceof Map<?, ?> dataMap)) {
                                log.warn("[queryByNlp] Unexpected response structure: {}", body);
                                return "No result returned from query service.";
                            }

                            Object queryResults = dataMap.get("queryResults");
                            boolean queryResultsEmpty =
                                    (queryResults == null)
                                            || (queryResults instanceof List<?> list
                                                    && list.isEmpty());

                            if (queryResultsEmpty) {
                                Object errorMsg = dataMap.get("errorMsg");
                                if (errorMsg != null && !errorMsg.toString().isBlank()) {
                                    log.warn(
                                            "[queryByNlp] queryResults is empty, errorMsg={}",
                                            errorMsg);
                                    return "查询执行出错，请根据以下错误信息调整问题后重试：" + errorMsg;
                                }
                                Object textResult = dataMap.get("textResult");
                                String textResultStr =
                                        (textResult != null) ? textResult.toString() : "";
                                log.warn(
                                        "[queryByNlp] queryResults is empty, no errorMsg,"
                                                + " textResult={}",
                                        textResultStr);
                                if (!textResultStr.isBlank()) {
                                    return "查询未返回数据，请根据以下提示调整问题后重试：" + textResultStr;
                                }
                                return "查询未返回任何数据，请尝试调整问题后重试。";
                            }

                            // queryResults non-empty — convert to compact CSV table
                            if (queryResults instanceof List<?> rows) {
                                String table = toCompactTable(rows);
                                log.debug("[queryByNlp] compact table length={}", table.length());
                                return table;
                            }
                            // Unexpected type — fall back to JSON
                            try {
                                return JSON.writeValueAsString(queryResults);
                            } catch (JsonProcessingException e) {
                                log.warn(
                                        "[queryByNlp] Failed to serialize queryResults,"
                                                + " returning toString()",
                                        e);
                                return queryResults.toString();
                            }
                        })
                .doOnError(
                        e ->
                                log.error(
                                        "[queryByNlp] Failed, agentId={}, question={}",
                                        agentId,
                                        question,
                                        e));
    }

    /**
     * Convert a {@code List<Map<String,Object>>} result to a compact CSV-like table:
     * one header line (column names) followed by value-only data lines.
     *
     * <p>Example output:
     * <pre>
     * 日期,指标名称,指标值
     * 20260402,B级及以上创作者TOP1视频点赞量,221.0
     * </pre>
     *
     * <p>Column order follows the first row’s key insertion order.
     */
    @SuppressWarnings("unchecked")
    private String toCompactTable(List<?> rows) {
        if (rows == null || rows.isEmpty()) {
            return "";
        }
        Object firstRaw = rows.get(0);
        if (!(firstRaw instanceof Map<?, ?> firstRow)) {
            return rows.toString();
        }
        List<String> headers = new ArrayList<>(((Map<String, Object>) firstRow).keySet());
        StringBuilder sb = new StringBuilder();
        sb.append(String.join(",", headers));
        for (Object rawRow : rows) {
            sb.append('\n');
            if (rawRow instanceof Map<?, ?> row) {
                Map<String, Object> typedRow = (Map<String, Object>) row;
                for (int i = 0; i < headers.size(); i++) {
                    if (i > 0) sb.append(',');
                    Object val = typedRow.get(headers.get(i));
                    String strVal = val == null ? "" : val.toString();
                    if (strVal.contains(",") || strVal.contains("\n") || strVal.contains("\"")) {
                        sb.append('"').append(strVal.replace("\"", "\"\"")).append('"');
                    } else {
                        sb.append(strVal);
                    }
                }
            }
        }
        return sb.toString();
    }

    /**
     * Query data lineage using the three-parameter API contract.
     *
     * <p>The raw API response is a JSON object; this method extracts the
     * {@code final_prompt} field (mirroring the Dify post-processing script).
     * If the response is unparseable / {@code final_prompt} is absent or blank,
     * a friendly error message is returned instead.
     *
     * @param query         user's question / rewritten question
     * @param projectId     project ID passed from frontend
     * @param memory        JSON string of previous conversation history (may be null/empty)
     * @param authorization SuperSonic auth token
     * @return extracted {@code final_prompt} value, or a fallback error message
     */
    public Mono<String> queryLineage(
            String query, String projectId, String memory, String authorization) {
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("query", query);
        body.put("project_id", projectId);
        if (memory != null && !memory.isBlank()) {
            body.put("memory", memory);
        }
        log.info(
                "[queryLineage] query={}, projectId={}, memoryLen={}",
                query,
                projectId,
                memory == null ? 0 : memory.length());
        return webClient
                .post()
                .uri("/api/data/lineage")
                .header("authorization", authorization)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve()
                .bodyToMono(String.class)
                .timeout(Duration.ofSeconds(60))
                .map(this::extractFinalPrompt)
                .onErrorResume(
                        e -> {
                            log.error(
                                    "[SuperSonic] queryLineage error query={}: {}",
                                    query,
                                    e.getMessage());
                            return Mono.just(LINEAGE_ERROR_MSG);
                        });
    }

    /** Fallback message returned when lineage API is unreachable or returns invalid data. */
    private static final String LINEAGE_ERROR_MSG =
            "你是红海chatBI问答小助手，你当前遇到了网络问题，请直接回复用户："
                    + "抱歉，我遇到了一个网络问题，暂时无法处理您的请求。请稍后再试，或联系分析云团队获取帮助。感谢您的理解！";

    /**
     * Extract the {@code final_prompt} field from the lineage API JSON response.
     *
     * <p>Equivalent to the Dify post-processing Python script:
     * <pre>
     *   body_data = json.loads(arg2)
     *   final_prompt = body_data.get("final_prompt", "").strip()
     *   return final_prompt if final_prompt else error_msg
     * </pre>
     *
     * @param responseBody raw JSON string from the lineage API
     * @return {@code final_prompt} value, or {@link #LINEAGE_ERROR_MSG} if absent / blank
     */
    private String extractFinalPrompt(String responseBody) {
        if (responseBody == null || responseBody.isBlank()) {
            log.warn("[extractFinalPrompt] Empty response body");
            return LINEAGE_ERROR_MSG;
        }
        try {
            Map<String, Object> parsed =
                    JSON.readValue(
                            responseBody, new com.fasterxml.jackson.core.type.TypeReference<>() {});
            Object fp = parsed.get("final_prompt");
            if (fp instanceof String finalPrompt && !finalPrompt.isBlank()) {
                log.debug(
                        "[extractFinalPrompt] Extracted final_prompt length={}",
                        finalPrompt.length());
                return finalPrompt.strip();
            }
            log.warn(
                    "[extractFinalPrompt] final_prompt missing or blank, body snippet={}",
                    responseBody.length() > 200
                            ? responseBody.substring(0, 200) + "..."
                            : responseBody);
            return LINEAGE_ERROR_MSG;
        } catch (Exception e) {
            log.error("[extractFinalPrompt] Failed to parse lineage response: {}", e.getMessage());
            return LINEAGE_ERROR_MSG;
        }
    }

    /**
     * Look up the agent ID registered for a given dataset name.
     */
    public String getAgentIdForDataset(String datasetName) {
        return datasetAgentIdMap.get(datasetName);
    }

    /**
     * Register a dataset name → agentId mapping (called after listDatasets in DataQueryAgent).
     *
     * @param datasetName the display name of the dataset
     * @param agentId     the SuperSonic agentId for this dataset
     */
    public void registerDataset(String datasetName, String agentId) {
        if (datasetName != null && agentId != null) {
            datasetAgentIdMap.put(datasetName, agentId);
        }
    }

    /**
     * Fetch detailed metadata (dimensions, metrics, dimension-values) for given agentIds.
     *
     * @param agentIds comma-separated SuperSonic agentIds
     * @return formatted detail string
     */
    public Mono<String> fetchDatasetDetail(String agentIds) {
        log.info("[fetchDatasetDetail] agentIds={}", agentIds);
        return webClient
                .get()
                .uri(
                        uriBuilder ->
                                uriBuilder
                                        .path("/api/chat/agent/getRedSeaDataSetInfo")
                                        .queryParam("agentIds", agentIds)
                                        .queryParam("queryType", "detail")
                                        .build())
                .retrieve()
                .bodyToMono(String.class)
                .timeout(java.time.Duration.ofSeconds(30))
                .onErrorResume(
                        e -> {
                            log.error(
                                    "[fetchDatasetDetail] Error agentIds={}: {}",
                                    agentIds,
                                    e.getMessage());
                            return Mono.just("Error fetching dataset detail: " + e.getMessage());
                        });
    }

    // ─────────────────── Private helpers ───────────────────

    private Mono<String> mockQueryDataset(String dataSetName, String question) {
        log.debug("Using mock query, dataSetName={}, question={}", dataSetName, question);
        String lowerQuestion = question.toLowerCase();

        return switch (dataSetName) {
            case "84" -> Mono.just(buildDauIndexMockResponse(lowerQuestion));
            case "86" -> Mono.just(buildCommunityMockResponse(lowerQuestion));
            case "85" -> Mono.just(buildKpiMockResponse(lowerQuestion));
            case "87" -> Mono.just(buildContentPlayMockResponse(lowerQuestion));
            default ->
                    Mono.just(
                            "Dataset '"
                                    + dataSetName
                                    + "' not found. Available datasets: xx视频APP日指数, 社区化xx号专项运营数据报表,"
                                    + " 2025关键考核指标日表, 驾驶舱热门内容日榜");
        };
    }

    private String buildDauIndexMockResponse(String question) {
        if (question.contains("趋势") || question.contains("月") || question.contains("变化")) {
            return """
            {
              "dataset": "ds_dau_index",
              "query": "近30日DAU活跃指数趋势",
              "result": [
                {"date": "2025-02-16", "dau_index": 82.3, "wow_change": "+1.2%"},
                {"date": "2025-02-23", "dau_index": 84.7, "wow_change": "+2.9%"},
                {"date": "2025-03-02", "dau_index": 83.1, "wow_change": "-1.9%"},
                {"date": "2025-03-09", "dau_index": 86.5, "wow_change": "+4.1%"},
                {"date": "2025-03-16", "dau_index": 88.2, "wow_change": "+2.0%"}
              ]
            }\
            """;
        }
        if (question.contains("同比") || question.contains("yoy")) {
            return """
            {
              "dataset": "ds_dau_index",
              "query": "DAU指数同比情况",
              "result": {
                "current_month_avg_index": 87.4,
                "yoy_change": "+12.6%",
                "same_period_last_year_avg_index": 77.6,
                "peak_day_index": 96.3,
                "peak_day": "2025-03-14"
              }
            }\
            """;
        }
        return """
        {
          "dataset": "ds_dau_index",
          "query": "最新DAU活跃指数概况",
          "result": {
            "latest_date": "2025-03-17",
            "latest_dau_index": 88.2,
            "wow_change": "+2.0%",
            "yoy_change": "+12.6%",
            "month_avg_index": 85.1,
            "peak_day_index": 96.3
          }
        }\
        """;
    }

    private String buildCommunityMockResponse(String question) {
        if (question.contains("创作者") || question.contains("creator")) {
            return """
            {
              "dataset": "ds_community",
              "query": "创作者相关指标",
              "result": {
                "total_creators": 1250000,
                "b_level_and_above_creators": 38600,
                "b_level_daily_avg_new_fans": 124,
                "b_level_top1_video_likes": 892400,
                "mom_change_total_creators": "+5.3%",
                "mom_change_b_level_creators": "+8.1%"
              }
            }\
            """;
        }
        if (question.contains("留存") || question.contains("retention")) {
            return """
            {
              "dataset": "ds_community",
              "query": "社区内容消费用户次日留存率",
              "result": {
                "next_day_retention_rate": "43.7%",
                "wow_change": "+0.8pp",
                "mom_change": "+2.1pp",
                "benchmark_industry_avg": "38.5%"
              }
            }\
            """;
        }
        if (question.contains("点赞") || question.contains("赞")) {
            return """
            {
              "dataset": "ds_community",
              "query": "社区内容点赞数据",
              "result": {
                "daily_total_likes": 6830000,
                "wow_change": "+3.5%",
                "b_level_top1_video_likes": 892400,
                "top1_video_title": "2025赛季CBA全明星精彩集锦",
                "top1_video_creator": "体育精选号"
              }
            }\
            """;
        }
        return """
        {
          "dataset": "ds_community",
          "query": "社区化核心指标概览",
          "result": {
            "monthly_active_community_users": 42800000,
            "daily_active_community_users": 8760000,
            "next_day_retention_rate": "43.7%",
            "total_creators": 1250000,
            "b_level_and_above_creators": 38600,
            "b_level_daily_avg_new_fans": 124,
            "daily_total_likes": 6830000,
            "b_level_top1_video_likes": 892400
          }
        }\
        """;
    }

    private String buildKpiMockResponse(String question) {
        // CSV格式输出：产品名称,指标名称,日期,指标值,目标值,完成进度,上月同期值,环比上月变化情况
        // 列顺序必须与元数据定义一致（8个字段）

        // xx视频相关指标
        if (question.contains("视频")
                || question.contains("video")
                || question.contains("ai+")
                || question.contains("自研")) {
            return """
            产品名称,指标名称,日期,指标值,目标值,完成进度,上月同期值,环比上月变化情况
            xx视频,AI+xx视频自研产品使用用户数,20260318,3721628,6000000,62.03,2792373,33.28
            xx视频,视频月活跃用户规模(MAU),20260318,91200000,85000000,107.29,87600000,4.11
            xx视频,视频日活跃用户规模(DAU),20260318,17650000,18000000,98.06,16900000,4.44
            xx视频,付费会员数,20260318,11340000,12000000,94.50,11100000,2.16
            xx视频,人均日使用时长(分钟),20260318,46.8,42,111.43,44.2,5.88
            """;
        }
        // xx音乐相关指标
        if (question.contains("音乐") || question.contains("music")) {
            return """
            产品名称,指标名称,日期,指标值,目标值,完成进度,上月同期值,环比上月变化情况
            xx音乐,音乐月活跃用户规模(MAU),20260318,63800000,60000000,106.33,61200000,4.25
            xx音乐,付费会员数,20260318,7920000,8000000,99.00,7750000,2.19
            xx音乐,人均日播放曲目数,20260318,13.5,12,112.50,12.8,5.47
            xx音乐,AI音乐创作工具使用用户数,20260318,2180000,3000000,72.67,1650000,32.12
            """;
        }
        // 元宇宙 / AI数智人相关指标
        if (question.contains("元宇宙") || question.contains("数智人") || question.contains("ai")) {
            return """
            产品名称,指标名称,日期,指标值,目标值,完成进度,上月同期值,环比上月变化情况
            元宇宙,AI数智人使用用户,20260318,1963914,5000000,39.28,1353245,45.13
            元宇宙,元宇宙月活跃用户规模,20260318,820000,2000000,41.00,610000,34.43
            元宇宙,虚拟形象创建数,20260318,345000,600000,57.50,268000,28.73
            元宇宙,元宇宙内容消费用户规模,20260318,1240000,2500000,49.60,980000,26.53
            """;
        }
        // 全量/集团考核汇总（默认兜底）
        return """
        产品名称,指标名称,日期,指标值,目标值,完成进度,上月同期值,环比上月变化情况
        全量,RFE高价值活跃规模,20260318,45911864,38080000,120.57,45377751,1.18
        全量,集团考核月活跃用户(MAU),20260318,198500000,195000000,101.79,192300000,3.22
        xx视频,AI+xx视频自研产品使用用户数,20260318,3721628,6000000,62.03,2792373,33.28
        xx视频,视频月活跃用户规模(MAU),20260318,91200000,85000000,107.29,87600000,4.11
        xx视频,付费会员数,20260318,11340000,12000000,94.50,11100000,2.16
        xx音乐,音乐月活跃用户规模(MAU),20260318,63800000,60000000,106.33,61200000,4.25
        xx音乐,付费会员数,20260318,7920000,8000000,99.00,7750000,2.19
        xx音乐,AI音乐创作工具使用用户数,20260318,2180000,3000000,72.67,1650000,32.12
        xx阅读,阅读月活跃用户规模(MAU),20260318,28400000,30000000,94.67,27100000,4.80
        xx阅读,付费会员数,20260318,3860000,4200000,91.90,3720000,3.76
        xx阅读,日均阅读时长(分钟),20260318,38.2,35,109.14,36.5,4.66
        云游戏,云游戏月活跃用户规模(MAU),20260318,14100000,18000000,78.33,13800000,2.17
        云游戏,云游戏付费用户数,20260318,1820000,2500000,72.80,1710000,6.43
        元宇宙,AI数智人使用用户,20260318,1963914,5000000,39.28,1353245,45.13
        元宇宙,元宇宙月活跃用户规模,20260318,820000,2000000,41.00,610000,34.43
        """;
    }

    private String buildContentPlayMockResponse(String question) {
        if (question.contains("体育") || question.contains("sport")) {
            return """
            {
              "dataset": "ds_content_play",
              "query": "体育内容播放数据",
              "result": {
                "category": "体育",
                "daily_play_count": 38200000,
                "wow_change": "+18.6%",
                "share_of_total": "21.3%",
                "hot_content": [
                  {"title": "2025赛季CBA总决赛第三场", "heat_index": 98.7, "rank": 1},
                  {"title": "中超联赛第5轮精彩集锦", "heat_index": 91.2, "rank": 2},
                  {"title": "WWE RAW 2025最新一期", "heat_index": 83.5, "rank": 3}
                ]
              }
            }\
            """;
        }
        if (question.contains("电视剧") || question.contains("电影") || question.contains("剧集")) {
            return """
            {
              "dataset": "ds_content_play",
              "query": "影视内容播放数据",
              "result": [
                {
                  "category": "电视剧",
                  "daily_play_count": 52600000,
                  "wow_change": "+3.1%",
                  "share_of_total": "29.4%",
                  "top1": {"title": "繁花续集", "heat_index": 97.3, "rank": 1}
                },
                {
                  "category": "电影",
                  "daily_play_count": 21300000,
                  "wow_change": "-2.4%",
                  "share_of_total": "11.9%",
                  "top1": {"title": "流浪地球3预告独家", "heat_index": 89.6, "rank": 1}
                }
              ]
            }\
            """;
        }
        if (question.contains("排行")
                || question.contains("热门")
                || question.contains("top")
                || question.contains("榜")) {
            return """
            {
              "dataset": "ds_content_play",
              "query": "内容热度总榜Top10",
              "result": [
                {"rank": 1,  "title": "2025赛季CBA总决赛第三场",  "category": "体育",  "heat_index": 98.7},
                {"rank": 2,  "title": "繁花续集",               "category": "电视剧", "heat_index": 97.3},
                {"rank": 3,  "title": "名侦探柯南剧场版2025",   "category": "动漫",  "heat_index": 94.8},
                {"rank": 4,  "title": "中超联赛第5轮精彩集锦",  "category": "体育",  "heat_index": 91.2},
                {"rank": 5,  "title": "快乐大本营2025春季特辑",  "category": "综艺",  "heat_index": 90.5},
                {"rank": 6,  "title": "航拍中国第五季",         "category": "纪实",  "heat_index": 88.1},
                {"rank": 7,  "title": "海底小纵队新番",         "category": "少儿",  "heat_index": 86.4},
                {"rank": 8,  "title": "流浪地球3预告独家",      "category": "电影",  "heat_index": 89.6},
                {"rank": 9,  "title": "WWE RAW 2025最新一期",  "category": "体育",  "heat_index": 83.5},
                {"rank": 10, "title": "明星大侦探第九季",       "category": "综艺",  "heat_index": 82.9}
              ]
            }\
            """;
        }
        return """
        {
          "dataset": "ds_content_play",
          "query": "各类型内容播放量汇总",
          "result": [
            {"category": "电视剧", "daily_play_count": 52600000, "share": "29.4%", "wow_change": "+3.1%"},
            {"category": "体育",   "daily_play_count": 38200000, "share": "21.3%", "wow_change": "+18.6%"},
            {"category": "综艺",   "daily_play_count": 28700000, "share": "16.0%", "wow_change": "+1.8%"},
            {"category": "动漫",   "daily_play_count": 22400000, "share": "12.5%", "wow_change": "+6.2%"},
            {"category": "电影",   "daily_play_count": 21300000, "share": "11.9%", "wow_change": "-2.4%"},
            {"category": "纪实",   "daily_play_count": 9800000,  "share": "5.5%",  "wow_change": "+4.7%"},
            {"category": "少儿",   "daily_play_count": 5200000,  "share": "2.9%",  "wow_change": "+0.5%"},
            {"category": "其他",   "daily_play_count": 1500000,  "share": "0.5%",  "wow_change": "-1.1%"}
          ]
        }\
        """;
    }
}
