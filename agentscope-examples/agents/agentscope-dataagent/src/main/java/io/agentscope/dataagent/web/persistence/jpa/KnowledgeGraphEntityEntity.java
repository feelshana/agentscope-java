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
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * A semantic entity node of a knowledge base's LLM-extracted graph (TC "知识图谱" analogue): a
 * table, column, column-type, sample value or business term. {@code label} is the entity type used
 * for colour-coding; {@code normalizedKey} (lowercased display text) deduplicates entities that
 * several source documents mention.
 */
@Entity
@Table(name = "dataagent_kg_entity")
public class KnowledgeGraphEntityEntity {

    @Id
    @Column(name = "entity_id", length = 64, nullable = false)
    private String id;

    @Column(name = "group_id", length = 64, nullable = false)
    private String groupId;

    @Column(name = "owner_id", length = 64, nullable = false)
    private String ownerId;

    @Column(name = "normalized_key", length = 256, nullable = false)
    private String normalizedKey;

    /** Entity type: 表 | 字段 | 字段类型 | 取值 | 业务词 */
    @Column(name = "label", length = 64, nullable = false)
    private String label;

    /** Display name. */
    @Column(name = "text", length = 256, nullable = false)
    private String text;

    @Lob
    @Column(name = "attributes_json", columnDefinition = "TEXT")
    private String attributesJson;

    @Column(name = "created_at")
    private Instant createdAt = Instant.now();

    public KnowledgeGraphEntityEntity() {}

    public KnowledgeGraphEntityEntity(
            String id,
            String groupId,
            String ownerId,
            String normalizedKey,
            String label,
            String text,
            String attributesJson) {
        this.id = id;
        this.groupId = groupId;
        this.ownerId = ownerId;
        this.normalizedKey = normalizedKey;
        this.label = label;
        this.text = text;
        this.attributesJson = attributesJson;
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

    public String getOwnerId() {
        return ownerId;
    }

    public void setOwnerId(String ownerId) {
        this.ownerId = ownerId;
    }

    public String getNormalizedKey() {
        return normalizedKey;
    }

    public void setNormalizedKey(String normalizedKey) {
        this.normalizedKey = normalizedKey;
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

    public String getAttributesJson() {
        return attributesJson;
    }

    public void setAttributesJson(String attributesJson) {
        this.attributesJson = attributesJson;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
