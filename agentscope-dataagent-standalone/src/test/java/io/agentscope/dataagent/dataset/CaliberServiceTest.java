package io.agentscope.dataagent.dataset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.agentscope.dataagent.web.persistence.jpa.DatasetEntity;
import io.agentscope.dataagent.web.persistence.jpa.DatasetGroupEntity;
import io.agentscope.dataagent.web.persistence.jpa.DatasetRepository;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CaliberServiceTest {
    @TempDir Path home;
    private final DatasetGroupService groups = mock(DatasetGroupService.class);
    private final DatasetRepository datasets = mock(DatasetRepository.class);
    private final TableProvisioner provisioner = mock(TableProvisioner.class);
    private final MdlPublishService publisher = mock(MdlPublishService.class);
    private MdlWorkspaceService workspace;
    private CaliberService service;
    private Path root;
    private DatasetEntity dataset;

    static final String LIST =
            """
            model: vip_m
            name: vip
            scenario:
              subject: 会员规模
              measures:
                - {column: cnt_zd, name: members}
              fixed_scope: {province_name: 全国}
            calibers:
              - {name: 足球通, where: {vip_type_6: 足球通会员}}
              - {name: 橄榄球, where: {vip_type_6: 橄榄球会员, vip_type_2: 不限}}
            """;

    @Test
    void publishedStateTellsWhetherQueryUsesTheDraftAnEarlierListOrNone() throws Exception {
        write("calibers/vip_m.yml", LIST);
        assertThat(service.overview("alice", "g").get(0).published()).isEqualTo("NONE");
        Path published = root.getParent().resolve("published").resolve("calibers");
        Files.createDirectories(published);
        Files.writeString(published.resolve("vip_m.yml"), LIST, StandardCharsets.UTF_8);
        assertThat(service.overview("alice", "g").get(0).published()).isEqualTo("CURRENT");
        write("calibers/vip_m.yml", LIST.replace("橄榄球会员", "橄榄球通会员"));
        assertThat(service.overview("alice", "g").get(0).published()).isEqualTo("OLDER");
        // A table without a list has nothing published.
        Files.delete(root.resolve("calibers/vip_m.yml"));
        assertThat(service.overview("alice", "g").get(0).published()).isEqualTo("NONE");
    }

    @BeforeEach
    void setUp() throws Exception {
        workspace =
                new MdlWorkspaceService(
                        new WrenProperties("wren", home.toString(), "dataagent", "mysql", 10),
                        (cwd, timeout, args) -> new WrenCli.Result(0, ""));
        service = new CaliberService(groups, workspace, datasets, provisioner, publisher);
        when(groups.getGroup("alice", "g"))
                .thenReturn(new DatasetGroupEntity("g", "alice", "会员", ""));
        when(groups.getGroup("bob", "g")).thenThrow(new DatasetException("知识库不存在", 404));
        root = workspace.workspaceRoot("g");
        write(
                "models/vip_m/metadata.yml",
                "name: vip_m\n"
                        + "columns:\n"
                        + "  - {name: cnt_zd, type: BIGINT}\n"
                        + "  - {name: province_name, type: VARCHAR}\n"
                        + "  - {name: vip_type_2, type: VARCHAR}\n"
                        + "  - {name: vip_type_6, type: VARCHAR}\n");
        write("models/other_m/metadata.yml", "name: other_m\ncolumns: []\n");
        write(".platform/datasets.json", "{\"d1\": \"vip_m\"}");
        write("raw/docs/会员.md", "适用模型：`vip_m`\n");
        dataset = new DatasetEntity();
        dataset.setId("d1");
        dataset.setTableName("ds_vip");
        dataset.setRowCount(327);
        dataset.setUpdatedAt(Instant.parse("2026-11-24T00:00:00Z"));
        dataset.setOrigin("upload");
        when(datasets.findByGroupId("g")).thenReturn(List.of(dataset));
    }

    @Test
    void documentedTablesAreDraftableThenCurrentThenOutdatedWhenTheDocumentChanges()
            throws Exception {
        assertThat(service.overview("alice", "g"))
                .singleElement()
                .satisfies(
                        t -> {
                            assertThat(t.model()).isEqualTo("vip_m");
                            assertThat(t.status()).isEqualTo("DRAFTABLE");
                            assertThat(t.documents()).containsExactly("会员.md");
                        });

        draft();
        assertThat(service.overview("alice", "g").get(0).status()).isEqualTo("CURRENT");

        write("raw/docs/会员.md", "适用模型：`vip_m`\n新增橄榄球\n");
        var outdated = service.overview("alice", "g").get(0);
        assertThat(outdated.status()).isEqualTo("OUTDATED");
        assertThat(outdated.changedDocuments()).containsExactly("会员.md");
    }

    @Test
    void uploadedTablesFlagMissingValuesOnceMeasuredAndCached() throws Exception {
        draft();
        when(provisioner.distinctValues("ds_vip", "vip_type_6", CaliberService.VALUE_LIMIT))
                .thenReturn(List.of("足球通会员", "篮球通会员"));
        when(provisioner.distinctValues("ds_vip", "province_name", CaliberService.VALUE_LIMIT))
                .thenReturn(List.of("全国"));
        when(provisioner.distinctValues("ds_vip", "vip_type_2", CaliberService.VALUE_LIMIT))
                .thenReturn(null);

        var rows = service.overview("alice", "g").get(0).rows();

        assertThat(rows.get(0).valueIssues()).isEmpty();
        assertThat(rows.get(1).valueIssues())
                .containsExactly(new CaliberService.ValueIssue("vip_type_6", "橄榄球会员", "missing"));
        assertThat(rows.get(0).source().document()).isEqualTo("会员.md");
        service.overview("alice", "g");
        verify(provisioner, times(1))
                .distinctValues("ds_vip", "vip_type_6", CaliberService.VALUE_LIMIT);
    }

    @Test
    void externalTablesOnlyCompareWithTheirSampleAndNeverQuery() throws Exception {
        draft();
        dataset.setOrigin("datasource");
        dataset.setSampleSummaryJson(
                DatasetSampling.encode(
                        new DatasetSampling.Summary(
                                1,
                                "OK",
                                "2026-11-24",
                                100,
                                100,
                                "limit",
                                List.of(
                                        new DatasetSampling.ColumnSample(
                                                "vip_type_6",
                                                List.of("足球通会员"),
                                                0,
                                                1,
                                                false,
                                                false)))));

        var rows = service.overview("alice", "g").get(0).rows();

        assertThat(rows.get(1).valueIssues())
                .containsExactly(new CaliberService.ValueIssue("vip_type_6", "橄榄球会员", "sample"));
        verifyNoInteractions(provisioner);
    }

    @Test
    void anotherUsersKnowledgeBaseIsRefused() {
        assertThatThrownBy(() -> service.overview("bob", "g"))
                .isInstanceOf(DatasetException.class)
                .hasMessageContaining("不存在");
        verify(provisioner, times(0)).distinctValues(anyString(), anyString(), anyInt());
    }

    @Test
    void reviewThenVerifyThenVerdictPassesRowsAndAnEditVoidsOnlyThatRow() throws Exception {
        draft();
        org.mockito.Mockito.when(
                        publisher.planDraftCubeQueries(
                                org.mockito.ArgumentMatchers.eq("g"),
                                org.mockito.ArgumentMatchers.anyList()))
                .thenAnswer(
                        inv -> {
                            List<List<String>> queries = inv.getArgument(1);
                            return queries.stream()
                                    .map(
                                            q ->
                                                    new MdlPublishService.DraftPlan(
                                                            String.join(" ", q),
                                                            "SELECT expanded",
                                                            null))
                                    .toList();
                        });
        String path = "calibers/vip_m.yml";

        // Unreviewed rows cannot be verified, and nothing ships yet.
        assertThatThrownBy(() -> service.verify("alice", "g", path, List.of("足球通")))
                .hasMessageContaining("尚未核查");
        var reviewed = service.review("alice", "g", path, true, List.of("足球通", "橄榄球"));
        assertThat(reviewed.scenarioReviewed()).isTrue();
        assertThat(reviewed.rows())
                .allSatisfy(r -> assertThat(r.review()).isEqualTo(CaliberReviews.VERIFY_PENDING));
        assertThat(reviewed.publishable()).isFalse();

        var checks = service.verify("alice", "g", path, null);
        assertThat(checks).extracting(CaliberService.Check::caliber).containsExactly("足球通", "橄榄球");
        assertThat(checks.get(0).query()).contains("--filter caliber:in:足球通", "--measures members");
        assertThat(checks.get(0).segment())
                .contains("vip_type_6 = '足球通会员'", "province_name = '全国'", "FROM vip_m");
        assertThat(checks.get(0).question()).isEqualTo("足球通的cnt_zd");

        var judged =
                service.verdict(
                        "alice", "g", path, java.util.Map.of("足球通", "EXPECTED", "橄榄球", "PROBLEM"));
        assertThat(judged.rows())
                .extracting(CaliberService.Row::review)
                .containsExactly(CaliberReviews.PASSED, CaliberReviews.PROBLEM);
        assertThat(judged.publishable()).isFalse();
        service.verify("alice", "g", path, List.of("橄榄球"));
        assertThat(
                        service.verdict("alice", "g", path, java.util.Map.of("橄榄球", "EXPECTED"))
                                .publishable())
                .isTrue();

        // Editing one row voids only that row; a stale verdict is refused.
        String edited = LIST.replace("vip_type_6: 橄榄球会员", "vip_type_6: 橄榄球通会员");
        write("calibers/vip_m.yml", edited);
        var table = service.overview("alice", "g").get(0);
        assertThat(table.rows())
                .extracting(CaliberService.Row::review)
                .containsExactly(CaliberReviews.PASSED, CaliberReviews.REVIEW_PENDING);
        assertThat(table.publishable()).isFalse();
        assertThatThrownBy(
                        () ->
                                service.verdict(
                                        "alice", "g", path, java.util.Map.of("橄榄球", "EXPECTED")))
                .hasMessageContaining("重新验证");
    }

    @Test
    void conflictedRowsCannotBeReviewedAndOtherUsersAreRefused() throws Exception {
        draft();
        var state = CaliberSources.read(root);
        var conflicts = new java.util.ArrayList<>(state.conflicts());
        conflicts.add(
                new CaliberSources.Conflict(
                        "calibers/vip_m.yml", "足球通", "edit", "会员.md", null, "now"));
        Files.writeString(
                root.resolve(".platform/caliber-sources.json"),
                new com.fasterxml.jackson.databind.ObjectMapper()
                        .writeValueAsString(
                                new CaliberSources.State(
                                        state.rows(), conflicts, state.documents())));

        assertThatThrownBy(
                        () ->
                                service.review(
                                        "alice", "g", "calibers/vip_m.yml", false, List.of("足球通")))
                .hasMessageContaining("冲突");
        assertThatThrownBy(
                        () ->
                                service.review(
                                        "bob", "g", "calibers/vip_m.yml", true, List.of("橄榄球")))
                .isInstanceOf(DatasetException.class);
        assertThatThrownBy(() -> service.review("alice", "g", "../x.yml", true, List.of()))
                .hasMessageContaining("路径无效");
        assertThat(CaliberReviews.read(root)).isEmpty();
    }

    private void draft() throws Exception {
        write("calibers/vip_m.yml", LIST);
        assertThat(
                        CaliberSources.apply(
                                root,
                                "calibers/vip_m.yml",
                                null,
                                LIST,
                                CaliberSources.DOCUMENT,
                                "alice",
                                BusinessDocumentService.documentsFor(root, "vip_m")))
                .isNull();
        CaliberCompiler.sync(root);
    }

    private void write(String relative, String content) throws Exception {
        Path file = root.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }
}
