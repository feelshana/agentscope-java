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
import io.agentscope.examples.chatbi.client.DataLineageApiClient;
import io.agentscope.examples.chatbi.service.DataLineageMemoryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

/**
 * Tool for querying data lineage and dependency relationships (dl intent).
 *
 * <p>Calls the dedicated {@link DataLineageApiClient} with {@code query},
 * {@code project_id} and {@code memory} (previous rounds in this session).
 * The client extracts {@code final_prompt} from the API response and returns it
 * directly to the LLM.
 *
 * <p>The user question is persisted immediately via {@link DataLineageMemoryService};
 * the assistant answer is saved by {@link io.agentscope.examples.chatbi.service.DataLineageRoundSaveHook}.
 */
public class DataLineageTool {

    private static final Logger log = LoggerFactory.getLogger(DataLineageTool.class);

    private final DataLineageApiClient lineageClient;
    private final String projectId;
    private final String sessionId;
    private final DataLineageMemoryService memoryService;

    public DataLineageTool(
            DataLineageApiClient lineageClient,
            String projectId,
            String sessionId,
            DataLineageMemoryService memoryService) {
        this.lineageClient = lineageClient;
        this.projectId = projectId;
        this.sessionId = sessionId;
        this.memoryService = memoryService;
    }

    /**
     * Query data lineage and build a {@code final_prompt} for the LLM.
     *
     * @param query the user's question (or rewritten question)
     * @return final_prompt containing lineage data, ready for LLM to answer
     */
    @Tool(
            name = "query_data_lineage",
            description =
                    "Query data lineage and dependency relationships. Use this when the user asks "
                            + "about '数据血缘', '数据来源', '上游依赖', '下游影响', '哪些表依赖', "
                            + "'被哪些运算元素更新', or 'where does this data come from'. "
                            + "Returns a final_prompt with lineage data for LLM to answer.")
    public Mono<String> queryDataLineage(
            @ToolParam(
                            name = "query",
                            description =
                                    "The user's question or rewritten question about data lineage. "
                                            + "E.g., 'dws_user_active_d 表的上游依赖有哪些', "
                                            + "'mgwh_rd_dwm.dwm_video_use_d 被哪些运算元素更新'")
                    String query) {
        log.info("[query_data_lineage] sessionId={}, query={}", sessionId, query);

        // Load previous lineage rounds for this session as memory
        String memory = memoryService.loadMemoryJson(sessionId);

        // Persist the user question immediately (assistant answer saved by hook)
        memoryService.saveUserMessage(sessionId, query);

        return lineageClient
                .queryLineage(query, projectId, memory)
                .doOnNext(fp -> log.debug("[query_data_lineage] final_prompt length={}", fp.length()))
                .onErrorResume(
                        e -> {
                            log.error("[query_data_lineage] Error: {}", e.getMessage());
                            return Mono.just("数据血缘查询接口异常：" + e.getMessage());
                        });
    }
}
