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
import java.util.List;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

/**
 * Tools exposed to the ReActAgent for data analysis.
 *
 * <p>Two tools are registered:
 * <ul>
 *   <li>{@code list_datasets} - retrieves available datasets with their descriptions</li>
 *   <li>{@code query_dataset} - queries a specific dataset with a natural-language question</li>
 * </ul>
 *
 * <p>The agent uses these tools to:
 * <ol>
 *   <li>Discover which datasets are available.</li>
 *   <li>Break the user's question into sub-tasks, each targeting one dataset.</li>
 *   <li>Evaluate query results and decide whether to stop or continue querying.</li>
 * </ol>
 */
public class DataAnalysisTool {

    private static final Logger log = LoggerFactory.getLogger(DataAnalysisTool.class);

    private final DataApiClient dataApiClient;

    public DataAnalysisTool(DataApiClient dataApiClient) {
        this.dataApiClient = dataApiClient;
    }

    /**
     * List all available datasets with their IDs and descriptions.
     * Call this first to understand which datasets can be queried.
     *
     * @return a formatted list of dataset IDs and their descriptions
     */
    @Tool(
            name = "list_datasets",
            description =
                    "List all available data datasets. Returns a list containing dataset ID and"
                            + " description for each dataset. Call this tool first to understand"
                            + " which datasets are available before querying.")
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
     * Query a specific dataset using a natural-language question.
     * Use the dataset ID obtained from list_datasets.
     *
     * @param datasetId the ID of the dataset to query (obtained from list_datasets)
     * @param question  the specific question or analysis request for this dataset
     * @return the query result data as a JSON string, or an error message if not found
     */
    @Tool(
            name = "query_dataset",
            description =
                    "Query a specific dataset by dataset ID and a question. The result contains"
                            + " the data for analysis. If the result is empty or null, it means"
                            + " no matching data was found. Analyze the result to determine"
                            + " whether it satisfies the user's requirement, and decide if"
                            + " further queries are needed.")
    public Mono<String> queryDataset(
            @ToolParam(
                            name = "dataset_id",
                            description =
                                    "The ID of the dataset to query, obtained from list_datasets"
                                            + " tool")
                    String datasetId,
            @ToolParam(
                            name = "question",
                            description =
                                    "The specific question or analysis request to query from this"
                                            + " dataset")
                    String question) {
        log.info("[query_dataset] datasetId={}, question={}", datasetId, question);
        return dataApiClient
                .queryDataset(datasetId, question)
                .doOnNext(result -> log.debug("[query_dataset] Result length={}", result.length()))
                .onErrorResume(
                        e -> {
                            log.error("[query_dataset] Error querying dataset={}", datasetId, e);
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
                                        "  - ID: "
                                                + ds.getId()
                                                + "\n    Name: "
                                                + ds.getName()
                                                + "\n    Description: "
                                                + ds.getDescription())
                        .collect(Collectors.joining("\n"));
    }
}
