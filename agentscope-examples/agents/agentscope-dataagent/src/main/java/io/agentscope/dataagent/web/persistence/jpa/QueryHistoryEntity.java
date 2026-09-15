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
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * 查询历史持久化实体。存储成功的 NL→JSON 查询对，用于：
 * <ul>
 *   <li>Seed Queries（source="seed"）— 从语义模型自动生成的初始示例</li>
 *   <li>User Queries（source="user"）— 用户问数成功后存储的实际查询</li>
 * </ul>
 * 下次问数时召回作为 few-shot 示例，提升 LLM 的查询准确率。
 */
@Entity
@Table(
        name = "dataagent_query_history",
        indexes = {
            @Index(name = "ix_qhistory_group", columnList = "group_id"),
            @Index(name = "ix_qhistory_source", columnList = "source")
        })
public class QueryHistoryEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "group_id", length = 64, nullable = false)
    private String groupId;

    /** 自然语言问题。 */
    @Lob
    @Column(name = "nl_query", nullable = false)
    private String nlQuery;

    /** 结构化查询 JSON（cube_query 或 sql_query 格式）。 */
    @Lob
    @Column(name = "query_json", nullable = false)
    private String queryJson;

    /** 生成的可执行 SQL。 */
    @Lob
    @Column(name = "sql_generated")
    private String sqlGenerated;

    /** 来源：seed（自动生成）| user（用户问数）。 */
    @Column(name = "source", length = 32)
    private String source;

    @Column(name = "created_at")
    private Instant createdAt = Instant.now();

    public QueryHistoryEntity() {}

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getGroupId() {
        return groupId;
    }

    public void setGroupId(String groupId) {
        this.groupId = groupId;
    }

    public String getNlQuery() {
        return nlQuery;
    }

    public void setNlQuery(String nlQuery) {
        this.nlQuery = nlQuery;
    }

    public String getQueryJson() {
        return queryJson;
    }

    public void setQueryJson(String queryJson) {
        this.queryJson = queryJson;
    }

    public String getSqlGenerated() {
        return sqlGenerated;
    }

    public void setSqlGenerated(String sqlGenerated) {
        this.sqlGenerated = sqlGenerated;
    }

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
