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
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.time.Instant;

/** A tenant- and group-scoped business rule injected into future data-question contexts. */
@Entity
@Table(
        name = "dataagent_semantic_business_rule",
        indexes = {
            @Index(name = "ix_semantic_rule_owner_group", columnList = "owner_id,group_id"),
            @Index(
                    name = "ix_semantic_rule_group_name",
                    columnList = "group_id,name",
                    unique = true)
        })
public class SemanticBusinessRuleEntity {

    @Id
    @Column(name = "rule_id", length = 64, nullable = false)
    private String id;

    @Column(name = "owner_id", length = 64, nullable = false)
    private String ownerId;

    @Column(name = "group_id", length = 64, nullable = false)
    private String groupId;

    @Column(name = "name", length = 100, nullable = false)
    private String name;

    @Column(name = "content", length = 2000, nullable = false)
    private String content;

    @Column(name = "source_label", length = 255)
    private String sourceLabel;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    public SemanticBusinessRuleEntity() {}

    public SemanticBusinessRuleEntity(
            String id,
            String ownerId,
            String groupId,
            String name,
            String content,
            String sourceLabel) {
        this.id = id;
        this.ownerId = ownerId;
        this.groupId = groupId;
        this.name = name;
        this.content = content;
        this.sourceLabel = sourceLabel;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getOwnerId() {
        return ownerId;
    }

    public void setOwnerId(String ownerId) {
        this.ownerId = ownerId;
    }

    public String getGroupId() {
        return groupId;
    }

    public void setGroupId(String groupId) {
        this.groupId = groupId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public String getSourceLabel() {
        return sourceLabel;
    }

    public void setSourceLabel(String sourceLabel) {
        this.sourceLabel = sourceLabel;
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
