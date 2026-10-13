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
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.dataagent.dataset.DatasetGroupService;
import io.agentscope.dataagent.dataset.DatasetScope;
import io.agentscope.dataagent.dataset.MdlPublishService;
import io.agentscope.dataagent.dataset.MdlQuestionStore;
import io.agentscope.dataagent.dataset.MdlWorkspaceService;
import io.agentscope.dataagent.dataset.ModelingWorkflowStore;
import io.agentscope.dataagent.dataset.ProcessWrenCli;
import io.agentscope.dataagent.dataset.WrenProjectAccess;
import io.agentscope.dataagent.dataset.WrenProperties;
import io.agentscope.dataagent.web.persistence.jpa.DatasetGroupEntity;
import io.agentscope.dataagent.web.persistence.jpa.DatasetKnowledgeRepository;
import io.agentscope.dataagent.web.session.ConversationScopeRegistry;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Opt-in real installed CLI comparison; no production project or database is touched. */
class OfficialWrenCliIntegrationTest {
    @TempDir Path home;

    @Test
    void adapterAndDirectCliAgreeOnBuildQueryAndMemory() throws Exception {
        String executable = System.getProperty("dataagent.test.wren", "");
        assumeTrue(!executable.isBlank(), "Set -Ddataagent.test.wren to an installed official CLI");
        var props = new WrenProperties(executable, home.toString(), "fixture", "duckdb", 60);
        var cli = new ProcessWrenCli(props);
        var groups = mock(DatasetGroupService.class);
        when(groups.getGroup("alice", "fixture"))
                .thenReturn(new DatasetGroupEntity("fixture", "alice", "测试工程", ""));
        var access =
                new WrenProjectAccess(
                        groups,
                        new MdlWorkspaceService(props, cli),
                        mock(DatasetKnowledgeRepository.class),
                        new ConversationScopeRegistry());
        var context = mock(RuntimeContext.class);
        when(context.getUserId()).thenReturn("alice");
        when(context.get(DatasetScope.class))
                .thenReturn(new DatasetScope("alice", List.of("fixture")));
        var files = new WrenProjectFilesystem(access);
        var publication = mock(MdlPublishService.class);
        when(publication.draftCliProfile("alice", "fixture")).thenReturn("fixture");
        var workspace = new MdlWorkspaceService(props, cli);
        var checks = new ModelingWorkflowStore(workspace, new MdlQuestionStore(workspace));
        var adapter = new OfficialWrenToolkit(access, cli, props, publication, checks);
        var p = access.resolve(context);
        Files.createDirectories(props.wrenHome());
        Files.writeString(
                props.wrenHome().resolve("profiles.yml"),
                "active: fixture\nprofiles:\n  fixture:\n    datasource: duckdb\n    url: '"
                        + home.toString().replace('\\', '/')
                        + "'\n    format: csv\n");
        assertThat(
                        files.write(
                                        context,
                                        "models/orders/metadata.yml",
                                        "name: orders\n"
                                                + "properties:\n"
                                                + "  description: Test orders\n"
                                                + "columns:\n"
                                                + "- name: amount\n"
                                                + "  type: BIGINT\n")
                                .isSuccess())
                .isTrue();
        assertThat(
                        files.write(
                                        context,
                                        "models/orders/ref_sql.sql",
                                        "SELECT 99 AS amount UNION ALL SELECT 2990 AS amount\n")
                                .isSuccess())
                .isTrue();
        assertThat(
                        files.write(
                                        context,
                                        "views/revenue/metadata.yml",
                                        "name: revenue\n"
                                                + "properties:\n"
                                                + "  description: Test total revenue\n")
                                .isSuccess())
                .isTrue();
        assertThat(
                        files.write(
                                        context,
                                        "views/revenue/sql.yml",
                                        "statement: SELECT SUM(amount) AS total FROM orders\n")
                                .isSuccess())
                .isTrue();
        for (List<String> args :
                List.of(
                        List.of("context", "show", "-o", "json"),
                        List.of("context", "validate", "--strict"),
                        List.of("context", "build"),
                        List.of("dry-plan", "--sql", "SELECT total FROM revenue"))) {
            String actual = adapter.execute(context, args);
            var direct = cli.run(p.root(), props.timeout(), adapter.command(p, args));
            assertThat(direct.exitCode()).as(args.toString() + direct.output()).isZero();
            assertThat(actual).endsWith(direct.output()).contains("退出码：0");
        }
        for (String operation : List.of("dry-run", "query")) {
            assertThat(checks.current("fixture").ok()).isTrue();
            var args = List.of(operation, "--sql", "SELECT total FROM revenue");
            String actual = adapter.execute(context, args);
            var direct = cli.run(p.root(), props.timeout(), adapter.command(p, args));
            assertThat(direct.exitCode()).as(direct.output()).isZero();
            assertThat(actual).contains("退出码：0").endsWith(direct.output());
            if (operation.equals("query")) assertThat(actual).contains("3089");
        }
        var bad = List.of("query", "--sql", "SELECT missing FROM revenue");
        var directBad = cli.run(p.root(), props.timeout(), adapter.command(p, bad));
        assertThat(directBad.exitCode()).isNotZero();
        assertThat(adapter.execute(context, bad))
                .endsWith(directBad.output())
                .contains("退出码：" + directBad.exitCode());
        assertThat(
                        adapter.execute(
                                context,
                                List.of(
                                        "memory",
                                        "store",
                                        "--nl",
                                        "全年营收是多少",
                                        "--sql",
                                        "SELECT total FROM revenue")))
                .contains("退出码：0", "Stored:");
        var recall = List.of("memory", "recall", "-q", "全年营收是多少", "-o", "json");
        var directRecall = cli.run(p.root(), props.timeout(), adapter.command(p, recall));
        assertThat(directRecall.exitCode()).isZero();
        assertThat(adapter.execute(context, recall)).endsWith(directRecall.output());
        assertThat(adapter.execute(context, List.of("memory", "status"))).contains("Backend:");
        assertThat(Files.isDirectory(p.root().resolve("knowledge/sql"))).isTrue();
        assertThat(Files.exists(props.groupRoot("fixture").resolve("published"))).isFalse();
        var published = props.groupRoot("fixture").resolve("published");
        try (var paths = Files.walk(p.root())) {
            for (Path file : paths.filter(Files::isRegularFile).toList()) {
                Path relative = p.root().relativize(file);
                if (relative.toString().startsWith(".")) continue;
                Files.createDirectories(published.resolve(relative).getParent());
                Files.copy(file, published.resolve(relative));
            }
        }
        var profileHome = mock(io.agentscope.dataagent.dataset.WrenProfileHome.class);
        when(profileHome.ensure()).thenReturn(props.wrenHome());
        var group = new DatasetGroupEntity("fixture", "alice", "测试工程", "");
        group.setMdlVersion(1);
        when(groups.getGroup("alice", "fixture")).thenReturn(group);
        when(groups.listGroups("alice")).thenReturn(List.of(group));
        var repository =
                mock(io.agentscope.dataagent.web.persistence.jpa.DatasetGroupRepository.class);
        when(repository.findById("fixture")).thenReturn(java.util.Optional.of(group));
        var deployed = new io.agentscope.dataagent.dataset.WrenPublishedProject(props, groups);
        var registry =
                new io.agentscope.dataagent.runtime.wren.WrenInstanceRegistry(
                        props, profileHome, deployed, repository);
        try {
            assertThat(registry.listTools("fixture"))
                    .extracting(io.modelcontextprotocol.spec.McpSchema.Tool::name)
                    .contains("run_sql", "get_instructions", "recall_queries", "get_context")
                    .doesNotContain("store_query");
            var toolkit =
                    new WrenMcpToolkit(
                            registry,
                            new io.agentscope.dataagent.dataset.WrenQueryAccess(
                                    groups, new ConversationScopeRegistry()),
                            deployed,
                            null);
            assertThat(
                            toolkit.callTool(
                                    context,
                                    "fixture",
                                    "run_sql",
                                    java.util.Map.of("sql", "SELECT total FROM revenue"),
                                    "全年营收是多少"))
                    .contains("3089", "WREN_QUERY_RECEIPT");
            assertThat(
                            toolkit.callTool(
                                    context,
                                    "fixture",
                                    "recall_queries",
                                    java.util.Map.of("question", "全年营收是多少"),
                                    null))
                    .contains("matches", "全年营收是多少");
        } finally {
            registry.invalidate("fixture");
        }
    }
}
