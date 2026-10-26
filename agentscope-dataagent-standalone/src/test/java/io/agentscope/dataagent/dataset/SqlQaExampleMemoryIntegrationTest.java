package io.agentscope.dataagent.dataset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.agentscope.dataagent.web.persistence.jpa.DatasetGroupEntity;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Opt-in installed official LanceDB regression; no application database or project is used. */
class SqlQaExampleMemoryIntegrationTest {
    @TempDir Path home;

    @Test
    void rollbackRemovesActuallyIndexedRejectedValuesAndRestoresOfficialRecall() throws Exception {
        String executable = System.getProperty("dataagent.test.sql-example-wren", "");
        assumeTrue(
                !executable.isBlank(),
                "Provide an installed official Wren CLI for real memory validation");
        var props = new WrenProperties(executable, home.toString(), "fixture", "duckdb", 180);
        var official = new ProcessWrenCli(props);
        var groups = mock(DatasetGroupService.class);
        var group = new DatasetGroupEntity("g", "alice", "测试工程", "");
        group.setMdlVersion(1);
        when(groups.queryGroup("alice", "g")).thenReturn(group);
        var workspace = new MdlWorkspaceService(props, official);
        workspace.ensureWorkspace("g");
        Path draft = workspace.workspaceRoot("g");
        Files.createDirectories(draft.resolve("models/orders"));
        Files.writeString(
                draft.resolve("models/orders/metadata.yml"),
                "name: orders\ncolumns:\n- name: amount\n  type: BIGINT\n");
        Files.writeString(draft.resolve("models/orders/ref_sql.sql"), "SELECT 99 AS amount");
        var built = official.run(draft, props.timeout(), List.of("context", "build"));
        assertThat(built.ok()).as(built.output()).isTrue();
        Path snapshot = props.groupRoot("g").resolve("published");
        try (var files = Files.walk(draft)) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                Path relative = draft.relativize(file);
                if (relative.toString().startsWith(".")) continue;
                Path target = snapshot.resolve(relative);
                Files.createDirectories(target.getParent());
                Files.copy(file, target);
            }
        }
        AtomicBoolean failAfterActualIndex = new AtomicBoolean();
        WrenCli injected =
                (cwd, timeout, args) -> {
                    if (args.size() > 1
                            && args.get(1).equals("status")
                            && failAfterActualIndex.compareAndSet(true, false)) {
                        return new WrenCli.Result(
                                1, "Injected failure after the actual new index was written");
                    }
                    return official.run(cwd, timeout, args);
                };
        var projects = new WrenPublishedProject(props, groups, injected);
        var service = new SqlQaExampleService(workspace, projects);
        Path example = projects.likedProject("alice", "g").resolve("knowledge/sql/query.md");
        Files.createDirectories(example.getParent());
        String original = "---\nnl: 原始验收订单金额\nsql: SELECT SUM(amount) FROM orders\n---\n";
        Files.writeString(example, original);
        Path runtime = projects.indexed("alice", "g");
        failAfterActualIndex.set(true);
        assertThatThrownBy(
                        () ->
                                service.update(
                                        "alice",
                                        "g",
                                        "liked",
                                        "query.md",
                                        "已拒绝写入测试问题",
                                        "SELECT COUNT(*) FROM orders"))
                .isInstanceOf(DatasetException.class)
                .hasMessageContaining("已恢复");
        assertThat(Files.readString(example)).isEqualTo(original);
        assertThat(Files.readString(runtime.resolve("knowledge/sql/liked-query.md")))
                .isEqualTo(original);
        assertThat(runtime.resolve(".indexed-source")).exists();
        var recalled =
                official.run(
                        runtime,
                        props.timeout(),
                        List.of(
                                "memory",
                                "recall",
                                "--query",
                                "原始验收订单金额",
                                "--limit",
                                "20",
                                "--output",
                                "json",
                                "--path",
                                runtime.resolve(".wren/memory").toString()));
        assertThat(recalled.ok()).as(recalled.output()).isTrue();
        assertThat(recalled.output())
                .contains("原始验收订单金额", "SUM(amount)")
                .doesNotContain("已拒绝写入测试问题", "COUNT(*)");
    }
}
