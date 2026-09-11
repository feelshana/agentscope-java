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
 * A semantic relation edge between two {@link KnowledgeGraphEntityEntity} nodes of a knowledge
 * base graph. {@code label} is the relation type (包含字段 | 是 | 示例 | 关联 | 同义 | 范围 ...);
 * {@code text} is the free-form description the model emitted.
 */
@Entity
@Table(name = "dataagent_kg_relation")
public class KnowledgeGraphRelationEntity {

    @Id
    @Column(name = "relation_id", length = 64, nullable = false)
    private String id;

    @Column(name = "group_id", length = 64, nullable = false)
    private String groupId;

    @Column(name = "source_entity_id", length = 64, nullable = false)
    private String sourceEntityId;

    @Column(name = "target_entity_id", length = 64, nullable = false)
    private String targetEntityId;

    @Column(name = "label", length = 64, nullable = false)
    private String label;

    @Column(name = "text", length = 500)
    private String text;

    @Column(name = "created_at")
    private Instant createdAt = Instant.now();

    public KnowledgeGraphRelationEntity() {}

    public KnowledgeGraphRelationEntity(
            String id,
            String groupId,
            String sourceEntityId,
            String targetEntityId,
            String label,
            String text) {
        this.id = id;
        this.groupId = groupId;
        this.sourceEntityId = sourceEntityId;
        this.targetEntityId = targetEntityId;
        this.label = label;
        this.text = text;
        this.createdAt = Instant.now();
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getGroupId() {
        return groupId;
    }

    public void setGroupId(String groupId) {
        this.groupId = groupId;
    }

    public String getSourceEntityId() {
        return sourceEntityId;
    }

    public void setSourceEntityId(String sourceEntityId) {
        this.sourceEntityId = sourceEntityId;
    }

    public String getTargetEntityId() {
        return targetEntityId;
    }

    public void setTargetEntityId(String targetEntityId) {
        this.targetEntityId = targetEntityId;
    }

    public String getLabel() {
        return label;
    }

    public void setLabel(String label) {
        this.label = label;
    }

    public String getText() {
        return text;
    }

    public void setText(String text) {
        this.text = text;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
