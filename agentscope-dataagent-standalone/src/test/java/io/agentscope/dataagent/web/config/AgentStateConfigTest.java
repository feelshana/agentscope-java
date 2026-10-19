package io.agentscope.dataagent.web.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.Model;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.core.state.InMemoryAgentStateStore;
import io.agentscope.core.state.JsonFileAgentStateStore;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import javax.sql.DataSource;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.mock.env.MockEnvironment;
import reactor.core.publisher.Flux;

class AgentStateConfigTest {
    @TempDir Path home;

    AgentStateStore store(String name) throws Exception {
        var source = new JdbcDataSource();
        source.setURL("jdbc:h2:mem:" + name + ";DB_CLOSE_DELAY=-1");
        return new AgentStateConfig()
                .durableAgentStateStore(
                        source, new MockEnvironment().withProperty("user.home", home.toString()));
    }

    @Test
    void h2ReloadsLatestSnapshotAndSeparatesUsersAndDatabases() throws Exception {
        var first = store("one");
        assertThat(first).isInstanceOf(JsonFileAgentStateStore.class);
        first.save("alice", "same", "agent_state", state("alice", "same", "old"));
        first.save("alice", "same", "agent_state", state("alice", "same", "latest"));
        first.save("bob", "same", "agent_state", state("bob", "same", "bob-only"));
        var reloaded = store("one");
        assertThat(
                        reloaded.get("alice", "same", "agent_state", AgentState.class)
                                .orElseThrow()
                                .getContext()
                                .get(0)
                                .getTextContent())
                .isEqualTo("latest");
        assertThat(
                        reloaded.get("bob", "same", "agent_state", AgentState.class)
                                .orElseThrow()
                                .getContext()
                                .get(0)
                                .getTextContent())
                .isEqualTo("bob-only");
        assertThat(reloaded.exists("alice", "another")).isFalse();
        assertThat(store("two").exists("alice", "same")).isFalse();
        reloaded.delete("alice", "same");
        assertThat(store("one").exists("alice", "same")).isFalse();
        assertThat(store("one").exists("bob", "same")).isTrue();
    }

    @Test
    void rebuiltAgentReceivesPersistedConversationWithoutFrontendHistoryReplay() throws Exception {
        var model = mock(Model.class);
        when(model.getModelName()).thenReturn("state-test-model");
        var observed = new CopyOnWriteArrayList<List<Msg>>();
        when(model.stream(
                        org.mockito.ArgumentMatchers.anyList(),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any()))
                .thenAnswer(
                        invocation -> {
                            observed.add(List.copyOf(invocation.getArgument(0)));
                            return Flux.just(
                                    ChatResponse.builder()
                                            .content(
                                                    List.of(
                                                            TextBlock.builder()
                                                                    .text("已记录")
                                                                    .build()))
                                            .build());
                        });
        var context = RuntimeContext.builder().userId("alice").sessionId("orders").build();
        try (var before = agent(store("resume"), model)) {
            before.call(List.of(user("统计2025年1月有效订单数，暗号为耐久会话测试")), context)
                    .block(Duration.ofSeconds(10));
        }
        try (var after = agent(store("resume"), model)) {
            after.call(List.of(user("那2月份呢？")), context).block(Duration.ofSeconds(10));
            assertThat(observed.get(1).stream().map(Msg::getTextContent).toList())
                    .anyMatch(text -> text.contains("2025年1月有效订单数"))
                    .contains("那2月份呢？");
            after.call(
                            List.of(user("新会话")),
                            RuntimeContext.builder().userId("alice").sessionId("new").build())
                    .block(Duration.ofSeconds(10));
            after.call(
                            List.of(user("其他用户")),
                            RuntimeContext.builder().userId("bob").sessionId("orders").build())
                    .block(Duration.ofSeconds(10));
            for (int n : List.of(2, 3)) {
                assertThat(observed.get(n).stream().map(Msg::getTextContent).toList())
                        .noneMatch(text -> text.contains("耐久会话测试"));
            }
        }
    }

    @Test
    void explicitFrameworkStoreOverridesDefaultWithoutRequiringDataSource() {
        var custom = new InMemoryAgentStateStore();
        new ApplicationContextRunner()
                .withUserConfiguration(AgentStateConfig.class)
                .withBean(AgentStateStore.class, () -> custom)
                .run(
                        context ->
                                assertThat(context.getBean(AgentStateStore.class))
                                        .isSameAs(custom));
    }

    @Test
    void unsupportedDatabaseFailsInsteadOfSilentlyDiscardingState() throws Exception {
        var source = source("PostgreSQL", "platform");
        assertThatThrownBy(
                        () ->
                                new AgentStateConfig()
                                        .durableAgentStateStore(source, new MockEnvironment()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Unsupported");
    }

    @Test
    void mysqlRequiresSelectedDatabase() throws Exception {
        var source = source("MySQL", null);
        assertThatThrownBy(
                        () ->
                                new AgentStateConfig()
                                        .durableAgentStateStore(source, new MockEnvironment()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("selected platform");
    }

    @Test
    void productionMissingSchemaFailsWithoutAttemptingToCreateIt() throws Exception {
        var source = source("MySQL", "platform");
        var connection = source.getConnection();
        var statement = mock(java.sql.Statement.class);
        when(connection.createStatement()).thenReturn(statement);
        when(statement.executeQuery(org.mockito.ArgumentMatchers.anyString()))
                .thenThrow(new SQLException("missing table"));
        assertThatThrownBy(
                        () ->
                                new AgentStateConfig()
                                        .durableAgentStateStore(
                                                source,
                                                new MockEnvironment()
                                                        .withProperty(
                                                                "spring.jpa.hibernate.ddl-auto",
                                                                "validate")))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("missing table");
        org.mockito.Mockito.verify(statement)
                .executeQuery(org.mockito.ArgumentMatchers.startsWith("SELECT session_id"));
        org.mockito.Mockito.verify(statement).close();
        org.mockito.Mockito.verifyNoMoreInteractions(statement);
    }

    private static DataSource source(String product, String database) throws Exception {
        var source = mock(DataSource.class);
        var connection = mock(Connection.class);
        var metadata = mock(DatabaseMetaData.class);
        when(source.getConnection()).thenReturn(connection);
        when(connection.getMetaData()).thenReturn(metadata);
        when(metadata.getDatabaseProductName()).thenReturn(product);
        when(connection.getCatalog()).thenReturn(database);
        return source;
    }

    private static Msg user(String text) {
        return Msg.builder().role(MsgRole.USER).textContent(text).build();
    }

    private static AgentState state(String user, String session, String text) {
        return AgentState.builder()
                .userId(user)
                .sessionId(session)
                .context(List.of(user(text)))
                .build();
    }

    private static ReActAgent agent(AgentStateStore store, Model model) {
        return ReActAgent.builder()
                .name("resume-test")
                .sysPrompt("测试提示")
                .stateStore(store)
                .model(model)
                .build();
    }
}
