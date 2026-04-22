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
import io.agentscope.examples.chatbi.client.KnowledgeSearchClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

/**
 * Tool for searching the internal knowledge base (DocRepo + Report Search).
 *
 * <p>Handles intents:
 * <ul>
 *   <li>{@code re} – report/dashboard search</li>
 *   <li>{@code in} – metric definition / calculation logic</li>
 *   <li>{@code bu} – business knowledge / system knowledge</li>
 * </ul>
 *
 * <p>Delegates to {@link KnowledgeSearchClient} which talks to the
 * dedicated knowledge search service (separate from Confluence).
 */
public class KnowledgeSearchTool {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeSearchTool.class);

    private final KnowledgeSearchClient knowledgeSearchClient;

    public KnowledgeSearchTool(KnowledgeSearchClient knowledgeSearchClient) {
        this.knowledgeSearchClient = knowledgeSearchClient;
    }

    /**
     * Search for reports or dashboards by keyword.
     *
     * @param query the user's search query (e.g., "活跃用户报表")
     * @return search results with matched report/dashboard information
     */
    @Tool(
            name = "search_reports",
            description = "搜索报表或仪表盘。当用户询问某类报表、仪表盘名称或需要查找相关报表时使用此工具。" + "参数 query 是用户的搜索关键词。")
    public Mono<String> searchReports(
            @ToolParam(name = "query", description = "用户的搜索关键词，例如：活跃用户报表") String query) {
        log.info("[KnowledgeSearchTool] searchReports query={}", query);
        return knowledgeSearchClient.searchReports(query, 10);
    }

    @Tool(
            name = "query_knowledge",
            description =
                    "查询知识库中的指标定义、业务知识或系统知识。参数 query 是用户的问题；questionType 是问题类型：\"in\""
                            + " 表示指标口径/定义查询，\"bu\" 表示业务知识/系统知识查询。")
    public Mono<String> queryKnowledge(
            @ToolParam(name = "query", description = "用户的问题") String query,
            @ToolParam(
                            name = "questionType",
                            description = "问题类型：\"in\" 表示指标口径/定义查询，\"bu\" 表示业务知识/系统知识查询")
                    String questionType) {
        log.info("[KnowledgeSearchTool] queryKnowledge query={}, type={}", query, questionType);
        return knowledgeSearchClient.queryDocRepo(query, questionType, null, null, null, null);
    }

    /**
     * Full-featured knowledge query with all context parameters, for use in agent factories
     * that have access to session context (projectId, reportName, dashboardName, memory).
     *
     * @param query          the user's question
     * @param questionType   "in" or "bu"
     * @param projectId      project identifier
     * @param reportName     current report name (optional)
     * @param dashboardName  current dashboard name (optional)
     * @param memoryJson     conversation history as JSON string (optional)
     * @return knowledge base answer
     */
    @Tool(
            name = "query_knowledge_with_context",
            description =
                    "查询知识库（完整版），支持传入项目ID、当前报表/仪表盘名称和对话历史记忆以获得更精准的结果。query: 用户问题; questionType:"
                            + " \"in\"(指标口径) 或 \"bu\"(业务知识); projectId: 项目ID; reportName: 报表名;"
                            + " dashboardName: 仪表盘名; memory: 对话历史JSON字符串")
    public Mono<String> queryKnowledgeWithContext(
            @ToolParam(name = "query", description = "用户的问题") String query,
            @ToolParam(name = "questionType", description = "\"in\"(指标口径) 或 \"bu\"(业务知识)")
                    String questionType,
            @ToolParam(name = "projectId", description = "项目ID") String projectId,
            @ToolParam(name = "reportName", description = "当前报表名称（可选）", required = false)
                    String reportName,
            @ToolParam(name = "dashboardName", description = "当前仪表盘名称（可选）", required = false)
                    String dashboardName,
            @ToolParam(name = "memory", description = "对话历史JSON字符串（可选）", required = false)
                    String memoryJson) {
        log.info(
                "[KnowledgeSearchTool] queryKnowledgeWithContext query={}, type={}, projectId={}",
                query,
                questionType,
                projectId);
        return knowledgeSearchClient.queryDocRepo(
                query, questionType, projectId, reportName, dashboardName, memoryJson);
    }
}
