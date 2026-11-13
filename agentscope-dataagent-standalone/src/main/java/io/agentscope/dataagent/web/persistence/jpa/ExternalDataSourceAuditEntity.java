package io.agentscope.dataagent.web.persistence.jpa;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** Source lifecycle and grant audit; credentials and connection strings are never included. */
@Entity
@Table(
        name = "dataagent_datasource_audit",
        indexes = @Index(name = "ix_ds_audit_source", columnList = "datasource_id,created_at"))
public class ExternalDataSourceAuditEntity {
    @Id
    @Column(length = 64)
    private String id = UUID.randomUUID().toString();

    @Column(name = "datasource_id", length = 64, nullable = false)
    private String datasourceId;

    @Column(name = "actor_id", length = 128, nullable = false)
    private String actorId;

    @Column(name = "subject_id", length = 128)
    private String subjectId;

    @Column(length = 32, nullable = false)
    private String action;

    @Column(name = "previous_role", length = 16)
    private String previousRole;

    @Column(name = "new_role", length = 16)
    private String newRole;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    public ExternalDataSourceAuditEntity() {}

    public ExternalDataSourceAuditEntity(
            String source,
            String actor,
            String subject,
            String action,
            String previous,
            String next) {
        datasourceId = source;
        actorId = actor;
        subjectId = subject;
        this.action = action;
        previousRole = previous;
        newRole = next;
    }
}
