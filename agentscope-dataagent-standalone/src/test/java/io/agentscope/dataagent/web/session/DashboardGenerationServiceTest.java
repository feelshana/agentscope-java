package io.agentscope.dataagent.web.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.dataagent.dataset.DashboardGeneration;
import io.agentscope.dataagent.dataset.DashboardService;
import io.agentscope.dataagent.dataset.KnowledgeBaseAccessService;
import io.agentscope.dataagent.dataset.WrenCli;
import io.agentscope.dataagent.dataset.WrenProperties;
import io.agentscope.dataagent.dataset.WrenPublishedProject;
import io.agentscope.dataagent.dataset.WrenQueryAccess;
import io.agentscope.dataagent.web.persistence.jpa.DatasetGroupEntity;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

class DashboardGenerationServiceTest {
    @Test
    void nativeAsyncEventsNeverRunJournalCompletionOnANonBlockingThread() throws Exception {
        var observed = new java.util.concurrent.atomic.AtomicReference<Boolean>();
        var emitted = new java.util.concurrent.atomic.AtomicBoolean();
        var watched = org.mockito.Mockito.spy(dashboards);
        org.mockito.Mockito.doAnswer(
                        call -> {
                            if (emitted.get())
                                observed.set(
                                        reactor.core.scheduler.Schedulers.isInNonBlockingThread());
                            return call.callRealMethod();
                        })
                .when(watched)
                .get("alice", board, true);
        service = new DashboardGenerationService(watched, deletion);
        service.start(
                "alice",
                board,
                request(null),
                (g, m) ->
                        Flux.just("{\"type\":\"done\"}")
                                .publishOn(reactor.core.scheduler.Schedulers.parallel())
                                .doOnNext(ignored -> emitted.set(true)));
        awaitTerminal();
        assertThat(observed.get()).isFalse();
    }

    @Test
    void anotherManagerStartingLaterDoesNotEraseTheOriginalUsersTerminalStatus() throws Exception {
        var first = request(null);
        service.start("alice", board, first, (g, m) -> Flux.just("{\"type\":\"done\"}"));
        awaitTerminal();
        var next = request(null);
        service.start("bob", board, next, (g, m) -> Flux.just("{\"type\":\"done\"}"));
        for (int i = 0; i < 100 && !"SUCCEEDED".equals(service.current("bob", board).status()); i++)
            Thread.sleep(10);
        assertThat(service.current("alice", board).id()).isEqualTo(first.requestId());
        assertThat(service.current("bob", board).id()).isEqualTo(next.requestId());
        assertThat(
                        service.start(
                                        "alice",
                                        board,
                                        first,
                                        (g, m) -> {
                                            throw new AssertionError("duplicate execution");
                                        })
                                .id())
                .isEqualTo(first.requestId());
    }

    @TempDir Path home;
    DashboardService dashboards;
    DashboardGenerationService service;
    DashboardDeletionService deletion = mock(DashboardDeletionService.class);
    WrenCli cli = mock(WrenCli.class);
    String board;

    @BeforeEach
    void setup() throws Exception {
        var access = mock(KnowledgeBaseAccessService.class);
        var query = mock(WrenQueryAccess.class);
        var projects = mock(WrenPublishedProject.class);
        var group = new DatasetGroupEntity("g", "alice", "销售", "");
        group.setMdlVersion(1);
        when(access.requireQuery(any(), eq("g"))).thenReturn(group);
        when(access.role(any(), eq(group))).thenReturn(KnowledgeBaseAccessService.Role.MANAGE);
        when(query.resolve(any(), eq("g"))).thenReturn(group);
        Path published = home.resolve("published");
        Files.createDirectories(published.resolve("target"));
        Files.writeString(published.resolve("target/mdl.json"), "{\"models\":[]}");
        Files.writeString(published.resolve("wren_project.yml"), "name: test\n");
        when(projects.publishedRoot("g")).thenReturn(published);
        when(projects.locked(eq("g"), any()))
                .thenAnswer(call -> ((Supplier<?>) call.getArgument(1)).get());
        when(cli.run(any(), any(), anyList())).thenReturn(new WrenCli.Result(0, "ok"));
        dashboards =
                new DashboardService(
                        new WrenProperties(
                                "wren", home.resolve("mdl").toString(), "test", "mysql", 10),
                        access,
                        query,
                        projects,
                        cli);
        board = dashboards.create("alice", "g", "看板").id();
        service = new DashboardGenerationService(dashboards, deletion);
    }

    @AfterEach
    void close() {
        service.close();
    }

    DashboardGenerationService.Request request(String parent) {
        return new DashboardGenerationService.Request(
                UUID.randomUUID().toString(),
                dashboards.get("alice", board, true).revision(),
                "生成看板",
                parent);
    }

    DashboardGenerationService.View awaitTerminal() throws Exception {
        for (int i = 0; i < 200; i++) {
            var view = service.current("alice", board);
            if (view != null
                    && !java.util.List.of("RUNNING", "QUEUED", "CHECKING").contains(view.status()))
                return view;
            Thread.sleep(10);
        }
        throw new AssertionError("task did not terminate");
    }

    @Test
    void changedPageWithoutToolVerificationIsCheckedAfterDoneAndProgressRemainsReadable()
            throws Exception {
        var entered = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        when(cli.run(any(), any(), eq(java.util.List.of("genbi", "verify", "dashboard"))))
                .thenAnswer(
                        call -> {
                            entered.countDown();
                            if (!release.await(5, java.util.concurrent.TimeUnit.SECONDS))
                                throw new AssertionError("check timed out");
                            return new WrenCli.Result(0, "ok");
                        });
        var source = Sinks.many().unicast().<String>onBackpressureBuffer();
        var task = service.start("alice", board, request(null), (g, m) -> source.asFlux());
        Files.writeString(
                home.resolve("mdl/dashboards")
                        .resolve(board)
                        .resolve("project/apps/dashboard/index.html"),
                "<html>dataagent.query()</html>");
        source.tryEmitNext("{\"type\":\"done\"}");
        source.tryEmitComplete();
        try {
            assertThat(entered.await(2, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            assertThat(service.current("alice", board).status()).isEqualTo("CHECKING");
            var draft = dashboards.get("alice", board, true);
            assertThat(draft.fileCheck().status()).isEqualTo("CHECKING");
            assertThat(
                            dashboards
                                    .checkIdleDraft("alice", board, draft.revision(), () -> {})
                                    .fileCheck()
                                    .status())
                    .isEqualTo("CHECKING");
            assertThatThrownBy(() -> service.stop("alice", board, task.id()))
                    .hasMessageContaining("文件检查");
            assertThatThrownBy(() -> dashboards.publish("alice", board, draft.revision()))
                    .hasMessageContaining("检查");
        } finally {
            release.countDown();
        }
        assertThat(awaitTerminal().status()).isEqualTo("SUCCEEDED");
        var draft = dashboards.get("alice", board, true);
        assertThat(draft.verified()).isTrue();
        assertThat(draft.publishedRevision()).isNull();
        org.mockito.Mockito.verify(cli)
                .run(any(), any(), eq(java.util.List.of("genbi", "verify", "dashboard")));
    }

    @Test
    void failedCheckPreservesDraftAndCanBeRetriedWithoutGeneratingOrPublishing() throws Exception {
        var verifyArgs = java.util.List.of("genbi", "verify", "dashboard");
        when(cli.run(any(), any(), eq(verifyArgs)))
                .thenReturn(new WrenCli.Result(1, "文件格式错误"), new WrenCli.Result(0, "ok"));
        var source = Sinks.many().unicast().<String>onBackpressureBuffer();
        var initial = request(null);
        service.start("alice", board, initial, (g, m) -> source.asFlux());
        Path page =
                home.resolve("mdl/dashboards")
                        .resolve(board)
                        .resolve("project/apps/dashboard/index.html");
        Files.writeString(page, "<html>dataagent.query()</html>");
        source.tryEmitNext("{\"type\":\"done\"}");
        source.tryEmitComplete();
        assertThat(awaitTerminal().status()).isEqualTo("CHECK_FAILED");
        var draft = dashboards.get("alice", board, true);
        assertThat(draft.fileCheck().error()).contains("文件格式错误");
        assertThatThrownBy(
                        () ->
                                dashboards.checkIdleDraft(
                                        "alice", board, initial.revision(), () -> {}))
                .hasMessageContaining("已变化");
        assertThat(dashboards.checkIdleDraft("alice", board, draft.revision(), () -> {}).verified())
                .isTrue();
        assertThat(dashboards.get("alice", board, true).publishedRevision()).isNull();
        Files.writeString(page, "<html>dataagent.query() updated</html>");
        assertThat(dashboards.get("alice", board, true).fileCheck().status()).isEqualTo("STALE");
    }

    @Test
    void onlyTheFinalHtmlRevisionCanSatisfyTheCompletionCheck() throws Exception {
        var path =
                home.resolve("mdl/dashboards")
                        .resolve(board)
                        .resolve("project/apps/dashboard/index.html");
        var context = DashboardService.context("alice", "g");
        context.put(
                io.agentscope.dataagent.dataset.DashboardTurn.class,
                new io.agentscope.dataagent.dataset.DashboardTurn(board));
        var verifyArgs = java.util.List.of("genbi", "verify", "dashboard");
        for (int round = 1; round <= 2; round++) {
            var source = Sinks.many().unicast().<String>onBackpressureBuffer();
            service.start("alice", board, request(null), (g, m) -> source.asFlux());
            Files.writeString(path, "<html>dataagent.query() " + round + "</html>");
            dashboards.genbi(context, "verify", null);
            if (round == 2) Files.writeString(path, "<html>dataagent.query() final</html>");
            source.tryEmitNext("{\"type\":\"done\"}");
            source.tryEmitComplete();
            assertThat(awaitTerminal().status()).isEqualTo("SUCCEEDED");
            assertThat(dashboards.get("alice", board, true).verified()).isTrue();
            org.mockito.Mockito.verify(cli, org.mockito.Mockito.times(round == 1 ? 1 : 3))
                    .run(any(), any(), eq(verifyArgs));
        }
    }

    @Test
    void idempotencyAndReadingNeverResubmitExecutionAndAnotherUserCannotReadMessages()
            throws Exception {
        var source = Sinks.many().unicast().<String>onBackpressureBuffer();
        var calls = new AtomicInteger();
        var body = request(null);
        service.start(
                "alice",
                board,
                body,
                (group, message) -> {
                    calls.incrementAndGet();
                    return source.asFlux();
                });
        for (int i = 0; i < 100 && calls.get() == 0; i++) Thread.sleep(10);
        assertThat(
                        service.start(
                                        "alice",
                                        board,
                                        body,
                                        (g, m) -> {
                                            throw new AssertionError();
                                        })
                                .id())
                .isEqualTo(body.requestId());
        for (int i = 0; i < 3; i++)
            assertThat(service.current("alice", board).id()).isEqualTo(body.requestId());
        assertThat(service.current("bob", board)).isNull();
        assertThat(service.snapshot("bob", board).busyElsewhere()).isTrue();
        assertThatThrownBy(() -> service.stop("bob", board, body.requestId()))
                .hasMessageContaining("不存在");
        assertThatThrownBy(() -> service.start("bob", board, request(null), (g, m) -> Flux.empty()))
                .hasMessageContaining("正在生成");
        assertThat(calls).hasValue(1);
        source.tryEmitNext("{\"type\":\"token\",\"data\":\"已完成\"}");
        source.tryEmitNext("{\"type\":\"done\"}");
        source.tryEmitComplete();
        assertThat(awaitTerminal().status()).isEqualTo("SUCCEEDED");
        assertThat(service.current("alice", board).answer()).isEqualTo("已完成");
        assertThat(service.snapshot("bob", board).busyElsewhere()).isFalse();
        org.mockito.Mockito.verifyNoInteractions(cli);
    }

    @Test
    void explicitStopCancelsTheNativeStreamAndActiveTaskBlocksAssetOperations() throws Exception {
        var source = Sinks.many().unicast().<String>onBackpressureBuffer();
        var cancelled = new java.util.concurrent.CountDownLatch(1);
        var body = request(null);
        service.start(
                "alice", board, body, (g, m) -> source.asFlux().doOnCancel(cancelled::countDown));
        assertThatThrownBy(() -> dashboards.delete("alice", board, () -> {}, p -> {}))
                .hasMessageContaining("仍在生成");
        assertThatThrownBy(
                        () ->
                                dashboards.restore(
                                        "alice", board, body.revision(), body.revision(), () -> {}))
                .hasMessageContaining("仍在生成");
        service.stop("alice", board, body.requestId());
        assertThat(awaitTerminal().status()).isEqualTo("CANCELLED");
        // Cancellation can precede the deferred native subscribe entirely.
        assertThat(source.currentSubscriberCount()).isZero();
        dashboards.delete("alice", board, () -> {}, p -> {});
        assertThat(Files.exists(home.resolve("mdl/dashboards").resolve(board))).isFalse();
    }

    @Test
    void nativeErrorOrIncompleteStreamNeverBecomesSuccessAndStaleRequestDoesNotStart()
            throws Exception {
        service.start(
                "alice",
                board,
                request(null),
                (g, m) -> Flux.just("{\"type\":\"error\",\"error\":\"需要日期范围\"}"));
        assertThat(awaitTerminal().status()).isEqualTo("FAILED");
        assertThat(service.current("alice", board).error()).contains("日期");
        service.start(
                "alice",
                board,
                request(null),
                (g, m) -> Flux.just("{\"type\":\"token\",\"data\":\"partial\"}"));
        assertThat(awaitTerminal().status()).isEqualTo("FAILED");
        var stale =
                new DashboardGenerationService.Request(
                        UUID.randomUUID().toString(), "stale", "生成", null);
        assertThatThrownBy(
                        () ->
                                service.start(
                                        "alice",
                                        board,
                                        stale,
                                        (g, m) -> {
                                            throw new AssertionError();
                                        }))
                .hasMessageContaining("已变化");
    }

    @Test
    void persistedOrphanIsInterruptedAfterRestartWithoutExecutingAnything() {
        var before = dashboards.get("alice", board, true);
        dashboards.withGeneration(
                "alice",
                board,
                w -> {
                    try {
                        Files.writeString(
                                w.file(),
                                new ObjectMapper()
                                        .writeValueAsString(
                                                new DashboardGeneration(
                                                        UUID.randomUUID().toString(),
                                                        "alice",
                                                        "old-process",
                                                        "RUNNING",
                                                        "检查数据",
                                                        Instant.now().toString(),
                                                        null,
                                                        before.revision(),
                                                        before.revision(),
                                                        null,
                                                        0,
                                                        "生成",
                                                        "部分回复",
                                                        null)));
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                    return null;
                });
        var recovered = service.current("alice", board);
        assertThat(recovered.status()).isEqualTo("INTERRUPTED");
        assertThat(recovered.resultRevision())
                .isEqualTo(dashboards.get("alice", board, true).revision());
        assertThat(service.current("alice", board).answer()).isEqualTo("部分回复");
    }

    @Test
    void automaticRepairIsBoundedAndInheritsTheOriginalCheckpoint() throws Exception {
        var body = request(null);
        service.start("alice", board, body, (g, m) -> Flux.just("{\"type\":\"done\"}"));
        var first = awaitTerminal();
        var repair = request(first.id());
        service.start("alice", board, repair, (g, m) -> Flux.just("{\"type\":\"done\"}"));
        var second = awaitTerminal();
        assertThat(second.autoAttempt()).isEqualTo(1);
        assertThat(second.checkpointRevision()).isEqualTo(first.checkpointRevision());
        assertThatThrownBy(
                        () ->
                                service.start(
                                        "alice",
                                        board,
                                        request(second.id()),
                                        (g, m) -> Flux.empty()))
                .hasMessageContaining("自动修复已结束");
    }
}
