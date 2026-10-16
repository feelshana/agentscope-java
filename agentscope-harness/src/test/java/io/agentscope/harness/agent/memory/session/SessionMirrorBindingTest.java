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
package io.agentscope.harness.agent.memory.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.IsolationScope;
import io.agentscope.harness.agent.filesystem.AbstractFilesystem;
import io.agentscope.harness.agent.filesystem.RoutedSandboxFilesystem;
import io.agentscope.harness.agent.filesystem.local.LocalFilesystem;
import io.agentscope.harness.agent.filesystem.sandbox.SandboxBackedFilesystem;
import io.agentscope.harness.agent.sandbox.ExecResult;
import io.agentscope.harness.agent.sandbox.Sandbox;
import io.agentscope.harness.agent.sandbox.SandboxAcquireResult;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SessionMirrorBindingTest {
    @TempDir Path root;

    @Test
    void asyncMirrorKeepsOwnSandboxAndHostRoutesAfterUnbind() throws Exception {
        Sandbox own = mock(Sandbox.class), other = mock(Sandbox.class);
        when(own.exec(any(), anyString(), any())).thenReturn(new ExecResult(1, "", "", false));
        var binding = mock(SandboxAcquireResult.class);
        when(binding.getSandbox()).thenReturn(own);
        var rc =
                RuntimeContext.builder()
                        .userId("alice")
                        .put(SandboxAcquireResult.class, binding)
                        .build();
        var proxy = new SandboxBackedFilesystem();
        proxy.setSandbox(other);
        var metadata =
                new LocalFilesystem(
                        root.resolve("metadata"),
                        true,
                        10,
                        IsolationScope.USER.toNamespaceFactory()) {
                    @Override
                    protected Path resolvePath(RuntimeContext context, String path) {
                        String relative = path;
                        while (relative.startsWith("/")) relative = relative.substring(1);
                        return super.resolvePath(context, relative.isEmpty() ? "." : relative);
                    }
                };
        var routed = new RoutedSandboxFilesystem(proxy, Map.of("agents/", metadata));
        var tree =
                new SessionTree(root.resolve("a.jsonl"), root, routed, null, "agents/a.jsonl")
                        .setRuntimeContext(rc);
        var method = SessionTree.class.getDeclaredMethod("pinIfSandbox", AbstractFilesystem.class);
        method.setAccessible(true);
        var pinned = (AbstractFilesystem) method.invoke(tree, routed);
        rc.put(SandboxAcquireResult.class, null);
        proxy.clearSandboxIfCurrent(other);
        pinned.write(RuntimeContext.empty(), "agents/x/sessions/a.jsonl", "alice only");
        assertEquals(
                "alice only",
                pinned.read(RuntimeContext.empty(), "agents/x/sessions/a.jsonl", 0, 0)
                        .fileData()
                        .content());
        assertFalse(
                metadata.read(
                                RuntimeContext.builder().userId("bob").build(),
                                "/x/sessions/a.jsonl",
                                0,
                                0)
                        .isSuccess());
        assertFalse(pinned.exists(RuntimeContext.empty(), "unrouted"));
        verify(own).exec(any(), anyString(), any());
        verify(other, never()).exec(any(), anyString(), any());
    }
}
