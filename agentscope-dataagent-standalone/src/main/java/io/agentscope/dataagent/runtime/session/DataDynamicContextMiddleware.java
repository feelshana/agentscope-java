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
import io.agentscope.dataagent.dataset.MdlCatalog;
import io.agentscope.dataagent.tools.data.DataSource;
import io.agentscope.dataagent.tools.data.DataSourceRegistry;
import io.agentscope.dataagent.web.persistence.jpa.DatasetGroupEntity;
import io.agentscope.dataagent.web.persistence.jpa.DatasetGroupRepository;
import io.agentscope.harness.agent.middleware.HarnessRuntimeMiddleware;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
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
 *
 * <p>The data-source overview exposes only Wren-queryable published manifests. It keeps a compact
 * model/Cube directory in the prompt and directs field-level discovery to {@code
 * wren_describe_model}; physical table names and direct-query fallback instructions are omitted.
 */
public class DataDynamicContextMiddleware implements HarnessRuntimeMiddleware {

    private static final Logger log = LoggerFactory.getLogger(DataDynamicContextMiddleware.class);

    private final DataSourceRegistry registry;
    private final DatasetContextProvider contextProvider;
    private final MdlCatalog mdlCatalog;
    private final DatasetGroupRepository groupRepository;

    public DataDynamicContextMiddleware(
            DataSourceRegistry registry, DatasetContextProvider contextProvider) {
        this(registry, contextProvider, null, null);
    }

    public DataDynamicContextMiddleware(
            DataSourceRegistry registry,
            DatasetContextProvider contextProvider,
            MdlCatalog mdlCatalog) {
        this(registry, contextProvider, mdlCatalog, null);
    }

    public DataDynamicContextMiddleware(
            DataSourceRegistry registry,
            DatasetContextProvider contextProvider,
            MdlCatalog mdlCatalog,
            DatasetGroupRepository groupRepository) {
        this.registry = registry;
        this.contextProvider = contextProvider;
        this.mdlCatalog = mdlCatalog;
        this.groupRepository = groupRepository;
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

        Map<String, MdlCatalog.GroupMdl> logical = new LinkedHashMap<>();
        Set<String> unavailableGroupIds = new java.util.LinkedHashSet<>();
        for (DataSource ds : sources) {
            String groupId = ds.properties() == null ? null : ds.properties().get("groupId");
            if (groupId == null || mdlCatalog == null || logical.containsKey(groupId)) {
                continue;
            }
            MdlCatalog.GroupMdl mdl = mdlCatalog.load(groupId).orElse(null);
            if (mdl != null) {
                logical.put(groupId, mdl);
            } else {
                unavailableGroupIds.add(groupId);
            }
        }

        if (logical.isEmpty() && unavailableGroupIds.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder("# [DATA_SOURCES_OVERVIEW]\n\n");
        boolean wrote = false;
        for (MdlCatalog.GroupMdl mdl : logical.values()) {
            if (wrote) {
                sb.append("\n\n");
            }
            sb.append(buildLogicalSection(mdl));
            if (groupRepository != null) {
                groupRepository
                        .findById(mdl.groupId())
                        .filter(group -> userId != null && userId.equals(group.getOwnerId()))
                        .filter(group -> "DIRTY".equals(group.getMdlState()))
                        .ifPresent(ignored -> sb.append("\n*(提示：当前查询使用已发布版本，草稿或数据变更尚未生效。)*\n"));
            }
            wrote = true;
        }
        for (String groupId : unavailableGroupIds) {
            DatasetGroupEntity group =
                    groupRepository == null
                            ? null
                            : groupRepository
                                    .findById(groupId)
                                    .filter(value -> userId.equals(value.getOwnerId()))
                                    .orElse(null);
            if (group == null) {
                continue;
            }
            if (wrote) {
                sb.append("\n\n");
            }
            sb.append("## 知识库「")
                    .append(group.getName())
                    .append("」（group_id: `")
                    .append(groupId)
                    .append("`）\n\n")
                    .append(unavailableState(group));
            wrote = true;
        }
        return wrote ? sb.toString().stripTrailing() : "";
    }

    /**
     * Lightweight directory for one published group. Field and relation details are fetched on
     * demand through {@code wren_describe_model}. Cube rows carry member names plus measure
     * expressions and a short description so the model can judge coverage (measure, group-by
     * dimension and time granularity all present) straight from the directory instead of
     * word-matching the question against cube/member names alone.
     */
    private static String buildLogicalSection(MdlCatalog.GroupMdl g) {
        StringBuilder sb = new StringBuilder();
        sb.append("## 知识库「").append(g.groupName()).append("」— 已发布语义模型");
        sb.append("（group_id: `")
                .append(g.groupId())
                .append("`，版本 v")
                .append(g.version())
                .append("；wren 工具的 group_id 参数可直接填本知识库名称「")
                .append(g.groupName())
                .append("」，无需复制长 ID）\n\n");
        sb.append(
                "问数路由（官方决策树）：聚合指标问题先核对 Cube 清单，Cube 成员能覆盖时优先用 wren_query_cube"
                        + "（引擎确定性编译聚合，错误率更低）——覆盖判定：问题的度量、分组维度、时间粒度需全部落在"
                        + " Cube 成员上；「按 X 的排名 / TOP-N」要求 X 是 Cube 的维度成员（dimensions=[X] + order_by"
                        + " 该度量 + limit），Cube 缺该维度即不覆盖，改按 View / 模型 SQL GROUP BY X 排名——禁止不分组"
                        + "而只对度量排序取 TOP-N（那只是对聚合行排序，不是实体排名）；已发布 View 能直接覆盖问题时优先用 wren_run_sql"
                        + " 按视图名直接查询（视图口径已经建模审阅）；跨模型属性先用"
                        + " wren_describe_model(expand_relation_fields=true) 展开 many"
                        + " 侧关联字段组，并以单一逻辑模型查询投影列让 Wren 自动 JOIN；语义资产都无法表达时使用其他逻辑"
                        + " SQL，显式 JOIN 是最后兜底。\n\n");
        sb.append("**逻辑模型：**\n");
        for (MdlCatalog.Model m : g.models()) {
            sb.append("- `").append(m.name()).append("`");
            if (m.derived()) {
                sb.append("（派生模型，口径 SQL 已建模审阅，可直接按模型名查询）");
            }
            String desc = flat(m.description(), 80);
            if (desc != null) {
                sb.append(" — ").append(desc);
            }
            sb.append('\n');
        }
        if (!g.cubes().isEmpty()) {
            sb.append("\n**Cube：**\n");
            for (MdlCatalog.Cube c : g.cubes()) {
                sb.append("- `")
                        .append(c.name())
                        .append("`（基础模型 `")
                        .append(c.baseModel())
                        .append("`）\n");
                if (!c.measures().isEmpty()) {
                    sb.append("  - 度量：").append(memberDefs(c.measures())).append('\n');
                }
                StringBuilder dims = new StringBuilder();
                if (!c.dimensions().isEmpty()) {
                    dims.append("维度：").append(memberNames(c.dimensions()));
                }
                if (!c.timeDimensions().isEmpty()) {
                    if (dims.length() > 0) {
                        dims.append("；");
                    }
                    dims.append("时间维度：").append(memberNames(c.timeDimensions()));
                }
                if (dims.length() > 0) {
                    sb.append("  - ").append(dims).append('\n');
                }
                String desc = flat(c.description(), 100);
                if (desc != null) {
                    sb.append("  - 说明：").append(desc).append('\n');
                }
            }
        }
        return sb.toString();
    }

    /** Joined member names for the Cube directory rows; member details stay in describe. */
    private static String memberNames(List<MdlCatalog.Member> members) {
        return members.stream().map(MdlCatalog.Member::name).collect(Collectors.joining("、"));
    }

    /**
     * Measure rows show the aggregation form ({@code name=expression}) so the model can judge
     * whether a question's metric is really covered; dimensions keep bare names.
     */
    private static String memberDefs(List<MdlCatalog.Member> members) {
        return members.stream()
                .map(
                        m ->
                                m.expression() == null || m.expression().isBlank()
                                        ? m.name()
                                        : m.name() + "=" + m.expression())
                .collect(Collectors.joining("、"));
    }

    /** One-line, length-capped prompt fragment (descriptions may contain newlines). */
    private static String flat(String text, int max) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String s = text.replace("\r", " ").replace("\n", " ").trim();
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    // -----------------------------------------------------------------
    //  [KNOWLEDGE_BASE_OVERVIEW]
    // -----------------------------------------------------------------

    private String buildKnowledgeBaseOverview(String userId, List<String> groupIds) {
        if (contextProvider == null) {
            return "";
        }
        String text = contextProvider.relationshipsText(userId, groupIds);
        String terms = contextProvider.semanticTermsText(groupIds);
        String views = contextProvider.semanticViewsText(groupIds);
        String rules = contextProvider.semanticBusinessRulesText(userId, groupIds);
        if (blank(text) && blank(terms) && blank(views) && blank(rules)) {
            return "";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("# [KNOWLEDGE_BASE_OVERVIEW]\n\n");
        sb.append("以下是当前会话可用的知识库内容：\n");
        if (!blank(text)) {
            sb.append('\n').append(text).append("\n");
        }

        if (terms != null && !terms.isBlank()) {
            sb.append("\n## 业务术语（语义配置）\n");
            sb.append(terms).append("\n");
        }
        if (views != null && !views.isBlank()) {
            sb.append("\n## 语义视图（工程 views/ 文件，wren_run_sql 可直接按视图名查询）\n");
            sb.append("视图口径已经建模审阅，能直接覆盖问题时优先按视图名查询。\n");
            sb.append(views).append("\n");
        }
        if (rules != null && !rules.isBlank()) {
            sb.append("\n## 业务规则（当前知识库）\n");
            sb.append(rules).append("\n");
        }
        return sb.toString();
    }

    private static String unavailableState(DatasetGroupEntity group) {
        return switch (String.valueOf(group.getMdlState())) {
            case "INITIALIZING" -> "基础 MDL 正在初始化，当前暂不可问数。";
            case "FAILED" -> "基础 MDL 初始化失败，当前不可问数，请在建模页重试。";
            default -> "尚无有效发布版本，当前不可问数。";
        };
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    // -----------------------------------------------------------------
    //  Tenant and knowledge-base visibility
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
