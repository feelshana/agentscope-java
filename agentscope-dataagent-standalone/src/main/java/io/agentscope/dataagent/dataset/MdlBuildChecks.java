package io.agentscope.dataagent.dataset;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.dataagent.dataset.MdlTypes.MdlIssue;
import io.agentscope.dataagent.web.persistence.jpa.ExternalDataSourceEntity;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Executes checks on an already-built project using the coordinator's selected connection.
 * Authorization, locks, source selection and publication state remain with the caller.
 */
final class MdlBuildChecks {
    private static final Pattern SUMMARY =
            Pattern.compile("(\\d+)\\s+warning\\(s\\),\\s+(\\d+)\\s+error\\(s\\)");

    private final WrenProperties props;
    private final WrenCli wrenCli;
    private final WrenProfileHome profileHome;
    private final ObjectMapper mapper;

    MdlBuildChecks(
            WrenProperties props,
            WrenCli wrenCli,
            WrenProfileHome profileHome,
            ObjectMapper mapper) {
        this.props = props;
        this.wrenCli = wrenCli;
        this.profileHome = profileHome;
        this.mapper = mapper;
    }

    // ------------------------------------------------------------------ dry-run gates

    /**
     * Dry-runs every named view against the freshly built {@code target/mdl.json} (specs/018,
     * ADR 0032 D2): {@code dry-run} is the only pre-publish step that plans view statements, so
     * it is the backstop for dialect errors the create/update blocklist cannot foresee. Each
     * failure becomes an error issue naming the view; the loop continues so one broken view does
     * not mask the others.
     */
    List<MdlIssue> dryRunSqlObjects(
            Path projectDir,
            MdlWorkspaceReader.Snapshot snapshot,
            ExternalDataSourceEntity external) {
        List<MdlWorkspaceReader.WorkspaceModel> derived = derivedModelsOf(snapshot);
        if (snapshot.views().isEmpty() && derived.isEmpty()) {
            return List.of();
        }
        List<MdlIssue> issues = new ArrayList<>();
        Path connectionFile = null;
        try {
            // The dry-run CLI has no --profile flag. Explicit per-operation connection JSON
            // prevents fallback to ~/.wren and avoids changing a shared active profile.
            Map<String, String> connection = profileHome.connectionInfo(external);
            connectionFile = Files.createTempFile("dataagent-wren-check-", ".json");
            mapper.writeValue(connectionFile.toFile(), connection);
            issues.addAll(dryRunViews(projectDir, snapshot.views(), connectionFile));
            issues.addAll(dryRunDerivedModels(projectDir, derived, connectionFile));
        } catch (IOException | DatasetException ex) {
            issues.add(issue("error", "准备 SQL 校验连接失败：" + ex.getMessage(), "local"));
        } finally {
            if (connectionFile != null) {
                try {
                    Files.deleteIfExists(connectionFile);
                } catch (IOException ex) {
                    issues.add(issue("error", "清理 SQL 校验临时连接文件失败", "local"));
                }
            }
        }
        return issues;
    }

    /**
     * spec149 P0: Wren 0.18 {@code dry-plan} expands SQL without resolving columns, so a
     * verification case naming a column that does not exist used to pass. Each SQL is therefore
     * also sent through the official {@code dry-run} (an EXPLAIN: the database resolves names but
     * reads no rows), the same gate publication applies to views. Returns one entry per SQL: the
     * error text, or {@code null} when the database accepted it.
     *
     * @throws DatasetException when the check connection cannot be prepared, so callers never
     *     mistake an unavailable database for SQL the database rejected
     */
    List<String> explainAll(Path projectDir, List<String> sqls, ExternalDataSourceEntity external) {
        Path connectionFile;
        try {
            Map<String, String> connection = profileHome.connectionInfo(external);
            connectionFile = Files.createTempFile("dataagent-wren-check-", ".json");
            mapper.writeValue(connectionFile.toFile(), connection);
        } catch (IOException ex) {
            throw new DatasetException("准备 SQL 校验连接失败：" + ex.getMessage(), ex);
        }
        try {
            List<String> errors = new ArrayList<>();
            for (String sql : sqls) {
                WrenCli.Result result =
                        wrenCli.run(
                                projectDir,
                                props.timeout(),
                                List.of(
                                        "dry-run",
                                        "-s",
                                        sql,
                                        "--connection-file",
                                        connectionFile.toString()));
                errors.add(result.ok() ? null : "数据库校验未通过：" + firstLine(result.output()));
            }
            return errors;
        } finally {
            try {
                Files.deleteIfExists(connectionFile);
            } catch (IOException ignored) {
                // Cleanup failure is not a verdict on the SQL; the file sits in the OS temp
                // directory exactly as publication's dry-run connection file does.
            }
        }
    }

    private List<MdlIssue> dryRunViews(
            Path projectDir, List<MdlWorkspaceReader.WorkspaceView> views, Path connectionFile) {
        List<MdlIssue> issues = new ArrayList<>();
        for (MdlWorkspaceReader.WorkspaceView view : views) {
            String quoted = view.name().replace("\"", "\"\"");
            WrenCli.Result result =
                    wrenCli.run(
                            projectDir,
                            props.timeout(),
                            List.of(
                                    "dry-run",
                                    "-s",
                                    "SELECT * FROM \"" + quoted + "\"",
                                    "--connection-file",
                                    connectionFile.toString()));
            if (!result.ok()) {
                issues.add(
                        issue(
                                "error",
                                "视图「" + view.name() + "」试跑失败：" + firstLine(result.output()),
                                "wren"));
            }
        }
        return issues;
    }

    /** Dry-runs ref_sql models against the unmodified official manifest. */
    private List<MdlIssue> dryRunDerivedModels(
            Path projectDir,
            List<MdlWorkspaceReader.WorkspaceModel> derivedModels,
            Path connectionFile) {
        List<MdlIssue> issues = new ArrayList<>();
        for (MdlWorkspaceReader.WorkspaceModel model : derivedModels) {
            String quoted = model.name().replace("\"", "\"\"");
            WrenCli.Result result =
                    wrenCli.run(
                            projectDir,
                            props.timeout(),
                            List.of(
                                    "dry-run",
                                    "-s",
                                    "SELECT * FROM \"" + quoted + "\"",
                                    "--connection-file",
                                    connectionFile.toString()));
            if (!result.ok()) {
                issues.add(
                        issue(
                                "error",
                                "派生模型「" + model.name() + "」试跑失败：" + firstLine(result.output()),
                                "wren"));
            }
        }
        return issues;
    }

    /** Derived models of the snapshot: those whose model is defined by SQL, not a table. */
    private static List<MdlWorkspaceReader.WorkspaceModel> derivedModelsOf(
            MdlWorkspaceReader.Snapshot snapshot) {
        List<MdlWorkspaceReader.WorkspaceModel> derived = new ArrayList<>();
        for (MdlWorkspaceReader.WorkspaceModel m : snapshot.models()) {
            if (m.refSql() != null) {
                derived.add(m);
            }
        }
        return derived;
    }

    /**
     * Translates every cube's full measure set through wren-core with {@code cube query
     * --sql-only} (ADR 0033 D6): pure SQL transformation, no database connection. Each failure
     * becomes an error issue naming the cube; the loop continues so one broken cube does not
     * mask the others. Cubes without measures are structurally covered by {@code validate
     * --strict} and skipped here ({@code cube query} requires at least one measure).
     *
     * <p>Before translating, a member whose name equals a column its own expression references is
     * rejected locally (spec149 P0): under Wren 0.18 a measure such as {@code member_count:
     * SUM(member_count)} fails as a circular dependency, and a dimension such as {@code
     * member_name: member_name} only fails once a query filters on it, which the measure-only dry
     * run cannot reveal. Such cubes are not dry-run, so the rename hint is the only error shown.
     */
    List<MdlIssue> dryRunCubes(Path projectDir, List<MdlWorkspaceReader.WorkspaceCube> cubes) {
        List<MdlIssue> issues = new ArrayList<>();
        for (MdlWorkspaceReader.WorkspaceCube cube : cubes) {
            List<MdlIssue> selfReferences = selfReferences(cube);
            if (!selfReferences.isEmpty()) {
                issues.addAll(selfReferences);
                continue;
            }
            if (cube.measures().isEmpty()) {
                continue;
            }
            String measures =
                    cube.measures().stream()
                            .map(MdlWorkspaceReader.WorkspaceMember::name)
                            .reduce((a, b) -> a + "," + b)
                            .orElse("");
            WrenCli.Result result =
                    wrenCli.run(
                            projectDir,
                            props.timeout(),
                            List.of(
                                    "cube",
                                    "query",
                                    "--cube",
                                    cube.name(),
                                    "--measures",
                                    measures,
                                    "--sql-only"));
            if (!result.ok()) {
                issues.add(
                        issue(
                                "error",
                                "Cube「" + cube.name() + "」试跑失败：" + firstLine(result.output()),
                                "wren"));
            }
        }
        return issues;
    }

    private static final Pattern STRING_LITERAL = Pattern.compile("'(?:[^']|'')*'");
    private static final Pattern IDENTIFIER =
            // Possessive so "COUNT(" is never shortened to "COUN" to dodge the function lookahead.
            Pattern.compile("(?<![\\p{L}\\p{N}_])[\\p{L}_][\\p{L}\\p{N}_]*+(?!\\s*\\()");

    /** Members (measures, dimensions, time dimensions) whose expression references their name. */
    static List<MdlIssue> selfReferences(MdlWorkspaceReader.WorkspaceCube cube) {
        List<MdlIssue> issues = new ArrayList<>();
        List<MdlWorkspaceReader.WorkspaceMember> members = new ArrayList<>(cube.measures());
        members.addAll(cube.dimensions());
        members.addAll(cube.timeDimensions());
        for (MdlWorkspaceReader.WorkspaceMember member : members) {
            if (member.name() == null || member.expression() == null) continue;
            // Literals are not column references; quoted identifiers are compared unquoted.
            String expression =
                    STRING_LITERAL.matcher(member.expression()).replaceAll(" ").replace("\"", "");
            Matcher identifier = IDENTIFIER.matcher(expression);
            while (identifier.find()) {
                if (identifier.group().equalsIgnoreCase(member.name())) {
                    issues.add(
                            issue(
                                    "error",
                                    "Cube「"
                                            + cube.name()
                                            + "」的成员「"
                                            + member.name()
                                            + "」与其表达式引用的列同名，Wren 会报循环依赖或在筛选时无法解析；"
                                            + "请给成员改名（例如 member_name 改为 member），表达式保持引用原列",
                                    "local"));
                    break;
                }
            }
        }
        return issues;
    }

    // ------------------------------------------------------------- wren CLI interaction

    /** Parses wren's stdout into structured issues; "0 errors" gates acceptance of rc!=0 runs. */
    List<MdlIssue> parseWrenOutput(WrenCli.Result result) {
        List<MdlIssue> issues = new ArrayList<>();
        int warnings = 0;
        int errors = 0;
        for (String line : result.output().split("\n")) {
            String t = line.strip();
            if (t.isEmpty()) {
                continue;
            }
            Matcher summary = SUMMARY.matcher(t);
            if (summary.find()) {
                warnings = Math.max(warnings, Integer.parseInt(summary.group(1)));
                errors = Math.max(errors, Integer.parseInt(summary.group(2)));
                continue;
            }
            if (t.startsWith("[ERROR]")) {
                errors++;
                issues.add(issue("error", t.substring("[ERROR]".length()).strip(), "wren"));
            } else if (t.startsWith("Error:")) {
                errors++;
                issues.add(issue("error", t.substring("Error:".length()).strip(), "wren"));
            } else if (t.startsWith("⚠")) {
                warnings++;
                issues.add(issue("warning", t.substring(1).strip(), "wren"));
            }
        }
        if (errors == 0 && !result.ok() && warnings == 0) {
            // A non-zero exit without any parseable diagnostics: surface the raw output so the
            // caller never mistakes a broken CLI (missing executable, crash) for success.
            issues.add(issue("error", "wren 执行失败：" + firstLine(result.output()), "wren"));
        }
        return issues;
    }

    private static MdlIssue issue(String severity, String message, String source) {
        return new MdlIssue(severity, message, source);
    }

    private static String firstLine(String output) {
        if (output == null || output.isBlank()) {
            return "(无输出)";
        }
        String t = output.strip();
        int nl = t.indexOf('\n');
        String line = nl < 0 ? t : t.substring(0, nl);
        return line.length() > 300 ? line.substring(0, 300) + "…" : line;
    }
}
