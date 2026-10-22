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
 * official describe_model; physical table names and direct-query fallback instructions are omitted.
 */
public class DataDynamicContextMiddleware implements HarnessRuntimeMiddleware {
    private io.agentscope.dataagent.web.session.ConversationScopeRegistry conversationScopes;

    public DataDynamicContextMiddleware withConversationScopes(
            io.agentscope.dataagent.web.session.ConversationScopeRegistry scopes) {
        conversationScopes = scopes;
        return this;
    }

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
                            if (groupIds == null && conversationScopes != null)
                                groupIds = conversationScopes.get(userId, ctx.getSessionId());
                            if (contextProvider != null)
                                groupIds = contextProvider.authorizedGroupIds(userId, groupIds);

                            if (scope != null && groupIds != null && groupIds.isEmpty()) {
                                return base
                                        + "\n\n"
                                        + "[CHAT_KNOWLEDGE_SCOPE]\n"
                                        + "本轮未选择知识库，仅进行普通聊天。"
                                        + "不要查询企业数据库、知识库或调用问数工具；需要业务数据时请提示用户在输入框选择知识库。历史回答可以作为聊天上下文，但不能据此继续查询旧知识库。";
                            }

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
                            sb.append(
                                    "\n"
                                        + "[CURRENT_TURN_SCOPE] 本轮查询只能使用当前选中的知识库。保留历史聊天帮助理解追问，但旧库、旧"
                                        + " SQL 和旧结果不能扩大本轮查询范围；跨库口径不确定时先澄清。\n");
                            if (groupIds != null && groupIds.size() > 1) {
                                sb.append(
                                        "\n[CHAT_KNOWLEDGE_SCOPE]\n用户选择了多个知识库。根据问题和各库模型说明，"
                                                + "自行判断需要查询哪些库，并按对应 group_id 分别调用工具，必要时综合多个库的结果。"
                                                + "同名知识库必须使用完整 group_id；不要把不同知识库的模型直接写入同一条 SQL，"
                                                + "不要假设指标口径和关联键一致，回答应注明采用的知识库来源。\n");
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
                        .filter(group -> "DIRTY".equals(group.getMdlState()))
                        .ifPresent(ignored -> sb.append("\n*(提示：当前查询使用已发布版本，草稿或数据变更尚未生效。)*\n"));
            }
            wrote = true;
        }
        for (String groupId : unavailableGroupIds) {
            DatasetGroupEntity group =
                    groupRepository == null ? null : groupRepository.findById(groupId).orElse(null);
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
     * demand through the official {@code describe_model}. Cube rows carry member names plus measure
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
                "问数统一通过 官方 run_sql 或 query_cube 查询已发布逻辑模型、视图和指标；先调用官方"
                        + " recall_queries 核对口径，再按需调用 describe_model 或"
                        + " describe_cube。年份是时间筛选，不代表按年分组；去重客户数在所需时间范围内 COUNT DISTINCT"
                        + " 客户身份，不能累加月度去重人数。复杂 SQL 先规划，禁止猜测派生列。\n\n");
        sb.append(
                "遇到常用指标或复杂口径时，按需通过官方 get_instructions 和 recall_queries"
                    + " 读取本库已发布规则与问答，核对业务定义后重新查询；不能复用旧数值。销售额/净营收等歧义未被规则明确时先澄清。月份字符串 yyyy-MM"
                    + " 与时间戳应按同一类型/格式对齐，MySQL 不使用 STRFTIME。\n"
                    + "用户仅确认环比时，不得擅自增加两个月都有营收、只保留增长客户等限制。按既定口径保留统计对象，前月为零时百分比标为不可计算/新增，本月为零可计算下降；特殊筛选需明确说明并确认。空结果不能直接推断原因，先核查实际分布。全年月报说明缺失月份的处理，不能把未返回月份自动当作零。所有过程说明与回答使用简体中文。\n\n");
        sb.append(
                "Top N 或单条结果优先使用官方 query_cube 的 order_by 与 limit；没有合适 Cube 时使用 run_sql 的标准"
                        + " SQL（可按官方语法使用 LIMIT/OFFSET）。不要把 View 传给 describe_model；View 用"
                        + " get_mdl/describe_schema，Cube 用 describe_cube。\n\n");
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
            sb.append("\n## 语义视图（工程 views/ 文件，官方 run_sql 可直接按视图名查询）\n");
            sb.append("视图口径已经建模审阅，能直接覆盖问题时优先通过官方 run_sql 按视图名查询。\n");
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
        if (groupIds != null) {
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
