package io.agentscope.dataagent.dataset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.model.Model;
import io.agentscope.dataagent.web.persistence.jpa.DatasetEntity;
import io.agentscope.dataagent.web.persistence.jpa.DatasetGroupRepository;
import io.agentscope.dataagent.web.persistence.jpa.DatasetRepository;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

/** spec103: platform-fixed dates, the model only names the table and metric. */
class RecommendedQuestionServiceTest {
    private final MdlCatalog catalog = mock(MdlCatalog.class);
    private final DatasetRepository datasets = mock(DatasetRepository.class);
    private final FactQueryWindowPolicy windows = mock(FactQueryWindowPolicy.class);
    private final TableProvisioner tables = mock(TableProvisioner.class);
    private final QuestionSuggestionService suggestions = mock(QuestionSuggestionService.class);
    private final MdlWorkspaceReader reader = mock(MdlWorkspaceReader.class);
    private final DatasetGroupRepository groupRepo = mock(DatasetGroupRepository.class);
    private final MdlWorkspaceService workspace = mock(MdlWorkspaceService.class);
    @org.junit.jupiter.api.io.TempDir java.nio.file.Path home;

    @SuppressWarnings("unchecked")
    private RecommendedQuestionService service() {
        ObjectProvider<TableProvisioner> tableProvider = mock(ObjectProvider.class);
        when(tableProvider.getIfAvailable()).thenReturn(tables);
        ObjectProvider<Model> models = mock(ObjectProvider.class);
        return new RecommendedQuestionService(
                catalog,
                datasets,
                groupRepo,
                windows,
                tableProvider,
                models,
                suggestions,
                reader,
                workspace,
                Duration.ofSeconds(1));
    }

    private static DatasetEntity dataset(String id, String origin, String table) {
        DatasetEntity d = mock(DatasetEntity.class);
        when(d.getOrigin()).thenReturn(origin);
        when(d.getTableName()).thenReturn(table);
        return d;
    }

    private void publish(List<Map<String, Object>> gateModels, List<MdlCatalog.Model> models) {
        when(catalog.load("g"))
                .thenReturn(
                        Optional.of(
                                new MdlCatalog.GroupMdl(
                                        "g", "测试", 2, models, List.of(), List.of(), List.of())));
        when(catalog.dateGateContext("g")).thenReturn(Map.of("models", gateModels));
    }

    private static Map<String, Object> gate(String name, String role, String column, String format)
            throws Exception {
        return Map.of(
                "name",
                name,
                "role",
                role,
                "temporalColumns",
                new ObjectMapper()
                        .readTree(
                                column == null
                                        ? "[]"
                                        : "[{\"column\":\""
                                                + column
                                                + "\",\"format\":\""
                                                + format
                                                + "\"}]"));
    }

    private static MdlCatalog.Model model(String name, String datasetId, String timeColumn) {
        return new MdlCatalog.Model(
                name,
                datasetId,
                name,
                List.of(
                        new MdlCatalog.Column(timeColumn, "VARCHAR", "访问时间", false, null),
                        new MdlCatalog.Column("user_account", "VARCHAR", "账号", false, null)));
    }

    @Test
    void uploadedTablesUseTheirRealRangeAndExternalTablesTheLatestMonthOrShorterWindow()
            throws Exception {
        publish(
                List.of(
                        gate("用户列表", "DIMENSION", null, null),
                        gate("外部访问", "UNKNOWN", "log_time", "NATIVE"),
                        gate("报表访问明细", "FACT", "access_time", "uuuu/M/d H:mm")),
                List.of(
                        model("用户列表", "d0", "name"),
                        model("外部访问", "d2", "log_time"),
                        model("报表访问明细", "d1", "access_time")));
        DatasetEntity ds1 = dataset("d1", "upload", "ds_a_visits");
        when(datasets.findById("d1")).thenReturn(Optional.of(ds1));
        DatasetEntity ds2 = dataset("d2", "datasource", "remote");
        when(datasets.findById("d2")).thenReturn(Optional.of(ds2));
        when(tables.dateRange(eq("ds_a_visits"), anyString()))
                .thenReturn(new String[] {"2026-09-20", "2026-10-07"});
        when(windows.rules("g"))
                .thenReturn(List.of(new SqlWindowGate.Rule("外部访问", List.of("log_time"), 7)));

        var candidates = service().candidates("g");

        // Registered fact tables come first; dimension tables without dates are not candidates.
        assertThat(candidates)
                .extracting(RecommendedQuestionService.Candidate::table)
                .containsExactly("报表访问明细", "外部访问");
        assertThat(candidates.get(0).datePhrase()).isEqualTo("2026-09-20 至 2026-10-07");
        assertThat(candidates.get(1).datePhrase()).isEqualTo("最近 7 天");
        verify(tables).dateRange("ds_a_visits", "STR_TO_DATE(`access_time`, '%Y/%c/%e %k:%i')");
    }

    @Test
    void theQuestionUsesThePlatformDatesAndAsksForADailySeries() {
        var candidates =
                List.of(
                        new RecommendedQuestionService.Candidate(
                                "报表访问明细", "FACT", "2026-09-20 至 2026-10-07", Map.of()),
                        new RecommendedQuestionService.Candidate(
                                "外部访问", "UNKNOWN", "最近一个月", Map.of()));
        assertThat(
                        RecommendedQuestionService.compose(
                                "好的：{\"table\":\"报表访问明细\",\"metric\":\"报表访问次数\"}", candidates))
                .contains("2026-09-20 至 2026-10-07 每天的报表访问次数");
        assertThat(
                        RecommendedQuestionService.compose(
                                "{\"table\":\"外部访问\",\"metric\":\"访问用户数\"}", candidates))
                .contains("最近一个月每天的访问用户数");
        // An unknown table or a metric that smuggles its own dates is never used.
        assertThat(
                        RecommendedQuestionService.compose(
                                "{\"table\":\"其他\",\"metric\":\"次数\"}", candidates))
                .isEmpty();
        assertThat(
                        RecommendedQuestionService.compose(
                                "{\"table\":\"外部访问\",\"metric\":\"2025-01 的访问次数\"}", candidates))
                .isEmpty();
        assertThat(RecommendedQuestionService.compose("not json", candidates)).isEmpty();
        // The question is by day only: a metric carrying another dimension is never used.
        for (String metric : List.of("各部门访问次数", "按项目的访问次数", "访问次数Top10", "访问最多的报表"))
            assertThat(
                            RecommendedQuestionService.compose(
                                    "{\"table\":\"外部访问\",\"metric\":\"" + metric + "\"}",
                                    candidates))
                    .as(metric)
                    .isEmpty();
        assertThat(RecommendedQuestionService.externalPhrase(30)).isEqualTo("最近一个月");
        assertThat(RecommendedQuestionService.externalPhrase(90)).isEqualTo("最近一个月");
    }

    /** Before the first publish the trial uses the same rule on the draft model. */
    @Test
    void theDraftModelGivesTheTrialItsRecommendation() throws Exception {
        java.nio.file.Path model = home.resolve("models/报表访问明细/metadata.yml");
        java.nio.file.Files.createDirectories(model.getParent());
        java.nio.file.Files.writeString(
                model,
                """
                name: 报表访问明细
                properties:
                  table_role: FACT
                  table_role_confirmed: true
                  temporal_columns:
                  - column: access_time
                    format: NATIVE
                """);
        when(workspace.workspaceRoot("g")).thenReturn(home);
        when(reader.read("g"))
                .thenReturn(
                        new MdlWorkspaceReader.Snapshot(
                                List.of(),
                                List.of(),
                                List.of(
                                        new MdlWorkspaceReader.WorkspaceModel(
                                                "报表访问明细",
                                                "d1",
                                                "报表访问明细",
                                                null,
                                                "ds_a_visits",
                                                "访问日志",
                                                List.of(
                                                        new MdlWorkspaceReader.WorkspaceColumn(
                                                                "access_time",
                                                                "DATETIME",
                                                                "访问时间",
                                                                null,
                                                                false,
                                                                null)),
                                                "models/报表访问明细/metadata.yml",
                                                null,
                                                null)),
                                List.of(),
                                List.of(),
                                List.of()));
        DatasetEntity upload = dataset("d1", "upload", "ds_a_visits");
        when(datasets.findById("d1")).thenReturn(Optional.of(upload));
        when(tables.dateRange("ds_a_visits", "`access_time`"))
                .thenReturn(new String[] {"2026-09-20", "2026-10-07"});

        var service = service();
        var draftTables =
                (List<RecommendedQuestionService.Table>)
                        org.springframework.test.util.ReflectionTestUtils.invokeMethod(
                                service, "draftTables", "g");
        var candidates = service.candidates("g", draftTables);
        assertThat(candidates)
                .singleElement()
                .satisfies(
                        c -> {
                            assertThat(c.role()).isEqualTo("FACT");
                            assertThat(c.datePhrase()).isEqualTo("2026-09-20 至 2026-10-07");
                        });
    }

    @Test
    void withoutADatedFactTableTheOriginalSuggestionIsUsed() throws Exception {
        publish(
                List.of(gate("用户列表", "DIMENSION", null, null)),
                List.of(model("用户列表", "d0", "name")));
        when(suggestions.suggest(eq("g"), any())).thenReturn(List.of("各部门的用户数", "用户最多的项目"));
        assertThat(service().generate("g")).contains("各部门的用户数");
        verify(tables, never()).dateRange(anyString(), anyString());
    }

    @Test
    void anUnreadableUploadedTableIsSkippedRatherThanGivenGuessedDates() throws Exception {
        publish(
                List.of(gate("报表访问明细", "FACT", "access_time", "NATIVE")),
                List.of(model("报表访问明细", "d1", "access_time")));
        DatasetEntity ds3 = dataset("d1", "upload", "ds_a_visits");
        when(datasets.findById("d1")).thenReturn(Optional.of(ds3));
        when(tables.dateRange(anyString(), anyString())).thenReturn(null);
        assertThat(service().candidates("g")).isEmpty();
    }

    private io.agentscope.dataagent.web.persistence.jpa.DatasetGroupEntity group(
            String question, boolean manual) {
        var g = mock(io.agentscope.dataagent.web.persistence.jpa.DatasetGroupEntity.class);
        when(g.getRecommendedQuestion()).thenReturn(question);
        when(g.isRecommendedQuestionManual()).thenReturn(manual);
        when(g.getMdlVersion()).thenReturn(1);
        return g;
    }

    /** The modeler's edit is kept: a publish does not regenerate over it. */
    @Test
    void anEditedQuestionIsKeptAcrossPublishes() {
        var edited = group("9 月每天的访问次数", true);
        when(groupRepo.findById("g")).thenReturn(Optional.of(edited));
        var service = service();
        service.published(new PublishedQueryProjectChanged("alice", "g"));
        verify(catalog, never()).load("g");
        assertThat(service.trial("g"))
                .isEqualTo(new RecommendedQuestionService.Recommendation("9 月每天的访问次数", true));
    }

    @Test
    void editingSavesTheModelersTextAndBlankTextReturnsToGeneration() {
        when(reader.read("g"))
                .thenReturn(
                        new MdlWorkspaceReader.Snapshot(
                                List.of(), List.of(), List.of(), List.of(), List.of(), List.of()));
        when(workspace.workspaceRoot("g")).thenReturn(home);
        when(suggestions.suggest(eq("g"), any())).thenReturn(List.of("访问总次数是多少"));
        var service = service();

        assertThat(service.edit("g", "  最近一周每天的访问次数 ").question()).isEqualTo("最近一周每天的访问次数");
        verify(groupRepo).saveRecommendedQuestion("g", "最近一周每天的访问次数", true);

        assertThat(service.edit("g", " "))
                .isEqualTo(new RecommendedQuestionService.Recommendation("访问总次数是多少", false));
        verify(groupRepo).saveRecommendedQuestion("g", "访问总次数是多少", false);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.edit("g", "第一行\n第二行"))
                .hasMessageContaining("一行");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.edit("g", "长".repeat(201)))
                .hasMessageContaining("200");
    }

    /** Before there is one, the trial generates from the draft and stores it for the query page. */
    @Test
    void theTrialStoresWhatItGenerates() {
        var empty = group(null, false);
        when(groupRepo.findById("g")).thenReturn(Optional.of(empty));
        when(reader.read("g"))
                .thenReturn(
                        new MdlWorkspaceReader.Snapshot(
                                List.of(), List.of(), List.of(), List.of(), List.of(), List.of()));
        when(workspace.workspaceRoot("g")).thenReturn(home);
        when(suggestions.suggest(eq("g"), any())).thenReturn(List.of("访问总次数是多少"));
        assertThat(service().trial("g").question()).isEqualTo("访问总次数是多少");
        verify(groupRepo).updateRecommendedQuestion("g", "访问总次数是多少");
    }
}
