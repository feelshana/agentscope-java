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
 * other, which the {@code [KNOWLEDGE_BASE_OVERVIEW]} section of the system prompt carries so the
 * agent can pick (and join) datasets without the user pre-selecting anything.
 */
public interface DatasetContextProvider {

    /** Free-text relationship/knowledge note for the owner, or empty when none was uploaded. */
    String relationshipsText(String ownerId);

    /** Same as {@link #relationshipsText(String)} but limited to the given knowledge-base ids. */
    String relationshipsText(String ownerId, java.util.List<String> onlyGroups);

    /**
     * Business-term dictionary text (term → explanation/synonyms) for the given knowledge bases
     * (specs/026); empty when none. Terms bind to one knowledge base, so without an explicit
     * boundary nothing is injected — another tenant's terms can never leak into the prompt.
     */
    String semanticTermsText(java.util.List<String> onlyGroups);

    /**
     * Published named-SQL views for the given knowledge bases (specs/011 M2), one line per view
     * as query-ready hints for the agent; empty when none are published yet. Draft views are
     * deliberately omitted — they cannot be queried until a publish.
     */
    String semanticViewsText(java.util.List<String> onlyGroups);

    /** Tenant- and group-scoped semantic business rules visible to the current conversation. */
    String semanticBusinessRulesText(String ownerId, java.util.List<String> onlyGroups);

    /**
     * Relation edges touching the given table (by dataset name, table name or dataset id) for the
     * owner, as human-readable lines plus suggested JOIN fragments; empty when none.
     */
    String relationsFor(String ownerId, String table);

    /** Same as {@link #relationsFor(String, String)} but limited to the given knowledge-base ids. */
    String relationsFor(String ownerId, String table, java.util.List<String> onlyGroups);

    /**
     * Keyword-level evidence retrieval across the owner's visible knowledge documents (optionally
     * narrowed to {@code onlyGroups}): returns up to {@code limit} cited passages ranked by
     * relevance. Documents small enough to be a single chunk are injected whole regardless of
     * term overlap. {@code null} when nothing matches and no such short document exists; callers
     * keep their own "not found" wording so agent-facing contracts stay stable.
     */
    String evidenceFor(String ownerId, java.util.List<String> onlyGroups, String query, int limit);
}
