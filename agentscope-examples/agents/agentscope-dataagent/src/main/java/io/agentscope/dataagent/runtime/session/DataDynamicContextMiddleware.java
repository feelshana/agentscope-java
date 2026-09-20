/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.dataagent.runtime.session;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.dataagent.dataset.DatasetContextProvider;
import io.agentscope.dataagent.dataset.DatasetScope;
import io.agentscope.dataagent.tools.data.DataSource;
import io.agentscope.dataagent.tools.data.DataSourceRegistry;
import io.agentscope.harness.agent.middleware.HarnessRuntimeMiddleware;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Injects dynamic {@code [DATA_SOURCES_OVERVIEW]} and {@code [KNOWLEDGE_BASE_OVERVIEW]} sections
 * into the system prompt on every call. The content is built from the current {@link DatasetScope}
 * carried on the {@link RuntimeContext}, so each (user, conversation) pair sees only their own
 * data sources and knowledge bases — matching the TC-DataAgent pattern where schema context is
 * pre-loaded into the prompt rather than discovered via tool calls.
 */
public class DataDynamicContextMiddleware implements HarnessRuntimeMiddleware {

    private static final Logger log = LoggerFactory.getLogger(DataDynamicContextMiddleware.class);

    private final DataSourceRegistry registry;
    private final DatasetContextProvider contextProvider;

    public DataDynamicContextMiddleware(
            DataSourceRegistry registry, DatasetContextProvider contextProvider) {
        this.registry = registry;
        this.contextProvider = contextProvider;
    }

    @Override
    public Mono<String> onSystemPrompt(Agent agent, RuntimeContext ctx, String currentPrompt) {
        return Mono.fromCallable(
                        () -> {
                            String base = currentPrompt != null ? currentPrompt : "";
                            if (ctx == null) {
                                return base;
                            }

                            DatasetScope scope = ctx.get(DatasetScope.class);
                            String userId = scope != null ? scope.ownerId() : ctx.getUserId();
                            List<String> groupIds =
                                    scope != null && scope.hasGroupFilter()
                                            ? scope.groupIds()
                                            : null;

                            String dsOverview = buildDataSourcesOverview(userId, groupIds);
                            String kbOverview = buildKnowledgeBaseOverview(userId, groupIds);

                            if (dsOverview.isEmpty() && kbOverview.isEmpty()) {
                                return base;
                            }

                            StringBuilder sb = new StringBuilder();
                            if (!base.isEmpty()) {
                                sb.append(base);
                                if (!base.endsWith("\n")) {
                                    sb.append("\n");
                                }
                                sb.append("\n");
                            }
                            if (!dsOverview.isEmpty()) {
                                sb.append(dsOverview).append("\n\n");
                            }
                            if (!kbOverview.isEmpty()) {
                                sb.append(kbOverview).append("\n");
                            }
                            return sb.toString();
                        })
                .subscribeOn(Schedulers.boundedElastic());
    }

    // -----------------------------------------------------------------
    //  [DATA_SOURCES_OVERVIEW]
    // -----------------------------------------------------------------

    private String buildDataSourcesOverview(String userId, List<String> groupIds) {
        List<DataSource> sources = visibleSources(userId, groupIds);
        if (sources.isEmpty()) {
            return "";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("# [DATA_SOURCES_OVERVIEW]\n\n");
        sb.append("以下是当前会话可用的数据源：\n\n");

        for (DataSource ds : sources) {
            sb.append("- ").append(ds.label());
            if (ds.description() != null && !ds.description().isBlank()) {
                sb.append(" — ").append(ds.description());
            }
            sb.append("\n  source_id: `").append(ds.id()).append("`");
            String tableName = ds.properties() != null ? ds.properties().get("tableName") : null;
            if (tableName != null && !tableName.isBlank()) {
                sb.append(", 表名: `").append(tableName).append("`");
            }
            sb.append("\n");
        }
        return sb.toString().stripTrailing();
    }

    // -----------------------------------------------------------------
    //  [KNOWLEDGE_BASE_OVERVIEW]
    // -----------------------------------------------------------------

    private String buildKnowledgeBaseOverview(String userId, List<String> groupIds) {
        if (contextProvider == null) {
            return "";
        }
        String text = contextProvider.relationshipsText(userId, groupIds);
        if (text == null || text.isBlank()) {
            return "";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("# [KNOWLEDGE_BASE_OVERVIEW]\n\n");
        sb.append("以下是当前会话可用的知识库内容：\n\n");
        sb.append(text).append("\n");

        String terms = contextProvider.semanticTermsText();
        if (terms != null && !terms.isBlank()) {
            sb.append("\n## 业务术语（语义配置）\n");
            sb.append(terms).append("\n");
        }
        return sb.toString();
    }

    // -----------------------------------------------------------------
    //  Visibility — mirrors DataAgentToolkit#visible
    // -----------------------------------------------------------------

    private List<DataSource> visibleSources(String userId, List<String> groupIds) {
        List<DataSource> all = registry.list();
        if (userId == null) {
            return all.stream().filter(this::isGlobal).toList();
        }
        if (groupIds != null && !groupIds.isEmpty()) {
            Set<String> groupSet = new HashSet<>(groupIds);
            return all.stream()
                    .filter(
                            ds ->
                                    ds.properties() != null
                                            && groupSet.contains(ds.properties().get("groupId")))
                    .toList();
        }
        return all.stream()
                .filter(
                        ds ->
                                isGlobal(ds)
                                        || (ds.properties() != null
                                                && userId.equals(ds.properties().get("ownerId"))))
                .toList();
    }

    private boolean isGlobal(DataSource ds) {
        return ds.properties() == null || !ds.properties().containsKey("ownerId");
    }
}
