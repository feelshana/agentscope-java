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
package io.agentscope.dataagent.web.session;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * Server-side map of conversation (session key) to the knowledge bases the user scoped that
 * conversation to (TC-style "answer within selected KBs"). The harness chatui channel does not
 * propagate typed {@code RuntimeContext} attributes to tool invocations, so the selected group ids
 * are stashed here by the chat controller and looked up by the data toolkit via the session id,
 * which does survive.
 */
@Component
public class ConversationScopeRegistry {

    private final ConcurrentHashMap<String, List<String>> scopes = new ConcurrentHashMap<>();

    public void put(String sessionKey, List<String> groupIds) {
        if (sessionKey == null || sessionKey.isBlank()) {
            return;
        }
        if (groupIds == null || groupIds.isEmpty()) {
            scopes.remove(sessionKey);
        } else {
            scopes.put(sessionKey, List.copyOf(groupIds));
        }
    }

    public List<String> get(String sessionKey) {
        return sessionKey == null ? null : scopes.get(sessionKey);
    }
}
