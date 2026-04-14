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
 * Tool for recommending reports and dashboards (re intent).
 *
 * <p>When users ask "which report shows X" or "recommend a dashboard for Y",
 * this tool searches available datasets/reports via SuperSonic and returns
 * relevant report names and descriptions.
 */
public class ReportQueryTool {

    private static final Logger log = LoggerFactory.getLogger(ReportQueryTool.class);

    private final SupersonicApiClient supersonicClient;
    private final String agentId;
    private final String supersonicToken;

    public ReportQueryTool(
            SupersonicApiClient supersonicClient, String agentId, String supersonicToken) {
        this.supersonicClient = supersonicClient;
        this.agentId = agentId;
        this.supersonicToken = supersonicToken;
    }

    /**
     * Recommend reports or dashboards that match the user's need.
     * Lists available datasets and returns those relevant to the query topic.
     *
     * @param topic the topic or metric the user wants to see in a report
     * @return list of recommended reports with names and descriptions
     */
    @Tool(
            name = "recommend_reports",
            description =
                    "Recommend relevant reports or dashboards based on the user's topic or need."
                        + " Use this when the user asks 'which report has X data', 'what reports"
                        + " are available for Y', or 'recommend a dashboard for Z metric'. Returns"
                        + " a list of matching report/dataset names with descriptions.")
    public Mono<String> recommendReports(
            @ToolParam(
                            name = "topic",
                            description =
                                    "The topic, metric or data area the user wants to find reports"
                                            + " for. E.g., '用户活跃度', '订单量', '流量分析'")
                    String topic) {
        log.info("[recommend_reports] topic={}", topic);
        return supersonicClient
                .listDatasets(agentId, supersonicToken)
                .map(datasets -> filterAndFormatReports(datasets, topic))
                .onErrorResume(
                        e -> {
                            log.error("[recommend_reports] Error: {}", e.getMessage());
                            return Mono.just("报表推荐接口异常：" + e.getMessage());
                        });
    }

    private String filterAndFormatReports(List<DatasetInfo> datasets, String topic) {
        if (datasets == null || datasets.isEmpty()) {
            return "暂无可用的报表/数据集信息。";
        }
        // Return all datasets; the LLM will decide which are relevant to the topic
        String list =
                datasets.stream()
                        .map(
                                ds ->
                                        "  - 名称: "
                                                + ds.getName()
                                                + (ds.getDescription() != null
                                                                && !ds.getDescription().isBlank()
                                                        ? "\n    描述: " + ds.getDescription()
                                                        : ""))
                        .collect(Collectors.joining("\n"));
        return "可用报表/数据集列表（请根据用户问题 '" + topic + "' 从以下列表中推荐最相关的报表）：\n" + list;
    }
}
