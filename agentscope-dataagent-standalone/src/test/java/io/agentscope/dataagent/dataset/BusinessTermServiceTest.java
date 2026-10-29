package io.agentscope.dataagent.dataset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.agentscope.dataagent.web.persistence.jpa.DatasetGroupEntity;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** spec100: business terms stored as the official Naming conventions of one knowledge base. */
class BusinessTermServiceTest {
    @TempDir Path home;
    private Path draft;
    private Path published;
    private final KnowledgeBaseAccessService access = mock(KnowledgeBaseAccessService.class);
    private final DatasetGroupService groups = mock(DatasetGroupService.class);
    private final ModelingActivity activity = new ModelingActivity();
    private BusinessTermService service;
    private final DatasetGroupEntity group = mock(DatasetGroupEntity.class);

    @BeforeEach
    void setup() throws IOException {
        var workspace =
                new MdlWorkspaceService(
                        new WrenProperties("wren", home.toString(), "dataagent", "mysql", 30),
                        (path, timeout, args) -> new WrenCli.Result(0, ""));
        draft = workspace.workspaceRoot("g1");
        published = draft.getParent().resolve("published");
        service =
                new BusinessTermService(
                        access, workspace, new DraftItemService(groups, workspace, activity));
        when(group.getMdlVersion()).thenReturn(2);
        when(access.requireQuery(anyString(), eq("g1"))).thenReturn(group);
        when(access.role(eq("manager"), any())).thenReturn(KnowledgeBaseAccessService.Role.MANAGE);
        when(access.role(eq("viewer"), any())).thenReturn(KnowledgeBaseAccessService.Role.QUERY);
        String online =
                "## Naming conventions\n\n"
                        + "- \"仪表盘\"（同义词：\"看板\"、\"大屏\"）= dashboard 模型中的一个仪表盘\n"
                        + "- \"大客户\" = 年合同额超过 100 万元的客户\n";
        write(published, BusinessTermService.PATH, online);
        write(draft, BusinessTermService.PATH, online);
    }

    private static void write(Path base, String path, String content) throws IOException {
        Path file = base.resolve(path);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }

    private String draftFile() throws IOException {
        return Files.readString(draft.resolve(BusinessTermService.PATH), StandardCharsets.UTF_8);
    }

    @Test
    void entriesFollowTheOfficialBulletFormatAndFreeTextIsKept() {
        var terms =
                BusinessTermService.parse(
                        "## Naming conventions\n"
                                + "- \"GMV\"（同义词：\"成交额\"）= 已支付订单金额合计\n"
                                + "- \"ARR\" = subscriptions.mrr * 12\n"
                                + "- customer means the customers model\n"
                                + "  not customers_v3\n");
        assertThat(terms).hasSize(3);
        assertThat(terms.get(0).term()).isEqualTo("GMV");
        assertThat(terms.get(0).synonyms()).containsExactly("成交额");
        assertThat(terms.get(0).explanation()).isEqualTo("已支付订单金额合计");
        assertThat(terms.get(1).synonyms()).isEmpty();
        assertThat(terms.get(2).structured()).isFalse();
        assertThat(terms.get(2).text())
                .isEqualTo("customer means the customers model\nnot customers_v3");

        String text =
                BusinessTermService.format(
                        new BusinessTermService.Input("活跃用户", "日活、DAU，活跃用户", "近 28 天有访问的用户", null));
        assertThat(text).isEqualTo("\"活跃用户\"（同义词：\"日活\"、\"DAU\"）= 近 28 天有访问的用户");
        assertThat(BusinessTermService.parseEntry(text).synonyms()).containsExactly("日活", "DAU");
        assertThrows(
                DatasetException.class,
                () ->
                        BusinessTermService.format(
                                new BusinessTermService.Input("a\"b", "", "x", null)));
        assertThrows(
                DatasetException.class,
                () ->
                        BusinessTermService.format(
                                new BusinessTermService.Input("术语", "", " ", null)));
    }

    @Test
    void managersEditTheDraftAndSeeItAgainstThePublishedVersion() throws IOException {
        var after =
                service.add(
                        "manager",
                        "g1",
                        new BusinessTermService.Input("活跃用户", "日活", "近 28 天有访问的用户", null));
        String file = draftFile();
        assertThat(file).startsWith("## Naming conventions\n\n- \"仪表盘\"");
        assertThat(file).contains("- \"活跃用户\"（同义词：\"日活\"）= 近 28 天有访问的用户");
        // Published snapshot is untouched until publish.
        assertThat(Files.readString(published.resolve(BusinessTermService.PATH)))
                .doesNotContain("活跃用户");
        assertThat(after.editable()).isTrue();
        assertThat(after.publishedVersion()).isEqualTo(2);
        assertThat(after.rows())
                .extracting(BusinessTermService.Row::term, BusinessTermService.Row::status)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("仪表盘", "PUBLISHED"),
                        org.assertj.core.groups.Tuple.tuple("大客户", "PUBLISHED"),
                        org.assertj.core.groups.Tuple.tuple("活跃用户", "ADDED"));

        String dashboard = after.rows().get(0).text();
        var modified =
                service.update(
                        "manager",
                        "g1",
                        dashboard,
                        new BusinessTermService.Input("仪表盘", "看板", "仪表盘（dashboard）", null));
        assertThat(modified.rows().get(0).status()).isEqualTo("MODIFIED");
        assertThat(modified.rows().get(0).publishedText()).isEqualTo(dashboard);

        var deleted = service.delete("manager", "g1", modified.rows().get(1).text());
        assertThat(deleted.rows())
                .filteredOn(r -> r.term().equals("大客户"))
                .singleElement()
                .extracting(BusinessTermService.Row::status)
                .isEqualTo("DELETED");
        assertThat(draftFile()).doesNotContain("大客户");
        assertThat(DraftChangeLog.read(draft))
                .extracting(DraftChangeLog.Entry::path)
                .contains(BusinessTermService.PATH);
    }

    @Test
    void termsAndSynonymsAreUniqueWithinTheKnowledgeBase() {
        var duplicate =
                assertThrows(
                        DatasetException.class,
                        () ->
                                service.add(
                                        "manager",
                                        "g1",
                                        new BusinessTermService.Input("报表大屏", "看板", "说明", null)));
        assertThat(duplicate.getMessage()).contains("看板").contains("仪表盘");
        assertThat(duplicate.status()).isEqualTo(409);
    }

    @Test
    void queryUsersSeeOnlyThePublishedVersionAndCannotEdit() throws IOException {
        write(draft, BusinessTermService.PATH, "## Naming conventions\n\n- \"草稿术语\" = 未发布\n");
        var view = service.list("viewer", "g1");
        assertThat(view.editable()).isFalse();
        assertThat(view.rows())
                .extracting(BusinessTermService.Row::term)
                .containsExactly("仪表盘", "大客户");
        assertThat(view.rows()).allMatch(r -> r.status().equals("PUBLISHED"));

        when(access.requireManage("viewer", "g1"))
                .thenThrow(new DatasetException("需要知识库管理权限", 403));
        var refused =
                assertThrows(
                        DatasetException.class,
                        () ->
                                service.add(
                                        "viewer",
                                        "g1",
                                        new BusinessTermService.Input("新术语", "", "说明", null)));
        assertThat(refused.status()).isEqualTo(403);
        assertThat(draftFile()).doesNotContain("新术语");
    }

    @Test
    void queuedReadersRecheckRevokedPermissionsBeforeReadingFiles() {
        when(access.requireQuery("viewer", "g1"))
                .thenReturn(group)
                .thenThrow(new DatasetException("知识库不存在或不可见", 404));
        var refused = assertThrows(DatasetException.class, () -> service.list("viewer", "g1"));
        assertThat(refused.status()).isEqualTo(404);
    }

    @Test
    void queuedManagersRecheckDowngradedRoleBeforeReadingDrafts() throws IOException {
        write(draft, BusinessTermService.PATH, "## Naming conventions\n- \"草稿机密\" = 未发布\n");
        when(access.role("manager", group)).thenReturn(KnowledgeBaseAccessService.Role.QUERY);
        assertThat(service.list("manager", "g1").rows())
                .extracting(BusinessTermService.Row::term)
                .containsExactly("仪表盘", "大客户");
    }

    @Test
    void strangersCannotReadAnotherKnowledgeBasesTerms() {
        when(access.requireQuery("stranger", "g1"))
                .thenThrow(new DatasetException("知识库不存在或不可见", 404));
        var refused = assertThrows(DatasetException.class, () -> service.list("stranger", "g1"));
        assertThat(refused.status()).isEqualTo(404);
    }

    @Test
    void revokedAccessWhileWaitingForTheLockIsRefused() throws IOException {
        // getGroup passes before the lock and fails on the re-check inside it.
        when(groups.getGroup("manager", "g1"))
                .thenReturn(group)
                .thenThrow(new DatasetException("需要知识库管理权限", 403));
        var refused =
                assertThrows(
                        DatasetException.class,
                        () ->
                                service.add(
                                        "manager",
                                        "g1",
                                        new BusinessTermService.Input("新术语", "", "说明", null)));
        assertThat(refused.status()).isEqualTo(403);
        assertThat(draftFile()).doesNotContain("新术语");
    }

    @Test
    void editsWaitWhileTheModelingAssistantRuns() {
        activity.begin("g1");
        try {
            assertThrows(
                    DatasetException.class,
                    () ->
                            service.add(
                                    "manager",
                                    "g1",
                                    new BusinessTermService.Input("新术语", "", "说明", null)));
        } finally {
            activity.end("g1");
        }
    }

    @Test
    void appendCreatesTheFileAndSectionWhenMissing() throws IOException {
        Files.delete(draft.resolve(BusinessTermService.PATH));
        service.add("manager", "g1", new BusinessTermService.Input("首个术语", "", "说明", null));
        assertThat(draftFile()).isEqualTo("## Naming conventions\n\n- \"首个术语\" = 说明\n");
        List<BusinessTermService.Row> rows = service.list("manager", "g1").rows();
        assertThat(rows).extracting(BusinessTermService.Row::status).contains("ADDED", "DELETED");
    }
}
