package io.agentscope.dataagent.dataset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.dataagent.tools.data.OfficialWrenToolkit;
import io.agentscope.dataagent.web.auth.UserStore;
import io.agentscope.dataagent.web.persistence.jpa.DatasetEntity;
import io.agentscope.dataagent.web.persistence.jpa.DatasetGroupEntity;
import io.agentscope.dataagent.web.persistence.jpa.DatasetGroupRepository;
import io.agentscope.dataagent.web.persistence.jpa.DatasetKnowledgeRepository;
import io.agentscope.dataagent.web.persistence.jpa.DatasetRepository;
import io.agentscope.dataagent.web.persistence.jpa.ExternalDataSourceEntity;
import io.agentscope.dataagent.web.persistence.jpa.ExternalDataSourceRepository;
import io.agentscope.dataagent.web.persistence.jpa.KnowledgeBaseGrantEntity;
import io.agentscope.dataagent.web.persistence.jpa.KnowledgeBaseGrantRepository;
import io.agentscope.dataagent.web.session.ConversationScopeRegistry;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

class SharedDraftWrenAuthorizationTest {
    @TempDir Path home;
    final Map<String, KnowledgeBaseGrantEntity> memberships = new ConcurrentHashMap<>();
    final List<List<String>> calls = new CopyOnWriteArrayList<>();
    KnowledgeBaseAccessService acl;
    MdlWorkspaceService workspace;
    WrenProjectAccess projects;
    MdlPublishService publisher;
    OfficialWrenToolkit toolkit;
    Path config;
    String originalConfig;

    @BeforeEach
    void setup() throws Exception {
        var groups = mock(DatasetGroupRepository.class);
        var datasets = mock(DatasetRepository.class);
        var sources = mock(ExternalDataSourceRepository.class);
        var grants = mock(KnowledgeBaseGrantRepository.class);
        var users = mock(UserStore.class);
        var operations = mock(KnowledgeBaseOperations.class);
        when(groups.findById("g"))
                .thenReturn(Optional.of(new DatasetGroupEntity("g", "owner", "共享库", "")));
        when(users.findById(anyString()))
                .thenAnswer(
                        i ->
                                Optional.of(
                                        new UserStore.UserRecord(
                                                i.getArgument(0),
                                                i.getArgument(0),
                                                "",
                                                List.of(
                                                        "admin".equals(i.getArgument(0))
                                                                ? "admin"
                                                                : "user"))));
        when(grants.findByGroupIdAndUserId(anyString(), anyString()))
                .thenAnswer(i -> Optional.ofNullable(memberships.get(i.getArgument(1))));
        grant("manager", "MANAGE");
        grant("reader", "QUERY");
        acl = spy(new KnowledgeBaseAccessService(groups, grants, users, operations));
        var data = new DatasetEntity();
        data.setId("d");
        data.setOwnerId("owner");
        data.setGroupId("g");
        data.setName("orders");
        data.setOrigin("datasource");
        data.setExternalDataSourceId("shared-source");
        when(datasets.findByGroupId("g")).thenReturn(List.of(data));
        var source =
                new ExternalDataSourceEntity(
                        "shared-source",
                        "owner",
                        "共享连接",
                        "mysql",
                        "jdbc:mysql://127.0.0.1:3306/shared",
                        "u",
                        "p",
                        true);
        when(sources.findById("shared-source")).thenReturn(Optional.of(source));
        when(sources.findAll()).thenReturn(List.of(source));
        WrenCli cli =
                (cwd, timeout, args) -> {
                    calls.add(List.copyOf(args));
                    if (args.size() > 1 && args.get(1).equals("set-profile")) {
                        try {
                            Files.writeString(
                                    cwd.resolve("wren_project.yml"), "profile: temporary\n");
                        } catch (java.io.IOException e) {
                            throw new IllegalStateException(e);
                        }
                    }
                    return new WrenCli.Result(0, "official-output");
                };
        var props = new WrenProperties("wren", home.toString(), "dataagent", "mysql", 30);
        workspace = new MdlWorkspaceService(props, cli);
        publisher =
                new MdlPublishService(
                        datasets,
                        groups,
                        sources,
                        new ObjectMapper(),
                        props,
                        cli,
                        mock(WrenProfileHome.class),
                        mock(WrenSourceProber.class),
                        mock(MdlSeeder.class),
                        workspace,
                        mock(MdlWorkspaceReader.class),
                        operations,
                        acl);
        var knowledge = mock(DatasetKnowledgeRepository.class);
        var service =
                new DatasetGroupService(
                        groups,
                        datasets,
                        knowledge,
                        null,
                        null,
                        null,
                        publisher,
                        acl,
                        null,
                        operations);
        projects =
                new WrenProjectAccess(
                        service, workspace, knowledge, new ConversationScopeRegistry());
        toolkit = new OfficialWrenToolkit(projects, cli, props, publisher);
        workspace.ensureWorkspace("g");
        config = workspace.workspaceRoot("g").resolve("wren_project.yml");
        originalConfig = Files.readString(config);
        calls.clear();
    }

    static Stream<Arguments> managersAndCommands() {
        return Stream.of("owner", "manager", "admin")
                .flatMap(
                        actor ->
                                Stream.of(
                                                List.of("query", "--sql", "SELECT 1"),
                                                List.of("dry-run", "--sql", "SELECT 1"),
                                                List.of(
                                                        "cube",
                                                        "query",
                                                        "--cube",
                                                        "sales",
                                                        "--measures",
                                                        "revenue"))
                                        .map(command -> Arguments.of(actor, command)));
    }

    @ParameterizedTest
    @MethodSource("managersAndCommands")
    void managersUseSharedConnectionWithoutReplacingActor(String actor, List<String> command)
            throws Exception {
        assertThat(projects.resolve(actor, "g").ownerId()).isEqualTo(actor);
        assertThat(toolkit.execute(context(actor), command)).contains("退出码：0");
        assertThat(calls).hasSize(2);
        assertThat(calls.get(0)).containsExactly("context", "set-profile", "ext-shared-source");
        assertThat(calls.get(1)).startsWith(command.toArray(String[]::new));
        assertThat(Files.readString(config)).isEqualTo(originalConfig);
        verify(acl, org.mockito.Mockito.atLeast(2)).requireManage(actor, "g");
    }

    @ParameterizedTest
    @ValueSource(strings = {"reader", "outsider"})
    void queryOnlyAndUnsharedUsersCannotBindOrExecuteDraft(String actor) {
        assertThatThrownBy(() -> publisher.draftCliProfile(actor, "g"))
                .isInstanceOf(DatasetException.class)
                .satisfies(
                        e ->
                                assertThat(((DatasetException) e).status())
                                        .isEqualTo("reader".equals(actor) ? 403 : 404));
        assertThat(toolkit.execute(context(actor), List.of("query", "--sql", "SELECT 1")))
                .startsWith("error:");
        assertThat(calls).isEmpty();
    }

    @Test
    void revocationWhileWaitingForWorkspacePreventsConnectionBinding() throws Exception {
        var locked = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var authorized = new CountDownLatch(1);
        doAnswer(
                        i -> {
                            Object result = i.callRealMethod();
                            authorized.countDown();
                            return result;
                        })
                .when(acl)
                .requireManage("manager", "g");
        var pool = Executors.newFixedThreadPool(2);
        try {
            var holder =
                    pool.submit(
                            () ->
                                    workspace.withWorkspaceLock(
                                            "g",
                                            () -> {
                                                locked.countDown();
                                                try {
                                                    if (!release.await(10, TimeUnit.SECONDS))
                                                        throw new IllegalStateException(
                                                                "lock timeout");
                                                } catch (InterruptedException e) {
                                                    Thread.currentThread().interrupt();
                                                    throw new IllegalStateException(e);
                                                }
                                                return null;
                                            }));
            assertThat(locked.await(5, TimeUnit.SECONDS)).isTrue();
            var query =
                    pool.submit(
                            () ->
                                    toolkit.execute(
                                            context("manager"),
                                            List.of("query", "--sql", "SELECT 1")));
            assertThat(authorized.await(5, TimeUnit.SECONDS)).isTrue();
            memberships.remove("manager");
            release.countDown();
            assertThat(query.get(5, TimeUnit.SECONDS)).startsWith("error: 知识库不存在或未授权");
            holder.get(5, TimeUnit.SECONDS);
            assertThat(calls).isEmpty();
            assertThat(Files.readString(config)).isEqualTo(originalConfig);
        } finally {
            release.countDown();
            pool.shutdownNow();
            assertThat(pool.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    private void grant(String actor, String role) {
        var grant = new KnowledgeBaseGrantEntity("g", actor);
        grant.grant(role, "owner");
        memberships.put(actor, grant);
    }

    private RuntimeContext context(String actor) {
        var context = mock(RuntimeContext.class);
        when(context.getUserId()).thenReturn(actor);
        when(context.get(DatasetScope.class)).thenReturn(new DatasetScope(actor, List.of("g")));
        return context;
    }
}
