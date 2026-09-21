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
 * One per knowledge-base group: the uploaded document (Word/Markdown text) describing how the
 * datasets inside that group relate to each other. Surfaced to the agent via {@code
 * list_data_sources} (aggregated per owner) so cross-dataset questions can be answered without
 * manual dataset selection.
 */
@Entity
@Table(name = "dataagent_group_knowledge")
public class DatasetKnowledgeEntity {

    @Id
    @Column(name = "group_id", length = 64, nullable = false)
    private String groupId;

    @Lob
    @Column(name = "content", columnDefinition = "TEXT")
    private String content;

    @Column(name = "updated_at")
    private Instant updatedAt = Instant.now();

    public DatasetKnowledgeEntity() {}

    public DatasetKnowledgeEntity(String groupId, String content) {
        this.groupId = groupId;
        this.content = content;
        this.updatedAt = Instant.now();
    }

    public String getGroupId() {
        return groupId;
    }

    public void setGroupId(String groupId) {
        this.groupId = groupId;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}
