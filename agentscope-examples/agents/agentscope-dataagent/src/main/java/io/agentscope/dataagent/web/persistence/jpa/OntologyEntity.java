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
 * 本体模型持久化实体。存储用户上传或系统自动生成的 model.yaml 本体文件。
 *
 * <p>{@code parsed_json} 存完整 OntologyModel 的 JSON 序列化，避免复杂关系表；
 * {@code raw_yaml} 保留原始 YAML 文本供编辑和导出。
 */
@Entity
@Table(
        name = "dataagent_ontology",
        indexes = {
            @Index(name = "ix_ontology_owner", columnList = "owner_id"),
            @Index(name = "ix_ontology_group", columnList = "group_id")
        })
public class OntologyEntity {

    @Id
    @Column(name = "ontology_id", length = 64, nullable = false)
    private String id;

    @Column(name = "owner_id", length = 128, nullable = false)
    private String ownerId;

    @Column(name = "group_id", length = 64)
    private String groupId;

    @Column(name = "name", length = 191)
    private String name;

    @Column(name = "version", length = 64)
    private String version;

    @Lob
    @Column(name = "raw_yaml")
    private String rawYaml;

    @Lob
    @Column(name = "parsed_json")
    private String parsedJson;

    /** "uploaded"（用户上传）或 "auto-generated"（自动生成）。 */
    @Column(name = "origin", length = 20)
    private String origin;

    @Column(name = "created_at")
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at")
    private Instant updatedAt = Instant.now();

    public OntologyEntity() {}

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

    public String getVersion() {
        return version;
    }

    public void setVersion(String version) {
        this.version = version;
    }

    public String getRawYaml() {
        return rawYaml;
    }

    public void setRawYaml(String rawYaml) {
        this.rawYaml = rawYaml;
    }

    public String getParsedJson() {
        return parsedJson;
    }

    public void setParsedJson(String parsedJson) {
        this.parsedJson = parsedJson;
    }

    public String getOrigin() {
        return origin;
    }

    public void setOrigin(String origin) {
        this.origin = origin;
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
