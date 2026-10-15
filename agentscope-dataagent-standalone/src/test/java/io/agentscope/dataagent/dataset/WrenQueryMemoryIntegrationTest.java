package io.agentscope.dataagent.dataset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.dataagent.runtime.wren.WrenInstanceRegistry;
import io.agentscope.dataagent.tools.data.WrenMcpToolkit;
import io.agentscope.dataagent.web.persistence.jpa.DatasetGroupEntity;
import io.agentscope.dataagent.web.persistence.jpa.DatasetGroupRepository;
import io.agentscope.dataagent.web.session.ConversationScopeRegistry;
import io.agentscope.dataagent.web.session.SessionTurnParser.TurnEntry;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Exercises real official Markdown storage and vector recall in an isolated project. */
class WrenQueryMemoryIntegrationTest {
    @TempDir Path home;

    @Test
    void modelingAndLikedPairsRemainRecallableAcrossMcpSessions() throws Exception {
        String executable = System.getProperty("dataagent.test.wren", "");
        assumeTrue(
                !executable.isBlank(), "Set dataagent.test.wren to an installed memory-onnx CLI");
        var props = new WrenProperties(executable, home.toString(), "fixture", "duckdb", 180);
        var cli = new ProcessWrenCli(props);
        var groups = mock(DatasetGroupService.class);
        var group = new DatasetGroupEntity("g", "alice", "测试", "");
        group.setMdlVersion(1);
        when(groups.getGroup("alice", "g")).thenReturn(group);
        when(groups.listGroups("alice")).thenReturn(List.of(group));
        when(groups.getGroup("bob", "g")).thenThrow(new DatasetException("不存在", 404));
        Files.createDirectories(props.wrenHome());
        Files.writeString(
                props.wrenHome().resolve("profiles.yml"),
                "active: fixture\nprofiles:\n  fixture:\n    datasource: duckdb\n    url: '"
                        + home.toString().replace('\\', '/')
                        + "'\n    format: csv\n");
        Path draft = props.groupRoot("g").resolve("workspace");
        Files.createDirectories(draft);
        run(cli, props, draft, "context", "init", "--empty", "--force");
        Files.createDirectories(draft.resolve("models/orders"));
        Files.writeString(
                draft.resolve("models/orders/metadata.yml"),
                "name: orders\ncolumns:\n- name: amount\n  type: BIGINT\n");
        Files.writeString(
                draft.resolve("models/orders/ref_sql.sql"),
                "SELECT 99 AS amount UNION ALL SELECT 2990 AS amount");
        run(cli, props, draft, "context", "build");
        String modelingQuestion = "全年总营收是多少";
        String modelingSql = "SELECT SUM(amount) AS revenue FROM orders";
        String likedQuestion = "有效订单有多少笔";
        String likedSql = "SELECT COUNT(*) AS orders_count FROM orders";
        run(cli, props, draft, "query", "--sql", modelingSql);
        run(
                cli,
                props,
                draft,
                "memory",
                "store",
                "--nl",
                modelingQuestion,
                "--sql",
                modelingSql,
                "--tags",
                "source:enrich");
        Path published = props.groupRoot("g").resolve("published");
        try (var paths = Files.walk(draft)) {
            for (Path file : paths.filter(Files::isRegularFile).toList()) {
                Path relative = draft.relativize(file);
                if (relative.toString().startsWith(".")) continue;
                Path target = published.resolve(relative);
                Files.createDirectories(target.getParent());
                Files.copy(file, target);
            }
        }
        // A later draft example must never become visible to the query agent before publishing.
        run(cli, props, draft, "memory", "store", "--nl", "未发布的秘密问题", "--sql", "SELECT 42");
        var projects = new WrenPublishedProject(props, groups, cli);
        var profile = mock(WrenProfileHome.class);
        when(profile.ensure()).thenReturn(props.wrenHome());
        var repository = mock(DatasetGroupRepository.class);
        when(repository.findById("g")).thenReturn(Optional.of(group));
        var registry = new WrenInstanceRegistry(props, profile, projects, repository);
        var context = mock(RuntimeContext.class);
        when(context.getUserId()).thenReturn("alice");
        when(context.get(DatasetScope.class)).thenReturn(new DatasetScope("alice", List.of("g")));
        var toolkit =
                new WrenMcpToolkit(
                        registry,
                        new WrenQueryAccess(groups, new ConversationScopeRegistry()),
                        projects,
                        null);
        var memory = new AnswerQueryMemory(props, cli, groups, projects);
        var json = new ObjectMapper();
        try {
            String recalled =
                    toolkit.callTool(
                            context,
                            "g",
                            "recall_queries",
                            Map.of("question", "一年的营业收入合计是多少"),
                            null);
            assertThat(recalled).contains(modelingQuestion, modelingSql).doesNotContain("未发布的秘密问题");
            String result =
                    toolkit.callTool(
                            context, "g", "run_sql", Map.of("sql", likedSql), likedQuestion);
            assertThat(result).contains("WREN_QUERY_RECEIPT").doesNotStartWith("error:");
            String input =
                    json.writeValueAsString(
                            Map.of(
                                    "tool_name",
                                    "run_sql",
                                    "arguments",
                                    Map.of("sql", likedSql),
                                    "answer_question",
                                    likedQuestion));
            List<TurnEntry> turns =
                    List.of(
                            new TurnEntry("u", null, "USER", likedQuestion, 1, null, null, null),
                            new TurnEntry(
                                    "c",
                                    null,
                                    "TOOL",
                                    null,
                                    1,
                                    "wren_call_tool",
                                    input,
                                    null,
                                    "call"),
                            new TurnEntry(
                                    "r", null, "TOOL", null, 1, "wren_call_tool", null, result),
                            new TurnEntry("a", null, "ASSISTANT", "共两笔订单", 1, null, null, null));
            assertThat(memory.feedback("alice", "s", "a", "UP", turns).savedPairs()).isEqualTo(1);
            Path runtime = projects.indexed("alice", "g");
            assertThat(Files.readString(published.resolve("knowledge/sql/query.md")))
                    .contains(modelingQuestion);
            assertThat(Files.readString(runtime.resolve("knowledge/sql/query.md")))
                    .contains(modelingQuestion);
            assertThat(Files.readString(runtime.resolve("knowledge/sql/liked-query.md")))
                    .contains(likedQuestion);
            registry.invalidate("g");
            String both =
                    toolkit.callTool(
                            context,
                            "g",
                            "recall_queries",
                            Map.of("question", "营收和订单数量", "limit", 5),
                            null);
            assertThat(both)
                    .contains(modelingQuestion, modelingSql, likedQuestion, likedSql)
                    .doesNotContain("未发布的秘密问题");
            assertThat(json.readTree(both).path("matches").size()).isEqualTo(2);
            assertThatThrownBy(() -> projects.indexed("bob", "g"))
                    .isInstanceOf(DatasetException.class);
            assertThat(
                            toolkit.callTool(
                                    context,
                                    "other",
                                    "recall_queries",
                                    Map.of("question", modelingQuestion),
                                    null))
                    .startsWith("error:");
            run(cli, props, runtime, "memory", "status");
        } finally {
            registry.close();
        }
    }

    private static void run(
            ProcessWrenCli cli, WrenProperties props, Path project, String... args) {
        var result = cli.run(project, props.timeout(), List.of(args));
        assertThat(result.exitCode()).as(result.output()).isZero();
    }
}
