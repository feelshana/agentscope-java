package io.agentscope.dataagent.runtime.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.dataagent.dataset.FactQueryWindowPolicy;
import io.agentscope.dataagent.dataset.SqlWindowGate;
import io.agentscope.dataagent.dataset.WrenQueryAccess;
import io.agentscope.dataagent.web.config.DataAgentConfig;
import io.agentscope.dataagent.web.persistence.jpa.DatasetGroupEntity;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.Test;

class RuntimeWindowContextTest {
    @Test
    void serviceTimeRefreshesAcrossMidnightUsingTheSameZone() {
        var access = mock(WrenQueryAccess.class);
        var clock = mock(Clock.class);
        when(clock.getZone()).thenReturn(ZoneId.of("Asia/Shanghai"));
        when(clock.instant())
                .thenReturn(Instant.parse("2026-10-20T15:59:59Z"))
                .thenReturn(Instant.parse("2026-10-20T16:00:01Z"));
        var context = RuntimeContext.builder().userId("alice").build();
        when(access.visible(context)).thenReturn(List.of());
        var middleware = new WrenQueryContextMiddleware(access, null, clock);
        assertThat(middleware.onSystemPrompt(null, context, "base").block())
                .contains(
                        "当前时间：2026-10-20T23:59:59+08:00", "当前日期：2026-10-20", "服务时区：Asia/Shanghai");
        assertThat(middleware.onSystemPrompt(null, context, "base").block())
                .contains("当前时间：2026-10-21T00:00:01+08:00", "当前日期：2026-10-21", "服务时区：Asia/Shanghai")
                .doesNotContain("当前日期：2026-10-20");
    }

    @Test
    void dateFirstRuleAppearsOnlyForRegisteredLimitedTables() {
        String none = WrenQueryContextMiddleware.dateRules(List.of());
        assertThat(none)
                .contains("没有登记受限事实表", "不因此追问", "按当前年份理解")
                .doesNotContain("缺少日期先询问", "直接询问日期范围");
        String limited = WrenQueryContextMiddleware.dateRules(List.of("订单", "订单", "支付"));
        assertThat(limited)
                .contains("缺少日期先询问", "受限事实表：订单、支付", "直接询问日期范围", "不再确认", "不提出拆分成多次查询")
                .doesNotContain("订单、订单");

        var access = mock(WrenQueryAccess.class);
        var policy = mock(FactQueryWindowPolicy.class);
        var ctx = RuntimeContext.builder().userId("alice").build();
        var group = new DatasetGroupEntity("g", "owner", "uploads", "");
        when(access.visible(ctx)).thenReturn(List.of(group));
        when(policy.rules("g")).thenReturn(List.of());
        assertThat(
                        new WrenQueryContextMiddleware(access, policy)
                                .onSystemPrompt(null, ctx, "base")
                                .block())
                .contains("没有登记受限事实表")
                .doesNotContain("缺少日期先询问");
    }

    @Test
    void queryingUsesOnlyVisibleDatabasePolicyAndRefreshesWithoutPublishing() {
        var access = mock(WrenQueryAccess.class);
        var policy = mock(FactQueryWindowPolicy.class);
        var ctx = RuntimeContext.builder().userId("alice").build();
        var group = new DatasetGroupEntity("g", "owner", "shared", "");
        group.setMdlVersion(1);
        when(access.visible(ctx)).thenReturn(List.of(group));
        when(policy.rules("g"))
                .thenReturn(List.of(new SqlWindowGate.Rule("订单", List.of("日期"), 31)))
                .thenReturn(List.of(new SqlWindowGate.Rule("订单", List.of("日期"), 90)));
        var middleware = new WrenQueryContextMiddleware(access, policy);
        assertThat(middleware.onSystemPrompt(null, ctx, "base").block())
                .contains("maxWindowDays\":31", "数据库策略是当前平台窗口唯一来源");
        assertThat(middleware.onSystemPrompt(null, ctx, "base").block())
                .contains("maxWindowDays\":90")
                .doesNotContain("maxWindowDays\":31");
        when(access.visible(ctx)).thenReturn(List.of());
        org.mockito.Mockito.clearInvocations(policy);
        middleware.onSystemPrompt(null, ctx, "base").block();
        verifyNoInteractions(policy);
    }

    @Test
    void queryContextGivesTheTimeConditionFromThePublishedColumnFormat(
            @org.junit.jupiter.api.io.TempDir java.nio.file.Path published) throws Exception {
        java.nio.file.Files.writeString(
                published.resolve("wren_project.yml"), "data_source: mysql\n");
        var model = published.resolve("models/report_visit/metadata.yml");
        java.nio.file.Files.createDirectories(model.getParent());
        java.nio.file.Files.writeString(
                model,
                "name: report_visit\n"
                        + "columns:\n"
                        + "- name: visit_time\n"
                        + "  type: VARCHAR\n"
                        + "properties:\n"
                        + "  temporal_columns:\n"
                        + "  - column: visit_time\n"
                        + "    format: uuuu/M/d H:mm\n");
        var access = mock(WrenQueryAccess.class);
        var policy = mock(FactQueryWindowPolicy.class);
        var ctx = RuntimeContext.builder().userId("alice").build();
        var group = new DatasetGroupEntity("g", "owner", "红海", "");
        when(access.visible(ctx)).thenReturn(List.of(group));
        when(policy.rules("g"))
                .thenReturn(
                        List.of(new SqlWindowGate.Rule("report_visit", List.of("visit_time"), 31)));
        assertThat(
                        new WrenQueryContextMiddleware(access, policy, id -> published)
                                .onSystemPrompt(null, ctx, "base")
                                .block())
                .contains("时间条件写法", "STR_TO_DATE(visit_time, '%Y/%c/%e %k:%i') >=");
    }

    @Test
    void modelingPromptSeparatesOperationalWindowsAndBusinessDefinitions() {
        assertThat(DataAgentConfig.MODELING_SCRIPT)
                .contains(
                        "具体天数、maxWindowDays和配置值禁止写进",
                        "保留真正的业务时间口径",
                        "建模样例日期可自行选择，无需用户确认",
                        "逐项核对名称、已确认口径与SQL过滤一致",
                        "不擅自新增业务条件");
        assertThat(DataAgentConfig.QUERY_EFFICIENCY_RULES).contains("不能要求修改MDL", "不得伪称已经调用工具门禁");
    }
}
