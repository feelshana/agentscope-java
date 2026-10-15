package io.agentscope.dataagent.web.persistence.jpa;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** Membership changes are retained even after a grant or KB is removed. */
@Entity
@Table(
        name = "dataagent_knowledge_base_grant_audit",
        indexes = {@Index(name = "ix_kb_grant_audit_group", columnList = "group_id,created_at")})
public class KnowledgeBaseGrantAuditEntity {
    @Id
    @Column(length = 64)
    private String id = UUID.randomUUID().toString();

    @Column(name = "group_id", length = 64, nullable = false)
    private String groupId;

    @Column(name = "user_id", length = 128, nullable = false)
    private String userId;

    @Column(name = "actor_id", length = 128, nullable = false)
    private String actorId;

    @Column(name = "previous_role", length = 16)
    private String previousRole;

    @Column(name = "new_role", length = 16)
    private String newRole;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    public KnowledgeBaseGrantAuditEntity() {}

    public KnowledgeBaseGrantAuditEntity(
            String groupId, String userId, String actorId, String previousRole, String newRole) {
        this.groupId = groupId;
        this.userId = userId;
        this.actorId = actorId;
        this.previousRole = previousRole;
        this.newRole = newRole;
    }
}
