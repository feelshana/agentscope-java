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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.dataagent.dataset.DatasetException;
import io.agentscope.dataagent.dataset.DatasetGroupService;
import io.agentscope.dataagent.dataset.DatasetScope;
import io.agentscope.dataagent.dataset.MdlPublishService;
import io.agentscope.dataagent.dataset.MdlQuestionStore;
import io.agentscope.dataagent.dataset.MdlWorkspaceService;
import io.agentscope.dataagent.dataset.ModelingWorkflowStore;
import io.agentscope.dataagent.dataset.WrenCli;
import io.agentscope.dataagent.dataset.WrenProjectAccess;
import io.agentscope.dataagent.dataset.WrenProperties;
import io.agentscope.dataagent.web.persistence.jpa.DatasetGroupEntity;
import io.agentscope.dataagent.web.persistence.jpa.DatasetKnowledgeRepository;
import io.agentscope.dataagent.web.session.ConversationScopeRegistry;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OfficialWrenToolkitTest {
    @TempDir Path home;
    DatasetGroupService groups;
    WrenProjectAccess access;
    WrenProjectFilesystem files;
    OfficialWrenToolkit commands;
    RuntimeContext alice;
    MdlPublishService publish;
    List<List<String>> invocations;
    WrenProperties props;

    @BeforeEach
    void setup() {
        groups = mock(DatasetGroupService.class);
        when(groups.getGroup("alice", "a"))
                .thenReturn(new DatasetGroupEntity("a", "alice", "销售", ""));
        when(groups.getGroup("bob", "a")).thenThrow(new DatasetException("知识库不存在", 404));
        invocations = new ArrayList<>();
        WrenCli cli =
                (cwd, timeout, args) -> {
                    invocations.add(List.copyOf(args));
                    return new WrenCli.Result(0, "official-output");
                };
        props = new WrenProperties("wren", home.toString(), "dataagent", "mysql", 30);
        access =
                new WrenProjectAccess(
                        groups,
                        new MdlWorkspaceService(props, cli),
                        mock(DatasetKnowledgeRepository.class),
                        new ConversationScopeRegistry());
        files = new WrenProjectFilesystem(access);
        publish = mock(MdlPublishService.class);
        when(publish.draftCliProfile("alice", "a")).thenReturn("dataagent");
        commands = new OfficialWrenToolkit(access, cli, props, publish);
        alice = mock(RuntimeContext.class);
        when(alice.getUserId()).thenReturn("alice");
        when(alice.get(DatasetScope.class)).thenReturn(new DatasetScope("alice", List.of("a")));
    }

    @Test
    void sharedDatabaseGateRejectsQueryAndMemoryBeforeCliExecution() {
        var policy = mock(io.agentscope.dataagent.dataset.FactQueryWindowPolicy.class);
        String sql = "SELECT SUM(o.amount) FROM orders o";
        org.mockito.Mockito.doThrow(new DatasetException("SQL_TIME_WINDOW_REJECTED", 422))
                .when(policy)
                .requireAllowed(
                        org.mockito.ArgumentMatchers.eq(alice),
                        org.mockito.ArgumentMatchers.eq("a"),
                        org.mockito.ArgumentMatchers.eq(sql),
                        org.mockito.ArgumentMatchers.anyString());
        WrenCli cli =
                (cwd, timeout, args) -> {
                    invocations.add(List.copyOf(args));
                    return new WrenCli.Result(0, "ok");
                };
        var adapter = new OfficialWrenToolkit(access, cli, props, publish, null, policy);
        assertThat(adapter.execute(alice, List.of("query", "--sql", sql)))
                .contains("SQL_TIME_WINDOW_REJECTED");
        // dry-plan reads no data: it is compiled without asking the window gate.
        assertThat(adapter.execute(alice, List.of("dry-plan", "--sql", sql)))
                .doesNotContain("SQL_TIME_WINDOW_REJECTED");
        // spec090: basic modeling never stores Q&A pairs, before any window check runs.
        assertThat(adapter.execute(alice, List.of("memory", "store", "--nl", "营收", "--sql", sql)))
                .startsWith("error:")
                .contains("不生成问答对");
        assertThat(invocations)
                .noneMatch(args -> args.contains(sql) && !args.get(0).equals("dry-plan"));
    }

    @Test
    void memoryStoreIsRefusedInPreflightAndNeverReachesTheWindowGate() {
        var policy = mock(io.agentscope.dataagent.dataset.FactQueryWindowPolicy.class);
        String sql = "SELECT SUM(o.amount) FROM orders o";
        org.mockito.Mockito.doThrow(new DatasetException("SQL_TIME_WINDOW_REJECTED", 422))
                .when(policy)
                .requireAllowed(
                        org.mockito.ArgumentMatchers.eq(alice),
                        org.mockito.ArgumentMatchers.eq("a"),
                        org.mockito.ArgumentMatchers.eq(sql),
                        org.mockito.ArgumentMatchers.anyString());
        var adapter =
                new OfficialWrenToolkit(
                        access, (cwd, t, args) -> null, props, publish, null, policy);
        assertThat(
                        adapter.preflight(
                                alice,
                                call(List.of("memory", "store", "--nl", "营收", "--sql", sql))))
                .contains("不生成问答对");
        org.mockito.Mockito.verifyNoInteractions(policy);
        assertThat(adapter.preflight(alice, call(List.of("memory", "index", "--no-seed"))))
                .isNull();
    }

    @Test
    void cubeIsRefusedWithoutRunningTheCli() {
        assertThat(commands.execute(alice, List.of("cube", "list")))
                .startsWith("error:")
                .contains("不执行 Cube");
        assertThat(invocations).noneMatch(args -> args.contains("cube"));
    }

    @Test
    void mountedSkillsCloseTheCliSkillsChannel() {
        var mounted =
                new OfficialWrenToolkit(
                        access,
                        (cwd, t, args) -> {
                            invocations.add(List.copyOf(args));
                            return new WrenCli.Result(0, "skill");
                        },
                        props,
                        publish,
                        null,
                        null,
                        true);
        for (var args :
                List.of(List.of("skills", "list"), List.of("skills", "get", "usage", "--full"))) {
            assertThat(mounted.execute(alice, args))
                    .startsWith("error:")
                    .contains("load_skill_through_path");
        }
        assertThat(invocations).noneMatch(args -> args.contains("skills"));
        assertThat(commands.execute(alice, List.of("skills", "get", "usage"))).contains("退出码：0");
    }

    @Test
    void unknownOptionErrorNamesTheAllowedOptions() {
        String result =
                commands.execute(
                        alice,
                        List.of(
                                "memory",
                                "store",
                                "--nl",
                                "营收",
                                "--sql",
                                "SELECT 1",
                                "--source",
                                "enrich"));
        assertThat(result)
                .contains("未开放的官方选项或路径：--source", "memory store 可用选项", "--tags", "--nl", "--sql");
        assertThat(commands.execute(alice, List.of("context", "instructions", "--verbose")))
                .contains("context instructions 可用选项：无（仅 --help）");
    }

    @Test
    void cartesianJoinsAreRejectedButOrdinaryCommasAreNot() {
        for (String sql :
                List.of(
                        "SELECT COUNT(*) FROM \"用户列表\" u CROSS JOIN \"报表访问明细\" v",
                        "select 1 from a cross   join b",
                        "SELECT COUNT(*) FROM \"用户列表\" u, \"报表访问明细\" v",
                        "SELECT COUNT(*) FROM a, b WHERE a.id = b.id",
                        "SELECT COUNT(*) FROM order o, customer c",
                        "SELECT * FROM \"a\" , \"b\"")) {
            assertThatThrownBy(() -> OfficialWrenToolkit.requireReadOnly(sql))
                    .as(sql)
                    .isInstanceOf(DatasetException.class)
                    .hasMessageContaining("笛卡尔积");
        }
        for (String sql :
                List.of(
                        "SELECT a, b FROM t GROUP BY a, b ORDER BY a, b",
                        "SELECT EXTRACT(YEAR FROM \"d\"), COUNT(*) FROM \"t\" GROUP BY 1",
                        "SELECT * FROM \"t\" WHERE x IN ('a', 'b') LIMIT 5",
                        "SELECT * FROM \"t\" LIMIT 5, 10",
                        "SELECT a, b FROM \"t\" ORDER BY a, b",
                        "SELECT a, COUNT(*) FROM \"t\" GROUP BY a, b",
                        "SELECT * FROM \"order\" o ORDER BY o.a, o.b",
                        "SELECT TRIM(BOTH ',' FROM name), COUNT(*) FROM t GROUP BY 1",
                        "SELECT u.a, v.b FROM \"用户列表\" u JOIN \"报表访问明细\" v ON u.k = v.k",
                        "SELECT 'cross join, from a, b' AS note FROM t")) {
            OfficialWrenToolkit.requireReadOnly(sql);
        }
    }

    @Test
    void dataQueriesAreCappedPerTurnAndTheNextTurnStartsFresh() {
        var turn = RuntimeContext.builder().userId("alice").build();
        turn.put(DatasetScope.class, new DatasetScope("alice", List.of("a")));
        for (int i = 0; i < OfficialWrenToolkit.MAX_QUERIES_PER_TURN; i++) {
            assertThat(commands.execute(turn, List.of("query", "--sql", "SELECT " + i)))
                    .contains("退出码：0");
        }
        int before = invocations.size();
        assertThat(commands.execute(turn, List.of("query", "--sql", "SELECT 99")))
                .startsWith("error:")
                .contains("达到单轮上限");
        assertThat(invocations).hasSize(before);
        assertThat(commands.execute(turn, List.of("context", "validate", "--strict")))
                .contains("退出码：0");

        var nextTurn = RuntimeContext.builder().userId("alice").build();
        nextTurn.put(DatasetScope.class, new DatasetScope("alice", List.of("a")));
        assertThat(commands.execute(nextTurn, List.of("query", "--sql", "SELECT 1")))
                .contains("退出码：0");
    }

    @Test
    void externalTablesCannotBeProbedOrRetriedOnOtherDatesAfterAnEmptyResult() {
        // The 2026-10-21 log: MIN/MAX probes on an external table, re-run with new dates each
        // time the previous window came back empty.
        String[] output = {"min_time max_time  total_rows\n     NaT      NaT           0\n"};
        WrenCli cli =
                (cwd, timeout, args) -> {
                    invocations.add(List.copyOf(args));
                    return new WrenCli.Result(0, output[0]);
                };
        var adapter =
                new OfficialWrenToolkit(
                        access,
                        cli,
                        props,
                        publish,
                        null,
                        null,
                        false,
                        g -> java.util.Set.of("uploads"));
        // Basic modeling turns (no purpose): external tables are never queried, uploads are.
        var modeling = RuntimeContext.builder().userId("alice").build();
        modeling.put(DatasetScope.class, new DatasetScope("alice", List.of("a")));
        access.resolve(modeling); // project scaffolding runs its own CLI init once
        int before = invocations.size();
        // The 2026-10-21 15:00 log: a full-table uniqueness/NULL profile of an external table.
        assertThat(
                        adapter.execute(
                                modeling,
                                List.of(
                                        "query",
                                        "--sql",
                                        "SELECT COUNT(*), COUNT(DISTINCT customer_id) FROM"
                                                + " customers")))
                .startsWith("error:")
                .contains("基础建模只使用采样资料与元数据", "customers");
        assertThat(invocations).hasSize(before);
        assertThat(
                        adapter.execute(
                                modeling,
                                List.of("query", "--sql", "SELECT COUNT(*) FROM uploads")))
                .contains("退出码：0");

        // 2026-10-25: trial questions are only planned; no query runs in a question turn, on
        // external or uploaded tables, while dry-plan (no data access) still does.
        var turn =
                RuntimeContext.builder()
                        .userId("alice")
                        .put(
                                io.agentscope.dataagent.dataset.ModelingTurnPurpose.class,
                                new io.agentscope.dataagent.dataset.ModelingTurnPurpose(
                                        io.agentscope.dataagent.dataset.ModelingTurnPurpose
                                                .QUESTION))
                        .build();
        turn.put(DatasetScope.class, new DatasetScope("alice", List.of("a")));
        before = invocations.size();
        assertThat(
                        adapter.execute(
                                turn,
                                List.of(
                                        "query",
                                        "--sql",
                                        "SELECT SUM(amount) FROM orders WHERE order_time >="
                                                + " '2026-09-01' AND order_time < '2026-10-01'")))
                .startsWith("error:")
                .contains("试问不查询数据", "dry-plan");
        assertThat(adapter.execute(turn, List.of("query", "--sql", "SELECT COUNT(*) FROM uploads")))
                .startsWith("error:")
                .contains("试问不查询数据");
        assertThat(invocations).hasSize(before);
        assertThat(
                        adapter.execute(
                                turn,
                                List.of("dry-plan", "--sql", "SELECT SUM(amount) FROM orders")))
                .contains("退出码：0");
        // Unknown purposes grant nothing.
        org.junit.jupiter.api.Assertions.assertNull(
                io.agentscope.dataagent.dataset.ModelingTurnPurpose.normalize("probe"));
    }

    @Test
    void autoModelingWithoutDocumentsCannotWriteRulesAndEveryWriteIsJournaled() throws Exception {
        var auto =
                RuntimeContext.builder()
                        .userId("alice")
                        .put(
                                io.agentscope.dataagent.dataset.ModelingTurnPurpose.class,
                                new io.agentscope.dataagent.dataset.ModelingTurnPurpose(
                                        io.agentscope.dataagent.dataset.ModelingTurnPurpose.AUTO))
                        .build();
        auto.put(DatasetScope.class, new DatasetScope("alice", List.of("a")));
        var root = access.resolve(auto).root();
        var refused = files.write(auto, "knowledge/rules/inferred.md", "- 只统计正式账号\n");
        assertThat(refused.isSuccess()).isFalse();
        assertThat(refused.error()).contains("knowledge/caveats/");
        assertThat(Files.exists(root.resolve("knowledge/rules/inferred.md"))).isFalse();
        assertThat(files.write(auto, "knowledge/caveats/inferred.md", "待确认\n").isSuccess())
                .isTrue();

        // A business document grounds the rule, and the user's own chat request always may.
        Files.createDirectories(root.resolve("raw/docs"));
        Files.writeString(root.resolve("raw/docs/口径.md"), "营收只算正式账号");
        assertThat(files.write(auto, "knowledge/rules/inferred.md", "- 只统计正式账号\n").isSuccess())
                .isTrue();
        Files.delete(root.resolve("raw/docs/口径.md"));
        var chat = RuntimeContext.builder().userId("alice").build();
        chat.put(DatasetScope.class, new DatasetScope("alice", List.of("a")));
        assertThat(files.write(chat, "knowledge/rules/user.md", "- 用户要求\n").isSuccess()).isTrue();

        assertThat(io.agentscope.dataagent.dataset.DraftChangeLog.read(root))
                .extracting(e -> e.path() + "|" + e.source() + "|" + e.userId())
                .containsExactly(
                        "knowledge/caveats/inferred.md|auto|alice",
                        "knowledge/rules/inferred.md|auto|alice",
                        "knowledge/rules/user.md|chat|alice");
        // Documents are uploaded by users only; the agent cannot write them.
        assertThat(files.write(chat, "raw/docs/伪造.md", "x").isSuccess()).isFalse();
    }

    @Test
    void confirmationQuestionsAreCappedPerTurnAndScopedToTheProject() {
        var confirmations =
                new io.agentscope.dataagent.dataset.ModelingConfirmationService(
                        new MdlWorkspaceService(
                                props, (cwd, timeout, args) -> new WrenCli.Result(0, "")),
                        groups);
        var tool = new ModelingConfirmationTool(access, confirmations);
        var turn = RuntimeContext.builder().userId("alice").build();
        turn.put(DatasetScope.class, new DatasetScope("alice", List.of("a")));
        // 2026-10-22 log: an extra "其他" label without a rule failed twice, and the failures used
        // up the allowance so a real question was never asked. Now the label is dropped and
        // failed calls do not count.
        assertThat(tool.request(turn, "缺少选项", "依据", List.of("只有一个"), List.of("规则 A"), null))
                .startsWith("error:");
        assertThat(
                        tool.request(
                                turn,
                                "聚合口径",
                                "依据",
                                List.of("推荐", "备选", "其他/我在对话中说明"),
                                List.of("规则 A", "规则 B"),
                                null))
                .startsWith("已提交给用户确认");
        assertThat(confirmations.list("alice", "a").get(0).options()).hasSize(2);
        for (int i = 1; i < ModelingConfirmationTool.MAX_PER_TURN; i++) {
            assertThat(
                            tool.request(
                                    turn,
                                    "问题 " + i,
                                    "依据",
                                    List.of("推荐", "其他"),
                                    List.of("规则 A", "规则 B"),
                                    null))
                    .startsWith("已提交给用户确认");
        }
        assertThat(tool.request(turn, "第四个", "依据", List.of("a", "b"), List.of("x", "y"), null))
                .startsWith("error:")
                .contains("本轮已提出");
        assertThat(confirmations.list("alice", "a")).hasSize(ModelingConfirmationTool.MAX_PER_TURN);
        // Relationship candidates have their own allowance.
        assertThat(
                        tool.request(
                                turn,
                                "report_visit.account 与 user.account 是否多对一",
                                "同名账号列",
                                List.of("多对一", "不建立"),
                                List.of("关系 A", "不建立关系"),
                                "relationship"))
                .startsWith("已提交给用户确认");
        // Another user's turn cannot ask on this knowledge base.
        var bob = RuntimeContext.builder().userId("bob").build();
        bob.put(DatasetScope.class, new DatasetScope("bob", List.of("a")));
        assertThat(tool.request(bob, "越权", "依据", List.of("a", "b"), List.of("x", "y"), null))
                .startsWith("error:");
    }

    @Test
    void brokenYamlIsRejectedAndTheOriginalFileIsKept() throws Exception {
        var root = access.resolve(alice).root();
        Path model = root.resolve("models/visits/metadata.yml");
        Files.createDirectories(model.getParent());
        String original =
                "name: visits\ncolumns:\n- name: account\n  properties:\n    description: 账号\n";
        Files.writeString(model, original);
        // The exact failure seen in the 2026-10-20 log: a tag line outside the block scalar.
        var broken =
                files.edit(
                        alice,
                        "models/visits/metadata.yml",
                        "    description: 账号\n",
                        "    description: 账号\n    [null] 全表验证未发现空值\n",
                        false);
        assertThat(broken.isSuccess()).isFalse();
        assertThat(broken.error()).contains("YAML 语法错误", "第 ", "description: |");
        assertThat(Files.readString(model)).isEqualTo(original);

        var fixed =
                files.edit(
                        alice,
                        "models/visits/metadata.yml",
                        "    description: 账号\n",
                        "    description: |\n      账号\n      [null] 全表验证未发现空值\n",
                        false);
        assertThat(fixed.isSuccess()).isTrue();
        assertThat(files.write(alice, "knowledge/rules/x.yml", "a: [1, 2\n").isSuccess()).isFalse();
        assertThat(
                        files.preview(
                                        "alice",
                                        "a",
                                        "edit_file",
                                        Map.of(
                                                "path", "relationships.yml",
                                                "old_string", "relationships: []",
                                                "new_string", "relationships: [\n  - bad: [x"))
                                .ok())
                .isFalse();
    }

    @Test
    void modelsDirectoryOnlyAcceptsOfficialModelFiles() throws Exception {
        var root = access.resolve(alice).root();
        Files.createDirectories(root.resolve("models/visits"));
        for (String path :
                List.of(
                        "models/visits/metadata.new.yml",
                        "models/visits/metadata.tmp.yml",
                        "models/notes.md")) {
            assertThat(files.write(alice, path, "name: x\n").isSuccess()).as(path).isFalse();
        }
        assertThat(files.write(alice, "models/derived/ref_sql.sql", "SELECT 1\n").isSuccess())
                .isTrue();
        assertThat(files.write(alice, "models/derived/metadata.yml", "name: derived\n").isSuccess())
                .isTrue();
    }

    @Test
    void queryOnAnUnbuiltDraftBuildsFirstAndAFailedBuildSkipsTheQuery() throws Exception {
        var root = access.resolve(alice).root();
        Files.deleteIfExists(root.resolve("target/mdl.json"));
        var buildFails = new java.util.concurrent.atomic.AtomicBoolean();
        var adapter =
                new OfficialWrenToolkit(
                        access,
                        (cwd, t, args) -> {
                            invocations.add(List.copyOf(args));
                            if (args.size() > 1
                                    && args.get(0).equals("context")
                                    && args.get(1).equals("build")) {
                                if (buildFails.get()) return new WrenCli.Result(1, "build broke");
                                try {
                                    Files.createDirectories(cwd.resolve("target"));
                                    Files.writeString(cwd.resolve("target/mdl.json"), "{}");
                                } catch (java.io.IOException e) {
                                    throw new java.io.UncheckedIOException(e);
                                }
                            }
                            return new WrenCli.Result(0, "ok");
                        },
                        props,
                        publish);
        invocations.clear();
        assertThat(adapter.execute(alice, List.of("query", "--sql", "SELECT 1")))
                .contains("退出码：0", "平台已先自动执行 context build");
        assertThat(invocations.stream().map(args -> args.get(0) + " " + args.get(1)).toList())
                .containsSubsequence("context build", "query --sql");

        invocations.clear();
        assertThat(adapter.execute(alice, List.of("query", "--sql", "SELECT 2")))
                .doesNotContain("自动执行 context build");
        assertThat(invocations).noneMatch(args -> args.contains("build"));

        Files.delete(root.resolve("target/mdl.json"));
        buildFails.set(true);
        invocations.clear();
        assertThat(adapter.execute(alice, List.of("dry-plan", "--sql", "SELECT 3")))
                .startsWith("error:")
                .contains("自动执行 context build 失败", "build broke");
        assertThat(invocations).noneMatch(args -> args.get(0).equals("dry-plan"));
    }

    @Test
    void toolDescriptionTellsTheModelToQueryModelNamesNotPhysicalTables() throws Exception {
        var description =
                OfficialWrenToolkit.class
                        .getMethod("execute", RuntimeContext.class, List.class)
                        .getAnnotation(io.agentscope.core.tool.Tool.class)
                        .description();
        assertThat(description).contains("SQL 中使用模型名", "不要使用 table_reference 中的物理库表名");
    }

    @Test
    void registrationContainsOnlyNativeFilesAndOfficialCli() {
        var target = new Toolkit();
        ModelingToolkitRegistrar.registerOfficialTools(target, files, commands);
        assertThat(target.getToolNames())
                .containsExactlyInAnyOrder(
                        "list_files",
                        "read_file",
                        "write_file",
                        "edit_file",
                        "grep_files",
                        "glob_files",
                        "delete_file",
                        "wren_cli");
    }

    @Test
    void scopeCannotSelectAnotherOwnerOrMultipleProjects() {
        var bob = mock(RuntimeContext.class);
        when(bob.getUserId()).thenReturn("bob");
        when(bob.get(DatasetScope.class)).thenReturn(new DatasetScope("bob", List.of("a")));
        assertThat(files.read(bob, "models/orders.yml", 0, 10).isSuccess()).isFalse();
        when(bob.get(DatasetScope.class)).thenReturn(new DatasetScope("alice", List.of("a")));
        assertThat(commands.execute(bob, List.of("context", "show"))).startsWith("error:");
        when(alice.get(DatasetScope.class))
                .thenReturn(new DatasetScope("alice", List.of("a", "b")));
        assertThat(commands.execute(alice, List.of("context", "show"))).startsWith("error:");
    }

    @Test
    void nativeFilesRejectTraversalProtectedPathsAndOverwrites() throws Exception {
        for (String path :
                List.of(
                        "../b/models/x.yml",
                        "D:/other/x.yml",
                        "//server/share",
                        "wren_project.yml",
                        "WREN_PROJECT.YML",
                        "target/mdl.json",
                        "TARGET/mdl.json",
                        "knowledge/sql./x.md",
                        "raw/business-document.md",
                        "raw/data-samples.md",
                        "knowledge/rules/data-sampling.md",
                        ".platform/map.json")) {
            assertThat(files.write(alice, path, "blocked").isSuccess()).as(path).isFalse();
        }
        String model = "models/orders/metadata.yml";
        assertThat(files.write(alice, model, "name: orders\n").isSuccess()).isTrue();
        // Whole-file replacement of an existing project file is allowed (YAML still checked).
        assertThat(files.write(alice, model, "name: orders\ncolumns: []\n").isSuccess()).isTrue();
        assertThat(Files.readString(access.resolve(alice).root().resolve(model)))
                .isEqualTo("name: orders\ncolumns: []\n");
        assertThat(files.write(alice, model, "name: [broken").isSuccess()).isFalse();
        assertThat(files.edit(alice, model, "name: orders", "name: sales", false).isSuccess())
                .isTrue();
        assertThat(files.edit(alice, model, "missing", "wrong", false).isSuccess()).isFalse();
        verify(groups, times(3)).markMdlDirty("alice", "a");
    }

    @Test
    void genericPreviewNeverWritesOrRunsSemanticValidation() throws Exception {
        var preview =
                files.preview(
                        "alice",
                        "a",
                        "write_file",
                        Map.of("path", "models/x/metadata.yml", "content", "name: [unknown]"));
        // Syntactically valid YAML that semantic validation would reject must still preview.
        assertThat(preview.ok()).isTrue();
        assertThat(preview.newContent()).isEqualTo("name: [unknown]");
        assertThat(Files.exists(access.resolve(alice).root().resolve("models/x/metadata.yml")))
                .isFalse();
        assertThat(invocations)
                .allSatisfy(args -> assertThat(args).doesNotContain("validate", "build"));
        verify(groups, never()).markMdlDirty("alice", "a");
    }

    @Test
    void basicModelingNeitherReadsNorWritesCubesAndViewsButPreservesThem() throws Exception {
        var root = access.resolve(alice).root();
        for (String kind : List.of("cubes", "views")) {
            var file = root.resolve(kind + "/sales/metadata.yml");
            Files.createDirectories(file.getParent());
            Files.writeString(file, "name: sales\n");
            String path = kind + "/sales/metadata.yml";
            assertThat(files.read(alice, path, 0, 100).isSuccess()).isFalse();
            assertThat(files.read(alice, path, 0, 100).error()).contains("不读取也不写入 Cube/View");
            assertThat(files.ls(alice, kind).isSuccess()).isFalse();
            assertThat(files.write(alice, kind + "/new/metadata.yml", "name: new\n").isSuccess())
                    .isFalse();
            assertThat(files.edit(alice, path, "sales", "changed", false).isSuccess()).isFalse();
            assertThat(files.delete(alice, path).isSuccess()).isFalse();
            assertThat(files.delete(alice, kind + "/sales").isSuccess()).isFalse();
            assertThat(files.preview("alice", "a", "delete_file", Map.of("path", path)).ok())
                    .isFalse();
            assertThat(Files.readString(file)).isEqualTo("name: sales\n");
        }
        assertThat(files.ls(alice, ".").entries())
                .extracting(f -> f.path())
                .noneMatch(s -> s.contains("cubes") || s.contains("views"));
        assertThat(files.glob(alice, "**/*.yml", ".").matches())
                .extracting(f -> f.path())
                .noneMatch(s -> s.contains("cubes") || s.contains("views"));
        assertThat(files.grep(alice, "sales", ".", null).matches()).isEmpty();
        // spec090: Q&A pairs are written only by the platform after user confirmation.
        assertThat(files.write(alice, "knowledge/sql/example.md", "example\n").isSuccess())
                .isFalse();
        assertThat(files.write(alice, "knowledge/rules/example.md", "example\n").isSuccess())
                .isTrue();
        assertThat(
                        files.write(
                                        alice,
                                        "knowledge/questions/q-1.yml",
                                        "question: 访问量\ndefinition: 行数\nsql: SELECT 1\n")
                                .isSuccess())
                .isTrue();
        assertThat(files.delete(alice, "wren_project.yml").isSuccess()).isFalse();
    }

    @Test
    void nativeRootGlobPreservesRootFilesAndHidesPlatformFiles() throws Exception {
        var p = access.resolve(alice);
        Files.writeString(p.root().resolve("relationships.yml"), "relationships: []\n");
        Files.createDirectories(p.root().resolve(".platform"));
        Files.writeString(p.root().resolve(".platform/secret.yml"), "secret");
        var result = files.glob(alice, "*.yml", ".");
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.matches())
                .extracting(f -> f.path())
                .anyMatch(s -> s.endsWith("relationships.yml"))
                .noneMatch(s -> s.contains(".platform"));
        assertThat(files.grep(alice, "secret", ".", null).matches()).isEmpty();
    }

    @Test
    void cliBindsPathsAndRejectsShellOverridesAndMutatingSql() {
        var p = access.resolve(alice);
        assertThat(commands.command(p, List.of("context", "validate", "--strict")))
                .containsSubsequence("--path", p.root().toString());
        assertThat(commands.command(p, List.of("memory", "fetch", "-q", "营收")))
                .contains("--mdl", "--path");
        assertThat(commands.command(p, List.of("memory", "index")))
                .contains(p.root().getParent().resolve("runtime/modeling-memory").toString());
        for (List<String> args :
                List.of(
                        List.of("context", "show", "--path", "D:/other"),
                        List.of("context", "set-profile", "other"),
                        List.of("query", "--sql", "SELECT 1", "--mdl", "foreign.json"),
                        List.of("query", "--sql", "SELECT 1", "--connection-info", "{}"),
                        List.of("query", "--sql", "DELETE FROM orders"),
                        List.of("query", "--sql", "WITH x AS (DELETE FROM orders) SELECT * FROM x"),
                        List.of("query", "--sql", "SELECT 1; DROP TABLE orders"),
                        List.of("query", "--sql", "SELECT 1", "--limit", "10001"),
                        List.of("skills", "get", "usage", "--script", "../secret"),
                        List.of("sh", "-c", "echo hello"))) {
            assertThatThrownBy(() -> commands.command(p, args))
                    .as(args.toString())
                    .isInstanceOf(DatasetException.class);
        }
    }

    @Test
    void queryPinsAuthorizedConnectionAndPreservesOfficialOutput() {
        String result =
                commands.execute(
                        alice, List.of("query", "--sql", "SELECT SUM(amount) FROM orders"));
        assertThat(result).contains("退出码：0", "official-output");
        assertThat(invocations)
                .anySatisfy(
                        args ->
                                assertThat(args)
                                        .containsExactly("context", "set-profile", "dataagent"));
        assertThat(invocations.get(invocations.size() - 1))
                .contains("query", "--mdl", "--limit", "1000");
        verify(publish).draftCliProfile("alice", "a");
    }

    @Test
    void onlySourceWritingMemoryCommandsRequireCliConfirmation() {
        assertThat(
                        OfficialWrenToolkit.requiresConfirmation(
                                call(List.of("memory", "store", "--help"))))
                .isFalse();
        assertThat(
                        OfficialWrenToolkit.requiresConfirmation(
                                call(
                                        List.of(
                                                "memory",
                                                "store",
                                                "--nl",
                                                "营收",
                                                "--sql",
                                                "SELECT 1"))))
                .isTrue();
        assertThat(OfficialWrenToolkit.requiresConfirmation(call(List.of("context", "build"))))
                .isFalse();
        assertThat(OfficialWrenToolkit.requiresConfirmation(call(List.of("memory", "index"))))
                .isTrue();
        assertThat(
                        OfficialWrenToolkit.requiresConfirmation(
                                call(List.of("memory", "index", "--no-seed"))))
                .isFalse();
        assertThat(
                        commands.preflight(
                                alice,
                                call(
                                        List.of(
                                                "memory",
                                                "store",
                                                "--nl",
                                                "营收",
                                                "--sql",
                                                "DELETE FROM orders"))))
                .isNotNull();
        assertThat(
                        commands.preflight(
                                alice,
                                call(
                                        List.of(
                                                "memory",
                                                "store",
                                                "--nl",
                                                "营收",
                                                "--sql",
                                                "SELECT 1"))))
                .contains("不生成问答对");
    }

    @Test
    void officialBuildUpdatesPublicationGuidanceAndFileChangesInvalidateIt() {
        var validationFails = new java.util.concurrent.atomic.AtomicBoolean();
        WrenCli cli =
                (cwd, timeout, args) ->
                        new WrenCli.Result(
                                validationFails.get() ? 1 : 0, "official validation output");
        var workspace = new MdlWorkspaceService(props, cli);
        var checks = new ModelingWorkflowStore(workspace, new MdlQuestionStore(workspace));
        var adapter = new OfficialWrenToolkit(access, cli, props, publish, checks);
        adapter.execute(alice, List.of("context", "validate", "--strict"));
        assertThat(checks.current("a")).isNull();
        adapter.execute(alice, List.of("context", "build"));
        assertThat(checks.current("a").ok()).isTrue();
        assertThat(files.write(alice, "models/new/metadata.yml", "name: new").isSuccess()).isTrue();
        assertThat(checks.current("a")).isNull();
        validationFails.set(true);
        assertThat(adapter.execute(alice, List.of("context", "validate", "--strict")))
                .contains("退出码：1", "official validation output");
        assertThat(checks.current("a").ok()).isFalse();
        assertThat(checks.current("a").issues().get(0).message())
                .isEqualTo("official validation output");
    }

    private static ToolUseBlock call(List<String> args) {
        return ToolUseBlock.builder()
                .id("cli")
                .name("wren_cli")
                .input(Map.of("arguments", args))
                .build();
    }

    /**
     * 2026-10-25: the agent no longer runs context build itself; query-class commands rebuild
     * when any build input changed after the last compile, so plans never use an old MDL.
     */
    @Test
    void compiledDraftIsStaleOnceAnyBuildInputChangesAfterIt() throws Exception {
        java.nio.file.Path root = Files.createTempDirectory("stale-mdl");
        Files.createDirectories(root.resolve("target"));
        Files.createDirectories(root.resolve("models/orders"));
        java.nio.file.Path model = root.resolve("models/orders/metadata.yml");
        Files.writeString(model, "name: orders\n");
        java.nio.file.Path compiled = root.resolve("target/mdl.json");
        Files.writeString(compiled, "{}");
        var later =
                java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() + 60_000);
        Files.setLastModifiedTime(compiled, later);
        assertThat(OfficialWrenToolkit.compiledMdlStale(root)).isFalse();
        Files.setLastModifiedTime(
                model, java.nio.file.attribute.FileTime.fromMillis(later.toMillis() + 1_000));
        assertThat(OfficialWrenToolkit.compiledMdlStale(root)).isTrue();
    }
}
