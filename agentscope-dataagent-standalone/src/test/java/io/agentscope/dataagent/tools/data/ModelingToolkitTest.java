/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.dataagent.tools.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.agentscope.core.tool.Tool;
import io.agentscope.dataagent.dataset.DatasetGroupService;
import io.agentscope.dataagent.dataset.DatasetScope;
import io.agentscope.dataagent.dataset.MdlPublishService;
import io.agentscope.dataagent.dataset.MdlSuggestionService;
import io.agentscope.dataagent.dataset.MdlWorkspaceReader;
import io.agentscope.dataagent.dataset.MdlWorkspaceService;
import io.agentscope.dataagent.dataset.ModelingWorkflowService;
import io.agentscope.dataagent.dataset.WrenCli;
import io.agentscope.dataagent.dataset.WrenProperties;
import io.agentscope.dataagent.web.config.DataAgentConfig;
import io.agentscope.dataagent.web.persistence.jpa.DatasetEntity;
import io.agentscope.dataagent.web.persistence.jpa.DatasetGroupEntity;
import io.agentscope.dataagent.web.persistence.jpa.DatasetRelationEntity;
import io.agentscope.dataagent.web.persistence.jpa.DatasetRepository;
import io.agentscope.dataagent.web.persistence.jpa.SemanticTermEntity;
import io.agentscope.dataagent.web.session.ConversationScopeRegistry;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Locks the YAML-first modeling tool layer (specs/019 M3, ADR 0033): writes go through the triple
 * gate (YAML parse → scratch-copy {@code context validate --strict} → real write, under the group
 * workspace lock), platform-owned paths and traversal are refused, the official command package
 * always builds a fresh {@code target/mdl.json} before translating, {@code list_modeling_state}
 * renders the workspace snapshot (DB fallback before seeding), and the retired structured write
 * tools are gone from both the toolkit and the modeling script. Relation candidates keep the DB
 * queue for HITL proposals; their confirmed write side is the workspace's relationships.yml (M4).
 *
 * <p>{@code create_view} (specs/035, ADR 0043) writes the {@code views/<name>/} file pair behind a
 * three-step gate (scratch {@code context validate --strict} → {@code context build} →
 * {@code dry-plan SELECT * FROM "<name>"}) and returns only after all three pass.
 *
 * <p>The file tools run against a REAL {@link MdlWorkspaceService} + {@link MdlWorkspaceReader}
 * over a temp mdl home; only the {@code wren} CLI is faked (records argv, produces skeletons).
 */
class ModelingToolkitTest {

    private static final String OWNER = "alice";
    private static final String GROUP = "g1";

    @TempDir Path temp;

    private WrenProperties props;
    private FakeWrenCli cli;
    private MdlWorkspaceService workspace;
    private MdlWorkspaceReader reader;
    private MdlSuggestionService suggestions;
    private MdlPublishService mdlPublish;
    private DatasetGroupService groupService;
    private ConversationScopeRegistry conversationScopes;
    private ModelingToolkit toolkit;

    @Test
    void previewRejectsPlatformFilesAndConfirmedExamplesEvenWithValidContent() {
        for (String path :
                List.of(
                        "wren_project.yml",
                        "./wren_project.yml",
                        "knowledge/sql/accepted.md",
                        "knowledge/questions/../sql/accepted.md")) {
            ModelingToolkit.PreviewResult preview =
                    toolkit.previewChange(
                            GROUP, "write_file", Map.of("path", path, "content", "name: valid\n"));
            assertFalse(preview.ok(), path);
        }
        assertTrue(cli.calls.isEmpty());
    }

    @BeforeEach
    void setUp() {
        props = new WrenProperties("wren", temp.toString(), "dataagent", "mysql", 30);
        cli = new FakeWrenCli();
        workspace = new MdlWorkspaceService(props, cli);
        reader = new MdlWorkspaceReader(props, workspace, mock(DatasetRepository.class));
        suggestions = mock(MdlSuggestionService.class);
        mdlPublish = mock(MdlPublishService.class);
        groupService = mock(DatasetGroupService.class);
        conversationScopes = mock(ConversationScopeRegistry.class);
        toolkit =
                new ModelingToolkit(
                        suggestions,
                        mdlPublish,
                        groupService,
                        conversationScopes,
                        workspace,
                        reader,
                        props,
                        cli);

        DatasetGroupEntity group = new DatasetGroupEntity(GROUP, OWNER, "KB", null);
        when(groupService.getGroup(anyString(), anyString())).thenReturn(group);
        when(groupService.listDatasets(anyString(), anyString())).thenReturn(List.of());
        when(suggestions.listRelations(anyString())).thenReturn(List.of());
        when(suggestions.listCubes(anyString())).thenReturn(List.of());
        when(suggestions.listViews(anyString())).thenReturn(List.of());
    }

    private DatasetScope ownerScope() {
        return new DatasetScope(OWNER);
    }

    @Test
    void stateAndEngineeringValidationExposeTheSameNextAction() {
        var workflow = mock(ModelingWorkflowService.class);
        var state =
                new ModelingWorkflowService.Workflow(
                        GROUP,
                        "CONFIRMATION",
                        "PUBLISHED",
                        2,
                        true,
                        true,
                        "PASSED",
                        null,
                        new ModelingWorkflowService.QuestionSummary(1, 0, 0, 0, 1),
                        List.of(),
                        List.of(
                                new ModelingWorkflowService.Blocker(
                                        "QUESTION_CONFIRMATION", "sales", "请确认结果")),
                        new ModelingWorkflowService.Action("CONFIRM", "审阅结果", "执行成功仍需确认"),
                        false);
        when(workflow.snapshot(any(), eq(GROUP))).thenReturn(state);
        var guided =
                new ModelingToolkit(
                        suggestions,
                        mdlPublish,
                        groupService,
                        conversationScopes,
                        workspace,
                        reader,
                        props,
                        cli,
                        null,
                        workflow);
        when(mdlPublish.validate(GROUP))
                .thenReturn(new MdlPublishService.MdlValidation(true, List.of(), "", List.of()));
        String inventory = guided.listModelingState(singleGroupScope(), null, null);
        String validation = guided.validateMdl(singleGroupScope(), null, null);
        assertTrue(inventory.contains("CONFIRMATION"));
        assertTrue(validation.contains("CONFIRMATION"));
        assertTrue(validation.contains("不代表业务结果已确认"));
        assertTrue(validation.contains("\"canPublish\":false"));
    }

    /** A conversation pinned to exactly one group — the frontend pulls up the session this way. */
    private DatasetScope singleGroupScope() {
        return new DatasetScope(OWNER, List.of(GROUP));
    }

    private Path ws() {
        return workspace.workspaceRoot(GROUP);
    }

    /** Writes a file straight into the workspace, bypassing the tool gate (fixture seeding). */
    private void seed(String relative, String content) throws IOException {
        Path p = ws().resolve(relative);
        Files.createDirectories(p.getParent());
        Files.writeString(p, content, StandardCharsets.UTF_8);
    }

    private String read(String relative) throws IOException {
        return Files.readString(ws().resolve(relative), StandardCharsets.UTF_8);
    }

    /** CLI double: records argv, materializes the official init skeleton, configurable failures. */
    private static final class FakeWrenCli implements WrenCli {

        record Call(String home, List<String> args) {}

        final List<Call> calls = new ArrayList<>();
        boolean failValidate;
        boolean failBuild;
        boolean failDryPlan;

        /** When set, per-model dry-plan commands mentioning this substring fail (specs/034). */
        String failDryPlanFor;

        @Override
        public Result run(Path projectHome, Duration timeout, List<String> args) {
            calls.add(new Call(projectHome.toString(), List.copyOf(args)));
            String joined = String.join(" ", args);
            if (failDryPlanFor != null
                    && joined.startsWith("dry-plan")
                    && joined.contains(failDryPlanFor)) {
                return new Result(1, "Error: model not found: " + failDryPlanFor);
            }
            switch (joined) {
                case "context init --empty --force" -> {
                    write(projectHome.resolve("wren_project.yml"), "schema_version: 5\n");
                    write(projectHome.resolve("relationships.yml"), "relationships: []\n");
                    write(projectHome.resolve("knowledge/rules/general.md"), "# Rules\n");
                    write(projectHome.resolve("AGENTS.md"), "# AGENTS\n");
                    return new Result(0, "initialized");
                }
                case "context validate --strict" -> {
                    return failValidate
                            ? new Result(1, "1 error(s): cubes/order_stats: unknown column")
                            : new Result(0, "0 warning(s), 0 error(s)");
                }
                case "context build" -> {
                    if (failBuild) {
                        return new Result(1, "build failed: broken model");
                    }
                    write(projectHome.resolve("target/mdl.json"), "{\"cubes\":[]}");
                    return new Result(0, "built");
                }
                // specs/023: the write-gate dry-plan always plans SELECT 1 against the scratch
                // copy; the wren_dry_plan tool's arbitrary SQL (e.g. SELECT * FROM orders) falls
                // through to the default branch below.
                case "dry-plan --sql SELECT 1 -d mysql" -> {
                    return failDryPlan
                            ? new Result(
                                    1,
                                    "Error: [INVALID_SQL] Serde JSON error: missing field `type`"
                                            + " at line 1 column 8061 phase=SQL_PLANNING")
                            : new Result(0, "SELECT 1");
                }
                default -> {
                    return new Result(0, "(fake " + joined + ")");
                }
            }
        }

        private static void write(Path file, String content) {
            try {
                Files.createDirectories(file.getParent());
                Files.writeString(file, content, StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }
    }

    // ------------------------------------------------------------------ write_file (triple gate)

    @Test
    void writeFileCreatesFileAfterStrictValidation() throws Exception {
        String out =
                toolkit.writeFile(
                        singleGroupScope(),
                        null,
                        "cubes/order_stats/metadata.yml",
                        "name: order_stats\nbase_object: orders\n",
                        "新增订单分析 Cube",
                        null);

        assertFalse(out.startsWith("error"), out);
        assertTrue(out.contains("已写入"));
        assertTrue(out.contains("新增订单分析 Cube"));
        assertEquals(
                "name: order_stats\nbase_object: orders\n", read("cubes/order_stats/metadata.yml"));
        // Gates ②+③ run the publish-chain argv against a scratch copy, in order.
        List<List<String>> gateArgv = cli.calls.stream().map(FakeWrenCli.Call::args).toList();
        assertTrue(gateArgv.contains(List.of("context", "validate", "--strict")));
        assertTrue(gateArgv.contains(List.of("context", "build")));
        assertTrue(gateArgv.contains(List.of("dry-plan", "--sql", "SELECT 1", "-d", "mysql")));
        assertTrue(
                gateArgv.lastIndexOf(List.of("context", "validate", "--strict"))
                        < gateArgv.lastIndexOf(List.of("context", "build")));
        assertTrue(
                gateArgv.lastIndexOf(List.of("context", "build"))
                        < gateArgv.lastIndexOf(
                                List.of("dry-plan", "--sql", "SELECT 1", "-d", "mysql")));
        // Scratch host is cleaned up after every write.
        assertFalse(Files.exists(props.groupRoot(GROUP).resolve("scratch")));
    }

    @Test
    void writeFileAutoInitializesWorkspaceSkeleton() {
        assertTrue(Files.notExists(ws().resolve("wren_project.yml")));

        String out =
                toolkit.writeFile(
                        singleGroupScope(), null, "cubes/x/metadata.yml", "name: x\n", "r", null);

        assertFalse(out.startsWith("error"), out);
        assertTrue(
                cli.calls.stream()
                        .anyMatch(
                                c ->
                                        c.args()
                                                .equals(
                                                        List.of(
                                                                "context", "init", "--empty",
                                                                "--force"))));
        assertTrue(Files.isRegularFile(ws().resolve("wren_project.yml")));
    }

    @Test
    void questionWriteRequiresPlanAndDoesNotLeakAcrossScopes() throws Exception {
        String source = "question: 销售额是多少\ndefinition: 有效订单\nsql: SELECT SUM(amount) FROM orders\n";
        String missing =
                toolkit.writeFile(
                        singleGroupScope(),
                        null,
                        "knowledge/questions/sales.yml",
                        source,
                        "登记分析问题",
                        null);
        assertTrue(missing.startsWith("error"), missing);
        assertFalse(Files.exists(ws().resolve("knowledge/questions/sales.yml")));
        String planned = source + "modeling:\n  strategy: EXAMPLE\n  reason: 一次性核对总额\n";
        String accepted =
                toolkit.writeFile(
                        singleGroupScope(),
                        null,
                        "knowledge/questions/sales.yml",
                        planned,
                        "登记分析问题",
                        null);
        assertFalse(accepted.startsWith("error"), accepted);
        when(groupService.getGroup("other", GROUP))
                .thenThrow(new io.agentscope.dataagent.dataset.DatasetException("无权访问", 403));
        String foreign =
                toolkit.writeFile(
                        new DatasetScope("other", List.of(GROUP)),
                        null,
                        "knowledge/questions/foreign.yml",
                        planned,
                        "越权测试",
                        null);
        assertTrue(foreign.startsWith("error"), foreign);
        assertFalse(Files.exists(ws().resolve("knowledge/questions/foreign.yml")));
    }

    @Test
    void writeFileRejectsMalformedYamlWithoutTouchingWorkspace() throws Exception {
        workspace.ensureWorkspace(GROUP);
        cli.calls.clear();

        String out =
                toolkit.writeFile(
                        singleGroupScope(),
                        null,
                        "cubes/bad/metadata.yml",
                        "name: [unclosed\n",
                        "坏文件",
                        null);

        assertTrue(out.startsWith("error: YAML 解析失败"), out);
        assertFalse(Files.exists(ws().resolve("cubes/bad/metadata.yml")));
        assertTrue(cli.calls.stream().noneMatch(c -> c.args().contains("validate")));
    }

    @Test
    void writeFileKeepsWorkspaceUntouchedWhenValidationFails() throws Exception {
        workspace.ensureWorkspace(GROUP);
        seed("relationships.yml", "relationships:\n  - name: r0\n");
        String before = read("relationships.yml");
        cli.failValidate = true;

        String out =
                toolkit.writeFile(
                        singleGroupScope(),
                        null,
                        "relationships.yml",
                        "relationships:\n  - name: r1\n",
                        "改关系",
                        null);

        assertTrue(out.startsWith("error: 工程校验未通过"), out);
        assertTrue(out.contains("unknown column"));
        // specs/023 addendum: the gate must coach the agent out of the fix-one-at-a-time
        // deadlock when unrelated warnings in the same file also fail strict validation.
        assertTrue(out.contains("一并修完"), out);
        assertEquals(before, read("relationships.yml"));
        assertFalse(Files.exists(props.groupRoot(GROUP).resolve("scratch")));
    }

    @Test
    void dryPlanGateBlocksWriteWhenSerdeRejectsManifest() throws Exception {
        workspace.ensureWorkspace(GROUP);
        seed("cubes/order_stats/metadata.yml", "name: order_stats\n");
        String before = read("cubes/order_stats/metadata.yml");
        cli.failDryPlan = true;

        String out =
                toolkit.writeFile(
                        singleGroupScope(),
                        null,
                        "cubes/order_stats/metadata.yml",
                        "name: order_stats\nbase_object: orders\n",
                        "补写 Cube",
                        null);

        // specs/023: a type-less cube passes validate --strict (the official validate never
        // deserializes cube members) and must still be rejected by the dry-plan gate.
        assertTrue(out.startsWith("error: 工程校验未通过"), out);
        assertTrue(out.contains("dry-plan 物化预检"), out);
        assertTrue(out.contains("missing field `type`"), out);
        assertTrue(out.contains("enrich-context"), out);
        assertEquals(before, read("cubes/order_stats/metadata.yml"));
        // validate ran but the real workspace never saw the write.
        assertTrue(
                cli.calls.stream()
                        .anyMatch(
                                c -> c.args().equals(List.of("context", "validate", "--strict"))));
        assertFalse(Files.exists(props.groupRoot(GROUP).resolve("scratch")));
    }

    @Test
    void buildGateBlocksWriteWhenContextBuildFails() throws Exception {
        workspace.ensureWorkspace(GROUP);
        seed("models/orders/metadata.yml", "name: orders\n");
        String before = read("models/orders/metadata.yml");
        cli.failBuild = true;

        String out =
                toolkit.writeFile(
                        singleGroupScope(),
                        null,
                        "models/orders/metadata.yml",
                        "name: orders\ncolumns: []\n",
                        "改模型",
                        null);

        assertTrue(out.startsWith("error: 工程校验未通过"), out);
        assertTrue(out.contains("context build 对工程副本执行"), out);
        assertEquals(before, read("models/orders/metadata.yml"));
        assertFalse(
                cli.calls.stream()
                        .anyMatch(
                                c ->
                                        c.args()
                                                .equals(
                                                        List.of(
                                                                "dry-plan",
                                                                "--sql",
                                                                "SELECT 1",
                                                                "-d",
                                                                "mysql"))));
        assertFalse(Files.exists(props.groupRoot(GROUP).resolve("scratch")));
    }

    @Test
    void writeFileRefusesPlatformOwnedAndOutOfBoundsPaths() {
        workspace.ensureWorkspace(GROUP);
        cli.calls.clear();

        assertTrue(
                toolkit.writeFile(singleGroupScope(), null, "wren_project.yml", "x: 1\n", "r", null)
                        .startsWith("error:"));
        assertTrue(
                toolkit.writeFile(
                                singleGroupScope(),
                                null,
                                ".platform/datasets.json",
                                "{}",
                                "r",
                                null)
                        .startsWith("error:"));
        assertTrue(
                toolkit.writeFile(singleGroupScope(), null, "../evil.yml", "a: 1\n", "r", null)
                        .startsWith("error:"));
        assertTrue(cli.calls.stream().noneMatch(c -> c.args().contains("validate")));
    }

    @Test
    void crossTenantWritesCollapseIntoUnknownGroupError() {
        String out =
                toolkit.writeFile(
                        new DatasetScope("bob", List.of("bob-group")),
                        null,
                        "cubes/x/metadata.yml",
                        "a: 1\n",
                        "r",
                        GROUP);

        assertTrue(out.startsWith("error: 未知或无权访问的知识库"), out);
        assertFalse(Files.exists(workspace.workspaceRoot("bob-group")));
    }

    // ------------------------------------------------------------------ patch_file

    @Test
    void patchFileReplacesUniqueMatchThroughTheGate() throws Exception {
        workspace.ensureWorkspace(GROUP);
        seed("relationships.yml", "relationships:\n  - name: r_old\n    condition: a.x = b.y\n");

        String out =
                toolkit.patchFile(
                        singleGroupScope(),
                        null,
                        "relationships.yml",
                        "name: r_old",
                        "name: r_new",
                        null,
                        "改关系名",
                        null);

        assertFalse(out.startsWith("error"), out);
        String content = read("relationships.yml");
        assertTrue(content.contains("name: r_new"), content);
        assertFalse(content.contains("r_old"), content);
    }

    @Test
    void patchFileRejectsMissingOriginalWithoutChanges() throws Exception {
        workspace.ensureWorkspace(GROUP);
        seed("relationships.yml", "relationships: []\n");
        String before = read("relationships.yml");

        String out =
                toolkit.patchFile(
                        singleGroupScope(),
                        null,
                        "relationships.yml",
                        "name: missing",
                        "name: x",
                        null,
                        "r",
                        null);

        assertTrue(out.startsWith("error: original 在文件中不存在"), out);
        assertEquals(before, read("relationships.yml"));
    }

    @Test
    void patchFileRejectsAmbiguousOriginalUnlessReplaceAll() throws Exception {
        workspace.ensureWorkspace(GROUP);
        seed("knowledge/rules/general.md", "# 规则\n默认排除已删除订单\n默认排除已取消订单\n");

        String ambiguous =
                toolkit.patchFile(
                        singleGroupScope(),
                        null,
                        "knowledge/rules/general.md",
                        "默认排除已",
                        "保留",
                        null,
                        "r",
                        null);
        assertTrue(ambiguous.startsWith("error: original 在文件中命中多处"), ambiguous);

        String all =
                toolkit.patchFile(
                        singleGroupScope(),
                        null,
                        "knowledge/rules/general.md",
                        "默认排除已",
                        "保留",
                        true,
                        "r",
                        null);
        assertFalse(all.startsWith("error"), all);
        assertEquals("# 规则\n保留删除订单\n保留取消订单\n", read("knowledge/rules/general.md"));
    }

    @Test
    void patchOnMissingFileDirectsToWriteTool() {
        workspace.ensureWorkspace(GROUP);

        String out =
                toolkit.patchFile(
                        singleGroupScope(),
                        null,
                        "cubes/new/metadata.yml",
                        "a",
                        "b",
                        null,
                        "r",
                        null);

        assertTrue(out.startsWith("error: 文件不存在"), out);
        assertTrue(out.contains("write_file"), out);
    }

    // ------------------------------------------------- create_view (specs/035, ADR 0043)

    @Test
    void createViewWritesPairedFilesThroughTheTripleGate() throws Exception {
        String result =
                toolkit.createView(
                        singleGroupScope(),
                        null,
                        "monthly_active",
                        "SELECT DATE_FORMAT(created_at, '%Y-%m') AS month,"
                                + " COUNT(DISTINCT user_id) AS active_users FROM orders GROUP BY 1",
                        "月活用户口径",
                        "沉淀月活口径",
                        null);

        assertTrue(result.contains("已创建命名视图 monthly_active"), result);
        assertTrue(result.contains("三道预检通过"), result);
        assertTrue(Files.isRegularFile(ws().resolve("views/monthly_active/metadata.yml")));
        assertTrue(Files.isRegularFile(ws().resolve("views/monthly_active/sql.yml")));
        String meta = read("views/monthly_active/metadata.yml");
        assertTrue(meta.contains("name: monthly_active"), meta);
        assertTrue(meta.contains("description: 月活用户口径"), meta);
        String sql = read("views/monthly_active/sql.yml");
        assertTrue(sql.startsWith("statement: |-\n"), sql);
        assertTrue(sql.contains("  SELECT DATE_FORMAT"), sql);
        List<List<String>> argv = cli.calls.stream().map(FakeWrenCli.Call::args).toList();
        assertTrue(argv.contains(List.of("context", "validate", "--strict")), String.valueOf(argv));
        assertTrue(argv.contains(List.of("context", "build")), String.valueOf(argv));
        List<String> dryPlan =
                List.of("dry-plan", "--sql", "SELECT * FROM \"monthly_active\"", "-d", "mysql");
        assertTrue(argv.contains(dryPlan), String.valueOf(argv));
        // The three gates run in order against the scratch copy of the workspace.
        assertTrue(
                argv.lastIndexOf(List.of("context", "validate", "--strict"))
                        < argv.lastIndexOf(List.of("context", "build")));
        assertTrue(argv.lastIndexOf(List.of("context", "build")) < argv.lastIndexOf(dryPlan));
        MdlWorkspaceReader.Snapshot snap = reader.read(GROUP);
        assertEquals(1, snap.views().size());
        assertEquals("monthly_active", snap.views().get(0).name());
        // Scratch host is cleaned up after every gated write.
        assertFalse(Files.exists(props.groupRoot(GROUP).resolve("scratch")));
    }

    @Test
    void createViewAcceptsWithQueries() throws Exception {
        String result =
                toolkit.createView(
                        singleGroupScope(),
                        null,
                        "recent_orders",
                        "WITH ranked AS (SELECT id, ROW_NUMBER() OVER (PARTITION BY customer_id) rn"
                                + " FROM orders) SELECT id FROM ranked WHERE rn = 1",
                        null,
                        "窗口口径",
                        null);

        assertTrue(result.contains("已创建命名视图 recent_orders"), result);
        assertTrue(read("views/recent_orders/sql.yml").contains("ROW_NUMBER() OVER"));
    }

    @Test
    void createViewRejectsDdlAndMultiStatements() {
        String ddl =
                toolkit.createView(
                        singleGroupScope(),
                        null,
                        "evil",
                        "DELETE FROM orders WHERE 1=1",
                        null,
                        "r",
                        null);
        assertTrue(ddl.contains("View SQL must start with SELECT or WITH"), ddl);

        String secondStatement =
                toolkit.createView(
                        singleGroupScope(),
                        null,
                        "evil2",
                        "SELECT 1; DROP TABLE orders",
                        null,
                        "r",
                        null);
        assertTrue(
                secondStatement.contains("View SQL must be a single statement"), secondStatement);
        assertFalse(Files.exists(ws().resolve("views/evil")));
        assertFalse(Files.exists(ws().resolve("views/evil2")));
    }

    @Test
    void createViewRejectsBadNames() {
        String digit =
                toolkit.createView(
                        singleGroupScope(), null, "1abc", "SELECT 1 AS id", null, "r", null);
        assertTrue(digit.startsWith("error: name"), digit);

        String spaced =
                toolkit.createView(
                        singleGroupScope(), null, "a b", "SELECT 1 AS id", null, "r", null);
        assertTrue(spaced.startsWith("error: name"), spaced);
    }

    @Test
    void createViewRejectsMysqlOnlyDialectFunctions() {
        String result =
                toolkit.createView(
                        singleGroupScope(),
                        null,
                        "recent_orders",
                        "SELECT * FROM orders"
                                + " WHERE created_at > DATE_SUB(CURRENT_DATE, INTERVAL 30 DAY)",
                        null,
                        "r",
                        null);

        assertTrue(result.contains("statement 校验未通过（视图未创建）"), result);
        assertTrue(result.contains("不支持的 MySQL/Doris 函数"), result);
        assertTrue(result.contains("DATE_SUB"), result);
        assertFalse(Files.exists(ws().resolve("views/recent_orders")));
    }

    @Test
    void createViewRejectsNameClashWithExistingAssets() throws Exception {
        workspace.ensureWorkspace(GROUP);
        seed(
                "models/orders/metadata.yml",
                "name: orders\ntable_reference:\n  catalog: ''\n  schema: s\n  table: t\n"
                        + "columns:\n- name: id\n  type: BIGINT\n");

        String clash =
                toolkit.createView(
                        singleGroupScope(),
                        null,
                        "orders",
                        "SELECT id FROM orders",
                        null,
                        "r",
                        null);

        assertTrue(clash.contains("重名"), clash);
        assertFalse(Files.exists(ws().resolve("views/orders")));
    }

    @Test
    void createViewGateFailureLeavesWorkspaceUntouched() {
        cli.failDryPlanFor = "monthly_active";

        String result =
                toolkit.createView(
                        singleGroupScope(),
                        null,
                        "monthly_active",
                        "SELECT CURRENT_DATE - INTERVAL 30 DAY AS d",
                        null,
                        "r",
                        null);

        assertTrue(result.contains("试跑失败，未创建"), result);
        assertTrue(result.contains("CURRENT_DATE - INTERVAL N DAY"), result);
        assertFalse(Files.exists(ws().resolve("views/monthly_active")));
        assertFalse(Files.exists(props.groupRoot(GROUP).resolve("scratch")));
    }

    // ------------------------------------------------- previewChange (HITL file-change card)

    @Test
    void previewWriteNewFilePassesGatesWithoutWriting() {
        ModelingToolkit.PreviewResult r =
                toolkit.previewChange(
                        GROUP,
                        "write_file",
                        Map.of(
                                "path",
                                "cubes/order_stats/metadata.yml",
                                "content",
                                "name: order_stats\n"));

        assertTrue(r.ok(), String.valueOf(r.error()));
        assertEquals("", r.oldContent());
        assertEquals("name: order_stats\n", r.newContent());
        assertTrue(
                cli.calls.stream()
                        .anyMatch(
                                c -> c.args().equals(List.of("context", "validate", "--strict"))));
        // Preview never touches the real workspace.
        assertFalse(Files.exists(ws().resolve("cubes/order_stats/metadata.yml")));
        assertFalse(Files.exists(props.groupRoot(GROUP).resolve("scratch")));
    }

    @Test
    void previewWriteExistingFileCarriesOldContentForDiff() throws Exception {
        workspace.ensureWorkspace(GROUP);
        seed("cubes/order_stats/metadata.yml", "name: order_stats_old\n");
        cli.calls.clear();

        ModelingToolkit.PreviewResult r =
                toolkit.previewChange(
                        GROUP,
                        "write_file",
                        Map.of(
                                "path",
                                "cubes/order_stats/metadata.yml",
                                "content",
                                "name: order_stats\n"));

        assertTrue(r.ok(), String.valueOf(r.error()));
        assertEquals("name: order_stats_old\n", r.oldContent());
        assertEquals("name: order_stats\n", r.newContent());
        assertEquals("name: order_stats_old\n", read("cubes/order_stats/metadata.yml"));
    }

    @Test
    void previewPatchShowsMergedContentWithoutWriting() throws Exception {
        workspace.ensureWorkspace(GROUP);
        String before = "relationships:\n  - name: r_old\n    condition: a.x = b.y\n";
        seed("relationships.yml", before);
        cli.calls.clear();

        ModelingToolkit.PreviewResult r =
                toolkit.previewChange(
                        GROUP,
                        "patch_file",
                        Map.of(
                                "path", "relationships.yml",
                                "original", "name: r_old",
                                "replacement", "name: r_new"));

        assertTrue(r.ok(), String.valueOf(r.error()));
        assertEquals(before, r.oldContent());
        assertEquals("relationships:\n  - name: r_new\n    condition: a.x = b.y\n", r.newContent());
        assertEquals(before, read("relationships.yml"));
    }

    @Test
    void previewPatchRejectsAmbiguousOriginalAndKeepsOldContent() throws Exception {
        workspace.ensureWorkspace(GROUP);
        String before = "# 规则\n默认排除已删除订单\n默认排除已取消订单\n";
        seed("knowledge/rules/general.md", before);

        ModelingToolkit.PreviewResult r =
                toolkit.previewChange(
                        GROUP,
                        "patch_file",
                        Map.of(
                                "path", "knowledge/rules/general.md",
                                "original", "默认排除已",
                                "replacement", "保留"));

        assertFalse(r.ok());
        assertTrue(r.error().contains("命中多处"), r.error());
        assertEquals(before, r.oldContent());
        assertEquals("", r.newContent());
        assertEquals(before, read("knowledge/rules/general.md"));
    }

    @Test
    void previewFailsFastOnMalformedYamlBeforeValidation() {
        workspace.ensureWorkspace(GROUP);
        cli.calls.clear();

        ModelingToolkit.PreviewResult r =
                toolkit.previewChange(
                        GROUP,
                        "write_file",
                        Map.of("path", "cubes/bad/metadata.yml", "content", "name: [unclosed\n"));

        assertFalse(r.ok());
        assertTrue(r.error().startsWith("error: YAML 解析失败"), r.error());
        assertTrue(cli.calls.stream().noneMatch(c -> c.args().contains("validate")));
    }

    @Test
    void previewFailsWhenStrictValidationFailsAndKeepsWorkspace() throws Exception {
        workspace.ensureWorkspace(GROUP);
        seed("relationships.yml", "relationships:\n  - name: r0\n");
        cli.failValidate = true;

        ModelingToolkit.PreviewResult r =
                toolkit.previewChange(
                        GROUP,
                        "write_file",
                        Map.of("path", "relationships.yml", "content", "relationships: []\n"));

        assertFalse(r.ok());
        assertTrue(r.error().contains("工程校验未通过"), r.error());
        assertEquals("relationships:\n  - name: r0\n", read("relationships.yml"));
    }

    @Test
    void previewRejectsTraversalAndInvalidInputs() {
        workspace.ensureWorkspace(GROUP);

        ModelingToolkit.PreviewResult traversal =
                toolkit.previewChange(
                        GROUP, "write_file", Map.of("path", "../evil.yml", "content", "a: 1\n"));
        assertFalse(traversal.ok());
        assertTrue(traversal.error().contains("越界"), traversal.error());

        ModelingToolkit.PreviewResult emptyContent =
                toolkit.previewChange(
                        GROUP,
                        "write_file",
                        Map.of("path", "cubes/x/metadata.yml", "content", " "));
        assertEquals("content 不能为空", emptyContent.error());

        ModelingToolkit.PreviewResult emptyOriginal =
                toolkit.previewChange(
                        GROUP, "patch_file", Map.of("path", "relationships.yml", "original", ""));
        assertEquals("original 不能为空", emptyOriginal.error());

        ModelingToolkit.PreviewResult missingFile =
                toolkit.previewChange(
                        GROUP,
                        "patch_file",
                        Map.of(
                                "path", "cubes/new/metadata.yml",
                                "original", "a",
                                "replacement", "b"));
        assertFalse(missingFile.ok());
        assertTrue(missingFile.error().contains("文件不存在"), missingFile.error());
        assertTrue(missingFile.error().contains("write_file"), missingFile.error());
    }

    // ------------------------------------------------------------------ reads

    @Test
    void readFileReturnsExactContentAndPlatformFilesAreReadable() throws Exception {
        workspace.ensureWorkspace(GROUP);
        seed("models/orders/metadata.yml", "name: orders\ncolumns: []\n");

        assertEquals(
                "name: orders\ncolumns: []\n",
                toolkit.readFile(singleGroupScope(), null, "models/orders/metadata.yml", null));
        // Platform-owned files are read-only for agents, but readable.
        assertFalse(
                toolkit.readFile(singleGroupScope(), null, "wren_project.yml", null)
                        .startsWith("error:"));
    }

    @Test
    void readFileRejectsTraversalAndMissingFiles() {
        workspace.ensureWorkspace(GROUP);

        assertTrue(
                toolkit.readFile(singleGroupScope(), null, "../outside.yml", null)
                        .startsWith("error:"));
        assertTrue(
                toolkit.readFile(singleGroupScope(), null, "models/none.yml", null)
                        .startsWith("error: 文件不存在"));
    }

    @Test
    void listFilesEnumeratesWorkspaceAndHintsEmptyState() throws Exception {
        assertTrue(toolkit.listFiles(singleGroupScope(), null, null).contains("尚未初始化"));

        workspace.ensureWorkspace(GROUP);
        seed("models/orders/metadata.yml", "name: orders\n");

        String listed = toolkit.listFiles(singleGroupScope(), null, null);
        assertTrue(listed.contains("models/orders/metadata.yml"), listed);
        assertTrue(listed.contains("relationships.yml"), listed);
    }

    // ------------------------------------------------------------------ official command package

    @Test
    void dryPlanBuildsFirstThenTranslates() {
        workspace.ensureWorkspace(GROUP);
        cli.calls.clear();

        String out = toolkit.wrenDryPlan(singleGroupScope(), null, "SELECT * FROM orders", null);

        assertFalse(out.startsWith("error"), out);
        assertEquals(2, cli.calls.size());
        assertEquals(List.of("context", "build"), cli.calls.get(0).args());
        assertEquals(
                List.of("dry-plan", "--sql", "SELECT * FROM orders", "-d", "mysql"),
                cli.calls.get(1).args());
    }

    @Test
    void buildFailureBlocksCommandPackage() {
        workspace.ensureWorkspace(GROUP);
        cli.failBuild = true;

        String out = toolkit.wrenCubeList(singleGroupScope(), null, null);

        assertTrue(out.startsWith("error: context build 失败"), out);
    }

    @Test
    void commandPackageFailsFastOnUninitializedWorkspace() {
        String out = toolkit.wrenContextInstructions(singleGroupScope(), null, null);

        assertTrue(out.startsWith("error: 工作区尚未初始化"), out);
        assertTrue(cli.calls.isEmpty());
    }

    @Test
    void cubeQueryArgvCarriesFullShape() {
        workspace.ensureWorkspace(GROUP);
        cli.calls.clear();

        String out =
                toolkit.wrenCubeQuery(
                        singleGroupScope(),
                        null,
                        "order_stats",
                        "revenue,orders",
                        "status",
                        "created_at:MONTH",
                        List.of("status:eq:PAID"),
                        List.of("revenue:desc"),
                        10,
                        0,
                        true,
                        null);

        assertFalse(out.startsWith("error"), out);
        List<String> argv = cli.calls.get(cli.calls.size() - 1).args();
        assertEquals(
                List.of(
                        "cube",
                        "query",
                        "--cube",
                        "order_stats",
                        "--measures",
                        "revenue,orders",
                        "--dimensions",
                        "status",
                        "--time-dimension",
                        "created_at:MONTH",
                        "--filter",
                        "status:eq:PAID",
                        "--order-by",
                        "revenue:desc",
                        "--limit",
                        "10",
                        "--offset",
                        "0",
                        "--sql-only"),
                argv);
    }

    @Test
    void cubeQueryDefaultsToSqlOnlyAndOmitsFlagWhenExecuting() {
        workspace.ensureWorkspace(GROUP);
        cli.calls.clear();

        toolkit.wrenCubeQuery(
                singleGroupScope(), null, "c", "m", null, null, null, null, null, null, null, null);
        assertTrue(cli.calls.get(cli.calls.size() - 1).args().contains("--sql-only"));

        cli.calls.clear();
        toolkit.wrenCubeQuery(
                singleGroupScope(),
                null,
                "c",
                "m",
                null,
                null,
                null,
                null,
                null,
                null,
                false,
                null);
        assertFalse(cli.calls.get(cli.calls.size() - 1).args().contains("--sql-only"));
    }

    @Test
    void contextShowAndSkillsGetAssembleArgv() {
        workspace.ensureWorkspace(GROUP);
        cli.calls.clear();

        toolkit.wrenContextShow(singleGroupScope(), null, "json", null);
        assertEquals(List.of("context", "show", "-o", "json"), cli.calls.get(0).args());

        assertTrue(
                toolkit.wrenContextShow(singleGroupScope(), null, "xml", null)
                        .startsWith("error: format"));

        toolkit.wrenSkillsGet(singleGroupScope(), null, "generate-mdl", true, "plan", null);
        assertEquals(
                List.of("skills", "get", "generate-mdl", "--full", "--script", "plan"),
                cli.calls.get(1).args());
    }

    // ------------------------------------------------------------------ list_modeling_state v2

    @Test
    void stateRendersWorkspaceSnapshotWithCandidateQueue() throws Exception {
        workspace.ensureWorkspace(GROUP);
        seed(
                "models/orders/metadata.yml",
                """
                name: orders
                table_reference:
                  schema: test_data
                  table: orders
                columns:
                  - name: order_id
                    type: VARCHAR
                  - name: amount
                    type: DECIMAL
                """);
        seed(
                "relationships.yml",
                """
                relationships:
                  - name: r_orders_customers
                    models: [orders, customers]
                    join_type: MANY_TO_ONE
                    condition: orders.customer_id = customers.id
                """);
        seed(
                "cubes/order_stats/metadata.yml",
                """
                name: order_stats
                base_object: orders
                measures:
                  - name: revenue
                    expression: SUM(amount)
                """);
        seed(
                "views/high_value/metadata.yml",
                "name: high_value\nproperties:\n  description: 高价值订单\n");
        seed("views/high_value/sql.yml", "statement: SELECT * FROM orders WHERE amount > 100\n");
        DatasetRelationEntity pending =
                new DatasetRelationEntity(
                        "r1", GROUP, "dsA", "c1", "dsB", "c2", "SAME_COLUMN", null, 0.8, "llm");
        when(suggestions.listRelations(GROUP)).thenReturn(List.of(pending));

        String out = toolkit.listModelingState(singleGroupScope(), null, null);

        assertFalse(out.startsWith("error"), out);
        assertTrue(out.contains("order_id"), out);
        assertTrue(out.contains("VARCHAR"), out);
        assertTrue(out.contains("orders.customer_id = customers.id"), out);
        assertTrue(out.contains("MANY_TO_ONE"), out);
        assertTrue(out.contains("dsA.c1 → dsB.c2"), out);
        assertTrue(out.contains("order_stats"), out);
        assertTrue(out.contains("revenue=SUM(amount)"), out);
        assertTrue(out.contains("high_value"), out);
    }

    @Test
    void stateFallsBackToDbDatasetsBeforeSeeding() {
        DatasetEntity d = new DatasetEntity();
        d.setId("dsA");
        d.setName("订单表");
        d.setOwnerId(OWNER);
        d.setGroupId(GROUP);
        d.setColumnSchemaJson(
                "[{\"name\":\"order_id\",\"originalName\":\"订单号\",\"sqlType\":\"VARCHAR\","
                        + "\"nullable\":false,\"description\":\"订单唯一编号\"}]");
        when(groupService.listDatasets(OWNER, GROUP)).thenReturn(List.of(d));

        String out = toolkit.listModelingState(singleGroupScope(), null, null);

        assertFalse(out.startsWith("error"), out);
        assertTrue(out.contains("尚未播种"), out);
        assertTrue(out.contains("dsA"), out);
        assertTrue(out.contains("order_id"), out);
        assertTrue(out.contains("订单唯一编号"), out);
    }

    @Test
    void stateSurfacesWorkspaceParseIssues() throws Exception {
        workspace.ensureWorkspace(GROUP);
        seed("cubes/broken/metadata.yml", "name: [unclosed\n");

        String out = toolkit.listModelingState(singleGroupScope(), null, null);

        assertTrue(out.contains("工作区解析问题"), out);
        assertTrue(out.contains("broken"), out);
    }

    @Test
    void stateWithoutGroupAndAmbiguousScopeErrors() {
        assertTrue(toolkit.listModelingState(ownerScope(), null, null).startsWith("error"));

        String out =
                toolkit.listModelingState(
                        new DatasetScope(OWNER, List.of(GROUP, "g2")), null, null);

        assertTrue(out.startsWith("error"));
        assertTrue(out.contains("多个知识库"));
    }

    // ------------------------------------------------------------------ kept tools (regression)

    @Test
    void validateMdlRendersIssues() {
        when(mdlPublish.validate(GROUP))
                .thenReturn(
                        new MdlPublishService.MdlValidation(
                                false,
                                List.of(
                                        new MdlPublishService.MdlIssue(
                                                "error", "关系引用了不存在的列", "local")),
                                "",
                                List.of()));

        String out = toolkit.validateMdl(singleGroupScope(), null, null);

        assertFalse(out.startsWith("error"));
        assertTrue(out.contains("未通过"));
        assertTrue(out.contains("关系引用了不存在的列"));
    }

    @Test
    void confirmRelationMarksDirtyAndReports() {
        DatasetRelationEntity edge = confirmed("r1", "dsA", "c1", "dsB", "c2");
        when(suggestions.confirmRelation(eq(GROUP), eq("r1"), any(), anyBoolean()))
                .thenReturn(edge);

        String out =
                toolkit.confirmRelation(singleGroupScope(), null, "r1", "MANY_TO_ONE", false, null);

        assertFalse(out.startsWith("error"));
        verify(suggestions).confirmRelation(GROUP, "r1", "MANY_TO_ONE", false);
        verify(groupService).markMdlDirty(OWNER, GROUP);
    }

    @Test
    void confirmRelationRejectsInvalidJoinType() {
        String out =
                toolkit.confirmRelation(singleGroupScope(), null, "r1", "SIDEWAYS", false, null);

        assertTrue(out.startsWith("error"));
        assertTrue(out.contains("join_type"));
        verify(groupService, never()).markMdlDirty(anyString(), anyString());
    }

    @Test
    void decideRelationConfirmsAdjustsRejectsAndSkips() {
        DatasetRelationEntity confirmed = confirmed("r1", "dsA", "c1", "dsB", "c2");
        when(suggestions.confirmRelation(GROUP, "r1", null, false, null, null))
                .thenReturn(confirmed);
        DatasetRelationEntity adjusted = confirmed("r2", "dsB", "owner", "dsA", "owner");
        when(suggestions.confirmRelation(
                        GROUP,
                        "r2",
                        "ONE_TO_MANY",
                        true,
                        List.of("owner", "stat_date"),
                        List.of("owner", "日期")))
                .thenReturn(adjusted);
        DatasetRelationEntity rejected = confirmed("r3", "dsA", "c1", "dsB", "c2");
        rejected.setStatus("REJECTED");
        when(suggestions.rejectRelation(GROUP, "r3")).thenReturn(rejected);

        String confirmOut =
                toolkit.decideRelation(
                        singleGroupScope(), null, "r1", "CONFIRM", null, null, null, null, null);
        String adjustOut =
                toolkit.decideRelation(
                        singleGroupScope(),
                        null,
                        "r2",
                        "ADJUST",
                        "one_to_many",
                        true,
                        List.of("owner", "stat_date"),
                        List.of("owner", "日期"),
                        null);
        String rejectOut =
                toolkit.decideRelation(
                        singleGroupScope(), null, "r3", "REJECT", null, null, null, null, null);
        String skipOut =
                toolkit.decideRelation(
                        singleGroupScope(), null, "r4", "SKIP", null, null, null, null, null);

        assertTrue(confirmOut.contains("已确认关系"));
        assertTrue(adjustOut.contains("已调整并确认关系"));
        assertTrue(rejectOut.contains("已否决关系"));
        assertTrue(skipOut.contains("未修改建模状态"));
        verify(suggestions, never()).rejectRelation(GROUP, "r4");
    }

    @Test
    void addRelationValidatesCompositePairsAndMarksDirty() {
        DatasetRelationEntity edge = confirmed("r2", "dsA", "province", "dsB", "省");
        edge.setSourceColumnList(List.of("province", "stat_date"));
        edge.setTargetColumnList(List.of("省", "日期"));
        when(suggestions.addManualRelation(eq(GROUP), any())).thenReturn(edge);

        String out =
                toolkit.addRelation(
                        singleGroupScope(),
                        null,
                        "dsA",
                        List.of("province", "stat_date"),
                        "dsB",
                        List.of("省", "日期"),
                        null,
                        null);

        assertFalse(out.startsWith("error"));
        assertTrue(out.contains("复合键关系"));
        verify(groupService).markMdlDirty(OWNER, GROUP);
    }

    @Test
    void addRelationRejectsMismatchedColumnCounts() {
        String out =
                toolkit.addRelation(
                        singleGroupScope(),
                        null,
                        "dsA",
                        List.of("province", "stat_date"),
                        "dsB",
                        List.of("省"),
                        null,
                        null);

        assertTrue(out.startsWith("error"));
        assertTrue(out.contains("列数必须两侧相等"));
    }

    @Test
    void listTermsRendersDictionary() {
        when(suggestions.listTerms(GROUP))
                .thenReturn(
                        List.of(
                                new SemanticTermEntity(
                                        "t1", GROUP, "高频用户", "近30天访问次数大于30的用户", "重度用户")));

        String out = toolkit.listTerms(singleGroupScope(), null, null);

        assertFalse(out.startsWith("error"));
        assertTrue(out.contains("高频用户"));
        assertTrue(out.contains("同义：重度用户"));
    }

    @Test
    void listTermsHandlesEmptyDictionary() {
        when(suggestions.listTerms(GROUP)).thenReturn(List.of());

        assertTrue(toolkit.listTerms(singleGroupScope(), null, null).contains("暂无"));
    }

    /** specs/026: terms bind to one KB; an unpinned conversation must be told to pass group_id. */
    @Test
    void listTermsRequiresResolvableGroup() {
        String out = toolkit.listTerms(ownerScope(), null, null);

        assertTrue(out.startsWith("error"), out);
        assertTrue(out.contains("无法确定知识库"), out);
        verify(suggestions, never()).listTerms(anyString());
    }

    /** specs/026: a group outside the conversation boundary must never leak its terms. */
    @Test
    void listTermsRejectsForeignGroup() {
        String out = toolkit.listTerms(singleGroupScope(), null, "g2");

        assertTrue(out.startsWith("error: 未知或无权访问"), out);
        verify(suggestions, never()).listTerms(anyString());
    }

    @Test
    void listBusinessRulesReadsWorkspaceRuleFilesAndRejectsForeignGroup() throws Exception {
        seed("knowledge/rules/refund.md", "有效订单：默认排除 deleted_at 非空的订单。\n");

        String listed = toolkit.listBusinessRules(singleGroupScope(), null, null);
        String rejected =
                toolkit.listBusinessRules(new DatasetScope(OWNER, List.of(GROUP)), null, "g2");

        assertFalse(listed.startsWith("error"));
        assertTrue(listed.contains("有效订单"), listed);
        assertTrue(listed.contains("deleted_at 非空的订单"), listed);
        assertTrue(rejected.startsWith("error: 未知或无权访问"));
    }

    // --------------------------------------------------- decide_relations (specs/024 batch gate)

    private DatasetRelationEntity pending(String id) {
        DatasetRelationEntity edge =
                new DatasetRelationEntity(
                        id,
                        GROUP,
                        "ds-click",
                        "user_account",
                        "ds-user",
                        "account_name",
                        "LLM",
                        "一个用户有多条访问记录",
                        0.8,
                        "llm");
        edge.setStatus("PENDING");
        return edge;
    }

    @Test
    void decideRelationsAppliesMixedDecisionsAndRunsWorkspaceValidation() {
        DatasetRelationEntity r1 = pending("rel-1");
        when(suggestions.listRelations(GROUP))
                .thenReturn(List.of(r1, pending("rel-2"), pending("rel-3")));
        when(suggestions.confirmRelation(
                        eq(GROUP), eq("rel-1"), eq("MANY_TO_ONE"), eq(false), isNull(), isNull()))
                .thenReturn(r1);

        String out =
                toolkit.decideRelations(
                        ownerScope(),
                        null,
                        "[{\"relation_id\":\"rel-1\",\"action\":\"CONFIRM\",\"join_type\":\"MANY_TO_ONE\","
                            + "\"note\":\"一个用户有多条访问记录\"},"
                            + "{\"relation_id\":\"rel-2\",\"action\":\"REJECT\"},"
                            + "{\"relation_id\":\"rel-3\",\"action\":\"SKIP\"}]",
                        GROUP);

        assertFalse(out.startsWith("error"), out);
        assertTrue(out.contains("已确认"), out);
        assertTrue(out.contains("一个用户有多条访问记录"), out);
        assertTrue(out.contains("共确认 1 条，否决 1 条，跳过 1 条"), out);
        assertTrue(out.contains("0 warning(s), 0 error(s)"), out);
        verify(suggestions).rejectRelation(GROUP, "rel-2");
        verify(suggestions, never())
                .confirmRelation(eq(GROUP), eq("rel-3"), any(), anyBoolean(), any(), any());
        verify(groupService).markMdlDirty(OWNER, GROUP);
        assertTrue(
                cli.calls.stream()
                        .anyMatch(c -> c.args().equals(List.of("context", "validate", "--strict"))),
                "workspace validation must run after a batched decision");
    }

    @Test
    void decideRelationsRejectsWholeBatchWhenAnyEntryIsInvalid() {
        when(suggestions.listRelations(GROUP)).thenReturn(List.of(pending("rel-1")));

        String out =
                toolkit.decideRelations(
                        ownerScope(),
                        null,
                        "[{\"relation_id\":\"rel-1\"},{\"relation_id\":\"ghost\"}]",
                        GROUP);

        assertTrue(out.startsWith("error:"), out);
        assertTrue(out.contains("ghost"), out);
        assertTrue(out.contains("整批拒绝"), out);
        verify(suggestions, never())
                .confirmRelation(anyString(), anyString(), any(), anyBoolean(), any(), any());
        verify(suggestions, never()).rejectRelation(anyString(), anyString());
        verify(groupService, never()).markMdlDirty(anyString(), anyString());
        assertTrue(cli.calls.isEmpty(), "validation must not run for a rejected batch");
    }

    @Test
    void decideRelationsRejectsMalformedJson() {
        String out = toolkit.decideRelations(ownerScope(), null, "[{broken", GROUP);

        assertTrue(out.startsWith("error:"), out);
        assertTrue(out.contains("JSON"), out);
        assertTrue(cli.calls.isEmpty());
    }

    @Test
    void decideRelationsKeepDecisionsWhenWorkspaceValidationFails() {
        DatasetRelationEntity r1 = pending("rel-1");
        when(suggestions.listRelations(GROUP)).thenReturn(List.of(r1));
        when(suggestions.confirmRelation(eq(GROUP), eq("rel-1"), any(), anyBoolean(), any(), any()))
                .thenReturn(r1);
        cli.failValidate = true;

        String out =
                toolkit.decideRelations(
                        ownerScope(),
                        null,
                        "[{\"relation_id\":\"rel-1\",\"action\":\"CONFIRM\"}]",
                        GROUP);

        assertFalse(out.startsWith("error"), out);
        verify(suggestions)
                .confirmRelation(eq(GROUP), eq("rel-1"), any(), anyBoolean(), any(), any());
        assertTrue(out.contains("未通过"), out);
        assertTrue(out.contains("unknown column"), out);
    }

    // ------------------------------------------------------------------ retirement guards

    @Test
    void retiredStructuredWriteToolsAreGone() {
        for (String name :
                new String[] {
                    "createCube",
                    "updateCube",
                    "deleteCube",
                    "updateView",
                    "deleteView",
                    "createTerm",
                    "deleteTerm",
                    "createBusinessRule"
                }) {
            assertTrue(findMethod(name) == null, "retired tool must be gone: " + name);
        }
        // Read-only dictionary tools: M4 flipped their sources to the workspace files.
        assertTrue(findMethod("listTerms") != null);
        assertTrue(findMethod("listBusinessRules") != null);
    }

    @Test
    void modelingScriptGuidesTheYamlFirstFlow() {
        // specs/019 M3 + specs/022 (ADR 0035): the script is a protocol layer only — it must
        // teach the file+CLI surface, point at the official wren skills as the single modeling
        // knowledge source, and never recite playbook details (YAML field guidance lives in the
        // official skills so platform copies cannot drift).
        String script = DataAgentConfig.MODELING_SCRIPT;
        for (String token :
                new String[] {
                    "list_modeling_state",
                    "list_files",
                    "read_file",
                    "write_file",
                    "patch_file",
                    "validate_mdl",
                    "wren_cube_query",
                    "suggest_relations",
                    "decide_relation",
                    "decide_relations",
                    "add_relation",
                    "relationships.yml",
                    "语义建模",
                    // Official-skill pointing (specs/022).
                    "load_skill_through_path",
                    "generate-mdl",
                    "enrich-context",
                    "usage",
                    "models/",
                    "只追加不重建",
                    "HITL",
                    // View-first route (specs/035, ADR 0043): complex metrics go through the
                    // create_view paired-write tool; ref_sql derived models are manual-edit only.
                    "create_view",
                    "命名视图",
                    "派生模型"
                }) {
            assertTrue(script.contains(token), "script must mention " + token);
        }
        for (String retired :
                new String[] {
                    "create_cube",
                    "update_cube",
                    "delete_cube",
                    "update_view",
                    "delete_view",
                    "create_term",
                    "delete_term",
                    "create_business_rule",
                    "propose_derived_model"
                }) {
            assertFalse(script.contains(retired), "script must not teach retired tool " + retired);
        }
        // Playbook details moved to the official skills (ADR 0035): the script must not recite
        // YAML structure guidance — a stale copy here is exactly what caused the missing-`type`
        // cube incident.
        assertFalse(script.contains("DISTINCT_COUNT"), "script must not recite cube YAML guidance");
        assertFalse(
                script.contains("wren_context_instructions"),
                "script must not point at the business-rules printer for editing guidance");
        assertFalse(script.contains("发布模型到对话内"));
    }

    /** Reflects the @Tool description off the toolkit method (annotation value, not name). */
    private static String toolDescription(String name) {
        Method m = findMethod(name);
        assertTrue(m != null, "tool method must exist: " + name);
        Tool tool = m.getAnnotation(Tool.class);
        assertTrue(tool != null, "method lacks @Tool: " + name);
        return tool.description();
    }

    @Test
    void officialChannelToolDescriptionsMatchCliReality() {
        // specs/022 (ADR 0035): descriptions must teach the real CLI contract — references
        // documents need full=true, script is for executable scripts only, and
        // wren_context_instructions prints business rules, NOT editing guidance (the stale
        // "authoritative guidance" promise sent the agent down the wrong channel in the
        // missing-`type` cube incident).
        String get = toolDescription("wrenSkillsGet");
        assertTrue(get.contains("full=true"), "wren_skills_get must teach full=true");
        assertTrue(get.contains("references"), "wren_skills_get must mention references docs");
        assertTrue(
                get.contains("不能用于 references"),
                "wren_skills_get must forbid --script for references docs");

        String list = toolDescription("wrenSkillsList");
        assertTrue(list.contains("generate-mdl"), "wren_skills_list must name the skills");
        assertTrue(list.contains("enrich-context"), "wren_skills_list must name the skills");

        String instructions = toolDescription("wrenContextInstructions");
        assertTrue(
                instructions.contains("业务规则"),
                "wren_context_instructions must describe business rules");
        assertFalse(
                instructions.contains("权威指引"),
                "wren_context_instructions must not promise editing guidance");
        assertTrue(
                instructions.contains("wren_skills_get"),
                "wren_context_instructions must redirect to the skills channel");
    }

    private static Method findMethod(String name) {
        for (Method m : ModelingToolkit.class.getDeclaredMethods()) {
            if (m.getName().equals(name)) {
                return m;
            }
        }
        return null;
    }

    private static DatasetRelationEntity confirmed(
            String id, String srcDs, String srcCol, String tgtDs, String tgtCol) {
        DatasetRelationEntity edge =
                new DatasetRelationEntity(
                        id, GROUP, srcDs, srcCol, tgtDs, tgtCol, "SAME_COLUMN", null, 0.8, "llm");
        edge.setStatus("CONFIRMED");
        return edge;
    }
}
