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
 * User-configured external database connection (TC "数据源管理" analogue): a local MySQL or
 * PostgreSQL instance whose existing tables can be associated into knowledge bases as read-only
 * datasets without copying data. Passwords are stored in plain text for now (production should
 * encrypt at rest).
 */
@Entity
@Table(name = "dataagent_external_datasource")
public class ExternalDataSourceEntity {

    @Id
    @Column(name = "datasource_id", length = 64, nullable = false)
    private String id;

    @Column(name = "owner_id", length = 128, nullable = false)
    private String ownerId;

    @Column(name = "name", length = 64, nullable = false)
    private String name;

    /** mysql | postgresql */
    @Column(name = "kind", length = 32, nullable = false)
    private String kind = "mysql";

    @Column(name = "jdbc_url", length = 512, nullable = false)
    private String jdbcUrl;

    @Column(name = "username", length = 128)
    private String username;

    @Column(name = "password", length = 256)
    private String password;

    /** When true, low-cardinality columns get sample values merged into their description. */
    @Column(name = "sampling", nullable = false)
    private boolean sampling = true;

    @Column(name = "created_at")
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at")
    private Instant updatedAt = Instant.now();

    public ExternalDataSourceEntity() {}

    public ExternalDataSourceEntity(
            String id,
            String ownerId,
            String name,
            String kind,
            String jdbcUrl,
            String username,
            String password,
            boolean sampling) {
        this.id = id;
        this.ownerId = ownerId;
        this.name = name;
        this.kind = kind;
        this.jdbcUrl = jdbcUrl;
        this.username = username;
        this.password = password;
        this.sampling = sampling;
        this.createdAt = Instant.now();
        this.updatedAt = Instant.now();
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

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getKind() {
        return kind;
    }

    public void setKind(String kind) {
        this.kind = kind;
    }

    public String getJdbcUrl() {
        return jdbcUrl;
    }

    public void setJdbcUrl(String jdbcUrl) {
        this.jdbcUrl = jdbcUrl;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public boolean isSampling() {
        return sampling;
    }

    public void setSampling(boolean sampling) {
        this.sampling = sampling;
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
