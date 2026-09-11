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
package io.agentscope.examples.dataanalysis.client;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.examples.dataanalysis.dto.DatasetInfo;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

/**
 * Client for calling the external data API.
 *
 * <p>Two APIs are wrapped:
 *
 * <ul>
 *   <li>{@code GET /datasets} - returns the list of available datasets (id + description)
 *   <li>{@code GET /query?datasetId=xxx&question=yyy} - queries a specific dataset
 * </ul>
 *
 * <p>The base URL is configured via {@code data.api.base-url} in application.yml. Set {@code
 * data.api.mock=true} to use built-in mock responses (useful during development).
 */
@Component
public class DataApiClient {

    private static final Logger log = LoggerFactory.getLogger(DataApiClient.class);

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final WebClient webClient;
    private final WebClient nlpWebClient;
    private final boolean mockEnabled;

    /** 配置文件中配置的 agentId 列表（逗号分隔），用于非 mock 模式下获取数据集列表 */
    private final List<String> nlpAgentIds;

    /** datasetName → agentId，由 listDatasets 调用后注册 */
    private final Map<String, String> datasetAgentIdMap = new ConcurrentHashMap<>();

    /** NLP 查询接口的 queryType，可在配置文件中修改（如 super_simple / plain 等） */
    private final String nlpQueryType;

    public DataApiClient(
            @Value("${data.api.base-url:http://localhost:9090}") String baseUrl,
            @Value("${data.api.mock:true}") boolean mockEnabled,
            @Value("${data.api.nlp-base-url:http://localhost:9080}") String nlpBaseUrl,
            @Value("${data.api.nlp-authorization:}") String nlpAuthorization,
            @Value("${data.api.nlp-app-key:}") String nlpAppKey,
            @Value("${data.api.nlp-agent-ids:}") String nlpAgentIdsStr,
            @Value("${data.api.nlp-query-type:super_simple}") String nlpQueryType) {
        this.mockEnabled = mockEnabled;
        this.nlpAgentIds =
                nlpAgentIdsStr.isBlank()
                        ? new ArrayList<>()
                        : Arrays.stream(nlpAgentIdsStr.split(","))
                                .map(String::trim)
                                .filter(s -> !s.isBlank())
                                .collect(Collectors.toList());
        this.nlpQueryType = nlpQueryType;
        this.webClient =
                WebClient.builder()
                        .baseUrl(baseUrl)
                        .codecs(
                                configurer ->
                                        configurer
                                                .defaultCodecs()
                                                .maxInMemorySize(10 * 1024 * 1024))
                        .build();
        this.nlpWebClient =
                WebClient.builder()
                        .baseUrl(nlpBaseUrl)
                        .defaultHeader("authorization", nlpAuthorization)
                        .defaultHeader("App-Key", nlpAppKey)
                        .codecs(
                                configurer ->
                                        configurer
                                                .defaultCodecs()
                                                .maxInMemorySize(10 * 1024 * 1024))
                        .build();
        log.info(
                "DataApiClient initialized, baseUrl={}, nlpBaseUrl={}, mock={}, agentIds={}",
                baseUrl,
                nlpBaseUrl,
                mockEnabled,
                nlpAgentIds);
    }

    /**
     * Retrieve the list of available datasets.
     *
     * <p>When mock is disabled, calls SuperSonic's {@code getRedSeaDataSetInfo} API with the
     * configured agentId list to get real dataset metadata.
     *
     * @return Mono wrapping the list of DatasetInfo
     */
    public Mono<List<DatasetInfo>> listDatasets() {
        if (mockEnabled) {
            return mockListDatasets();
        }
        return fetchDatasetsFromNlp()
                .flatMap(
                        datasets -> {
                            if (datasets.isEmpty()) {
                                log.warn(
                                        "[listDatasets] fetchDatasetsFromNlp returned empty list,"
                                                + " falling back to mock datasets");
                                return mockListDatasets();
                            }
                            return Mono.just(datasets);
                        })
                .onErrorResume(
                        e -> {
                            log.error(
                                    "[listDatasets] fetchDatasetsFromNlp failed, falling back to"
                                            + " mock datasets",
                                    e);
                            return mockListDatasets();
                        });
    }

    /**
     * Look up the SuperSonic agentId for the given dataset ID.
     *
     * @param datasetId e.g. "ds_123"
     * @return agentId string, or {@code null} if not registered
     */
    public String getAgentId(String datasetId) {
        return datasetAgentIdMap.get(datasetId);
    }

    /**
     * Call {@code GET /api/chat/agent/getRedSeaDataSetInfo?queryType=brief} to fetch the brief
     * dataset catalogue (name + description only, without dimension/metric detail).
     *
     * <p>The response is a JSON-wrapped list of AgentDataSetInfoDTO objects, each containing:
     *
     * <ul>
     *   <li>{@code agentId} - the SuperSonic agent id
     *   <li>{@code agentName} - the agent name, used as dataset display name
     *   <li>{@code dataSetInfo} - semantic description of the dataset (dimensions, metrics, etc.)
     * </ul>
     *
     * Each item is converted to a {@link DatasetInfo} with: id = "ds_" + agentId, description =
     * agentName + "\n" + dataSetInfo, agentId = agentId.
     */
    private Mono<List<DatasetInfo>> fetchDatasetsFromNlp() {
        if (nlpAgentIds.isEmpty()) {
            log.warn("[fetchDatasetsFromNlp] nlp-agent-ids is empty, returning empty dataset list");
            return Mono.just(new ArrayList<>());
        }
        String agentIdsParam = String.join(",", nlpAgentIds);
        log.info("[fetchDatasetsFromNlp] Fetching brief catalogue for agentIds={}", agentIdsParam);
        return nlpWebClient
                .get()
                .uri(
                        uriBuilder ->
                                uriBuilder
                                        .path("/api/chat/agent/getRedSeaDataSetInfo")
                                        .queryParam("agentIds", agentIdsParam)
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
                                        "[fetchDatasetsFromNlp] Unexpected response structure: {}",
                                        body);
                                return new ArrayList<DatasetInfo>();
                            }
                            List<?> items = (List<?>) data;
                            List<DatasetInfo> result = new ArrayList<>();
                            for (Object item : items) {
                                if (!(item instanceof Map<?, ?> itemMap)) {
                                    continue;
                                }
                                Object agentId = itemMap.get("agentId");
                                Object description = itemMap.get("description");
                                Object agentName = itemMap.get("agentName");
                                if (agentId == null) {
                                    continue;
                                }
                                String id = "ds_" + agentId;
                                String descStr = description != null ? description.toString() : "";
                                result.add(
                                        new DatasetInfo(
                                                id,
                                                agentName != null ? agentName.toString() : id,
                                                descStr,
                                                agentId.toString()));
                                log.debug(
                                        "[fetchDatasetsFromNlp] Loaded dataset: id={}, agentId={}."
                                                + " name={}",
                                        id,
                                        agentId,
                                        agentName);
                            }
                            log.info("[fetchDatasetsFromNlp] Loaded {} datasets", result.size());
                            return result;
                        })
                .onErrorResume(
                        e -> {
                            log.error(
                                    "[fetchDatasetsFromNlp] Failed to fetch datasets from NLP"
                                            + " service",
                                    e);
                            return Mono.just(new ArrayList<>());
                        });
    }

    /**
     * Fetch detailed metadata for one or more datasets including dimensions, metrics, and terms.
     *
     * <p>Calls {@code GET /api/chat/agent/getRedSeaDataSetInfo?agentIds=X,Y&queryType=detail}.
     * The {@code queryType=detail} parameter instructs SuperSonic to include the full
     * {@code dataSetInfo} payload (dimensions, metrics, dimension-values, terms, etc.).
     *
     * @param agentIds one or more SuperSonic agent IDs to query (comma-separated)
     * @return a formatted string containing the detail of each dataset
     */
    public Mono<String> fetchDatasetDetail(String agentIds) {
        log.info("[fetchDatasetDetail] Fetching detail for agentIds={}", agentIds);
        return nlpWebClient
                .get()
                .uri(
                        uriBuilder ->
                                uriBuilder
                                        .path("/api/chat/agent/getRedSeaDataSetInfo")
                                        .queryParam("agentIds", agentIds)
                                        .queryParam("queryType", "detail")
                                        .build())
                .retrieve()
                .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {})
                .timeout(Duration.ofSeconds(30))
                .map(
                        body -> {
                            Object data = body.get("data");
                            if (!(data instanceof List<?> items) || items.isEmpty()) {
                                return "No detail found for agentIds=" + agentIds;
                            }
                            StringBuilder sb = new StringBuilder();
                            for (Object item : items) {
                                if (!(item instanceof Map<?, ?> itemMap)) {
                                    continue;
                                }
                                Object agentName = itemMap.get("agentName");
                                Object dataSetInfo = itemMap.get("dataSetInfo");
                                if (sb.length() > 0) {
                                    sb.append("\n\n---\n");
                                }
                                sb.append("Dataset: ").append(agentName);
                                if (dataSetInfo != null) {
                                    sb.append("\n").append(dataSetInfo);
                                }
                            }
                            String result = sb.toString();
                            log.debug("[fetchDatasetDetail] Result length={}", result.length());
                            return result.isEmpty()
                                    ? "No detail found for agentIds=" + agentIds
                                    : result;
                        })
                .onErrorResume(
                        e -> {
                            log.error("[fetchDatasetDetail] Failed for agentIds={}", agentIds, e);
                            return Mono.just("Failed to fetch dataset detail: " + e.getMessage());
                        });
    }

    /**
     * Query a specific dataset.
     *
     * <p>When mock is disabled, calls the NLP intelligent query service ({@code POST
     * /api/chat/query/parseAndExecute}) with the agentId registered for the dataset.
     *
     * @param dataSetName the ID of the dataset to query
     * @param question the question or query string
     * @return Mono wrapping the query result as a String
     */
    public Mono<String> queryDataset(String dataSetName, String question) {
        if (mockEnabled) {
            return mockQueryDataset(dataSetName, question);
        }
        String agentId = datasetAgentIdMap.get(dataSetName);
        if (agentId == null) {
            log.warn(
                    "[queryDataset] No agentId registered for datasetName={}, falling back to"
                            + " legacy API",
                    dataSetName);
            return queryDatasetLegacy(dataSetName, question);
        }
        return queryByNlp(agentId, question)
                .switchIfEmpty(
                        Mono.fromSupplier(
                                () -> {
                                    log.warn(
                                            "[queryDataset] queryByNlp returned empty Mono,"
                                                    + " dataSetName={}",
                                            dataSetName);
                                    return "Query returned no result.";
                                }))
                .doOnError(
                        e ->
                                log.error(
                                        "[queryDataset] Failed, datasetId={}, question={}",
                                        dataSetName,
                                        question,
                                        e))
                .onErrorResume(e -> Mono.just("Query failed: " + e.getMessage()));
    }

    /**
     * Register agentId mapping from a list of datasets. Called after listDatasets() to record
     * datasetId → agentId.
     *
     * @param datasets the dataset list returned by listDatasets
     */
    public void registerDatasets(List<DatasetInfo> datasets) {
        if (datasets == null) {
            return;
        }
        for (DatasetInfo ds : datasets) {
            if (ds.getAgentId() != null && !ds.getAgentId().isBlank()) {
                String key =
                        (ds.getName() != null && !ds.getName().isBlank())
                                ? ds.getName()
                                : ds.getId();
                datasetAgentIdMap.put(key, ds.getAgentId());
                log.debug(
                        "[registerDatasets] Registered agentId={} for datasetName={}",
                        ds.getAgentId(),
                        key);
            }
        }
    }

    // ==================== Private: NLP Service ====================

    /**
     * Call {@code POST /api/chat/query/parseAndExecute} and extract the result.
     *
     * <p>In {@code super_simple} queryType, the external NLP service does not require a chatId,
     * so the chatId field is set to 0 (ignored by the service). This eliminates the need for
     * the pre-creation of chat sessions via {@code /api/chat/manage/save}.
     *
     * <ol>
     *   <li>If {@code queryResults} is empty, the query produced no data rows:
     *       <ul>
     *         <li>If {@code errorMsg} is non-blank → return the error message so the agent can
     *             adjust the question accordingly.
     *         <li>If {@code errorMsg} is blank → return the {@code textResult} (which may contain
     *             an explanation) so the agent can adjust the question accordingly.
     *       </ul>
     *   <li>If {@code queryResults} is non-empty, the query succeeded → convert the row list to a
     *       compact CSV-like table (one header line + value-only data lines) to reduce LLM token
     *       consumption compared with repeating JSON keys on every row.
     * </ol>
     *
     * @param agentId the agent responsible for this dataset
     * @param question the natural-language question
     * @return a string the calling agent can use to either present results or reformulate the
     *     question
     */
    private Mono<String> queryByNlp(String agentId, String question) {
        Map<String, Object> requestBody =
                Map.of(
                        "agentId",
                        Long.parseLong(agentId),
                        "chatId",
                        0L,
                        "queryText",
                        question,
                        "queryType",
                        this.nlpQueryType);
        log.info(
                "[queryByNlp] agentId={}, question={}, queryType={}",
                agentId,
                question,
                this.nlpQueryType);
        return nlpWebClient
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

                            // Check whether the query returned any data rows.
                            // queryResults is List<Map<String,Object>> per QueryResult definition.
                            Object queryResults = dataMap.get("queryResults");
                            boolean queryResultsEmpty =
                                    (queryResults == null)
                                            || (queryResults instanceof java.util.List<?> list
                                                    && list.isEmpty());

                            if (queryResultsEmpty) {
                                // No data rows returned – inspect errorMsg first
                                Object errorMsg = dataMap.get("errorMsg");
                                if (errorMsg != null && !errorMsg.toString().isBlank()) {
                                    log.warn(
                                            "[queryByNlp] queryResults is empty and errorMsg is"
                                                    + " present: {}",
                                            errorMsg);
                                    return "查询执行出错，请根据以下错误信息调整问题后重试：" + errorMsg;
                                }

                                // errorMsg is absent – use textResult as the hint
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

                            // queryResults is non-empty – convert to compact table format
                            // (one header row + value-only data rows) to minimise token usage.
                            // Each repeated key in JSON would waste tokens; CSV-like format
                            // carries column names only once.
                            if (queryResults instanceof List<?> rows) {
                                String table = toCompactTable(rows);
                                log.debug("[queryByNlp] compact table length={}", table.length());
                                return table;
                            }
                            // Unexpected type – fall back to JSON
                            try {
                                return OBJECT_MAPPER.writeValueAsString(queryResults);
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
     * Convert a {@code List<Map<String,Object>>} (database result rows) to a compact column-header
     * + value-only table format, minimising LLM token consumption.
     *
     * <p>Output example:
     *
     * <pre>
     * 日期,指标名称,指标值
     * 20260402,B级及以上创作者TOP1视频点赞量,221.0
     * 20260402,B级及以上创作者二台直播日均互动量次,624146.5
     * </pre>
     *
     * <p>Column order is determined by the key insertion order of the first row. If the list is
     * empty or contains non-Map entries, an empty string is returned.
     */
    @SuppressWarnings("unchecked")
    private String toCompactTable(List<?> rows) {
        if (rows == null || rows.isEmpty()) {
            return "";
        }
        // Collect column names from the first row
        Object firstRaw = rows.get(0);
        if (!(firstRaw instanceof Map<?, ?> firstRow)) {
            // Rows are not maps – cannot build header; fall back to toString
            return rows.toString();
        }
        List<String> headers = new ArrayList<>(((Map<String, Object>) firstRow).keySet());
        StringBuilder sb = new StringBuilder();
        // Header line
        sb.append(String.join(",", headers));
        // Data lines
        for (Object rawRow : rows) {
            sb.append('\n');
            if (rawRow instanceof Map<?, ?> row) {
                Map<String, Object> typedRow = (Map<String, Object>) row;
                for (int i = 0; i < headers.size(); i++) {
                    if (i > 0) sb.append(',');
                    Object val = typedRow.get(headers.get(i));
                    // Wrap values containing commas or newlines in double-quotes
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

    /** Legacy query via the original REST API (fallback when agentId is not registered). */
    private Mono<String> queryDatasetLegacy(String datasetId, String question) {
        return webClient
                .get()
                .uri(
                        uriBuilder ->
                                uriBuilder
                                        .path("/query")
                                        .queryParam("datasetId", datasetId)
                                        .queryParam("question", question)
                                        .build())
                .retrieve()
                .bodyToMono(String.class)
                .timeout(Duration.ofSeconds(60))
                .doOnError(
                        e ->
                                log.error(
                                        "[queryDatasetLegacy] Failed, dataset={}, question={}",
                                        datasetId,
                                        question,
                                        e))
                .onErrorResume(e -> Mono.just("Query failed: " + e.getMessage()));
    }

    // ==================== Mock Implementations ====================

    private Mono<List<DatasetInfo>> mockListDatasets() {
        log.debug("Using mock dataset list");
        return Mono.just(
                List.of(
                        new DatasetInfo(
                                "咪咕视频APP日指数",
                                "咪咕视频APP日指数",
                                "咪咕视频APP日指数，基于高质量日活跃样本用户计算的活跃指数，用于反映核心活跃用户的变化趋势。"
                                        + "包含每日活跃指数值、环比变化率、同比变化率。",
                                "84"),
                        new DatasetInfo(
                                "社区化咪咕号专项运营数据报表",
                                "社区化咪咕号专项运营数据报表",
                                "咪咕视频APP社区化相关指标，包含："
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
                                "##咪咕各产品的考核指标情况### 元数据（字段顺序）：产品名称, 指标名称, 日期, 指标值, 目标值, 完成进度, 上月同期值,"
                                    + " 环比上月变化情况\n"
                                    + "### 示例数据：\n"
                                    + "#### 全量, RFE高价值活跃规模, 20260318, 45911864, 3.808E+7, 20.57,"
                                    + " 45377751, 1.18\n"
                                    + "#### 元宇宙, AI数智人使用用户, 20260318, 1963914, 5000000, 39.28,"
                                    + " 1353245, 45.13\n"
                                    + "#### 咪咕视频, AI+咪咕视频自研产品使用用户数, 20260318, 3721628, 6000000,"
                                    + " 62.03, 2792373, 33.28\n",
                                "85"),
                        new DatasetInfo(
                                "ds_content_play",
                                "驾驶舱热门内容日榜",
                                "内容播放情况，包含咪咕视频各类型的内容播放数据，"
                                        + "分类：动漫、少儿、电视剧、电影、纪实、体育、综艺、总榜。"
                                        + "包含热门内容名称、热门内容热度指数、热度排名。",
                                "87")));
    }

    private Mono<String> mockQueryDataset(String dataSetName, String question) {
        log.debug("Using mock query, dataSetName={}, question={}", dataSetName, question);
        String lowerQuestion = question.toLowerCase();

        return switch (dataSetName) {
            case "咪咕视频APP日指数" -> Mono.just(buildDauIndexMockResponse(lowerQuestion));
            case "社区化咪咕号专项运营数据报表" -> Mono.just(buildCommunityMockResponse(lowerQuestion));
            case "2025关键考核指标日表" -> Mono.just(buildKpiMockResponse(lowerQuestion));
            case "驾驶舱热门内容日榜" -> Mono.just(buildContentPlayMockResponse(lowerQuestion));
            default ->
                    Mono.just(
                            "Dataset '"
                                    + dataSetName
                                    + "' not found. Available datasets: 咪咕视频APP日指数, 社区化咪咕号专项运营数据报表,"
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

        // 咪咕视频相关指标
        if (question.contains("视频")
                || question.contains("video")
                || question.contains("ai+")
                || question.contains("自研")) {
            return """
            产品名称,指标名称,日期,指标值,目标值,完成进度,上月同期值,环比上月变化情况
            咪咕视频,AI+咪咕视频自研产品使用用户数,20260318,3721628,6000000,62.03,2792373,33.28
            咪咕视频,视频月活跃用户规模(MAU),20260318,91200000,85000000,107.29,87600000,4.11
            咪咕视频,视频日活跃用户规模(DAU),20260318,17650000,18000000,98.06,16900000,4.44
            咪咕视频,付费会员数,20260318,11340000,12000000,94.50,11100000,2.16
            咪咕视频,人均日使用时长(分钟),20260318,46.8,42,111.43,44.2,5.88
            """;
        }
        // 咪咕音乐相关指标
        if (question.contains("音乐") || question.contains("music")) {
            return """
            产品名称,指标名称,日期,指标值,目标值,完成进度,上月同期值,环比上月变化情况
            咪咕音乐,音乐月活跃用户规模(MAU),20260318,63800000,60000000,106.33,61200000,4.25
            咪咕音乐,付费会员数,20260318,7920000,8000000,99.00,7750000,2.19
            咪咕音乐,人均日播放曲目数,20260318,13.5,12,112.50,12.8,5.47
            咪咕音乐,AI音乐创作工具使用用户数,20260318,2180000,3000000,72.67,1650000,32.12
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
        咪咕视频,AI+咪咕视频自研产品使用用户数,20260318,3721628,6000000,62.03,2792373,33.28
        咪咕视频,视频月活跃用户规模(MAU),20260318,91200000,85000000,107.29,87600000,4.11
        咪咕视频,付费会员数,20260318,11340000,12000000,94.50,11100000,2.16
        咪咕音乐,音乐月活跃用户规模(MAU),20260318,63800000,60000000,106.33,61200000,4.25
        咪咕音乐,付费会员数,20260318,7920000,8000000,99.00,7750000,2.19
        咪咕音乐,AI音乐创作工具使用用户数,20260318,2180000,3000000,72.67,1650000,32.12
        咪咕阅读,阅读月活跃用户规模(MAU),20260318,28400000,30000000,94.67,27100000,4.80
        咪咕阅读,付费会员数,20260318,3860000,4200000,91.90,3720000,3.76
        咪咕阅读,日均阅读时长(分钟),20260318,38.2,35,109.14,36.5,4.66
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
