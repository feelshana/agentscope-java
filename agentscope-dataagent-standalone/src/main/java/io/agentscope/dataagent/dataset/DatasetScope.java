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
 * Authenticated caller identity and selected KB IDs. ownerId is a legacy accessor name for the
 * caller, never an impersonated KB creator. Services resolve each KB's real owner after ACL checks.
 * null selection is unresolved; an empty list explicitly grants no KB access for this turn.
 */
public record DatasetScope(String ownerId, java.util.List<String> groupIds) {

    public DatasetScope(String ownerId) {
        this(ownerId, null);
    }

    public boolean hasGroupFilter() {
        return groupIds != null;
    }
}
