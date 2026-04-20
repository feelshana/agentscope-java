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
package io.agentscope.examples.chatbi.tool;

import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import io.agentscope.examples.chatbi.client.SupersonicApiClient;
import io.agentscope.examples.chatbi.dto.DatasetInfo;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

/**
 * Tools for DataQueryAgent — mirrors the data-analysis DataAnalysisTool logic.
 *
 * <p>Exposes:
 * <ul>
 *   <li>{@code get_dataset_detail} – fetch detailed metadata (dimensions/metrics/values) for one
 *       or more datasets. Use only when the brief catalogue in the system prompt is insufficient.
 *   <li>{@code query_dataset} – send a natural-language query to a dataset and receive raw data.
 * </ul>
 *
 * <p>{@code list_datasets} is intentionally NOT annotated with {@code @Tool}. The dataset
 * catalogue is injected into the DataQueryAgent's system prompt at session creation time via
 * {@link io.agentscope.examples.chatbi.service.DataQueryDatasetInjectionHook}, so the LLM never
 * needs to call list_datasets at runtime.
 */
public class DataQueryAgentTool {

    private static final Logger log = LoggerFactory.getLogger(DataQueryAgentTool.class);

    private final SupersonicApiClient supersonicClient;
    private final String agentId;

    public DataQueryAgentTool(SupersonicApiClient supersonicClient, String agentId) {
        this.supersonicClient = supersonicClient;
        this.agentId = agentId;
    }

    /**
     * Fetch the dataset list — used internally for session initialisation only.
     * NOT annotated with {@code @Tool}: invisible to the LLM, cannot be called at runtime.
     *
     * @return Mono emitting list of DatasetInfo
     */
    public Mono<List<DatasetInfo>> listDatasets() {
        log.info("[list_datasets] Fetching datasets for DataQueryAgent, agentId={}", agentId);
        return supersonicClient
                .listDatasets(agentId)
                .doOnNext(
                        datasets ->
                                datasets.forEach(
                                        ds ->
                                                supersonicClient.registerDataset(
                                                        ds.getName(), ds.getAgentId())))
                .doOnNext(
                        result ->
                                log.debug("[list_datasets] Got {} datasets", result.size()))
                .onErrorResume(
                        e -> {
                            log.error("[list_datasets] Error fetching datasets", e);
                            return Mono.just(List.of());
                        });
    }

    /**
     * Get detailed metadata for one or more datasets.
     *
     * <p>Only call this when the basic dataset info in the system prompt is insufficient to
     * construct an accurate query (e.g., the exact field name or dimension value is unknown).
     * Do NOT call this for every query — use only when necessary to avoid wasting tokens.
     * Each dataset should be fetched only ONCE per session — check conversation history first.
     *
     * @param datasetIds comma-separated dataset names, e.g. "考核指标日表" or "考核指标日表,付费数据集"
     * @return detailed metadata string for each requested dataset
     */
    @Tool(
            name = "get_dataset_detail",
            description =
                    "Get detailed metadata (dimensions, metrics, dimension-values, date ranges) for"
                            + " one or more datasets. All metadata is in Chinese. Use this ONLY"
                            + " when the basic dataset info in the system prompt is insufficient —"
                            + " for example when you need exact dimension names, metric names, or"
                            + " dimension values to construct an accurate query."
                            + " Do NOT call this for every query."
                            + " Each dataset should only be fetched ONCE per session —"
                            + " check conversation history for existing get_dataset_detail results."
                            + " Pass one or more dataset names separated by commas.")
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
        String resolvedAgentIds =
                Arrays.stream(datasetIds.split(","))
                        .map(String::trim)
                        .filter(id -> !id.isBlank())
                        .map(
                                name -> {
                                    String aid = supersonicClient.getAgentIdForDataset(name);
                                    if (aid == null) {
                                        log.warn(
                                                "[get_dataset_detail] Unknown dataset name={},"
                                                        + " skipping",
                                                name);
                                    }
                                    return aid;
                                })
                        .filter(Objects::nonNull)
                        .collect(Collectors.joining(","));

        if (resolvedAgentIds.isBlank()) {
            return Mono.just(
                    "None of the requested dataset names are registered: "
                            + datasetIds
                            + ". Available dataset names are listed in the system prompt.");
        }
        return supersonicClient
                .fetchDatasetDetail(resolvedAgentIds)
                .doOnNext(
                        result ->
                                log.debug(
                                        "[get_dataset_detail] Result length={}", result.length()))
                .onErrorResume(
                        e -> {
                            log.error(
                                    "[get_dataset_detail] Error for datasetIds={}", datasetIds, e);
                            return Mono.just("Error fetching dataset detail: " + e.getMessage());
                        });
    }

    /**
     * Query a specific dataset using a natural-language question.
     *
     * <p>Before calling, check ALL previous tool_results in the conversation history to see if
     * existing data can be reused. Skip results showing '[历史数据已过期]' or '[数据无效或为空]'.
     *
     * @param datasetName the dataset Name from the system prompt
     * @param question    the specific natural-language question
     * @return the query result as JSON/text string
     */
    @Tool(
            name = "query_dataset",
            description =
                    "Query a specific dataset by dataset Name and a question. The result contains"
                            + " the data for analysis. If the result is empty or null, it means no"
                            + " matching data was found. Analyze the result to determine whether it"
                            + " satisfies the user's requirement, and decide if further queries are"
                            + " needed. **IMPORTANT**: Before calling this tool, traverse ALL"
                            + " tool_result in the conversation history (not just the most recent"
                            + " one) to see if existing data can be reused. Skip results showing"
                            + " '[历史数据已过期]' or '[数据无效或为空]'.")
    public Mono<String> queryDataset(
            @ToolParam(
                            name = "dataset_name",
                            description =
                                    "The Name of the dataset to query. Available dataset names are"
                                            + " listed in the system prompt. Use the exact Name value.")
                    String datasetName,
            @ToolParam(
                            name = "question",
                            description =
                                    "The specific question or analysis request to query from this"
                                            + " dataset")
                    String question) {
        log.info("[query_dataset] dataset_name={}, question={}", datasetName, question);
        return supersonicClient
                .queryDataset(datasetName, question)
                .map(this::stripMetadata)
                .doOnNext(
                        result ->
                                log.debug("[query_dataset] Result length={}", result.length()))
                .onErrorResume(
                        e -> {
                            log.error(
                                    "[query_dataset] Error querying dataset={}", datasetName, e);
                            return Mono.just("Error querying dataset: " + e.getMessage());
                        });
    }

    /**
     * Strip 'fields' metadata from query result JSON to reduce token usage.
     */
    private String stripMetadata(String result) {
        if (result == null || result.isBlank()) {
            return result;
        }
        String trimmed = result.strip();
        if (!trimmed.startsWith("{")) {
            return result;
        }
        try {
            String stripped = trimmed.replaceAll("(?s)\\s*\"fields\"\\s*:\\s*\\[.*?\\]\\s*,?", "");
            if (!stripped.equals(trimmed)) {
                log.debug("[query_dataset] Stripped 'fields' metadata from result");
            }
            return stripped;
        } catch (Exception e) {
            log.warn("[query_dataset] stripMetadata failed, returning original result", e);
            return result;
        }
    }
}
