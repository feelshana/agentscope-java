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
package io.agentscope.dataagent.web.persistence.jpa;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * Business-term dictionary entry (TC "语义配置" analogue): a business noun, its explanation
 * and synonyms, bound to one knowledge base (specs/026). Surfaced to the agent via the
 * [KNOWLEDGE_BASE_OVERVIEW] section so natural-language questions map onto the right
 * tables/columns.
 */
@Entity
@Table(name = "dataagent_semantic_term")
public class SemanticTermEntity {

    @Id
    @Column(name = "term_id", length = 64, nullable = false)
    private String id;

    @Column(name = "term", length = 30, nullable = false)
    private String term;

    /** Owning knowledge base (specs/026). Null only for legacy global rows pending migration. */
    @Column(name = "group_id", length = 64)
    private String groupId;

    @Column(name = "explanation", length = 100)
    private String explanation;

    /** Comma/newline separated synonyms. */
    @Column(name = "synonyms", length = 500)
    private String synonyms;

    /** Retired provenance label (specs/011): kept only so legacy rows still render pre-migration. */
    @Column(name = "scope", length = 191)
    private String scope;

    @Column(name = "created_at")
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at")
    private Instant updatedAt = Instant.now();

    public SemanticTermEntity() {}

    public SemanticTermEntity(
            String id, String groupId, String term, String explanation, String synonyms) {
        this.id = id;
        this.groupId = groupId;
        this.term = term;
        this.explanation = explanation;
        this.synonyms = synonyms;
        this.createdAt = Instant.now();
        this.updatedAt = Instant.now();
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getTerm() {
        return term;
    }

    public void setTerm(String term) {
        this.term = term;
    }

    public String getExplanation() {
        return explanation;
    }

    public void setExplanation(String explanation) {
        this.explanation = explanation;
    }

    public String getSynonyms() {
        return synonyms;
    }

    public void setSynonyms(String synonyms) {
        this.synonyms = synonyms;
    }

    public String getScope() {
        return scope;
    }

    public void setScope(String scope) {
        this.scope = scope;
    }

    public String getGroupId() {
        return groupId;
    }

    public void setGroupId(String groupId) {
        this.groupId = groupId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}
