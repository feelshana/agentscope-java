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
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * 语义模型持久化实体。存储用户上传或系统自动生成的 ontology.json 语义模型。
 *
 * <p>{@code mdl_json} 存完整 SemanticModel 的 JSON 序列化，避免复杂关系表；
 * {@code mdl_hash} 用于缓存失效检测（SHA256 前16位）。
 */
@Entity
@Table(
        name = "dataagent_semantic_model",
        indexes = {
            @Index(name = "ix_semantic_owner", columnList = "owner_id"),
            @Index(name = "ix_semantic_group", columnList = "group_id"),
            @Index(name = "ix_semantic_origin", columnList = "origin")
        })
public class SemanticModelEntity {

    @Id
    @Column(name = "semantic_model_id", length = 64, nullable = false)
    private String id;

    @Column(name = "owner_id", length = 128, nullable = false)
    private String ownerId;

    @Column(name = "group_id", length = 64)
    private String groupId;

    @Column(name = "name", length = 255)
    private String name;

    @Lob
    @Column(name = "description")
    private String description;

    /** 完整 SemanticModel JSON 序列化。 */
    @Lob
    @Column(name = "mdl_json", nullable = false)
    private String mdlJson;

    /** MDL 内容的 SHA256 前16位，用于缓存失效检测。 */
    @Column(name = "mdl_hash", length = 32)
    private String mdlHash;

    /** "uploaded"（用户上传）或 "auto-generated"（自动生成）。 */
    @Column(name = "origin", length = 32, nullable = false)
    private String origin;

    /** 业务规则和数据限制（Markdown 文本，来自 instructions.md）。 */
    @Lob
    @Column(name = "instructions_text")
    private String instructionsText;

    @Column(name = "created_at")
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at")
    private Instant updatedAt = Instant.now();

    public SemanticModelEntity() {}

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

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getMdlJson() {
        return mdlJson;
    }

    public void setMdlJson(String mdlJson) {
        this.mdlJson = mdlJson;
    }

    public String getMdlHash() {
        return mdlHash;
    }

    public void setMdlHash(String mdlHash) {
        this.mdlHash = mdlHash;
    }

    public String getOrigin() {
        return origin;
    }

    public void setOrigin(String origin) {
        this.origin = origin;
    }

    public String getInstructionsText() {
        return instructionsText;
    }

    public void setInstructionsText(String instructionsText) {
        this.instructionsText = instructionsText;
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
