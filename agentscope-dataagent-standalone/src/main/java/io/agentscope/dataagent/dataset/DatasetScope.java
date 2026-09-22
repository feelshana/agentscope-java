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
package io.agentscope.dataagent.dataset;

/**
 * Per-request tenant identity carried in the agent {@code RuntimeContext} and auto-injected into
 * data-tool methods. Users do not pick datasets up front: the agent lists everything this owner
 * may see (via {@code [DATA_SOURCES_OVERVIEW]}) and chooses, so the scope only needs to pin {@code
 * ownerId} for isolation. {@code groupIds} optionally narrows the visible datasets to one or more
 * knowledge bases (TC-style "answer within selected KBs"); null/empty means all of the owner's KBs.
 */
public record DatasetScope(String ownerId, java.util.List<String> groupIds) {

    public DatasetScope(String ownerId) {
        this(ownerId, null);
    }

    public boolean hasGroupFilter() {
        return groupIds != null && !groupIds.isEmpty();
    }
}
