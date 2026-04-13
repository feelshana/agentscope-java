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
package io.agentscope.examples.dataanalysis.tool;

import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import io.agentscope.examples.dataanalysis.client.DataApiClient;
import io.agentscope.examples.dataanalysis.dto.DatasetInfo;
import io.agentscope.examples.dataanalysis.service.QueryResultCacheService;
import java.util.List;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

/**
 * Tools exposed to the ReActAgent for data analysis.
 *
 * <p>Only {@code query_dataset} is registered as an agent tool. The dataset catalogue is pre-loaded
 * into the system prompt at session creation time, so {@code list_datasets} is intentionally NOT
 * exposed to the LLM – calling it at runtime is unnecessary and wastes tokens.
 *
 * <p>All query results are cached in the database for troubleshooting.
 */
public class DataAnalysisTool {

    private static final Logger log = LoggerFactory.getLogger(DataAnalysisTool.class);

    private final DataApiClient dataApiClient;
    private final String sessionId;
    private final String userName;
    private final QueryResultCacheService cacheService;

    public DataAnalysisTool(
            DataApiClient dataApiClient,
            String sessionId,
            String userName,
            QueryResultCacheService cacheService) {
        this.dataApiClient = dataApiClient;
        this.sessionId = sessionId;
        this.userName = userName;
        this.cacheService = cacheService;
    }

    /**
     * List all available datasets — used internally for session initialisation only. NOT annotated
     * with {@code @Tool}: invisible to the LLM, cannot be called at runtime.
     *
     * @return a formatted list of dataset names and descriptions
     */
    public Mono<String> listDatasets() {
        log.info("[list_datasets] Fetching available datasets");
        return dataApiClient
                .listDatasets()
                .doOnNext(dataApiClient::registerDatasets)
                .map(this::formatDatasetList)
                .doOnNext(result -> log.debug("[list_datasets] Result: {}", result))
                .onErrorResume(
                        e -> {
                            log.error("[list_datasets] Error fetching datasets", e);
                            return Mono.just("Error fetching datasets: " + e.getMessage());
                        });
    }

    /**
     * Get detailed metadata for one or more datasets, including dimensions, metrics,
     * dimension-values, and semantic terms.
     *
     * <p>Only call this when the basic dataset info in the system prompt is not sufficient
     * to construct an accurate query (e.g., the exact field name or dimension value is unknown).
     * Do NOT call this for every query — use it only when necessary to avoid wasting tokens.
     *
     * @param datasetIds comma-separated dataset IDs, e.g. "ds_1" or "ds_1,ds_2"
     * @return detailed metadata string for each requested dataset
     */
    @Tool(
            name = "get_dataset_detail",
            description =
                    "Get detailed metadata (dimensions, metrics, dimension-values, date ranges,"
                            + " terms) for one or more datasets. All metadata is in Chinese"
                            + " (e.g. dimension names, metric names, dimension values, terms)."
                            + " Use this only when the basic dataset info in the system prompt is"
                            + " insufficient — for example when you need exact dimension names,"
                            + " metric names, or dimension values to construct an accurate query."
                            + " Do NOT call this for every query. Pass one or more dataset names"
                            + " separated by commas.")
    public Mono<String> getDatasetDetail(
            @ToolParam(
                            name = "dataset_ids",
                            description =
                                    "One or more dataset names from the system prompt,"
                                            + " comma-separated. Use the exact Name value as shown"
                                            + " in the system prompt."
                                            + " Example: \"考核指标日表\" or \"考核指标日表,付费数据集\"")
                    String datasetIds) {
        log.info("[get_dataset_detail] datasetIds={}", datasetIds);
        // Convert dataset Names to agentIds, skipping unknown ones.
        String agentIds =
                java.util.Arrays.stream(datasetIds.split(","))
                        .map(String::trim)
                        .filter(id -> !id.isBlank())
                        .map(
                                id -> {
                                    String agentId = dataApiClient.getAgentId(id);
                                    if (agentId == null) {
                                        log.warn(
                                                "[get_dataset_detail] Unknown datasetId={},"
                                                        + " skipping",
                                                id);
                                    }
                                    return agentId;
                                })
                        .filter(java.util.Objects::nonNull)
                        .collect(Collectors.joining(","));
        if (agentIds.isBlank()) {
            return Mono.just(
                    "None of the requested dataset names are registered: "
                            + datasetIds
                            + ". Available dataset names are listed in the system prompt.");
        }
        return dataApiClient
                .fetchDatasetDetail(agentIds)
                .doOnNext(
                        result ->
                                log.debug("[get_dataset_detail] Result length={}", result.length()))
                .onErrorResume(
                        e -> {
                            log.error(
                                    "[get_dataset_detail] Error for datasetIds={}", datasetIds, e);
                            return Mono.just("Error fetching dataset detail: " + e.getMessage());
                        });
    }

    /**
     * Query a specific dataset using a natural-language question. Use the dataset ID obtained from
     * list_datasets.
     *
     * @param dataSetName the ID of the dataset to query (obtained from list_datasets)
     * @param question the specific question or analysis request for this dataset
     * @return the query result data as a JSON string, or an error message if not found
     */
    @Tool(
            name = "query_dataset",
            description =
                    "Query a specific dataset by dataset Name and a question. The result contains"
                        + " the data for analysis. If the result is empty or null, it means no"
                        + " matching data was found. Analyze the result to determine whether it"
                        + " satisfies the user's requirement, and decide if further queries are"
                        + " needed. **IMPORTANT**: Before calling this tool, traverse ALL"
                        + " tool_result in the conversation history (not just the most recent one)"
                        + " to see if existing data can be reused. Skip results showing '[历史数据已过期]'"
                        + " or '[数据无效或为空]'.")
    public Mono<String> queryDataset(
            @ToolParam(
                            name = "dataset_name",
                            description =
                                    "The Name of the dataset to query. Available dataset names are"
                                            + " listed in the system prompt.")
                    String dataSetName,
            @ToolParam(
                            name = "question",
                            description =
                                    "The specific question or analysis request to query from this"
                                            + " dataset")
                    String question) {
        log.info("[query_dataset] dataset_name={}, question={}", dataSetName, question);
        return dataApiClient
                .queryDataset(dataSetName, question)
                .map(this::stripMetadata)
                .doOnNext(
                        result -> {
                            log.debug("[query_dataset] Result length={}", result.length());
                            // Cache all query results for troubleshooting
                            cacheQueryResult(dataSetName, question, result);
                        })
                .onErrorResume(
                        e -> {
                            log.error("[query_dataset] Error querying dataset={}", dataSetName, e);
                            String errorMsg = "Error querying dataset: " + e.getMessage();
                            // Cache error result for troubleshooting
                            cacheQueryResult(dataSetName, question, errorMsg);
                            return Mono.just(errorMsg);
                        });
    }

    /**
     * Cache the query result for troubleshooting.
     * Stores all results including empty results and errors.
     */
    private void cacheQueryResult(String datasetId, String question, String result) {
        if (cacheService == null || sessionId == null) {
            log.debug("[query_dataset] Cache service not available, skipping cache");
            return;
        }
        try {
            cacheService.storeQueryResult(sessionId, datasetId, question, result, userName);
            log.info(
                    "[query_dataset] Cached result for session={}, dataset={}, user={}",
                    sessionId,
                    datasetId,
                    userName);
        } catch (Exception e) {
            log.warn("[query_dataset] Failed to cache query result", e);
        }
    }

    /**
     * Strip metadata-only fields from the query result to reduce token usage.
     *
     * <p>When the upstream service returns a JSON object containing a {@code "fields"} key (column
     * schema information) alongside the actual {@code "result"} data, the fields declaration is
     * redundant for the LLM – the data rows already carry that information. This method removes
     * {@code "fields"} from the top-level JSON object so only the raw data rows are forwarded to
     * the model.
     *
     * <p>If the result is not valid JSON or does not contain {@code "fields"}, it is returned
     * unchanged.
     */
    private String stripMetadata(String result) {
        if (result == null || result.isBlank()) {
            return result;
        }
        String trimmed = result.strip();
        if (!trimmed.startsWith("{")) {
            // Not a JSON object (plain text from textResult); return as-is.
            return result;
        }
        try {
            // Remove the "fields": [...] entry using a simple regex to avoid pulling in a
            // full JSON library dependency. The pattern matches:
            //   "fields"  : [  ...  ] ,?   (with optional trailing comma)
            // We handle both compact and pretty-printed forms.
            String stripped = trimmed.replaceAll("(?s)\\s*\"fields\"\\s*:\\s*\\[.*?\\]\\s*,?", "");
            if (!stripped.equals(trimmed)) {
                log.debug("[query_dataset] Stripped 'fields' metadata from result");
            }
            return stripped;
        } catch (Exception e) {
            // Safety net: if regex fails for any reason, return original
            log.warn("[query_dataset] stripMetadata failed, returning original result", e);
            return result;
        }
    }

    private String formatDatasetList(List<DatasetInfo> datasets) {
        if (datasets == null || datasets.isEmpty()) {
            return "No datasets available.";
        }
        return "Available datasets:\n"
                + datasets.stream()
                        .map(
                                ds ->
                                        "  - Name: "
                                                + ds.getName()
                                                + "\n    Description: "
                                                + ds.getDescription())
                        .collect(Collectors.joining("\n"));
    }
}
