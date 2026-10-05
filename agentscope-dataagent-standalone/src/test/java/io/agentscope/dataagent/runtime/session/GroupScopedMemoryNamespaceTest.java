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
package io.agentscope.dataagent.runtime.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.dataagent.dataset.DatasetScope;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Locks the knowledge-base scoping contract of {@link GroupScopedMemoryNamespace} (specs/029,
 * ADR 0039): memory namespaces must differ across knowledge bases (a conversation must not see
 * another group's memory) and must degrade exactly like {@code IsolationScope.USER} when the
 * turn carries no single-group scope.
 */
class GroupScopedMemoryNamespaceTest {

    @Test
    void singleGroupTurnScopesMemoryToOwnerAndGroup() {
        RuntimeContext rc =
                RuntimeContext.builder()
                        .userId("admin")
                        .put(DatasetScope.class, new DatasetScope("admin", List.of("g-4444")))
                        .build();

        assertEquals(List.of("admin", "kb-g-4444"), ns(rc));
    }

    @Test
    void twoKnowledgeBasesNeverShareANamespace() {
        RuntimeContext redSea =
                RuntimeContext.builder()
                        .userId("admin")
                        .put(DatasetScope.class, new DatasetScope("admin", List.of("g-99701d70")))
                        .build();
        RuntimeContext fourK =
                RuntimeContext.builder()
                        .userId("admin")
                        .put(DatasetScope.class, new DatasetScope("admin", List.of("g-b31d134a")))
                        .build();

        assertNotEquals(ns(redSea), ns(fourK));
    }

    @Test
    void multiGroupTurnFallsBackToOwnerLevel() {
        RuntimeContext rc =
                RuntimeContext.builder()
                        .userId("admin")
                        .put(DatasetScope.class, new DatasetScope("admin", List.of("g-1", "g-2")))
                        .build();

        assertEquals(List.of("admin"), ns(rc));
    }

    @Test
    void missingScopeOrGlobalScopeFallsBackToOwnerLevel() {
        RuntimeContext noScope = RuntimeContext.builder().userId("admin").build();
        RuntimeContext globalScope =
                RuntimeContext.builder()
                        .userId("admin")
                        .put(DatasetScope.class, new DatasetScope("admin"))
                        .build();

        assertEquals(List.of("admin"), ns(noScope));
        assertEquals(List.of("admin"), ns(globalScope));
    }

    @Test
    void ownerIdOnScopeCoversWhenUserIdIsAbsent() {
        RuntimeContext rc =
                RuntimeContext.builder()
                        .put(DatasetScope.class, new DatasetScope("admin", List.of("g-4444")))
                        .build();

        assertEquals(List.of("admin", "kb-g-4444"), ns(rc));
    }

    @Test
    void noOwnerFallsBackToSessionThenEmpty() {
        RuntimeContext withSession = RuntimeContext.builder().sessionId("main-1").build();

        assertEquals(List.of("main-1"), ns(withSession));
        assertEquals(List.of(), ns(RuntimeContext.empty()));
        assertEquals(List.of(), GroupScopedMemoryNamespace.INSTANCE.getNamespace(null));
    }

    private static List<String> ns(RuntimeContext rc) {
        return GroupScopedMemoryNamespace.INSTANCE.getNamespace(rc);
    }
}
