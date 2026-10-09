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
package io.agentscope.dataagent.runtime.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.dataagent.runtime.session.AgentManagerConfig;
import io.agentscope.dataagent.runtime.session.SessionAgentManager;
import io.agentscope.dataagent.web.workspace.LazySandbox;
import io.agentscope.dataagent.web.workspace.UserSandboxRegistry;
import io.agentscope.harness.agent.IsolationScope;
import io.agentscope.harness.agent.sandbox.Sandbox;
import io.agentscope.harness.agent.sandbox.SandboxClient;
import io.agentscope.harness.agent.sandbox.SandboxContext;
import io.agentscope.harness.agent.sandbox.impl.docker.DockerSandboxClientOptions;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Locks the sandboxless-agent contract of {@link HarnessGateway}: ids registered through {@link
 * HarnessGateway#setSandboxlessAgents} never borrow a per-user container — so metadata-only agents
 * like the modeling assistant keep working with the Docker engine down — while every other agent
 * still receives the per-user external sandbox (the Priority-1 acquire path shared with the
 * browser workspace controllers).
 */
class HarnessGatewaySandboxlessTest {

    private static final String MODELING_AGENT = "modeling-agent";
    private static final String DATA_AGENT = "data-agent";

    private SandboxClient<DockerSandboxClientOptions> client;
    private Sandbox sandbox;
    private HarnessGateway gateway;
    private UserSandboxRegistry registry;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        client = mock(SandboxClient.class);
        sandbox = mock(Sandbox.class);
        SessionAgentManager sessionAgentManager = mock(SessionAgentManager.class);
        when(sessionAgentManager.getConfig()).thenReturn(AgentManagerConfig.defaults());
        when(sessionAgentManager.allSessions()).thenReturn(List.of());

        registry =
                new UserSandboxRegistry(
                        client, null, Duration.ofMinutes(15), Duration.ofSeconds(60));
        gateway = HarnessGateway.create(sessionAgentManager);
        gateway.setUserSandboxRegistry(registry);
        gateway.setSandboxlessAgents(Set.of(MODELING_AGENT));
    }

    @AfterEach
    void tearDown() {
        registry.shutdownAll();
    }

    @Test
    void sandboxlessAgentSkipsTheRegistryBorrowEntirely() {
        RuntimeContext.Builder builder = RuntimeContext.builder();

        gateway.attachUserSandboxContext(builder, "alice", MODELING_AGENT);

        assertThat(builder.build().get(SandboxContext.class)).isNull();
        verify(client, never()).create(any(), any(), any());
    }

    @Test
    void everyOtherAgentBorrowsOnlyOnFirstOperationAndReusesTheUserSandbox() throws Exception {
        when(client.create(any(), any(), any())).thenReturn(sandbox);
        RuntimeContext.Builder first = RuntimeContext.builder();
        RuntimeContext.Builder second = RuntimeContext.builder();

        gateway.attachUserSandboxContext(first, "alice", DATA_AGENT);
        gateway.attachUserSandboxContext(second, "alice", DATA_AGENT);

        SandboxContext ctx = first.build().get(SandboxContext.class);
        assertThat(ctx).isNotNull();
        assertThat(ctx.getExternalSandbox()).isInstanceOf(LazySandbox.class);
        assertThat(ctx.getIsolationScope()).isEqualTo(IsolationScope.USER);
        verify(client, never()).create(any(), any(), any());
        RuntimeContext firstContext = first.build();
        RuntimeContext secondContext = second.build();
        ctx.getExternalSandbox().exec(firstContext, "echo first", 5);
        secondContext
                .get(SandboxContext.class)
                .getExternalSandbox()
                .exec(secondContext, "echo second", 5);
        verify(client, times(1)).create(any(), any(), any());
        verify(sandbox).exec(firstContext, "echo first", 5);
        verify(sandbox).exec(secondContext, "echo second", 5);
        ((LazySandbox) ctx.getExternalSandbox()).releaseLease();
        ((LazySandbox) secondContext.get(SandboxContext.class).getExternalSandbox()).releaseLease();
    }

    @Test
    void clearingTheSandboxlessSetRestoresLazyBorrowingForEveryId() throws Exception {
        when(client.create(any(), any(), any())).thenReturn(sandbox);
        gateway.setSandboxlessAgents(null);
        RuntimeContext.Builder builder = RuntimeContext.builder();

        gateway.attachUserSandboxContext(builder, "alice", MODELING_AGENT);

        assertThat(builder.build().get(SandboxContext.class)).isNotNull();
        verify(client, never()).create(any(), any(), any());
        RuntimeContext context = builder.build();
        Sandbox lazy = context.get(SandboxContext.class).getExternalSandbox();
        lazy.exec(context, "echo modeling", 5);
        verify(client).create(any(), any(), any());
        ((LazySandbox) lazy).releaseLease();
    }
}
