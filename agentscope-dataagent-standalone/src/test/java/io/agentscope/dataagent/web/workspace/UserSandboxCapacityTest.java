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
package io.agentscope.dataagent.web.workspace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.agentscope.harness.agent.sandbox.Sandbox;
import io.agentscope.harness.agent.sandbox.SandboxClient;
import io.agentscope.harness.agent.sandbox.impl.docker.DockerSandboxClientOptions;
import java.time.Duration;
import java.util.ArrayList;
import org.junit.jupiter.api.Test;

class UserSandboxCapacityTest {
    @Test
    void reclaimsIdleUsersButNeverAnActiveOperation() throws Exception {
        @SuppressWarnings("unchecked")
        SandboxClient<DockerSandboxClientOptions> client = mock(SandboxClient.class);
        Sandbox first = mock(Sandbox.class),
                second = mock(Sandbox.class),
                third = mock(Sandbox.class);
        when(client.create(any(), any(), any())).thenReturn(first, second, third);
        var registry =
                new UserSandboxRegistry(client, null, Duration.ofMinutes(15), Duration.ofHours(1));
        registry.configureLimits("test-capacity", 2);
        try {
            var a = registry.acquire("a", "agent");
            var b = registry.acquire("b", "agent");
            assertThatThrownBy(() -> registry.acquire("c", "agent")).hasMessageContaining("繁忙");
            a.close();
            try (var c = registry.acquire("c", "agent")) {
                assertThat(c.sandbox()).isSameAs(third);
            }
            verify(first).close();
            verify(second, never()).close();
            b.close();
        } finally {
            registry.shutdownAll();
        }
    }

    @Test
    void manySequentialUsersShareAContainerBudget() throws Exception {
        @SuppressWarnings("unchecked")
        SandboxClient<DockerSandboxClientOptions> client = mock(SandboxClient.class);
        var created = new ArrayList<Sandbox>();
        when(client.create(any(), any(), any()))
                .thenAnswer(
                        invocation -> {
                            var sandbox = mock(Sandbox.class);
                            created.add(sandbox);
                            return sandbox;
                        });
        var registry =
                new UserSandboxRegistry(client, null, Duration.ofMinutes(15), Duration.ofHours(1));
        registry.configureLimits("test-sequential", 2);
        try {
            for (int i = 0; i < 30; i++)
                try (var lease = registry.acquire("user" + i, "agent")) {
                    assertThat(lease.sandbox()).isNotNull();
                }
            assertThat(created).hasSize(30);
            long closed =
                    created.stream()
                            .filter(
                                    sandbox ->
                                            org.mockito.Mockito.mockingDetails(sandbox)
                                                    .getInvocations()
                                                    .stream()
                                                    .anyMatch(
                                                            invocation ->
                                                                    invocation
                                                                            .getMethod()
                                                                            .getName()
                                                                            .equals("close")))
                            .count();
            assertThat(closed).isEqualTo(28);
        } finally {
            registry.shutdownAll();
        }
    }
}
