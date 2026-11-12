package io.agentscope.dataagent.dataset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.dataagent.web.persistence.jpa.ExternalDataSourceEntity;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Exercises CLI contracts and temporary connection lifetime without a process or database. */
class MdlBuildChecksTest {
    @TempDir Path project;
    private final WrenCli cli = mock(WrenCli.class);
    private final WrenProfileHome profiles = mock(WrenProfileHome.class);
    private final ObjectMapper json = new ObjectMapper();
    private final List<List<String>> calls = new ArrayList<>();
    private WrenProperties props;
    private MdlBuildChecks checks;

    @BeforeEach
    void setUp() {
        props = new WrenProperties("not-used", project.toString(), "builtin", "mysql", 7);
        checks = new MdlBuildChecks(props, cli, profiles, json);
    }

    @Test
    void diagnosticsKeepIssueSeverityTextSourceAndOrder() {
        var result =
                new WrenCli.Result(
                        1,
                        "note\n"
                                + "⚠ check this\n"
                                + "[ERROR] broken model\n"
                                + "Error: invalid type\n"
                                + "1 warning(s), 2 error(s)\n");
        assertThat(checks.parseWrenOutput(result))
                .containsExactly(
                        new MdlTypes.MdlIssue("warning", "check this", "wren"),
                        new MdlTypes.MdlIssue("error", "broken model", "wren"),
                        new MdlTypes.MdlIssue("error", "invalid type", "wren"));
    }

    @Test
    void unexplainedExitKeepsBoundedFirstLineAsAnError() {
        var issues = checks.parseWrenOutput(new WrenCli.Result(2, "x".repeat(350) + "\nsecond"));
        assertThat(issues)
                .containsExactly(
                        new MdlTypes.MdlIssue(
                                "error", "wren 执行失败：" + "x".repeat(300) + "…", "wren"));
    }

    @Test
    void warningOnlyNonzeroExitKeepsTheExistingWarningContract() {
        assertThat(
                        checks.parseWrenOutput(
                                new WrenCli.Result(
                                        1, "⚠ optional warning\n1 warning(s), 0 error(s)")))
                .containsExactly(new MdlTypes.MdlIssue("warning", "optional warning", "wren"));
    }

    @Test
    void noSqlAssetsDoNotResolveConnectionsOrInvokeWren() {
        assertThat(checks.dryRunSqlObjects(project, snapshot(List.of(), List.of()), null))
                .isEmpty();
        verifyNoInteractions(cli, profiles);
    }

    @Test
    void viewsThenDerivedModelsUseTheSelectedConnectionAndCollectEveryFailure() throws Exception {
        var source = mock(ExternalDataSourceEntity.class);
        when(profiles.connectionInfo(source))
                .thenReturn(Map.of("host", "test", "password", "synthetic"));
        List<Path> files = new ArrayList<>();
        answer(
                args -> {
                    Path file = Path.of(args.get(4));
                    files.add(file);
                    assertThat(file).isRegularFile();
                    try {
                        assertThat(json.readTree(file.toFile()).path("host").asText())
                                .isEqualTo("test");
                    } catch (java.io.IOException e) {
                        throw new AssertionError(e);
                    }
                    return new WrenCli.Result(1, "first failure\nignored detail");
                });
        var issues =
                checks.dryRunSqlObjects(
                        project,
                        snapshot(
                                List.of(
                                        new MdlWorkspaceReader.WorkspaceView(
                                                "v\"one", "SELECT 1", "", "views/v"),
                                        new MdlWorkspaceReader.WorkspaceView(
                                                "two", "SELECT 2", "", "views/two")),
                                List.of(derived("d\"one"))),
                        source);
        assertThat(issues)
                .extracting(MdlTypes.MdlIssue::message)
                .containsExactly(
                        "视图「v\"one」试跑失败：first failure",
                        "视图「two」试跑失败：first failure",
                        "派生模型「d\"one」试跑失败：first failure");
        assertThat(calls)
                .extracting(args -> args.get(2))
                .containsExactly(
                        "SELECT * FROM \"v\"\"one\"",
                        "SELECT * FROM \"two\"",
                        "SELECT * FROM \"d\"\"one\"");
        assertThat(calls)
                .allSatisfy(
                        args -> {
                            assertThat(args.subList(0, 2)).containsExactly("dry-run", "-s");
                            assertThat(args.get(3)).isEqualTo("--connection-file");
                            assertThat(args).doesNotContain("--profile");
                        });
        assertThat(files).hasSize(3).containsOnly(files.get(0));
        assertThat(files.get(0)).doesNotExist();
        verify(profiles).connectionInfo(source);
    }

    @Test
    void unexpectedCliFailureStillRemovesTheConnectionFile() {
        when(profiles.connectionInfo(null)).thenReturn(Map.of("host", "test"));
        List<Path> files = new ArrayList<>();
        var failure = new IllegalStateException("synthetic cli failure");
        answer(
                args -> {
                    files.add(Path.of(args.get(4)));
                    throw failure;
                });
        assertThatThrownBy(
                        () ->
                                checks.dryRunSqlObjects(
                                        project,
                                        snapshot(
                                                List.of(
                                                        new MdlWorkspaceReader.WorkspaceView(
                                                                "v", "SELECT 1", "", "v")),
                                                List.of()),
                                        null))
                .isSameAs(failure);
        assertThat(files).hasSize(1);
        assertThat(files.get(0)).doesNotExist();
    }

    @Test
    void connectionPreparationFailureIsLocalAndDoesNotInvokeWren() {
        when(profiles.connectionInfo(null))
                .thenThrow(new DatasetException("synthetic refusal", 403));
        assertThat(
                        checks.dryRunSqlObjects(
                                project,
                                snapshot(
                                        List.of(
                                                new MdlWorkspaceReader.WorkspaceView(
                                                        "v", "SELECT 1", "", "v")),
                                        List.of()),
                                null))
                .containsExactly(
                        new MdlTypes.MdlIssue("error", "准备 SQL 校验连接失败：synthetic refusal", "local"));
        verifyNoInteractions(cli);
    }

    @Test
    void cubesUseAllMeasuresSkipEmptyCubesAndContinueAfterFailureWithoutAConnection() {
        var a = new MdlWorkspaceReader.WorkspaceMember("total", "SUM(x)", "BIGINT", "");
        var b = new MdlWorkspaceReader.WorkspaceMember("count", "COUNT(*)", "BIGINT", "");
        answer(args -> new WrenCli.Result(args.contains("first") ? 1 : 0, "failed\nmore"));
        var issues =
                checks.dryRunCubes(
                        project,
                        List.of(
                                cube("empty", List.of()),
                                cube("first", List.of(a, b)),
                                cube("last", List.of(b))));
        assertThat(calls)
                .containsExactly(
                        List.of(
                                "cube",
                                "query",
                                "--cube",
                                "first",
                                "--measures",
                                "total,count",
                                "--sql-only"),
                        List.of(
                                "cube",
                                "query",
                                "--cube",
                                "last",
                                "--measures",
                                "count",
                                "--sql-only"));
        assertThat(issues)
                .containsExactly(new MdlTypes.MdlIssue("error", "Cube「first」试跑失败：failed", "wren"));
        verifyNoInteractions(profiles);
    }

    private void answer(Function<List<String>, WrenCli.Result> response) {
        when(cli.run(any(), any(), any()))
                .thenAnswer(
                        inv -> {
                            assertThat((Path) inv.getArgument(0)).isEqualTo(project);
                            assertThat((java.time.Duration) inv.getArgument(1))
                                    .isEqualTo(props.timeout());
                            List<String> args = inv.getArgument(2);
                            calls.add(List.copyOf(args));
                            return response.apply(args);
                        });
    }

    private static MdlWorkspaceReader.Snapshot snapshot(
            List<MdlWorkspaceReader.WorkspaceView> views,
            List<MdlWorkspaceReader.WorkspaceModel> models) {
        return new MdlWorkspaceReader.Snapshot(
                List.of(), List.of(), models, List.of(), List.of(), views);
    }

    private static MdlWorkspaceReader.WorkspaceModel derived(String name) {
        return new MdlWorkspaceReader.WorkspaceModel(
                name,
                null,
                null,
                null,
                null,
                null,
                List.of(),
                "models/derived",
                "SELECT 1",
                "models/derived/ref_sql.sql",
                null,
                null);
    }

    private static MdlWorkspaceReader.WorkspaceCube cube(
            String name, List<MdlWorkspaceReader.WorkspaceMember> measures) {
        return new MdlWorkspaceReader.WorkspaceCube(
                name, "orders", "", measures, List.of(), List.of(), "cubes/" + name);
    }
}
