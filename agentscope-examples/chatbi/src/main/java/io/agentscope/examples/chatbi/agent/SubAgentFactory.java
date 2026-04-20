/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.examples.chatbi.agent;

import io.agentscope.core.ReActAgent;

/**
 * Factory interface for creating a per-session sub-Agent instance.
 *
 * <p>Each implementation is a Spring {@code @Component} that holds its own
 * static dependencies (clients, system-prompt strings) and produces a fresh
 * {@link ReActAgent} for every new chat session via {@link #create(AgentContext)}.
 *
 * <p>This decouples sub-Agent construction from {@code SessionAgentManager},
 * making it easy to add, remove, or modify individual agents in isolation.
 */
public interface SubAgentFactory {

    /**
     * Create a new {@link ReActAgent} instance for the given session context.
     *
     * @param ctx per-session context (models, tokens, IDs, etc.)
     * @return a freshly built ReActAgent ready to be registered as a SubAgentTool
     */
    ReActAgent create(AgentContext ctx);
}
