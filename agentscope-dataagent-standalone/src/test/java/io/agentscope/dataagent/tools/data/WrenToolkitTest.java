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
package io.agentscope.dataagent.tools.data;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.tool.Tool;
import io.agentscope.dataagent.dataset.DatasetException;
import io.agentscope.dataagent.dataset.DatasetGroupService;
import io.agentscope.dataagent.dataset.DatasetScope;
import io.agentscope.dataagent.dataset.MdlCatalog;
import io.agentscope.dataagent.runtime.wren.WrenQueryGateway;
import io.agentscope.dataagent.runtime.wren.WrenQueryGateway.WrenCallResult;
import io.agentscope.dataagent.web.persistence.jpa.DatasetGroupEntity;
import io.agentscope.harness.agent.filesystem.model.FileUploadResponse;
import io.agentscope.harness.agent.filesystem.sandbox.AbstractSandboxFilesystem;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Locks the wren-channel routing contract (specs/010 M3): a never-published group is rejected
 * with a guided error (acceptance #9), a foreign or out-of-scope group is indistinguishable from
 * a missing one (multi-tenant), DIRTY groups keep serving the previous publish with an inline
 * note (ADR 0018 D10) and the engine's own diagnostics are relayed verbatim for self-correction.
 */
class WrenToolkitTest {

    private static final DatasetScope ALICE = new DatasetScope("alice");

    private static final String ROWS_PAYLOAD =
            "{\"columns\":[\"城市\",\"销售额\"],\"rows\":[{\"城市\":\"杭州\",\"销售额\":120},"
                    + "{\"城市\":\"宁波\",\"销售额\":80}],\"row_count\":2,\"truncated\":false}";

    private DatasetGroupService groupService;
    private MdlCatalog mdlCatalog;
    private RecordingGateway gateway;
    private WrenToolkit toolkit;

    @BeforeEach
    void setUp() {
        groupService = mock(DatasetGroupService.class);
        mdlCatalog = mock(MdlCatalog.class);
        gateway = new RecordingGateway();
        toolkit = new WrenToolkit(gateway, groupService, null, mdlCatalog);
    }

    // ------------------------------------------------------------------ NONE / multi-tenant

    @Test
    void unpublishedGroupReturnsGuidedError() {
        stubGroup("NONE");

        String out = toolkit.wrenRunSql(ALICE, null, "gA", "SELECT * FROM 订单表", null, null);

        assertThat(out).startsWith("error:");
        assertThat(out).contains("没有有效的已发布 MDL").contains("基础 MDL 初始化");
        assertThat(gateway.tools).isEmpty();
    }

    @Test
    void foreignGroupIsIndistinguishableFromMissing() {
        DatasetGroupEntity own = new DatasetGroupEntity("gA", "alice", "订单分析", null);
        when(groupService.listGroups("alice")).thenReturn(List.of(own));

        assertThat(toolkit.wrenRunSql(ALICE, null, "gB", "SELECT 1", null, null))
                .isEqualTo("error: 未知或无权访问的知识库 'gB'");
        assertThat(
                        toolkit.wrenQueryCube(
                                ALICE,
                                null,
                                "gB",
                                "c",
                                List.of("m"),
                                null,
                                null,
                                null,
                                null,
                                null,
                                null))
                .isEqualTo("error: 未知或无权访问的知识库 'gB'");
        assertThat(gateway.tools).isEmpty();
    }

    @Test
    void conversationGroupFilterBlocksOtherGroups() {
        DatasetScope scoped = new DatasetScope("alice", List.of("gOther"));
        DatasetGroupEntity own = new DatasetGroupEntity("gA", "alice", "订单分析", null);
        when(groupService.listGroups("alice")).thenReturn(List.of(own));

        // The KB exists for the owner, but the conversation selected another one.
        assertThat(toolkit.wrenRunSql(scoped, null, "gA", "SELECT 1", null, null))
                .isEqualTo("error: 未知或无权访问的知识库 'gA'");
        assertThat(gateway.tools).isEmpty();
    }

    /**
     * Name addressing (typo incident 2026-10-05 LLM.log): the model copied a 36-char group_id
     * from the injected directory with a one-character error and got a permission-flavoured
     * rejection. The parameter now also accepts the short KB name shown in the directory title.
     */
    @Test
    void groupIdAcceptsKnowledgeBaseName() {
        stubGroup("PUBLISHED");

        String out = toolkit.wrenRunSql(ALICE, null, "订单分析", "SELECT 1", null, null);

        assertThat(out).doesNotStartWith("error:");
        assertThat(gateway.groupIds).containsExactly("gA");
        assertThat(toolkit.wrenDescribeModel(ALICE, null, "订单分析", List.of("订单")))
                .doesNotStartWith("error:")
                .contains("已发布语义模型详情");
    }

    @Test
    void knowledgeBaseNameResolvesOnlyWithinVisibleGroups() {
        stubGroup("PUBLISHED");

        // A name outside alice's own groups stays indistinguishable from a missing KB.
        assertThat(toolkit.wrenRunSql(ALICE, null, "别人的库", "SELECT 1", null, null))
                .isEqualTo("error: 未知或无权访问的知识库 '别人的库'");

        // The conversation-level group filter also gates name resolution.
        DatasetScope scoped = new DatasetScope("alice", List.of("gOther"));
        assertThat(toolkit.wrenRunSql(scoped, null, "订单分析", "SELECT 1", null, null))
                .isEqualTo("error: 未知或无权访问的知识库 '订单分析'");
        assertThat(gateway.tools).isEmpty();
    }

    @Test
    void blankGroupIdAndMissingScopeAreGuided() {
        assertThat(toolkit.wrenRunSql(ALICE, null, " ", "SELECT 1", null, null))
                .startsWith("error: group_id 不能为空");
        assertThat(toolkit.wrenRunSql(null, null, "gA", "SELECT 1", null, null))
                .isEqualTo("error: 无法确定租户上下文");
    }

    @Test
    void bakedRuntimeContextResolvesOwner() {
        stubGroup("PUBLISHED");
        RuntimeContext baked = RuntimeContext.builder().userId("alice").sessionId("s").build();

        // Out-of-band tool execution supplies userId only (no typed DatasetScope).
        assertThat(toolkit.wrenRunSql(null, baked, "gA", "SELECT 1", null, null))
                .doesNotStartWith("error:");
        verify(groupService).listGroups("alice");
    }

    // ------------------------------------------------------------------ run_sql happy paths

    @Test
    void publishedGroupRunsSqlAndRendersRows() {
        stubGroup("PUBLISHED");
        gateway.result = new WrenCallResult(true, ROWS_PAYLOAD);

        String out =
                toolkit.wrenRunSql(
                        ALICE,
                        null,
                        "gA",
                        "SELECT 城市, SUM(销售额) AS 销售额 FROM 订单表 GROUP BY 城市",
                        "各城市销售额",
                        500);

        assertThat(out)
                .contains("## wren 语义查询结果")
                .contains("订单分析")
                .contains("group_id: `gA`")
                .contains("各城市销售额")
                .contains("| 城市 | 销售额 |")
                .contains("| 杭州 | 120 |")
                .contains("| 宁波 | 80 |");
        assertThat(gateway.tools).containsExactly("run_sql");
        assertThat(gateway.args.get(0))
                .containsEntry("limit", 500)
                .containsEntry("sql", "SELECT 城市, SUM(销售额) AS 销售额 FROM 订单表 GROUP BY 城市");
    }

    @Test
    void dirtyGroupServesPreviousPublishWithNote() {
        stubGroup("DIRTY");
        gateway.result = new WrenCallResult(true, ROWS_PAYLOAD);

        String out = toolkit.wrenRunSql(ALICE, null, "gA", "SELECT 城市 FROM 订单表", null, null);

        assertThat(out).doesNotStartWith("error:");
        assertThat(out).contains("上一次成功发布的 MDL 快照").doesNotContain("query_structured_data");
        assertThat(gateway.tools).containsExactly("run_sql");
    }

    @Test
    void runSqlRejectsNonSelectAndNegativeLimit() {
        stubGroup("PUBLISHED");

        assertThat(toolkit.wrenRunSql(ALICE, null, "gA", "DELETE FROM 订单表", null, null))
                .isEqualTo("error: 只允许 SELECT / WITH 语句");
        assertThat(toolkit.wrenRunSql(ALICE, null, "gA", "SELECT 1", null, -1))
                .isEqualTo("error: limit 不能为负数");
        assertThat(gateway.tools).isEmpty();
    }

    /**
     * wrenai's mysql connector appends its own row limit to the SQL tail (_apply_limit, ADR
     * 0021); an explicit LIMIT/OFFSET in the SQL would stack into a 1064 syntax error, so the
     * gate must refuse it before the subprocess is ever reached.
     */
    @Test
    void runSqlRejectsExplicitLimitOrOffsetClauses() {
        stubGroup("PUBLISHED");

        assertThat(toolkit.wrenRunSql(ALICE, null, "gA", "SELECT * FROM 订单表 LIMIT 50", null, null))
                .startsWith("error: SQL 中禁止显式 LIMIT/OFFSET 子句")
                .contains("limit 参数");
        assertThat(
                        toolkit.wrenRunSql(
                                ALICE,
                                null,
                                "gA",
                                "WITH t AS (SELECT * FROM 订单表) SELECT * FROM t LIMIT 10 OFFSET 5",
                                null,
                                null))
                .startsWith("error: SQL 中禁止显式 LIMIT/OFFSET 子句");
        assertThat(gateway.tools).isEmpty();
    }

    @Test
    void matchingPublishedViewAllowsDirectViewQuery() {
        stubGroup("PUBLISHED");

        String out =
                toolkit.wrenRunSql(
                        ALICE,
                        null,
                        "gA",
                        "SELECT user_segment, SUM(visit_count) FROM \"近30天用户行为分层\" GROUP BY"
                                + " user_segment",
                        "对比低频用户与高频用户的行为偏好",
                        null);

        assertThat(out).doesNotStartWith("error:");
        assertThat(gateway.tools).containsExactly("run_sql");
    }

    @Test
    void unrelatedBaseModelQueryIsNotBlockedByPublishedView() {
        stubGroup("PUBLISHED");

        String out =
                toolkit.wrenRunSql(
                        ALICE,
                        null,
                        "gA",
                        "SELECT 城市, SUM(销售额) FROM 订单表 GROUP BY 城市",
                        "各城市销售额",
                        null);

        assertThat(out).doesNotStartWith("error:");
        assertThat(gateway.tools).containsExactly("run_sql");
    }

    @Test
    void truncationAndEmptyResultsAreAnnotated() {
        stubGroup("PUBLISHED");
        gateway.result =
                new WrenCallResult(
                        true,
                        "{\"columns\":[\"n\"],\"rows\":[],\"row_count\":0,\"truncated\":false}");
        assertThat(toolkit.wrenRunSql(ALICE, null, "gA", "SELECT 1 AS n", null, null))
                .contains("*(0 rows returned)*");

        gateway.result =
                new WrenCallResult(
                        true,
                        "{\"columns\":[\"n\"],\"rows\":[{\"n\":1}],\"row_count\":1,\"truncated\":true}");
        assertThat(toolkit.wrenRunSql(ALICE, null, "gA", "SELECT 1 AS n", null, null))
                .contains("结果已截断");
    }

    @Test
    void unexpectedPayloadShapeFallsBackToRawJson() {
        stubGroup("PUBLISHED");
        gateway.result = new WrenCallResult(true, "{\"sql\":\"SELECT 1\"}");

        assertThat(toolkit.wrenRunSql(ALICE, null, "gA", "SELECT 1", null, null))
                .contains("原始 JSON");
    }

    // ------------------------------------------------------------------ failure relay

    @Test
    void engineRejectionIsRelayedVerbatim() {
        stubGroup("PUBLISHED");
        gateway.result =
                new WrenCallResult(false, "Error executing tool run_sql: Model '订单表' not found.");

        String out = toolkit.wrenRunSql(ALICE, null, "gA", "SELECT * FROM 订单表", null, null);

        assertThat(out).startsWith("error: wren 语义引擎拒绝了该查询").contains("Model '订单表' not found.");
    }

    @Test
    void transportFailureBecomesErrorStringNotException() {
        stubGroup("PUBLISHED");
        gateway.failure = new DatasetException("启动 wren 语义引擎失败（可执行文件「wren」）：not found", 503);

        assertThat(toolkit.wrenRunSql(ALICE, null, "gA", "SELECT 1", null, null))
                .startsWith("error: 启动 wren 语义引擎失败");
    }

    // ------------------------------------------------------------------ query_cube

    @Test
    void cubeRequiresMeasuresAndForwardsStructuredArguments() {
        stubGroup("PUBLISHED");

        assertThat(
                        toolkit.wrenQueryCube(
                                ALICE, null, "gA", "销售Cube", null, null, null, null, null, null,
                                null))
                .isEqualTo("error: measures 至少需要 1 个度量");

        gateway.result = new WrenCallResult(true, ROWS_PAYLOAD);
        String out =
                toolkit.wrenQueryCube(
                        ALICE,
                        null,
                        "gA",
                        "销售Cube",
                        List.of("销售额"),
                        List.of("城市"),
                        "下单日期:month",
                        List.of("状态:eq:已完成"),
                        List.of("销售额:desc"),
                        10,
                        5);

        assertThat(out)
                .contains("## wren Cube 查询结果")
                .contains("**Cube：** 销售Cube")
                .contains("**度量：** 销售额")
                .contains("**时间维度：** 下单日期:month")
                .contains("**筛选：** 状态:eq:已完成");
        assertThat(gateway.tools).containsExactly("query_cube");
        Map<String, Object> args = gateway.args.get(0);
        assertThat(args)
                .containsEntry("cube", "销售Cube")
                .containsEntry("time_dimension", "下单日期:month")
                .containsEntry("limit", 10)
                .containsEntry("offset", 5);
        assertThat(args.get("measures")).isEqualTo(List.of("销售额"));
        assertThat(args.get("dimensions")).isEqualTo(List.of("城市"));
        assertThat(args.get("order_by")).isEqualTo(List.of("销售额:desc"));
    }

    @Test
    void cubeRejectsNegativeOffset() {
        stubGroup("PUBLISHED");

        assertThat(
                        toolkit.wrenQueryCube(
                                ALICE,
                                null,
                                "gA",
                                "销售Cube",
                                List.of("销售额"),
                                null,
                                null,
                                null,
                                null,
                                null,
                                -1))
                .isEqualTo("error: offset 不能为负数");
    }

    /**
     * wren-core compiles dateRange with an exclusive end ({@code >= start AND < end}); the
     * model passes inclusive ranges (SQL BETWEEN intuition), so a same-day spec must be
     * widened by one granularity unit or the query silently returns zero rows.
     */
    @Test
    void sameEndendTimeDimensionIsWidenedToOneGranularityUnit() {
        stubGroup("PUBLISHED");

        toolkit.wrenQueryCube(
                ALICE,
                null,
                "gA",
                "访问日志分析",
                List.of("访问次数"),
                null,
                "访问日期:day:2026-09-29,2026-09-29",
                null,
                null,
                10,
                null);
        assertThat(gateway.args.get(0))
                .containsEntry("time_dimension", "访问日期:day:2026-09-29,2026-09-30");

        toolkit.wrenQueryCube(
                ALICE,
                null,
                "gA",
                "访问日志分析",
                List.of("访问次数"),
                null,
                "访问月份:month:2026-09-01,2026-09-01",
                null,
                null,
                null,
                null);
        assertThat(gateway.args.get(1))
                .containsEntry("time_dimension", "访问月份:month:2026-09-01,2026-10-01");

        // Non-calendar granularity with datetime endpoints passes through untouched.
        toolkit.wrenQueryCube(
                ALICE,
                null,
                "gA",
                "访问日志分析",
                List.of("访问次数"),
                null,
                "访问日期:hour:2026-09-29 10:00,2026-09-29 10:00",
                null,
                null,
                null,
                null);
        assertThat(gateway.args.get(2))
                .containsEntry("time_dimension", "访问日期:hour:2026-09-29 10:00,2026-09-29 10:00");
    }

    @Test
    void distinctEndTimeDimensionNormalizesGranularityCase() {
        stubGroup("PUBLISHED");

        toolkit.wrenQueryCube(
                ALICE,
                null,
                "gA",
                "访问日志分析",
                List.of("访问次数"),
                null,
                "访问日期:DAY:2026-09-29,2026-09-30",
                null,
                null,
                null,
                null);

        assertThat(gateway.args.get(0))
                .containsEntry("time_dimension", "访问日期:day:2026-09-29,2026-09-30");
    }

    /**
     * wren-core validates order-by members against the raw names selected by the query
     * (measures + dimensions + time_dimensions); the compiled SQL only presents the time bucket
     * as {@code <name>__<granularity>} in the SELECT list. The model, primed by an alias seen in
     * a previous result header (for example {@code order_time__month}), writes the alias into
     * order_by and the engine rejects the whole query — the gateway rewrites the exact
     * output-alias shape back to the raw member name before forwarding.
     */
    @Test
    void orderByTimeDimensionOutputAliasIsRewrittenToRawName() {
        stubGroup("PUBLISHED");

        toolkit.wrenQueryCube(
                ALICE,
                null,
                "gA",
                "访问日志分析",
                List.of("访问次数"),
                null,
                "访问日期:month",
                null,
                List.of("访问日期__month:desc", "访问次数:asc"),
                10,
                null);

        assertThat(gateway.args.get(0).get("order_by")).isEqualTo(List.of("访问日期:desc", "访问次数:asc"));
    }

    @Test
    void orderByRawAndNonMatchingMembersPassThroughUntouched() {
        stubGroup("PUBLISHED");

        // Raw member name stays as-is.
        toolkit.wrenQueryCube(
                ALICE,
                null,
                "gA",
                "访问日志分析",
                List.of("访问次数"),
                null,
                "访问日期:month",
                null,
                List.of("访问日期:desc"),
                null,
                null);
        assertThat(gateway.args.get(0).get("order_by")).isEqualTo(List.of("访问日期:desc"));

        // Alias with a granularity the query did not select stays untouched.
        toolkit.wrenQueryCube(
                ALICE,
                null,
                "gA",
                "访问日志分析",
                List.of("访问次数"),
                null,
                "访问日期:month",
                null,
                List.of("访问日期__week:desc"),
                null,
                null);
        assertThat(gateway.args.get(1).get("order_by")).isEqualTo(List.of("访问日期__week:desc"));

        // No time dimension: nothing to rewrite.
        toolkit.wrenQueryCube(
                ALICE,
                null,
                "gA",
                "访问日志分析",
                List.of("访问次数"),
                null,
                null,
                null,
                List.of("访问日期__month:desc"),
                null,
                null);
        assertThat(gateway.args.get(2).get("order_by")).isEqualTo(List.of("访问日期__month:desc"));
    }

    /**
     * An order-by rejection that survives normalization (member never selected) must carry the
     * raw-name guidance so the model self-corrects in one step instead of retrying blindly.
     */
    @Test
    void cubeOrderByMemberRejectionCarriesRawNameGuidance() {
        stubGroup("PUBLISHED");
        gateway.result =
                new WrenCallResult(
                        false,
                        "Error executing tool query_cube: Error during planning: Cannot order by"
                            + " member 'order_time__month': member is not selected by the query");

        String out =
                toolkit.wrenQueryCube(
                        ALICE,
                        null,
                        "gA",
                        "访问日志分析",
                        List.of("访问次数"),
                        null,
                        null,
                        null,
                        List.of("order_time__month:desc"),
                        10,
                        null);

        assertThat(out)
                .startsWith("error: wren 语义引擎拒绝了该 Cube 查询")
                .contains("Cannot order by member 'order_time__month'")
                .contains("原始成员名")
                .contains("结果输出列别名");
    }

    // ------------------------------------------------------------------ describe_model

    @Test
    void describeModelReturnsPublishedFieldsRelationsAndCubes() {
        stubGroup("PUBLISHED");

        String out = toolkit.wrenDescribeModel(ALICE, null, "gA", List.of("订单"));

        assertThat(out)
                .contains("## 已发布语义模型详情")
                .contains("### 订单")
                .contains("| 城市 | VARCHAR | 开通城市 |")
                .contains("订单 MANY_TO_ONE 客户")
                .contains("销售Cube")
                .doesNotContain("ds_a");
        assertThat(gateway.tools).isEmpty();
    }

    @Test
    void describeModelFoldsRelationProjectionFieldsByDefault() {
        stubGroup("PUBLISHED");

        String folded = toolkit.wrenDescribeModel(ALICE, null, "gA", List.of("订单"), false);
        String expanded = toolkit.wrenDescribeModel(ALICE, null, "gA", List.of("订单"), true);

        assertThat(folded)
                .contains("关联字段组：客户 → 客户")
                .contains("2 个字段")
                .contains("自动 JOIN")
                .contains("含税金额")
                .doesNotContain("客户负责人")
                .doesNotContain("客户地区");
        assertThat(expanded)
                .contains("关联字段组：客户 → 客户")
                .contains("客户负责人")
                .contains("表达式：客户.负责人")
                .contains("客户地区")
                .contains("表达式：客户.地区");
    }

    @Test
    void describeModelValidatesNamesWithoutLeakingUnknownModels() {
        stubGroup("PUBLISHED");

        assertThat(toolkit.wrenDescribeModel(ALICE, null, "gA", List.of()))
                .isEqualTo("error: model_names 至少需要 1 个逻辑模型名");
        assertThat(
                        toolkit.wrenDescribeModel(
                                ALICE, null, "gA", List.of("a", "b", "c", "d", "e", "f")))
                .contains("最多允许 5 个");
        assertThat(toolkit.wrenDescribeModel(ALICE, null, "gA", List.of("不存在")))
                .isEqualTo("error: 未知或无权访问的已发布逻辑模型");
    }

    /**
     * specs/020: a rejected name that belongs to a published View or Cube must route the model
     * to wren_run_sql / wren_query_cube in one step instead of triggering the retry loop seen
     * in 2026-10-04 LLM.log; mixed batches stay all-or-nothing but name the offender.
     */
    @Test
    void describeModelGuidesViewAndCubeNamesToTheirTools() {
        stubGroup("PUBLISHED");

        assertThat(toolkit.wrenDescribeModel(ALICE, null, "gA", List.of("近30天用户行为分层")))
                .contains("视图 近30天用户行为分层", "已审阅定义", "输出列");
        assertThat(toolkit.wrenDescribeModel(ALICE, null, "gA", List.of("销售Cube")))
                .isEqualTo("error: '销售Cube' 是 Cube 而非逻辑模型，请用 wren_query_cube 按度量/维度查询");
        assertThat(toolkit.wrenDescribeModel(ALICE, null, "gA", List.of("订单", "销售Cube")))
                .startsWith("error: '销售Cube'")
                .contains("wren_query_cube");
        assertThat(gateway.tools).containsExactly("run_sql");
    }

    @Test
    void failedAndInitializingGroupsAreNotQueryableWithoutSnapshot() {
        stubGroup("FAILED");
        assertThat(toolkit.wrenDescribeModel(ALICE, null, "gA", List.of("订单")))
                .contains("基础 MDL 初始化失败");

        stubGroup("INITIALIZING");
        assertThat(toolkit.wrenRunSql(ALICE, null, "gA", "SELECT 1", null, null)).contains("正在初始化");
        assertThat(gateway.tools).isEmpty();
    }

    // ------------------------------------------------------------------ CSV data handoff
    // (specs/016)

    /**
     * The file-level data handoff: a successful query persists its payload as a CSV inside the
     * session's sandbox workspace and the returned text references the file, so run_python reads
     * it with pd.read_csv instead of the model copying rows through the context.
     */
    @Test
    void sqlResultIsPersistedAsCsvAndReferenced() {
        stubGroup("PUBLISHED");
        AbstractSandboxFilesystem fs = mock(AbstractSandboxFilesystem.class);
        Uploads uploads = stubSuccessfulUploads(fs);
        WrenToolkit withHandoff = new WrenToolkit(gateway, groupService, null, mdlCatalog, fs);

        String out =
                withHandoff.wrenRunSql(ALICE, rc("s-1"), "gA", "SELECT 城市 FROM 订单表", null, null);

        assertThat(out)
                .doesNotStartWith("error:")
                .contains("**数据文件：** data/")
                .contains("（2 行 × 2 列；run_python 中 pd.read_csv('data/")
                .contains("') 读取）");
        assertThat(uploads.paths).hasSize(1);
        String path = uploads.paths.get(0);
        assertThat(path).startsWith("/workspace/runpython/s-1/data/").endsWith(".csv");
        String fileName = path.substring(path.lastIndexOf('/') + 1);
        assertThat(out).contains("data/" + fileName);
        assertThat(new String(uploads.contents.get(0), StandardCharsets.UTF_8))
                .isEqualTo("城市,销售额\n杭州,120\n宁波,80\n");
    }

    @Test
    void cubeResultIsPersistedAsCsvAndReferenced() {
        stubGroup("PUBLISHED");
        AbstractSandboxFilesystem fs = mock(AbstractSandboxFilesystem.class);
        Uploads uploads = stubSuccessfulUploads(fs);
        WrenToolkit withHandoff = new WrenToolkit(gateway, groupService, null, mdlCatalog, fs);

        String out =
                withHandoff.wrenQueryCube(
                        ALICE,
                        rc("s1"),
                        "gA",
                        "销售Cube",
                        List.of("销售额"),
                        null,
                        null,
                        null,
                        null,
                        null,
                        null);

        assertThat(out).doesNotStartWith("error:").contains("**数据文件：** data/");
        assertThat(uploads.paths).hasSize(1);
        assertThat(uploads.paths.get(0)).startsWith("/workspace/runpython/s1/data/");
    }

    /** A failed handoff never errors: exceptions add a hint, upload failures degrade silently (ADR 0030/0060). */
    @Test
    void handoffFailureDegradesToMarkdownOnlyResult() {
        stubGroup("PUBLISHED");
        AbstractSandboxFilesystem fs = mock(AbstractSandboxFilesystem.class);
        WrenToolkit withHandoff = new WrenToolkit(gateway, groupService, null, mdlCatalog, fs);

        // doThrow/doReturn (not when().thenX()): re-stubbing with when() would invoke the
        // previous thenThrow answer inside the when() call itself.
        doThrow(new RuntimeException("sandbox not ready")).when(fs).uploadFiles(any(), any());
        assertThat(withHandoff.wrenRunSql(ALICE, rc("s1"), "gA", "SELECT 城市 FROM 订单表", null, null))
                .doesNotStartWith("error:")
                .contains("| 杭州 | 120 |")
                .doesNotContain("**数据文件：**")
                .contains("数据文件保存失败");

        doReturn(List.of(FileUploadResponse.fail("p", "denied")))
                .when(fs)
                .uploadFiles(any(), any());
        assertThat(withHandoff.wrenRunSql(ALICE, rc("s1"), "gA", "SELECT 城市 FROM 订单表", null, null))
                .doesNotStartWith("error:")
                .contains("| 杭州 | 120 |")
                .doesNotContain("**数据文件：**");
    }

    /** Content-addressed names make re-running the same query overwrite the same file. */
    @Test
    void samePayloadReusesContentAddressedFileName() {
        stubGroup("PUBLISHED");
        AbstractSandboxFilesystem fs = mock(AbstractSandboxFilesystem.class);
        Uploads uploads = stubSuccessfulUploads(fs);
        WrenToolkit withHandoff = new WrenToolkit(gateway, groupService, null, mdlCatalog, fs);

        withHandoff.wrenRunSql(ALICE, rc("s1"), "gA", "SELECT 城市 FROM 订单表", null, null);
        withHandoff.wrenRunSql(ALICE, rc("s1"), "gA", "SELECT 城市 FROM 订单表", null, null);

        assertThat(uploads.paths).hasSize(2);
        assertThat(uploads.paths.get(0)).isEqualTo(uploads.paths.get(1));
    }

    @Test
    void csvEscapesCommasQuotesAndNewlines() {
        stubGroup("PUBLISHED");
        gateway.result =
                new WrenCallResult(
                        true,
                        "{\"columns\":[\"名称\",\"备注\"],\"rows\":["
                                + "{\"名称\":\"a,b\",\"备注\":\"he said \\\"hi\\\"\"},"
                                + "{\"名称\":\"line1\\nline2\",\"备注\":null}],\"truncated\":false}");
        AbstractSandboxFilesystem fs = mock(AbstractSandboxFilesystem.class);
        Uploads uploads = stubSuccessfulUploads(fs);
        WrenToolkit withHandoff = new WrenToolkit(gateway, groupService, null, mdlCatalog, fs);

        withHandoff.wrenRunSql(ALICE, rc("s1"), "gA", "SELECT 名称, 备注 FROM 订单表", null, null);

        // RFC 4180: commas/quotes/newlines trigger quoting, inner quotes double, NULL -> empty
        assertThat(new String(uploads.contents.get(0), StandardCharsets.UTF_8))
                .isEqualTo("名称,备注\n\"a,b\",\"he said \"\"hi\"\"\"\n\"line1\nline2\",\n");
    }

    /** The legacy 4-arg constructor keeps the pre-handoff behaviour (filesystem == null). */
    @Test
    void legacyConstructorSkipsHandoff() {
        stubGroup("PUBLISHED");

        String out = toolkit.wrenRunSql(ALICE, rc("s1"), "gA", "SELECT 1", null, null);

        assertThat(out).doesNotStartWith("error:").doesNotContain("数据文件");
    }

    /** Data files land in per-session directories under the shared runpython root. */
    @Test
    void dataFilesAreScopedPerSessionDirectory() {
        stubGroup("PUBLISHED");
        AbstractSandboxFilesystem fs = mock(AbstractSandboxFilesystem.class);
        Uploads uploads = stubSuccessfulUploads(fs);
        WrenToolkit withHandoff = new WrenToolkit(gateway, groupService, null, mdlCatalog, fs);

        withHandoff.wrenRunSql(ALICE, rc("s1"), "gA", "SELECT 1", null, null);
        withHandoff.wrenRunSql(ALICE, rc("s2"), "gA", "SELECT 1", null, null);

        assertThat(uploads.paths.get(0)).startsWith("/workspace/runpython/s1/data/");
        assertThat(uploads.paths.get(1)).startsWith("/workspace/runpython/s2/data/");
        assertThat(uploads.paths.get(0)).isNotEqualTo(uploads.paths.get(1));
    }

    /** Results beyond the 10MB cap skip the handoff with a convergence hint. */
    @Test
    void oversizedResultSkipsHandoffWithHint() {
        stubGroup("PUBLISHED");
        String bigValue = "x".repeat(10 * 1024 * 1024 + 1024);
        gateway.result =
                new WrenCallResult(
                        true,
                        "{\"columns\":[\"v\"],\"rows\":[{\"v\":\""
                                + bigValue
                                + "\"}],\"truncated\":false}");
        AbstractSandboxFilesystem fs = mock(AbstractSandboxFilesystem.class);
        WrenToolkit withHandoff = new WrenToolkit(gateway, groupService, null, mdlCatalog, fs);

        String out = withHandoff.wrenRunSql(ALICE, rc("s1"), "gA", "SELECT 1", null, null);

        assertThat(out)
                .doesNotStartWith("error:")
                .contains("结果过大未生成数据文件")
                .doesNotContain("**数据文件：**");
        verify(fs, never()).uploadFiles(any(), any());
    }

    // ------------------------------------------------------------------ prompt gates

    @Test
    void toolDescriptionsCarryWrenOnlyGates() {
        assertThat(toolDescription("wren_run_sql"))
                .contains("基础 MDL 初始化")
                .contains("已发布 View")
                .contains("优先用 wren_query_cube")
                // coverage judgement + ranking split (2026-10-05 cube-misroute analysis): a
                // ranking needs X as a Cube dimension; otherwise fall back to GROUP BY X in SQL
                .contains("覆盖判定")
                .contains("GROUP BY X 排名")
                .contains("TOP-N")
                .contains("优先直接按视图名查询")
                .contains("语义资产都无法表达")
                .contains("SELECT / WITH")
                .contains("LIMIT/OFFSET")
                .contains("知识库名称（推荐")
                .doesNotContain("query_structured_data")
                // wren_cube_describe lives on the modeling agent's toolkit only; the asking
                // toolkit must never point the model at a tool it cannot see
                .doesNotContain("wren_cube_describe")
                // specs/025: official alignment — prefer wording, no deterministic routing gate
                .doesNotContain("必须只按该视图名查询")
                .doesNotContain("不得从基础模型重建同等语义")
                .doesNotContain("不得再 JOIN 其他逻辑模型");
        assertThat(toolDescription("wren_query_cube"))
                .contains("基础 MDL 初始化")
                .contains("Cube")
                .contains("覆盖时优先用本工具")
                .contains("覆盖判定")
                .contains("dimensions=[X]")
                .contains("不是实体排名")
                .contains("左闭右开")
                .contains("granularity 使用小写")
                .contains("知识库名称（推荐")
                .contains("start=end 会得到空结果")
                .contains("原始成员名")
                .contains("默认按时间升序")
                .doesNotContain("wren_cube_describe")
                .doesNotContain("不得改写为手工聚合 SQL")
                .doesNotContain("query_structured_data");
        assertThat(toolDescription("wren_describe_model"))
                .contains("逻辑模型名")
                .contains("知识库名称（推荐")
                .doesNotContain("query_structured_data");
    }

    // ------------------------------------------------------------------ fixtures

    private void stubGroup(String mdlState) {
        DatasetGroupEntity group = new DatasetGroupEntity("gA", "alice", "订单分析", null);
        group.setMdlState(mdlState);
        boolean published = "PUBLISHED".equals(mdlState) || "DIRTY".equals(mdlState);
        group.setMdlVersion(published ? 3 : 0);
        when(groupService.listGroups("alice")).thenReturn(List.of(group));
        when(mdlCatalog.load("gA")).thenReturn(published ? Optional.of(mdl()) : Optional.empty());
    }

    private static MdlCatalog.GroupMdl mdl() {
        MdlCatalog.Model order =
                new MdlCatalog.Model(
                        "订单",
                        "ds_a",
                        "订单明细",
                        List.of(
                                new MdlCatalog.Column("城市", "VARCHAR", "开通城市", false, null),
                                new MdlCatalog.Column("客户", "客户", "客户关系", false, null),
                                new MdlCatalog.Column(
                                        "客户负责人", "VARCHAR", "关联客户负责人", true, "客户.负责人"),
                                new MdlCatalog.Column("客户地区", "VARCHAR", "关联客户地区", true, "客户.地区"),
                                new MdlCatalog.Column(
                                        "含税金额", "DECIMAL", "订单含税金额", true, "金额 * 1.13")));
        MdlCatalog.Model customer = new MdlCatalog.Model("客户", "ds_b", "客户主数据", List.of());
        MdlCatalog.Relation relation =
                new MdlCatalog.Relation("订单", "客户", "MANY_TO_ONE", "订单.客户ID = 客户.客户ID");
        MdlCatalog.Cube cube =
                new MdlCatalog.Cube(
                        "销售Cube",
                        "订单",
                        "销售指标",
                        List.of(new MdlCatalog.Member("销售额", "SUM(金额)", "decimal", "成交金额")),
                        List.of(new MdlCatalog.Member("城市", "城市", "varchar", "开通城市")),
                        List.of());
        MdlCatalog.View view =
                new MdlCatalog.View(
                        "近30天用户行为分层",
                        "SELECT user_segment, visit_count FROM 点击详情数据",
                        "基于点击详情数据汇总最近30天每个账号的访问次数，按低频用户和高频用户汇总行为偏好");
        return new MdlCatalog.GroupMdl(
                "gA",
                "订单分析",
                3,
                List.of(order, customer),
                List.of(relation),
                List.of(cube),
                List.of(view));
    }

    /** Reads the {@code @Tool#description} the framework exposes to the model for a tool name. */
    private static String toolDescription(String toolName) {
        for (Method m : WrenToolkit.class.getDeclaredMethods()) {
            Tool tool = m.getAnnotation(Tool.class);
            if (tool != null && toolName.equals(tool.name())) {
                return tool.description();
            }
        }
        throw new AssertionError("no @Tool named '" + toolName + "' on WrenToolkit");
    }

    private static RuntimeContext rc(String sessionId) {
        return RuntimeContext.builder().userId("alice").sessionId(sessionId).build();
    }

    /** Records every (path, bytes) pair handed to {@code uploadFiles} and reports success. */
    private static Uploads stubSuccessfulUploads(AbstractSandboxFilesystem fs) {
        Uploads uploads = new Uploads();
        when(fs.uploadFiles(any(), any()))
                .thenAnswer(
                        invocation -> {
                            List<?> files = invocation.getArgument(1);
                            for (Object entry : files) {
                                @SuppressWarnings("unchecked")
                                Map.Entry<String, byte[]> file = (Map.Entry<String, byte[]>) entry;
                                uploads.paths.add(file.getKey());
                                uploads.contents.add(file.getValue());
                            }
                            return List.of(FileUploadResponse.success("uploaded"));
                        });
        return uploads;
    }

    private static final class Uploads {
        final List<String> paths = new ArrayList<>();
        final List<byte[]> contents = new ArrayList<>();
    }

    private static final class RecordingGateway implements WrenQueryGateway {
        private final List<String> tools = new ArrayList<>();
        private final List<String> groupIds = new ArrayList<>();
        private final List<Map<String, Object>> args = new ArrayList<>();
        private WrenCallResult result = new WrenCallResult(true, ROWS_PAYLOAD);
        private RuntimeException failure;

        @Override
        public WrenCallResult call(String groupId, String tool, Map<String, Object> arguments) {
            if (failure != null) {
                throw failure;
            }
            tools.add(tool);
            groupIds.add(groupId);
            args.add(arguments);
            return result;
        }

        @Override
        public void invalidate(String groupId) {}
    }
}
