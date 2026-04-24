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
import io.agentscope.examples.chatbi.client.ReportSearchApiClient;
import io.agentscope.examples.chatbi.service.SubAgentMemoryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

/**
 * Tool for recommending reports and dashboards (re intent).
 *
 * <p>Calls the dedicated report search API to find relevant reports,
 * dashboards, or data screens based on the user's natural-language query.
 *
 * <p>The user question is persisted by {@link SubAgentUserMessageHook} at agent entry;
 * the assistant answer is saved by {@link RoundSaveHook}.
 */
public class ReportQueryTool {

    private static final Logger log = LoggerFactory.getLogger(ReportQueryTool.class);

    private static final String TYPE_RE = "re";

    private final ReportSearchApiClient searchClient;
    private final String projectId;
    private final String reportName;
    private final String dashboardName;
    private final String sessionId;
    private final SubAgentMemoryService memoryService;

    public ReportQueryTool(
            ReportSearchApiClient searchClient,
            String projectId,
            String reportName,
            String dashboardName,
            String sessionId,
            SubAgentMemoryService memoryService) {
        this.searchClient = searchClient;
        this.projectId = projectId;
        this.reportName = reportName;
        this.dashboardName = dashboardName;
        this.sessionId = sessionId;
        this.memoryService = memoryService;
    }

    /**
     * Recommend reports or dashboards that match the user's need.
     *
     * @param query the user's question or topic for report recommendation
     * @return list of recommended reports with names and descriptions from the search API
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
                            name = "query",
                            description =
                                    "The user's question or topic describing what reports or"
                                            + " dashboards they need. E.g., '用户活跃度报表', '订单分析仪表盘'")
                    String query) {
        log.info("[recommend_reports] sessionId={}, query={}", sessionId, query);

        // Load previous report query rounds for this session as memory
        String memory = memoryService.loadMemoryJson(sessionId, TYPE_RE);

        return searchClient
                .searchReports(query, projectId, reportName, dashboardName, memory, 10)
                .onErrorResume(
                        e -> {
                            log.error("[recommend_reports] Error: {}", e.getMessage());
                            return Mono.just("报表推荐接口异常：" + e.getMessage());
                        });
    }
}
