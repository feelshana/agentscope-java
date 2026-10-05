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

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.dataagent.dataset.DatasetScope;
import io.agentscope.harness.agent.filesystem.remote.store.NamespaceFactory;
import java.util.List;

/**
 * Knowledge-base-scoped namespaces for the modeling agent's local filesystem runtime data
 * (long-term memory {@code MEMORY.md} / plaintext {@code memory/} ledger, session logs).
 *
 * <p>The harness default ({@code IsolationScope.USER}) shares one memory file across every
 * knowledge base of the same user, so terms written while modeling group A are injected into
 * group B's conversation through the {@code <memory_context>} system-prompt block. This factory
 * routes per-turn paths through the {@link DatasetScope} carried on the {@link RuntimeContext}
 * instead (specs/029, ADR 0039):
 *
 * <ul>
 *   <li>owner + exactly one {@code groupId} → {@code [owner, "kb-<groupId>"]}: every single-KB
 *       modeling conversation gets its own memory namespace;
 *   <li>anything else (multi-KB turn, no scope, global-only) → {@code [owner]}: falls back to
 *       the USER-level namespace, mirroring {@code IsolationScope.USER};
 *   <li>no owner on the context → falls back to {@code sessionId}; when that is missing as well
 *       → {@code []} (mirrors the framework semantics).
 * </ul>
 *
 * <p>Wired in {@code DataAgentConfig} through {@code LocalFilesystemSpec.namespaceFactory}.
 * Because the workspace manager, the memory tools and the flush / consolidation middlewares all
 * resolve paths through the same namespace factory, one wiring point keeps reads and writes
 * consistent.
 */
public final class GroupScopedMemoryNamespace implements NamespaceFactory {

    /** Stateless singleton — safe to share across agents. */
    public static final GroupScopedMemoryNamespace INSTANCE = new GroupScopedMemoryNamespace();

    private GroupScopedMemoryNamespace() {}

    @Override
    public List<String> getNamespace(RuntimeContext rc) {
        if (rc == null) {
            return List.of();
        }
        DatasetScope scope = rc.get(DatasetScope.class);
        String owner = rc.getUserId();
        if ((owner == null || owner.isBlank()) && scope != null) {
            owner = scope.ownerId();
        }
        if (owner == null || owner.isBlank()) {
            String sid = rc.getSessionId();
            return (sid != null && !sid.isBlank()) ? List.of(sid) : List.of();
        }
        if (scope != null && scope.groupIds() != null && scope.groupIds().size() == 1) {
            String groupId = scope.groupIds().get(0);
            if (groupId != null && !groupId.isBlank()) {
                return List.of(owner, "kb-" + groupId);
            }
        }
        return List.of(owner);
    }
}
