package io.agentscope.dataagent.web.persistence.jpa;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** Explicit source membership; it does not govern existing knowledge-base bindings. */
@Entity
@Table(
        name = "dataagent_datasource_grant",
        indexes = {
            @Index(
                    name = "ix_ds_grant_source_user",
                    columnList = "datasource_id,user_id",
                    unique = true),
            @Index(name = "ix_ds_grant_user", columnList = "user_id")
        })
public class ExternalDataSourceGrantEntity {
    @Id
    @Column(length = 64)
    private String id = UUID.randomUUID().toString();

    @Column(name = "datasource_id", length = 64, nullable = false)
    private String datasourceId;

    @Column(name = "user_id", length = 128, nullable = false)
    private String userId;

    @Column(length = 16, nullable = false)
    private String role;

    @Column(name = "granted_by", length = 128, nullable = false)
    private String grantedBy;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    public ExternalDataSourceGrantEntity() {}

    public ExternalDataSourceGrantEntity(String source, String user) {
        datasourceId = source;
        userId = user;
    }

    public String getDatasourceId() {
        return datasourceId;
    }

    public String getUserId() {
        return userId;
    }

    public String getRole() {
        return role;
    }

    public String getGrantedBy() {
        return grantedBy;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void grant(String value, String actor) {
        role = value;
        grantedBy = actor;
        updatedAt = Instant.now();
    }
}
