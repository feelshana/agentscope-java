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
package io.agentscope.harness.agent.workspace;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.IsolationScope;
import io.agentscope.harness.agent.filesystem.AbstractFilesystem;
import io.agentscope.harness.agent.filesystem.model.ReadResult;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorkspaceNamespaceLockTest {
    @TempDir Path root;

    @Test
    void separateUserIndexesDoNotSerializeEachOther() throws Exception {
        AbstractFilesystem fs = mock(AbstractFilesystem.class);
        when(fs.read(any(), anyString(), anyInt(), anyInt())).thenReturn(ReadResult.fail("absent"));
        CountDownLatch both = new CountDownLatch(2);
        when(fs.uploadFiles(any(), any()))
                .thenAnswer(
                        invocation -> {
                            both.countDown();
                            assertTrue(both.await(3, TimeUnit.SECONDS));
                            return List.of();
                        });
        WorkspaceManager workspace =
                new WorkspaceManager(root, fs, null, IsolationScope.USER.toNamespaceFactory());
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread alice =
                new Thread(
                        () -> {
                            try {
                                workspace.updateSessionIndex(
                                        RuntimeContext.builder().userId("alice").build(),
                                        "agent",
                                        "a",
                                        "a");
                            } catch (Throwable error) {
                                failure.set(error);
                            }
                        });
        Thread bob =
                new Thread(
                        () -> {
                            try {
                                workspace.updateSessionIndex(
                                        RuntimeContext.builder().userId("bob").build(),
                                        "agent",
                                        "b",
                                        "b");
                            } catch (Throwable error) {
                                failure.set(error);
                            }
                        });
        alice.start();
        bob.start();
        alice.join(5000);
        bob.join(5000);
        assertFalse(alice.isAlive() || bob.isAlive());
        assertNull(failure.get());
        assertTrue(both.getCount() == 0);
    }
}
