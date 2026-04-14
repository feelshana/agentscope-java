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
import java.util.List;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

/**
 * Tool for querying data from SuperSonic (da intent).
 *
 * <p>Two tools:
 * <ul>
 *   <li>{@code list_datasets} – discover available datasets</li>
 *   <li>{@code query_dataset} – send natural-language query to a dataset</li>
 * </ul>
 */
public class DataQueryTool {

    private static final Logger log = LoggerFactory.getLogger(DataQueryTool.class);

    private final SupersonicApiClient supersonicClient;

    /** Per-request context: agentId and supersonicToken injected at session creation. */
    private final String agentId;

    private final String supersonicToken;

    public DataQueryTool(
            SupersonicApiClient supersonicClient, String agentId, String supersonicToken) {
        this.supersonicClient = supersonicClient;
        this.agentId = agentId;
        this.supersonicToken = supersonicToken;
    }

    /**
     * List all available data datasets. Returns dataset names and descriptions.
     * Call this first to understand which datasets can be queried.
     */
    @Tool(
            name = "list_datasets",
            description =
                    "List all available data datasets. Returns a list containing dataset Name and"
                            + " description. Call this tool first to understand which datasets are"
                            + " available before querying.")
    public Mono<String> listDatasets() {
        log.info("[list_datasets] Fetching available datasets, agentId={}", agentId);
        return supersonicClient
                .listDatasets(agentId, supersonicToken)
                .map(this::formatDatasetList)
                .onErrorResume(
                        e -> {
                            log.error("[list_datasets] Error: {}", e.getMessage());
                            return Mono.just("Error fetching datasets: " + e.getMessage());
                        });
    }

    /**
     * Query a specific dataset using a natural-language question.
     *
     * @param datasetId the dataset Name obtained from list_datasets
     * @param question  the natural-language query
     * @return query result as JSON/text string
     */
    @Tool(
            name = "query_dataset",
            description =
                    "Query a specific dataset by dataset Name and a question. The result contains"
                            + " the data for analysis. If the result is empty, no matching data was"
                            + " found. Analyze the result to determine whether it satisfies the"
                            + " user's requirement and decide if further queries are needed.")
    public Mono<String> queryDataset(
            @ToolParam(
                            name = "dataset_id",
                            description = "The Name of the dataset to query, from list_datasets")
                    String datasetId,
            @ToolParam(
                            name = "question",
                            description = "The specific natural-language question to query")
                    String question) {
        log.info("[query_dataset] datasetId={}, question={}", datasetId, question);
        String resolvedAgentId = supersonicClient.getAgentIdForDataset(datasetId);
        if (resolvedAgentId == null) {
            resolvedAgentId = agentId;
        }
        return supersonicClient
                .queryDataset(resolvedAgentId, question, supersonicToken)
                .doOnNext(r -> log.debug("[query_dataset] result length={}", r.length()))
                .onErrorResume(
                        e -> {
                            log.error("[query_dataset] Error: {}", e.getMessage());
                            return Mono.just("Error querying dataset: " + e.getMessage());
                        });
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
