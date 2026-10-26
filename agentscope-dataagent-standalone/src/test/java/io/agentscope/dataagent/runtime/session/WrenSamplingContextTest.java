package io.agentscope.dataagent.runtime.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.agentscope.dataagent.dataset.WrenProjectAccess;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WrenSamplingContextTest {
    @TempDir Path root;

    @Test
    void projectPromptKeepsPolicyWithoutInjectingRawSamplingData() throws Exception {
        Path first = root.resolve("first"), second = root.resolve("second");
        Files.createDirectories(first.resolve("raw"));
        Files.createDirectories(second.resolve("raw"));
        Files.writeString(
                first.resolve("raw/data-samples.md"), "first-secret-" + "x".repeat(10000));
        Files.writeString(second.resolve("raw/data-samples.md"), "second-sample");
        var access = mock(WrenProjectAccess.class);
        when(access.resolve((io.agentscope.core.agent.RuntimeContext) null))
                .thenReturn(new WrenProjectAccess.Project("alice", "first", "first", first))
                .thenReturn(new WrenProjectAccess.Project("alice", "second", "second", second));
        var middleware = new WrenProjectContextMiddleware(access);
        String one = middleware.onSystemPrompt(null, null, "base").block();
        String two = middleware.onSystemPrompt(null, null, "base").block();
        assertThat(one).doesNotContain("first-secret", "摘要截断").hasSizeLessThan(8000);
        assertThat(two).contains("采样").doesNotContain("second-sample", "first-secret");
    }

    @Test
    void modelingListsProbeBoundaryPerTableSourceAndServiceTime() {
        var access = mock(WrenProjectAccess.class);
        when(access.resolve((io.agentscope.core.agent.RuntimeContext) null))
                .thenReturn(new WrenProjectAccess.Project("alice", "g", "g", root));
        var repository = mock(io.agentscope.dataagent.web.persistence.jpa.DatasetRepository.class);
        when(repository.findByGroupId("g"))
                .thenReturn(
                        java.util.List.of(
                                table("报表访问明细", "upload"),
                                table("订单", "datasource"),
                                table("手工表", "manual")));
        var clock =
                java.time.Clock.fixed(
                        java.time.Instant.parse("2026-10-20T12:00:00.123Z"),
                        java.time.ZoneId.of("Asia/Shanghai"));
        String prompt =
                new WrenProjectContextMiddleware(access, null, repository, clock)
                        .onSystemPrompt(null, null, "base")
                        .block();
        assertThat(prompt)
                .contains(
                        "上传表：报表访问明细",
                        "外部数据源及其他来源表：订单、手工表",
                        "未出现在以上列表中的表按外部数据源表处理",
                        "每张事实表一次时间列 MIN/MAX",
                        "每条候选关系一次合并查询",
                        "不执行 SELECT DISTINCT",
                        "当前时间：2026-10-20T20:00+08:00",
                        "当前日期：2026-10-20",
                        "服务时区：Asia/Shanghai");
    }

    @Test
    void buildStateReflectsWhetherCompiledMdlExists() throws Exception {
        Path project = root.resolve("project");
        Files.createDirectories(project);
        assertThat(WrenProjectContextMiddleware.buildState(project))
                .contains("尚未编译", "平台会自动先执行 context build");
        Files.createDirectories(project.resolve("target"));
        Files.writeString(project.resolve("target/mdl.json"), "{}");
        assertThat(WrenProjectContextMiddleware.buildState(project))
                .contains("已编译")
                .doesNotContain("尚未编译");
    }

    @Test
    void tablesAddedAfterAPublishAreListedForIncrementalModeling(@TempDir Path group)
            throws Exception {
        Path root = group.resolve("workspace");
        Files.createDirectories(root.resolve("models/orders"));
        Files.createDirectories(root.resolve("models/refunds"));
        assertThat(WrenProjectContextMiddleware.newTables(root)).isEmpty();
        Files.createDirectories(group.resolve("published/models/orders"));
        assertThat(WrenProjectContextMiddleware.newTables(root))
                .contains("本次新增的表", "refunds", "已发布的模型、关系和规则保持不变")
                .doesNotContain("orders");
        Files.createDirectories(group.resolve("published/models/refunds"));
        assertThat(WrenProjectContextMiddleware.newTables(root)).isEmpty();
    }

    @Test
    void noUploadedTablesKeepsEveryTableProbeFree() {
        assertThat(
                        WrenProjectContextMiddleware.probeBoundary(
                                java.util.List.of(table("订单", "datasource"))))
                .contains("上传表：无", "外部数据源及其他来源表：订单", "在基础建模中不执行任何 query（工具会直接拒绝）", "试问 SQL 也不查询数据");
    }

    @Test
    void relationCandidatesComeFromColumnNamesNotTypes() {
        var log = table("dashboard_log", "datasource");
        log.setColumnSchemaJson(
                "[{\"name\":\"operator_id\",\"sqlType\":\"BIGINT\"},{\"name\":\"dashboard_id\",\"sqlType\":\"VARCHAR\"},"
                    + "{\"name\":\"login_system\",\"sqlType\":\"BIGINT\",\"included\":false}]");
        var users = table("sys_operator", "datasource");
        users.setColumnSchemaJson(
                "[{\"name\":\"id\",\"sqlType\":\"VARCHAR\"},{\"name\":\"user_name\"}]");
        var excluded = table("orders", "upload");
        excluded.setColumnSchemaJson("[{\"name\":\"id\",\"included\":false}]");
        var candidates =
                WrenProjectContextMiddleware.relationCandidates(
                        java.util.List.of(log, users, excluded));
        assertThat(candidates)
                .contains(
                        "dashboard_log.operator_id → sys_operator.id",
                        "与列类型、采样值格式无关",
                        "request_user_confirmation")
                .doesNotContain("dashboard_id →", "orders");
        assertThat(WrenProjectContextMiddleware.relationCandidates(java.util.List.of(users)))
                .isEmpty();
    }

    private static io.agentscope.dataagent.web.persistence.jpa.DatasetEntity table(
            String name, String origin) {
        var entity = new io.agentscope.dataagent.web.persistence.jpa.DatasetEntity();
        entity.setName(name);
        entity.setOrigin(origin);
        return entity;
    }

    @Test
    void modelingSeesOnlyCurrentProjectsDatabaseWindows() {
        var access = mock(WrenProjectAccess.class);
        var windows = mock(io.agentscope.dataagent.dataset.FactQueryWindowPolicy.class);
        when(access.resolve((io.agentscope.core.agent.RuntimeContext) null))
                .thenReturn(new WrenProjectAccess.Project("alice", "first", "first", root))
                .thenReturn(new WrenProjectAccess.Project("alice", "second", "second", root));
        when(windows.rules("first"))
                .thenReturn(
                        java.util.List.of(
                                new io.agentscope.dataagent.dataset.SqlWindowGate.Rule(
                                        "订单", java.util.List.of("支付日期"), 7)));
        when(windows.rules("second")).thenReturn(java.util.List.of());
        var middleware = new WrenProjectContextMiddleware(access, windows);
        assertThat(middleware.onSystemPrompt(null, null, "base").block())
                .contains("订单", "支付日期", "maxWindowDays", "建模可自行选择有限样例日期", "禁止把具体窗口天数复制");
        assertThat(middleware.onSystemPrompt(null, null, "base").block())
                .doesNotContain("订单", "支付日期");
    }

    @Test
    void turnPurposeSelectsTheContextSections() throws Exception {
        var access = mock(WrenProjectAccess.class);
        var repository = mock(io.agentscope.dataagent.web.persistence.jpa.DatasetRepository.class);
        when(repository.findByGroupId("g"))
                .thenReturn(java.util.List.of(table("订单", "datasource")));
        var windows = mock(io.agentscope.dataagent.dataset.FactQueryWindowPolicy.class);
        when(windows.rules("g"))
                .thenReturn(
                        java.util.List.of(
                                new io.agentscope.dataagent.dataset.SqlWindowGate.Rule(
                                        "订单", java.util.List.of("支付日期"), 7)));
        var middleware = new WrenProjectContextMiddleware(access, windows, repository);
        java.util.function.Function<String, String> promptFor =
                purpose -> {
                    var context =
                            purpose == null
                                    ? io.agentscope.core.agent.RuntimeContext.builder().build()
                                    : io.agentscope.core.agent.RuntimeContext.builder()
                                            .put(
                                                    io.agentscope.dataagent.dataset
                                                            .ModelingTurnPurpose.class,
                                                    new io.agentscope.dataagent.dataset
                                                            .ModelingTurnPurpose(purpose))
                                            .build();
                    when(access.resolve(context))
                            .thenReturn(new WrenProjectAccess.Project("alice", "g", "g", root));
                    return middleware.onSystemPrompt(null, context, "base").block();
                };
        assertThat(promptFor.apply("auto"))
                .contains("表来源与探查边界", "本轮任务要求：自动建模")
                .doesNotContain("maxWindowDays", "本轮任务要求：试问 SQL");
        assertThat(promptFor.apply("question"))
                .contains("maxWindowDays", "本轮任务要求：试问 SQL")
                .doesNotContain("表来源与探查边界");
        assertThat(promptFor.apply("confirm"))
                .contains("本轮任务要求：写入用户的确定")
                .doesNotContain("表来源与探查边界", "maxWindowDays");
        assertThat(promptFor.apply(null)).contains("表来源与探查边界", "maxWindowDays", "用户对话");
    }

    @Test
    void everyModelingModelCallEndsWithTheChineseOnlyReminder() {
        var middleware =
                new WrenProjectContextMiddleware(
                        mock(io.agentscope.dataagent.dataset.WrenProjectAccess.class));
        var sent =
                new java.util.concurrent.atomic.AtomicReference<
                        io.agentscope.core.middleware.ModelCallInput>();
        middleware
                .onModelCall(
                        null,
                        null,
                        new io.agentscope.core.middleware.ModelCallInput(
                                java.util.List.of(), java.util.List.of(), null, null),
                        input -> {
                            sent.set(input);
                            return reactor.core.publisher.Flux.empty();
                        })
                .blockLast();
        assertThat(sent.get().messages())
                .singleElement()
                .satisfies(m -> assertThat(m.getTextContent()).contains("只用简体中文"));
    }
}
