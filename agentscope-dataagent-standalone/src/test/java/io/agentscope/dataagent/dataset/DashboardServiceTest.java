package io.agentscope.dataagent.dataset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.dataagent.web.persistence.jpa.DatasetGroupEntity;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DashboardServiceTest {
    @TempDir Path home;
    private final KnowledgeBaseAccessService access = mock(KnowledgeBaseAccessService.class);
    private final WrenQueryAccess query = mock(WrenQueryAccess.class);
    private final WrenPublishedProject projects = mock(WrenPublishedProject.class);
    private final WrenCli cli = mock(WrenCli.class);
    private final DatasetGroupEntity group = new DatasetGroupEntity("group", "alice", "销售", "");
    private final List<List<String>> commands = new ArrayList<>();
    private DashboardService service;
    private WrenProperties props;
    private static final String FIRST =
            "<html><body><script>dataagent.query('SELECT 1')</script>第一版</body></html>";

    @BeforeEach
    void setup() throws Exception {
        group.setMdlVersion(1);
        when(query.resolve(any(), eq("group"))).thenReturn(group);
        when(access.requireQuery("alice", "group")).thenReturn(group);
        when(access.requireQuery("bob", "group")).thenReturn(group);
        when(access.role("alice", group)).thenReturn(KnowledgeBaseAccessService.Role.MANAGE);
        when(access.role("bob", group)).thenReturn(KnowledgeBaseAccessService.Role.QUERY);
        when(access.requireManage("alice", "group")).thenReturn(group);
        when(access.requireManage("bob", "group"))
                .thenThrow(new DatasetException("需要知识库管理权限", 403));
        Path published = home.resolve("published");
        Files.createDirectories(published.resolve("target"));
        Files.writeString(published.resolve("target/mdl.json"), "{\"models\":[]}");
        Files.writeString(published.resolve("wren_project.yml"), "name: test\n");
        when(projects.publishedRoot("group")).thenReturn(published);
        when(projects.locked(eq("group"), any()))
                .thenAnswer(call -> ((Supplier<?>) call.getArgument(1)).get());
        when(cli.run(any(), any(), anyList()))
                .thenAnswer(
                        call -> {
                            commands.add(List.copyOf(call.getArgument(2)));
                            return new WrenCli.Result(0, "official success");
                        });
        props = new WrenProperties("wren", home.resolve("mdl").toString(), "test", "mysql", 10);
        service = new DashboardService(props, access, query, projects, cli);
    }

    private RuntimeContext context(String user, String id) {
        var c = DashboardService.context(user, "group");
        c.put(DashboardTurn.class, new DashboardTurn(id));
        return c;
    }

    private void write(String id, String html) {
        service.withWorkspace(
                context("alice", id),
                w -> {
                    try {
                        Files.writeString(service.file(w, "apps/dashboard/index.html", true), html);
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                    return null;
                });
    }

    @Test
    void privateDraftPublishesAnImmutableRevisionAndSurvivesRestart() {
        var draft = service.create("alice", "group", "销售概览");
        assertThat(service.list("bob")).isEmpty();
        assertThatThrownBy(() -> service.get("bob", draft.id(), true)).hasMessageContaining("草稿");
        write(draft.id(), FIRST);
        var before = service.get("alice", draft.id(), true);
        service.genbi(context("alice", draft.id()), "build", "生成销售概览");
        service.genbi(context("alice", draft.id()), "verify", null);
        assertThat(service.get("alice", draft.id(), true).verified()).isTrue();
        service.publish("alice", draft.id(), before.revision());
        assertThat(service.get("bob", draft.id(), false).html()).isEqualTo(FIRST);
        write(draft.id(), FIRST.replace("第一版", "第二版"));
        assertThat(service.get("alice", draft.id(), true).verified()).isFalse();
        assertThat(service.get("bob", draft.id(), false).html()).isEqualTo(FIRST);
        assertThatThrownBy(() -> service.publish("alice", draft.id(), before.revision()))
                .hasMessageContaining("草稿已变化");
        var restarted = new DashboardService(props, access, query, projects, cli);
        assertThat(restarted.get("bob", draft.id(), false).html()).isEqualTo(FIRST);
        assertThat(commands)
                .contains(
                        List.of(
                                "genbi",
                                "build",
                                "dashboard",
                                "--prompt",
                                "生成销售概览",
                                "--data-mode",
                                "live"),
                        List.of("genbi", "verify", "dashboard"));
    }

    @Test
    void libraryOwnershipDoesNotConfuseKnowledgeManagersWithCreators() {
        var own = service.create("bob", "group", "成员创建的看板");
        assertThat(own.owned()).isTrue();
        assertThat(own.canPublish()).isFalse();
        var ownerList = service.list("bob");
        assertThat(ownerList).hasSize(1);
        assertThat(ownerList.get(0).owned()).isTrue();
        assertThat(ownerList.get(0).html()).isNull();
        var managerList = service.list("alice");
        assertThat(managerList).hasSize(1);
        assertThat(managerList.get(0).owned()).isFalse();
        assertThat(managerList.get(0).canEdit()).isTrue();
        assertThat(managerList.get(0).canPublish()).isTrue();
    }

    @Test
    void deleteRemovesAllRevisionsAndProjectButNeverOtherAssets() throws Exception {
        var board = service.create("alice", "group", "删除验收");
        var other = service.create("alice", "group", "保留");
        write(board.id(), FIRST);
        service.genbi(context("alice", board.id()), "verify", null);
        service.publish("alice", board.id(), service.get("alice", board.id(), true).revision());
        Path directory = props.mdlRoot().resolve("dashboards").resolve(board.id());
        Files.writeString(directory.resolve("leftover.tmp"), "private");
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        assertThatThrownBy(
                        () ->
                                service.delete(
                                        "bob", board.id(), () -> {}, p -> calls.incrementAndGet()))
                .hasMessageContaining("无法删除");
        assertThat(calls.get()).isZero();
        service.delete("alice", board.id(), () -> {}, p -> calls.incrementAndGet());
        assertThat(calls.get()).isEqualTo(1);
        assertThat(directory).doesNotExist();
        assertThat(service.get("alice", other.id(), true).id()).isEqualTo(other.id());
        assertThat(home.resolve("published/target/mdl.json")).exists();
    }

    @Test
    void incompleteDeletionBlocksAllWorkAndSurvivesRestartForRetry() {
        var board = service.create("alice", "group", "待清理");
        write(board.id(), FIRST);
        assertThatThrownBy(
                        () ->
                                service.delete(
                                        "alice",
                                        board.id(),
                                        () -> {},
                                        p -> {
                                            throw new DatasetException("附件存储不可用", 503);
                                        }))
                .hasMessageContaining("不可用");
        assertThat(service.list("alice").get(0).deletionPending()).isTrue();
        assertThatThrownBy(() -> service.get("alice", board.id(), true))
                .hasMessageContaining("正在清理");
        assertThatThrownBy(() -> service.genbi(context("alice", board.id()), "build", "生成"))
                .hasMessageContaining("正在清理");
        assertThatThrownBy(() -> service.publish("alice", board.id(), "old"))
                .hasMessageContaining("正在清理");
        assertThatThrownBy(() -> service.queryGroup("alice", board.id(), true, "old"))
                .hasMessageContaining("正在清理");
        var restarted = new DashboardService(props, access, query, projects, cli);
        restarted.delete("alice", board.id(), () -> {}, p -> {});
        assertThat(restarted.list("alice")).isEmpty();
    }

    @Test
    void activeOrRevokedDeletionNeverCreatesATombstone() {
        var board = service.create("alice", "group", "保留");
        assertThatThrownBy(
                        () ->
                                service.delete(
                                        "alice",
                                        board.id(),
                                        () -> {
                                            throw new DatasetException("仍在生成", 409);
                                        },
                                        p -> {}))
                .hasMessageContaining("仍在生成");
        assertThat(service.get("alice", board.id(), true).deletionPending()).isFalse();
        assertThatThrownBy(
                        () ->
                                service.delete(
                                        "alice",
                                        board.id(),
                                        () -> {
                                            when(access.requireQuery("alice", "group"))
                                                    .thenThrow(new DatasetException("未授权", 404));
                                        },
                                        p -> {}))
                .hasMessageContaining("未授权");
        assertThat(
                        props.mdlRoot()
                                .resolve("dashboards")
                                .resolve(board.id())
                                .resolve("deleting.flag"))
                .doesNotExist();
    }

    @Test
    void deletionRejectsLinkedChildrenBeforeAnyCleanup() throws Exception {
        var board = service.create("alice", "group", "路径安全");
        Path outside = Files.createDirectories(home.resolve("outside"));
        Files.writeString(outside.resolve("keep.txt"), "keep");
        Path link = props.mdlRoot().resolve("dashboards").resolve(board.id()).resolve("redirect");
        try {
            Files.createSymbolicLink(link, outside);
        } catch (java.io.IOException | UnsupportedOperationException e) {
            org.junit.jupiter.api.Assumptions.abort("Symbolic links unavailable on this host");
        }
        var cleaned = new java.util.concurrent.atomic.AtomicBoolean();
        assertThatThrownBy(
                        () -> service.delete("alice", board.id(), () -> {}, p -> cleaned.set(true)))
                .hasMessageContaining("符号链接");
        assertThat(cleaned.get()).isFalse();
        assertThat(outside.resolve("keep.txt")).hasContent("keep");
    }

    @Test
    void invocationCannotSelectAnotherScopeOrWriteOutsideTheSingleHtml() {
        var draft = service.create("alice", "group", null);
        var c = context("alice", draft.id());
        c.put(DatasetScope.class, new DatasetScope("alice", List.of("other")));
        assertThatThrownBy(() -> service.genbi(c, "build", "看板")).hasMessageContaining("不匹配");
        assertThatThrownBy(() -> service.genbi(RuntimeContext.empty(), "build", "看板"))
                .hasMessageContaining("工作台");
        for (String path :
                List.of(
                        "../asset.json",
                        "/C:/secret",
                        "apps/dashboard/../../asset.json",
                        "target/mdl.json",
                        ".wren/apps.yml",
                        "apps/dashboard/mdl.json"))
            assertThatThrownBy(
                            () ->
                                    service.withWorkspace(
                                            context("alice", draft.id()),
                                            w -> service.file(w, path, true)))
                    .isInstanceOf(DatasetException.class);
        assertThatThrownBy(() -> service.genbi(context("alice", draft.id()), "deploy", "看板"))
                .hasMessageContaining("仅开放");
        assertThatThrownBy(() -> service.genbi(context("alice", draft.id()), "verify", null))
                .hasMessageContaining("完整 HTML");
    }

    @Test
    void queryAndPublicationRecheckRevokedRightsAndOldRevisions() {
        var draft = service.create("bob", "group", "成员草稿");
        assertThatThrownBy(() -> service.publish("bob", draft.id(), "unknown"))
                .hasMessageContaining("管理权限");
        write(draft.id(), FIRST);
        var revision = service.get("alice", draft.id(), true).revision();
        doAnswer(
                        call -> {
                            List<String> args = call.getArgument(2);
                            if (args.equals(List.of("genbi", "verify", "dashboard")))
                                when(access.requireManage("alice", "group"))
                                        .thenThrow(new DatasetException("权限已撤销", 403));
                            return new WrenCli.Result(0, "ok");
                        })
                .when(cli)
                .run(any(), any(), anyList());
        assertThatThrownBy(() -> service.publish("alice", draft.id(), revision))
                .hasMessageContaining("撤销");
        assertThat(service.get("alice", draft.id(), true).publishedRevision()).isNull();
        when(access.requireQuery("bob", "group")).thenThrow(new DatasetException("未授权", 404));
        assertThatThrownBy(() -> service.queryGroup("bob", draft.id(), true, revision))
                .hasMessageContaining("未授权");
        assertThat(service.list("bob")).isEmpty();
    }

    @Test
    void officialVerificationFailureAndChangedModelNeverPublish() {
        var draft = service.create("alice", "group", "看板");
        write(draft.id(), FIRST);
        String rev = service.get("alice", draft.id(), true).revision();
        when(cli.run(any(), any(), anyList()))
                .thenReturn(new WrenCli.Result(1, "secret scan rejected"));
        assertThatThrownBy(() -> service.publish("alice", draft.id(), rev))
                .hasMessageContaining("secret scan rejected");
        assertThat(service.get("alice", draft.id(), true).publishedRevision()).isNull();
        group.setMdlVersion(2);
        assertThatThrownBy(() -> service.genbi(context("alice", draft.id()), "verify", null))
                .hasMessageContaining("新模型");
    }
}
