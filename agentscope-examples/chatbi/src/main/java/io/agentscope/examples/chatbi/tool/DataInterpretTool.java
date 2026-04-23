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
import io.agentscope.examples.chatbi.client.ReportDataApiClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

/**
 * Tools for data interpretation — a sub-type of the {@code da} intent.
 *
 * <p>Provides two ways to get data for interpretation:
 * <ul>
 *   <li>{@link #interpretChartData} — fetches raw data from the report API using {@code reportId @chartParam}</li>
 * </ul>
 *
 * <p>Unlike {@code DataQueryAgentTool} which queries SuperSonic datasets, these tools
 * operate on data that is already rendered in the frontend or associated with a report.
 */
public class DataInterpretTool {

    private static final Logger log = LoggerFactory.getLogger(DataInterpretTool.class);

    private final String chartParam;
    private final String reportId;
    private final String easyBiSession;
    private final ReportDataApiClient reportDataClient;

    public DataInterpretTool(
            String chartParam,
            String reportId,
            String easyBiSession,
            ReportDataApiClient reportDataClient) {
        this.chartParam = chartParam;
        this.reportId = reportId;
        this.easyBiSession = easyBiSession;
        this.reportDataClient = reportDataClient;
    }

    /**
     * Fetch raw data for a report via the getPureData4ChatBI endpoint.
     * Use this when the user asks to interpret the current report's data
     * and a reportId is available from context.
     *
     * @return the report's raw data as JSON, or an error message if reportId is unavailable
     */
    @Tool(
            name = "interpret_chart_data",
            description =
                    "Fetch raw data for the report the user is currently viewing. "
                            + "Use this when the user says '解读当前数据', '解读当前报表数据', "
                            + "'分析三月数据' or similar requests to analyze report data. "
                            + "The tool calls the report data API and returns the raw data "
                            + "in a structured format for interpretation. "
                            + "Call this tool BEFORE analyzing the data.")
    public Mono<String> interpretChartData() {
        log.info("[interpret_chart_data] reportId={}", reportId);
        return reportDataClient.interpretChartData(reportId, easyBiSession, chartParam);
    }
}
