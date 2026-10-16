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
    void nativeFilesRejectTraversalProtectedPathsAndOverwrites() {
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
        assertThat(files.write(alice, "models/orders.yml", "name: orders\n").isSuccess()).isTrue();
        assertThat(files.write(alice, "models/orders.yml", "overwritten").isSuccess()).isFalse();
        assertThat(
                        files.edit(alice, "models/orders.yml", "name: orders", "name: sales", false)
                                .isSuccess())
                .isTrue();
        assertThat(files.edit(alice, "models/orders.yml", "missing", "wrong", false).isSuccess())
                .isFalse();
        verify(groups, times(2)).markMdlDirty("alice", "a");
    }

    @Test
    void genericPreviewNeverWritesOrRunsSemanticValidation() throws Exception {
        var preview =
                files.preview(
                        "alice",
                        "a",
                        "write_file",
                        Map.of("path", "models/x.yml", "content", "name: ["));
        assertThat(preview.ok()).isTrue();
        assertThat(preview.newContent()).isEqualTo("name: [");
        assertThat(Files.exists(access.resolve(alice).root().resolve("models/x.yml"))).isFalse();
        assertThat(invocations)
                .allSatisfy(args -> assertThat(args).doesNotContain("validate", "build"));
        verify(groups, never()).markMdlDirty("alice", "a");
    }

    @Test
    void officialAssetsCanBeWrittenAndInvalidCubeCanBeRetractedWithoutRecursiveDeletion()
            throws Exception {
        assertThat(files.write(alice, "cubes/sales/metadata.yml", "name: sales\n").isSuccess())
                .isTrue();
        assertThat(files.delete(alice, "cubes/sales").isSuccess()).isFalse();
        assertThat(
                        files.preview(
                                        "alice",
                                        "a",
                                        "delete_file",
                                        Map.of("path", "cubes/sales/metadata.yml"))
                                .oldContent())
                .isEqualTo("name: sales\n");
        assertThat(files.delete(alice, "cubes/sales/metadata.yml").isSuccess()).isTrue();
        assertThat(files.delete(alice, "cubes/sales").isSuccess()).isTrue();
        assertThat(files.write(alice, "knowledge/sql/example.md", "官方样例\n").isSuccess()).isTrue();
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
                .isNull();
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
        assertThat(files.write(alice, "models/new.yml", "name: new").isSuccess()).isTrue();
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
}
