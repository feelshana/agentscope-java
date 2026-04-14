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
 * Tool for querying data lineage and dependency relationships (dl intent).
 *
 * <p>When users ask "这个指标的数据来源是哪里", "X表依赖哪些上游表",
 * or "数据血缘关系是什么", this tool calls the SuperSonic lineage API.
 */
public class DataLineageTool {

    private static final Logger log = LoggerFactory.getLogger(DataLineageTool.class);

    private final SupersonicApiClient supersonicClient;
    private final String supersonicToken;

    public DataLineageTool(SupersonicApiClient supersonicClient, String supersonicToken) {
        this.supersonicClient = supersonicClient;
        this.supersonicToken = supersonicToken;
    }

    /**
     * Query data lineage information for a given table, metric or field.
     *
     * @param target the table name, metric name or field to query lineage for
     * @return lineage relationship information
     */
    @Tool(
            name = "query_data_lineage",
            description =
                    "Query data lineage and dependency relationships for a specific table, metric "
                            + "or data field. Use this when the user asks about '数据血缘', '数据来源', "
                            + "'上游依赖', '下游影响', or 'where does this data come from'. "
                            + "Returns upstream/downstream dependency information.")
    public Mono<String> queryDataLineage(
            @ToolParam(
                            name = "target",
                            description =
                                    "The table name, metric name or field to query lineage for. "
                                            + "E.g., 'dws_user_active_d', '日活用户数', 'order_count'")
                    String target) {
        log.info("[query_data_lineage] target={}", target);
        return supersonicClient
                .queryLineage(target, supersonicToken)
                .doOnNext(r -> log.debug("[query_data_lineage] result length={}", r.length()))
                .onErrorResume(
                        e -> {
                            log.error("[query_data_lineage] Error: {}", e.getMessage());
                            return Mono.just("数据血缘查询接口异常：" + e.getMessage());
                        });
    }
}
