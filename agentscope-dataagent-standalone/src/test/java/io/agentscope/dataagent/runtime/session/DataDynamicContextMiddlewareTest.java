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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.dataagent.dataset.DatasetContextProvider;
import io.agentscope.dataagent.dataset.DatasetScope;
import io.agentscope.dataagent.dataset.MdlCatalog;
import io.agentscope.dataagent.tools.data.DataSource;
import io.agentscope.dataagent.tools.data.InMemoryDataSourceRegistry;
import io.agentscope.dataagent.web.persistence.jpa.DatasetGroupEntity;
import io.agentscope.dataagent.web.persistence.jpa.DatasetGroupRepository;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Locks the Wren-only dynamic context contract: only valid published manifests expose a lightweight
 * model/Cube directory, unavailable groups expose lifecycle guidance, and no physical source IDs,
 * table names, full columns or direct-query fallback instructions reach the prompt.
 */
class DataDynamicContextMiddlewareTest {

    // ------------------------------------------------------------------ unavailable rendering

    @Test
    void absentCatalogDoesNotExposePhysicalOverview() {
        var middleware = new DataDynamicContextMiddleware(registry(aliceDataset()), null);

        assertThat(prompt(middleware, new DatasetScope("alice"))).isEqualTo("BASE");
    }

    @Test
    void unpublishedGroupWithoutStateDoesNotExposePhysicalOverview() {
        MdlCatalog catalog = mock(MdlCatalog.class);
        when(catalog.load("gA")).thenReturn(Optional.empty());
        var middleware = new DataDynamicContextMiddleware(registry(aliceDataset()), null, catalog);

        assertThat(prompt(middleware, new DatasetScope("alice"))).isEqualTo("BASE");
    }

    @Test
    void failedGroupRendersUnavailableStateWithoutPhysicalHints() {
        MdlCatalog catalog = mock(MdlCatalog.class);
        DatasetGroupRepository groups = mock(DatasetGroupRepository.class);
        when(catalog.load("gA")).thenReturn(Optional.empty());
        when(groups.findById("gA")).thenReturn(Optional.of(group("FAILED")));
        var middleware =
                new DataDynamicContextMiddleware(registry(aliceDataset()), null, catalog, groups);

        assertThat(prompt(middleware, new DatasetScope("alice")))
                .contains("基础 MDL 初始化失败")
                .contains("当前不可问数")
                .doesNotContain("source_id", "ds_a_t1", "query_structured_data");
    }

    @Test
    void noVisibleSourcesLeavesPromptUntouched() {
        var middleware = new DataDynamicContextMiddleware(registry(bobDataset()), null);

        assertThat(prompt(middleware, new DatasetScope("alice"))).isEqualTo("BASE");
    }

    // ------------------------------------------------------------------ logical rendering

    @Test
    void publishedGroupRendersLogicalSectionWithoutPhysicalHints() {
        MdlCatalog catalog = mock(MdlCatalog.class);
        when(catalog.load("gA")).thenReturn(Optional.of(mdl()));
        var middleware = new DataDynamicContextMiddleware(registry(aliceDataset()), null, catalog);

        String out = prompt(middleware, new DatasetScope("alice"));

        assertThat(out)
                .startsWith(
                        "BASE\n\n# [DATA_SOURCES_OVERVIEW]\n\n"
                                + "## 知识库「订单分析」— 已发布语义模型（group_id: `gA`，版本 v3；"
                                + "wren 工具的 group_id 参数可直接填本知识库名称「订单分析」，无需复制长 ID）\n\n")
                .contains("wren_describe_model")
                .contains("**逻辑模型：**")
                .contains("- `订单` — 订单明细")
                .contains("**Cube：**")
                // member names are part of the directory so question wording maps onto Cube
                // measures/dimensions without an extra describe round trip
                .contains("- `销售Cube`（基础模型 `订单`；度量：销售额；维度：城市；时间维度：下单日期）")
                // empty member lists omit their segment entirely
                .contains("- `计数Cube`（基础模型 `订单`；度量：订单数）")
                .doesNotContain("；维度：）")
                .doesNotContain("；时间维度：）")
                // column-level details (descriptions, names, types) stay in describe only
                .doesNotContain("| 字段 |", "开通城市", "金额", "VARCHAR", "客户ID", "MANY_TO_ONE")
                .doesNotContain("以下是当前会话可用的数据源")
                .doesNotContain("source_id")
                .doesNotContain("ds_a_t1")
                .doesNotContain("ds_a");
    }

    @Test
    void publishedGroupInjectsCubeViewProjectionSqlRoutingOrder() {
        MdlCatalog catalog = mock(MdlCatalog.class);
        when(catalog.load("gA")).thenReturn(Optional.of(mdl()));
        var middleware = new DataDynamicContextMiddleware(registry(aliceDataset()), null, catalog);

        assertThat(prompt(middleware, new DatasetScope("alice")))
                .contains("问数路由（官方决策树）")
                .contains("Cube 成员能覆盖时优先用 wren_query_cube")
                .contains("已发布 View 能直接覆盖问题时优先用 wren_run_sql 按视图名直接查询")
                .contains("wren_describe_model(expand_relation_fields=true)")
                .contains("many 侧关联字段组")
                .contains("单一逻辑模型查询投影列让 Wren 自动 JOIN")
                .contains("语义资产都无法表达时使用其他逻辑 SQL")
                .contains("显式 JOIN 是最后兜底")
                // specs/025: official alignment — prefer wording, no mandatory gate
                .doesNotContain("不得从基础模型重建同等语义")
                .doesNotContain("时必须用 wren_query_cube");
    }

    @Test
    void dirtyGroupUsesPublishedDirectoryWithoutPhysicalFallback() {
        MdlCatalog catalog = mock(MdlCatalog.class);
        DatasetGroupRepository groups = mock(DatasetGroupRepository.class);
        when(catalog.load("gA")).thenReturn(Optional.of(mdl()));
        when(groups.findById("gA")).thenReturn(Optional.of(group("DIRTY")));
        DataSource uploadedAfterPublish = dataset("ds_new", "DS ds_new", "gA", "ds_new_t1");
        var middleware =
                new DataDynamicContextMiddleware(
                        registry(aliceDataset(), uploadedAfterPublish), null, catalog, groups);

        String out = prompt(middleware, new DatasetScope("alice"));

        assertThat(out)
                .contains("## 知识库「订单分析」— 已发布语义模型")
                .contains("当前查询使用已发布版本，草稿或数据变更尚未生效")
                .doesNotContain("source_id", "ds_new_t1", "query_structured_data");
    }

    @Test
    void groupFilteredScopeShowsOnlySelectedGroup() {
        MdlCatalog catalog = mock(MdlCatalog.class);
        when(catalog.load("gA")).thenReturn(Optional.of(mdl()));
        var middleware =
                new DataDynamicContextMiddleware(
                        registry(
                                aliceDataset(),
                                dataset("ds_other", "DS other", "gOther", "t_other")),
                        null,
                        catalog);

        String out = prompt(middleware, new DatasetScope("alice", List.of("gA")));

        assertThat(out)
                .startsWith("BASE\n\n# [DATA_SOURCES_OVERVIEW]\n\n## 知识库「订单分析」")
                // the out-of-scope group is neither rendered nor even loaded
                .doesNotContain("ds_other")
                .doesNotContain("以下是当前会话可用的数据源");
        verify(catalog, never()).load("gOther");
    }

    // ------------------------------------------------------------------ knowledge-base section

    @Test
    void knowledgeBaseOverviewStillRendersAlongsideLogicalSection() {
        DatasetContextProvider ctxProvider = mock(DatasetContextProvider.class);
        when(ctxProvider.relationshipsText(anyString(), any())).thenReturn("订单表与客户表通过客户ID关联");
        when(ctxProvider.semanticViewsText(List.of("gA")))
                .thenReturn(
                        "近30天用户行为分层：已按用户汇总并完成行为分层\n" + "  SQL: SELECT user_segment FROM 点击详情数据");
        MdlCatalog catalog = mock(MdlCatalog.class);
        when(catalog.load("gA")).thenReturn(Optional.of(mdl()));
        var middleware =
                new DataDynamicContextMiddleware(registry(aliceDataset()), ctxProvider, catalog);

        String out = prompt(middleware, new DatasetScope("alice", List.of("gA")));

        assertThat(out)
                .contains("# [DATA_SOURCES_OVERVIEW]")
                .contains("# [KNOWLEDGE_BASE_OVERVIEW]")
                .contains("订单表与客户表通过客户ID关联")
                .contains("## 语义视图（工程 views/ 文件，wren_run_sql 可直接按视图名查询）")
                .contains("视图口径已经建模审阅")
                .contains("近30天用户行为分层")
                .contains("SQL: SELECT user_segment FROM 点击详情数据")
                .doesNotContain("不得从基础模型重建同等语义");
    }

    @Test
    void groupScopedBusinessRulesRenderWithoutDocumentsOrVisibleSources() {
        DatasetContextProvider ctxProvider = mock(DatasetContextProvider.class);
        when(ctxProvider.semanticBusinessRulesText("alice", List.of("gA")))
                .thenReturn("- 有效订单：默认排除 deleted_at 非空的订单");
        var middleware = new DataDynamicContextMiddleware(registry(bobDataset()), ctxProvider);

        String out = prompt(middleware, new DatasetScope("alice", List.of("gA")));

        assertThat(out)
                .doesNotContain("# [DATA_SOURCES_OVERVIEW]")
                .contains("# [KNOWLEDGE_BASE_OVERVIEW]")
                .contains("## 业务规则（当前知识库）")
                .contains("默认排除 deleted_at 非空的订单");
        verify(ctxProvider).semanticBusinessRulesText("alice", List.of("gA"));
    }

    // ------------------------------------------------------------------ fixtures

    private static String prompt(DataDynamicContextMiddleware middleware, DatasetScope scope) {
        RuntimeContext ctx =
                RuntimeContext.builder()
                        .userId(scope.ownerId())
                        .sessionId("s")
                        .put(DatasetScope.class, scope)
                        .build();
        return middleware.onSystemPrompt(null, ctx, "BASE").block();
    }

    private static InMemoryDataSourceRegistry registry(DataSource... sources) {
        return new InMemoryDataSourceRegistry(List.of(sources));
    }

    private static DataSource aliceDataset() {
        return dataset("ds_a", "DS ds_a", "gA", "ds_a_t1");
    }

    private static DataSource bobDataset() {
        DataSource ds = dataset("ds_bob", "DS bob", "gBob", "ds_bob_t1");
        return new DataSource(
                ds.id(),
                ds.label(),
                ds.description(),
                ds.kind(),
                ds.urlHint(),
                ds.tags(),
                Map.of(
                        "jdbcUrl", "jdbc:h2:mem:unused",
                        "ownerId", "bob",
                        "groupId", "gBob",
                        "tableName", "ds_bob_t1"));
    }

    private static DataSource dataset(String id, String label, String groupId, String table) {
        return new DataSource(
                id,
                label,
                "数据集 " + id,
                "jdbc",
                null,
                List.of("user-dataset"),
                Map.of(
                        "jdbcUrl",
                        "jdbc:h2:mem:unused",
                        "ownerId",
                        "alice",
                        "groupId",
                        groupId,
                        "tableName",
                        table));
    }

    private static DatasetGroupEntity group(String state) {
        DatasetGroupEntity group = new DatasetGroupEntity("gA", "alice", "订单分析", null);
        group.setMdlState(state);
        group.setMdlVersion("FAILED".equals(state) ? 0 : 3);
        return group;
    }

    private static MdlCatalog.GroupMdl mdl() {
        MdlCatalog.Model order =
                new MdlCatalog.Model(
                        "订单",
                        "ds_a",
                        "订单明细",
                        List.of(
                                new MdlCatalog.Column("城市", "VARCHAR", "开通城市", false, null),
                                new MdlCatalog.Column("金额", "DECIMAL(18,6)", null, false, null)));
        MdlCatalog.Relation relation =
                new MdlCatalog.Relation("订单", "客户", "MANY_TO_ONE", "订单.客户ID = 客户.客户ID");
        MdlCatalog.Cube cube =
                new MdlCatalog.Cube(
                        "销售Cube",
                        "订单",
                        "销售指标",
                        List.of(new MdlCatalog.Member("销售额", "SUM(金额)", "decimal", "成交金额")),
                        List.of(new MdlCatalog.Member("城市", "城市", "varchar", "开通城市")),
                        List.of(new MdlCatalog.Member("下单日期", "下单日期", "date", "下单时间")));
        MdlCatalog.Cube countCube =
                new MdlCatalog.Cube(
                        "计数Cube",
                        "订单",
                        "行数指标",
                        List.of(new MdlCatalog.Member("订单数", "COUNT(*)", "integer", "行数")),
                        List.of(),
                        List.of());
        return new MdlCatalog.GroupMdl(
                "gA", "订单分析", 3, List.of(order), List.of(relation), List.of(cube, countCube));
    }
}
