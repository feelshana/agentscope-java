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
 * <p>Only {@code query_dataset} is registered as an agent tool. The dataset catalogue is
 * pre-loaded into the system prompt at session creation time, so {@code list_datasets} is
 * intentionally NOT exposed to the LLM – calling it at runtime is unnecessary and wastes tokens.
 */
public class DataAnalysisTool {

    private static final Logger log = LoggerFactory.getLogger(DataAnalysisTool.class);

    private final DataApiClient dataApiClient;

    public DataAnalysisTool(DataApiClient dataApiClient) {
        this.dataApiClient = dataApiClient;
    }

    /**
     * List all available datasets — used internally for session initialisation only.
     * NOT annotated with {@code @Tool}: invisible to the LLM, cannot be called at runtime.
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
                    "Query a specific dataset by dataset Name and a question. The result contains"
                            + " the data for analysis. If the result is empty or null, it means"
                            + " no matching data was found. Analyze the result to determine"
                            + " whether it satisfies the user's requirement, and decide if"
                            + " further queries are needed.")
    public Mono<String> queryDataset(
            @ToolParam(
                            name = "dataset_id",
                            description =
                                    "The Name of the dataset to query. Available dataset names are"
                                            + " listed in the system prompt.")
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
                .map(this::stripMetadata)
                .doOnNext(result -> log.debug("[query_dataset] Result length={}", result.length()))
                .onErrorResume(
                        e -> {
                            log.error("[query_dataset] Error querying dataset={}", datasetId, e);
                            return Mono.just("Error querying dataset: " + e.getMessage());
                        });
    }

    /**
     * Strip metadata-only fields from the query result to reduce token usage.
     *
     * <p>When the upstream service returns a JSON object containing a {@code "fields"} key
     * (column schema information) alongside the actual {@code "result"} data, the fields
     * declaration is redundant for the LLM – the data rows already carry that information.
     * This method removes {@code "fields"} from the top-level JSON object so only the
     * raw data rows are forwarded to the model.
     *
     * <p>If the result is not valid JSON or does not contain {@code "fields"}, it is
     * returned unchanged.
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
            String stripped =
                    trimmed.replaceAll(
                            "(?s)\\s*\"fields\"\\s*:\\s*\\[.*?\\]\\s*,?", "");
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
