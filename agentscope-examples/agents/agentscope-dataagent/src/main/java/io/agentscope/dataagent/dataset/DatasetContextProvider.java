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
 * Supplies per-tenant dataset context that is not carried on the {@code DataSource} records
 * themselves — currently the user-authored document describing how their datasets relate to each
 * other, which {@code list_data_sources} appends so the agent can pick (and join) datasets
 * without the user pre-selecting anything.
 */
public interface DatasetContextProvider {

    /** Free-text relationship/knowledge note for the owner, or empty when none was uploaded. */
    String relationshipsText(String ownerId);

    /** Global business-term dictionary text (term → explanation/synonyms), or empty. */
    String semanticTermsText();

    /**
     * Relation edges touching the given table (by dataset name, table name or dataset id) for the
     * owner, as human-readable lines plus suggested JOIN fragments; empty when none.
     */
    String relationsFor(String ownerId, String table);
}
