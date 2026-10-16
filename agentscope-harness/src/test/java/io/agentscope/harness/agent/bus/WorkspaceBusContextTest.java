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
package io.agentscope.harness.agent.bus;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.filesystem.sandbox.SandboxBackedFilesystem;
import io.agentscope.harness.agent.sandbox.ExecResult;
import io.agentscope.harness.agent.sandbox.Sandbox;
import io.agentscope.harness.agent.sandbox.SandboxAcquireResult;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class WorkspaceBusContextTest {
    @Test
    void inboxAndAsyncChecksUseCapturedBindingAfterSiblingClearsFallback() throws Exception {
        Sandbox own = mock(Sandbox.class), sibling = mock(Sandbox.class);
        when(own.exec(any(), anyString(), any())).thenReturn(new ExecResult(1, "", "", false));
        SandboxAcquireResult binding = mock(SandboxAcquireResult.class);
        when(binding.getSandbox()).thenReturn(own);
        RuntimeContext context =
                RuntimeContext.builder()
                        .userId("alice")
                        .sessionId("a")
                        .put(SandboxAcquireResult.class, binding)
                        .build();
        var proxy = new SandboxBackedFilesystem();
        proxy.setSandbox(sibling);
        MessageBus bus = new WorkspaceMessageBus(proxy, "/bus").withContext(context);
        AsyncToolRegistry tools =
                new WorkspaceAsyncToolRegistry(proxy, "/async").withContext(context);
        proxy.clearSandboxIfCurrent(sibling);
        context.put(SandboxAcquireResult.class, null);
        assertTrue(bus.inboxDrain("a", 10).block().isEmpty());
        assertTrue(tools.findStale("a", Duration.ofMinutes(1)).block().isEmpty());
        verify(own, org.mockito.Mockito.times(2)).exec(any(), anyString(), any());
        verify(sibling, org.mockito.Mockito.never()).exec(any(), anyString(), any());
    }
}
