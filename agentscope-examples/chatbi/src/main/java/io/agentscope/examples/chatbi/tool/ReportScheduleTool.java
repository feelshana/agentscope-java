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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

/**
 * Tool for querying report data schedule / refresh time (cs intent: 出数时间).
 *
 * <p>When users ask "这个报表什么时候出数", "数据更新时间是什么", "报表多久刷新一次",
 * this tool calls the schedule API using the current report/dashboard context
 * from the request.
 */
public class ReportScheduleTool {

    private static final Logger log = LoggerFactory.getLogger(ReportScheduleTool.class);

    private final SupersonicApiClient supersonicClient;

    /** Per-session context: reportId and dashboardId from the original request. */
    private final String reportId;

    private final String dashboardId;
    private final String supersonicToken;

    public ReportScheduleTool(
            SupersonicApiClient supersonicClient,
            String reportId,
            String dashboardId,
            String supersonicToken) {
        this.supersonicClient = supersonicClient;
        this.reportId = reportId;
        this.dashboardId = dashboardId;
        this.supersonicToken = supersonicToken;
    }

    /**
     * Query the data refresh schedule for the current report or a specified report.
     *
     * @param reportName optional report name mentioned by the user (for context only)
     * @return schedule and refresh time information for the report
     */
    @Tool(
            name = "query_report_schedule",
            description =
                    "Query the data refresh schedule and update time for a report or dashboard. "
                            + "Use this when the user asks '这个报表什么时候出数', '数据多久更新', "
                            + "'报表刷新时间', '什么时候能看到最新数据', or similar questions about "
                            + "data freshness and update schedules.")
    public Mono<String> queryReportSchedule(
            @ToolParam(
                            name = "report_name",
                            description =
                                    "The report or dashboard name mentioned by the user (optional)."
                                        + " If the user is asking about the current report, use the"
                                        + " current report context. E.g., '用户活跃报表', '销售仪表盘'")
                    String reportName) {
        log.info(
                "[query_report_schedule] reportName={}, reportId={}, dashboardId={}",
                reportName,
                reportId,
                dashboardId);
        return supersonicClient
                .queryReportSchedule(reportId, dashboardId, supersonicToken)
                .doOnNext(r -> log.debug("[query_report_schedule] result={}", r))
                .onErrorResume(
                        e -> {
                            log.error("[query_report_schedule] Error: {}", e.getMessage());
                            return Mono.just("出数时间查询接口异常：" + e.getMessage());
                        });
    }
}
