package io.agentscope.dataagent.dataset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.agentscope.dataagent.runtime.wren.WrenQueryGateway;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SemanticAssetServiceTest {
    private static final String GROUP = "g";
    private static final String USER = "alice";
    private static final String VIEW = "sales_valid";
    private static final String SQL = "SELECT COUNT(*) FROM sales_valid";

    @TempDir Path temp;

    private final DatasetGroupService groups = mock(DatasetGroupService.class);
    private final MdlWorkspaceReader reader = mock(MdlWorkspaceReader.class);
    private final MdlWorkspaceService workspace = mock(MdlWorkspaceService.class);
    private final WrenProperties props = mock(WrenProperties.class);
    private final MdlPublishService publisher = mock(MdlPublishService.class);
    private final AssetSqlGenerator generator = mock(AssetSqlGenerator.class);
    private final FactQueryWindowPolicy windows = mock(FactQueryWindowPolicy.class);
    private final WrenQueryGateway gateway = mock(WrenQueryGateway.class);
    private AssetReviewStore store;
    private SemanticAssetService service;
    private Path draft;
    private Path group;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() throws Exception {
        group = temp.resolve("mdl");
        draft = temp.resolve("draft");
        write(draft.resolve("views/" + VIEW + "/metadata.yml"), "name: sales_valid\n");
        when(workspace.workspaceRoot(GROUP)).thenReturn(draft);
        when(props.groupRoot(GROUP)).thenReturn(group);
        when(workspace.withWorkspaceLock(eq(GROUP), any()))
                .thenAnswer(call -> ((Supplier<Object>) call.getArgument(1)).get());
        when(reader.read(GROUP))
                .thenReturn(
                        new MdlWorkspaceReader.Snapshot(
                                List.of(),
                                List.of(),
                                List.of(),
                                List.of(),
                                List.of(),
                                List.of(
                                        new MdlWorkspaceReader.WorkspaceView(
                                                VIEW,
                                                "SELECT day FROM orders",
                                                "",
                                                "views/" + VIEW + "/metadata.yml"))));
        when(windows.facts(GROUP)).thenReturn(List.of());
        store = new AssetReviewStore(reader, workspace, props);
        service =
                new SemanticAssetService(
                        groups, store, publisher, workspace, reader, windows, gateway, generator);
    }

    private static void write(Path file, String content) throws Exception {
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    private AssetReviewStore.Asset asset() {
        return store.require(GROUP, AssetReviewStore.KIND_VIEW, VIEW);
    }

    private void review() {
        service.review(USER, GROUP, AssetReviewStore.KIND_VIEW, VIEW, List.of());
    }

    private void run() {
        // spec110: verification only plans the SQL; nothing is executed.
        when(publisher.planDraft(eq(GROUP), anyList()))
                .thenReturn(
                        List.of(
                                new MdlPublishService.DraftPlan(
                                        SQL,
                                        "SELECT COUNT(*) FROM (SELECT ...) sales_valid",
                                        null)));
        service.verify(USER, GROUP, AssetReviewStore.KIND_VIEW, VIEW, cases());
    }

    private static List<SemanticAssetService.VerifyCase> cases() {
        return List.of(new SemanticAssetService.VerifyCase("总量", SQL));
    }

    private static String sqlKey() throws IllegalStateException {
        try {
            return java.util.HexFormat.of()
                    .formatHex(
                            java.security.MessageDigest.getInstance("SHA-256")
                                    .digest(SQL.getBytes()))
                    .substring(0, 16);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void newAssetBlocksPublishingUntilReviewedAndVerified() {
        assertThat(asset().status()).isEqualTo(AssetReviewStore.REVIEW_PENDING);
        assertThat(store.publishIssues(GROUP))
                .singleElement()
                .matches(i -> i.message().contains("尚未核查"));

        review();
        assertThat(asset().status()).isEqualTo(AssetReviewStore.VERIFY_PENDING);
        assertThat(store.publishIssues(GROUP))
                .singleElement()
                .matches(i -> i.message().contains("尚未通过问题验证"));

        run();
        service.verdict(
                USER,
                GROUP,
                AssetReviewStore.KIND_VIEW,
                VIEW,
                Map.of("sql:" + sqlKey(), "EXPECTED"));
        assertThat(asset().status()).isEqualTo(AssetReviewStore.VERIFIED);
        assertThat(store.publishIssues(GROUP)).isEmpty();
    }

    @Test
    void anyEditVoidsTheEarlierConclusion() {
        review();
        run();
        service.verdict(
                USER,
                GROUP,
                AssetReviewStore.KIND_VIEW,
                VIEW,
                Map.of("sql:" + sqlKey(), "EXPECTED"));

        service.save(
                USER,
                GROUP,
                AssetReviewStore.KIND_VIEW,
                VIEW,
                "metadata.yml",
                "name: sales_valid\ndescription: changed\n",
                asset().hash());

        assertThat(asset().status()).isEqualTo(AssetReviewStore.REVIEW_PENDING);
        assertThat(asset().review()).isNull();
        verify(groups).markMdlDirty(USER, GROUP);
    }

    @Test
    void assetMatchingThePublishedSnapshotNeedsNoReview() throws Exception {
        write(group.resolve("published/views/" + VIEW + "/metadata.yml"), "name: sales_valid\n");
        assertThat(asset().status()).isEqualTo(AssetReviewStore.PUBLISHED);
        assertThat(store.publishIssues(GROUP)).isEmpty();
    }

    @Test
    void problemVerdictKeepsTheAssetUnverified() {
        review();
        run();
        service.verdict(
                USER,
                GROUP,
                AssetReviewStore.KIND_VIEW,
                VIEW,
                Map.of("sql:" + sqlKey(), "PROBLEM"));
        assertThat(asset().status()).isEqualTo(AssetReviewStore.VERIFY_PENDING);
    }

    /** spec110 / ADR0127: windows or aggregation over a limited fact table warn, never block. */
    @Test
    void windowOrAggregationOverALimitedTableWarns() {
        String window =
                "SELECT r.*, ROW_NUMBER() OVER (PARTITION BY user_account ORDER BY t) AS n FROM"
                        + " visits r";
        assertThat(SemanticAssetService.warnings(window, true))
                .singleElement()
                .asString()
                .contains("推不进内部计算");
        assertThat(
                        SemanticAssetService.warnings(
                                "SELECT user_account, COUNT(*) FROM visits GROUP BY 1", true))
                .hasSize(1);
        assertThat(SemanticAssetService.warnings(window, false)).isEmpty();
        assertThat(
                        SemanticAssetService.warnings(
                                "SELECT r.*, u.dept FROM visits r JOIN users u ON r.a = u.a", true))
                .isEmpty();
    }

    /** spec109: deleting an asset also removes the rule entries naming it, in the draft only. */
    @Test
    void deletingAnAssetRemovesItsRuleEntriesAndKeepsOtherRules() throws Exception {
        Path rules = draft.resolve("knowledge/rules/general.md");
        write(
                rules,
                "## Canonical tables\n"
                        + "- 按公司统计销售默认使用 sales_valid\n\n"
                        + "## Default filters\n"
                        + "- 默认排除测试订单\n");
        var impact = service.impact(USER, GROUP, AssetReviewStore.KIND_VIEW, VIEW);
        assertThat(impact.rules()).containsExactly("- 按公司统计销售默认使用 sales_valid");

        service.delete(USER, GROUP, AssetReviewStore.KIND_VIEW, VIEW);
        assertThat(Files.exists(draft.resolve("views/" + VIEW))).isFalse();
        assertThat(Files.readString(rules))
                .doesNotContain("sales_valid")
                .contains("## Canonical tables", "- 默认排除测试订单");
        verify(groups).markMdlDirty(USER, GROUP);
        // A physical model is not an asset and cannot be deleted here.
        assertThatThrownBy(
                        () -> service.delete(USER, GROUP, AssetReviewStore.KIND_REF_SQL, "orders"))
                .isInstanceOf(DatasetException.class);
    }

    /** spec108: expected verification cases can become confirmed questions, only as they ran. */
    @Test
    void expectedCasesAreSavedAsQuestionsOnlyForTheSqlThatRan() {
        var questions = org.mockito.Mockito.mock(MdlQuestionService.class);
        var saving =
                new SemanticAssetService(
                        groups, store, publisher, workspace, reader, windows, gateway, generator,
                        questions);
        review();
        run();
        var expected = Map.of("sql:" + sqlKey(), "EXPECTED");
        assertThatThrownBy(
                        () ->
                                saving.verdict(
                                        USER,
                                        GROUP,
                                        AssetReviewStore.KIND_VIEW,
                                        VIEW,
                                        expected,
                                        List.of(
                                                new SemanticAssetService.VerifyCase(
                                                        "总量", SQL + " LIMIT 5"))))
                .isInstanceOf(DatasetException.class)
                .hasMessageContaining("本次运行");
        assertThat(asset().status()).isEqualTo(AssetReviewStore.VERIFY_PENDING);
        org.mockito.Mockito.verifyNoInteractions(questions);

        saving.verdict(USER, GROUP, AssetReviewStore.KIND_VIEW, VIEW, expected, cases());
        assertThat(asset().status()).isEqualTo(AssetReviewStore.VERIFIED);
        verify(questions)
                .confirmVerified(
                        any(DatasetScope.class),
                        eq(GROUP),
                        eq("总量"),
                        org.mockito.ArgumentMatchers.contains("查询模板「" + VIEW + "」"),
                        eq(SQL));
    }

    @Test
    void problemCasesCannotBeSavedAsQuestions() {
        var questions = org.mockito.Mockito.mock(MdlQuestionService.class);
        var saving =
                new SemanticAssetService(
                        groups, store, publisher, workspace, reader, windows, gateway, generator,
                        questions);
        review();
        run();
        assertThatThrownBy(
                        () ->
                                saving.verdict(
                                        USER,
                                        GROUP,
                                        AssetReviewStore.KIND_VIEW,
                                        VIEW,
                                        Map.of("sql:" + sqlKey(), "PROBLEM"),
                                        cases()))
                .isInstanceOf(DatasetException.class);
        org.mockito.Mockito.verifyNoInteractions(questions);
    }

    @Test
    void verdictNeedsACompleteSuccessfulRun() {
        review();
        assertThatThrownBy(
                        () ->
                                service.verdict(
                                        USER,
                                        GROUP,
                                        AssetReviewStore.KIND_VIEW,
                                        VIEW,
                                        Map.of("x", "EXPECTED")))
                .isInstanceOf(DatasetException.class)
                .hasMessageContaining("请先运行问题验证");
    }

    @Test
    void verifyingBeforeReviewIsRefusedWithoutRunningAnything() {
        assertThatThrownBy(
                        () ->
                                service.verify(
                                        USER, GROUP, AssetReviewStore.KIND_VIEW, VIEW, cases()))
                .isInstanceOf(DatasetException.class);
        verify(publisher, never()).verifyAssets(anyString(), anyList(), any(), any());
    }

    @Test
    void assetOverLimitedTableCannotPassReviewWithoutTimeColumn() {
        when(windows.facts(GROUP))
                .thenReturn(List.of(new PublishedAssetWindows.Fact("orders", "ods_orders", 30)));

        assertThat(service.get(USER, GROUP, AssetReviewStore.KIND_VIEW, VIEW).needsTimeColumn())
                .isTrue();
        assertThatThrownBy(this::review)
                .isInstanceOf(DatasetException.class)
                .hasMessageContaining("时间列");

        service.review(USER, GROUP, AssetReviewStore.KIND_VIEW, VIEW, List.of("day"));
        assertThat(asset().review().timeColumns()).containsExactly("day");
    }

    @Test
    void windowRefusalIsReportedPerQuestionAndNeverReachesTheEngine() {
        review();
        doThrow(new DatasetException("SQL_TIME_WINDOW_REJECTED", 422))
                .when(windows)
                .requireAllowed(any(), eq(GROUP), anyString(), anyString(), any());

        var checks = service.verify(USER, GROUP, AssetReviewStore.KIND_VIEW, VIEW, cases());

        assertThat(checks).singleElement().matches(c -> c.error().contains("REJECTED"));
        verify(publisher, never()).verifyAssets(anyString(), anyList(), any(), any());
        assertThat(asset().review().ran()).isEmpty();
    }

    @Test
    void sqlThatNeverTouchesTheAssetCannotVerifyIt() {
        review();
        var checks =
                service.verify(
                        USER,
                        GROUP,
                        AssetReviewStore.KIND_VIEW,
                        VIEW,
                        List.of(
                                new SemanticAssetService.VerifyCase(
                                        "每天访问", "SELECT COUNT(*) FROM orders")));

        assertThat(checks).singleElement().matches(c -> c.error().contains("没有用到资产"));
        verify(publisher, never()).verifyAssets(anyString(), anyList(), any(), any());
        assertThat(asset().review().ran()).isEmpty();
    }

    @Test
    void generatedSqlMustUseTheAsset() {
        review();
        when(generator.generate(
                        any(), anyString(), any(), anyList(), anyList(), any(), anyString()))
                .thenReturn("SELECT 1 FROM orders");
        assertThatThrownBy(
                        () -> service.generate(USER, GROUP, AssetReviewStore.KIND_VIEW, VIEW, "总量"))
                .hasMessageContaining("没有用到资产");

        when(generator.generate(
                        any(), anyString(), any(), anyList(), anyList(), any(), anyString()))
                .thenReturn(SQL);
        assertThat(service.generate(USER, GROUP, AssetReviewStore.KIND_VIEW, VIEW, "总量"))
                .isEqualTo(SQL);
    }

    @Test
    void timeColumnMustBeAnOutputColumnOfTheAsset() throws Exception {
        write(
                draft.resolve("views/" + VIEW + "/metadata.yml"),
                "name: sales_valid\ncolumns:\n- name: 访问时间\n  type: DATETIME\n");

        assertThat(service.get(USER, GROUP, AssetReviewStore.KIND_VIEW, VIEW).outputColumns())
                .containsExactly(new SemanticAssetService.OutputColumn("访问时间", "DATETIME"));
        assertThatThrownBy(
                        () ->
                                service.review(
                                        USER,
                                        GROUP,
                                        AssetReviewStore.KIND_VIEW,
                                        VIEW,
                                        List.of("visit_time")))
                .hasMessageContaining("输出列");
        service.review(USER, GROUP, AssetReviewStore.KIND_VIEW, VIEW, List.of("访问时间"));
        assertThat(asset().review().timeColumns()).containsExactly("访问时间");
    }

    @Test
    void userWithoutManageSeesNothingOfTheDraft() {
        doThrow(new DatasetException("知识库不存在", 404)).when(groups).getGroup("bob", GROUP);

        assertThatThrownBy(() -> service.list("bob", GROUP)).isInstanceOf(DatasetException.class);
        assertThatThrownBy(() -> service.get("bob", GROUP, AssetReviewStore.KIND_VIEW, VIEW))
                .isInstanceOf(DatasetException.class);
        assertThatThrownBy(
                        () ->
                                service.save(
                                        "bob",
                                        GROUP,
                                        AssetReviewStore.KIND_VIEW,
                                        VIEW,
                                        "metadata.yml",
                                        "x: 1\n",
                                        null))
                .isInstanceOf(DatasetException.class);
        assertThat(asset().review()).isNull();
    }

    @Test
    void reviewShowsDatesWrittenIntoTheAssetButLeavesTheDecisionToAPerson() {
        assertThat(AssetReviewStore.fixedDates("WHERE d >= '2026-09-20 00:00:00' AND x = 'a'"))
                .containsExactly("'2026-09-20 00:00:00'");
        assertThat(AssetReviewStore.fixedDates("SELECT day FROM orders")).isEmpty();
        assertThat(service.get(USER, GROUP, AssetReviewStore.KIND_VIEW, VIEW).fixedDates())
                .isEmpty();
    }

    @Test
    void savingRefusesPathsOutsideTheAsset() {
        assertThatThrownBy(
                        () ->
                                service.save(
                                        USER,
                                        GROUP,
                                        AssetReviewStore.KIND_VIEW,
                                        "../secret",
                                        "metadata.yml",
                                        "x: 1\n",
                                        null))
                .isInstanceOf(DatasetException.class);
        assertThatThrownBy(
                        () ->
                                service.save(
                                        USER,
                                        GROUP,
                                        AssetReviewStore.KIND_VIEW,
                                        VIEW,
                                        "../../wren_project.yml",
                                        "x: 1\n",
                                        null))
                .isInstanceOf(DatasetException.class);
    }

    @Test
    void publishPinsConfirmedTimeColumnsBesideTheSnapshot() throws Exception {
        when(windows.facts(GROUP))
                .thenReturn(List.of(new PublishedAssetWindows.Fact("orders", "ods_orders", 30)));
        service.review(USER, GROUP, AssetReviewStore.KIND_VIEW, VIEW, List.of("day"));
        Path prepared = Files.createDirectories(temp.resolve("published.next"));

        store.writeWindows(GROUP, prepared);

        assertThat(Files.readString(prepared.resolve(PublishedAssetWindows.FILE)))
                .contains("sales_valid", "day");
    }

    /** 2026-11-02: saving a new ref_sql model's metadata.yml first answered 404. */
    @Test
    void newRefSqlModelIsAnAssetBeforeItsSqlIsWritten() throws Exception {
        write(draft.resolve("models/visits_clean/metadata.yml"), "name: visits_clean\n");
        when(reader.read(GROUP))
                .thenReturn(
                        new MdlWorkspaceReader.Snapshot(
                                List.of(),
                                List.of(),
                                List.of(
                                        new MdlWorkspaceReader.WorkspaceModel(
                                                "visits_clean",
                                                null,
                                                null,
                                                "",
                                                "",
                                                "",
                                                List.of(),
                                                "models/visits_clean/metadata.yml",
                                                null,
                                                null,
                                                null,
                                                null),
                                        new MdlWorkspaceReader.WorkspaceModel(
                                                "orders",
                                                null,
                                                null,
                                                "s",
                                                "orders",
                                                "",
                                                List.of(),
                                                "models/orders/metadata.yml",
                                                null,
                                                null,
                                                null,
                                                null)),
                                List.of(),
                                List.of(),
                                List.of()));

        var saved =
                service.save(
                        USER,
                        GROUP,
                        AssetReviewStore.KIND_REF_SQL,
                        "visits_clean",
                        "metadata.yml",
                        "name: visits_clean\ndescription: 排除测试账号\n",
                        null);
        assertThat(saved.asset().status()).isEqualTo(AssetReviewStore.REVIEW_PENDING);
        assertThat(store.assets(GROUP))
                .extracting(AssetReviewStore.Asset::name)
                .contains("visits_clean")
                .doesNotContain("orders");
    }

    @Test
    void sqlFileBeforeMetadataIsRefusedWithoutWritingAnything() {
        assertThatThrownBy(
                        () ->
                                service.save(
                                        USER,
                                        GROUP,
                                        AssetReviewStore.KIND_REF_SQL,
                                        "visits_clean",
                                        "ref_sql.sql",
                                        "SELECT 1",
                                        null))
                .hasMessageContaining("请先保存 metadata.yml");
        assertThat(draft.resolve("models/visits_clean")).doesNotExist();
    }
}
