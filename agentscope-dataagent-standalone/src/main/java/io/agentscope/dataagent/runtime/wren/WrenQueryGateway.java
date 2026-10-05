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
package io.agentscope.dataagent.runtime.wren;

import java.util.Map;

/**
 * Runtime gateway to a knowledge base's live wren semantic engine (specs/010 M3). Implemented by
 * {@link WrenInstanceRegistry}; the agent-facing {@code WrenToolkit} depends on this interface so
 * the tool logic (scope checks, guided errors, rendering) is testable without spawning a real
 * {@code wren serve mcp} subprocess.
 *
 * <p>Callers are responsible for tenant/state validation — the gateway only answers "run this MCP
 * tool on that group's instance".
 */
public interface WrenQueryGateway {

    /**
     * One MCP tool invocation. {@code ok=false} carries the server-side error text (a query the
     * wren engine rejected, e.g. an unknown model or a bad filter); transport/spawn failures are
     * raised as {@code DatasetException} instead so the caller can tell "your SQL is wrong" from
     * "the engine is down".
     */
    record WrenCallResult(boolean ok, String payload) {}

    /**
     * Runs {@code tool} on the group's published engine, spawning the instance on demand.
     *
     * @param groupId knowledge base whose published MDL project serves the query
     * @param tool MCP tool name on the wren server ({@code run_sql} / {@code query_cube})
     * @param arguments JSON-native MCP arguments
     */
    WrenCallResult call(String groupId, String tool, Map<String, Object> arguments);

    /**
     * Closes the group's instance so the next call respawns it against the current published
     * snapshot; called after a successful publish (发布 = 重建实例, ADR 0018 D8).
     */
    void invalidate(String groupId);
}
