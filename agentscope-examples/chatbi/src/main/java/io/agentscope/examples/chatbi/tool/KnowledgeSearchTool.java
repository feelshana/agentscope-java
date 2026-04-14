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
import io.agentscope.examples.chatbi.client.ConfluenceApiClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

/**
 * Tool for searching the internal Confluence knowledge base.
 *
 * <p>Handles intents: {@code in} (指标口径), {@code gu} (大数据平台工具使用),
 * {@code bu} (业务知识/系统知识), {@code re} (报表相关知识).
 */
public class KnowledgeSearchTool {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeSearchTool.class);

    private final ConfluenceApiClient confluenceClient;

    public KnowledgeSearchTool(ConfluenceApiClient confluenceClient) {
        this.confluenceClient = confluenceClient;
    }

    /**
     * Search the internal knowledge base (Confluence) for information related to metrics,
     * platform tools, business knowledge, or report documentation.
     *
     * @param query the search query, derived from the user's question
     * @return relevant knowledge base content
     */
    @Tool(
            name = "search_knowledge",
            description =
                    "Search the internal Confluence knowledge base for information about: (1)"
                        + " metric definitions and caliber (指标口径), (2) big-data platform tool usage"
                        + " (大数据工具使用), (3) business knowledge and system knowledge (业务知识/系统知识), (4)"
                        + " report documentation and dashboard descriptions. Use this tool when the"
                        + " user asks about metric meanings, data definitions, how to use platform"
                        + " tools, or business background knowledge.")
    public Mono<String> searchKnowledge(
            @ToolParam(
                            name = "query",
                            description =
                                    "The search query extracted from the user's question. "
                                            + "Should be concise keywords or a short phrase.")
                    String query) {
        log.info("[search_knowledge] query={}", query);
        return confluenceClient
                .searchAndFetch(query)
                .doOnNext(r -> log.debug("[search_knowledge] result length={}", r.length()))
                .onErrorResume(
                        e -> {
                            log.error("[search_knowledge] Error: {}", e.getMessage());
                            return Mono.just("知识库检索异常：" + e.getMessage());
                        });
    }
}
