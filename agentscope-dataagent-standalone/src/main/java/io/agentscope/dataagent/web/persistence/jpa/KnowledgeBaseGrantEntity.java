package io.agentscope.dataagent.web.persistence.jpa;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** Explicit membership; ownership and global administration are implicit. */
@Entity
@Table(
        name = "dataagent_knowledge_base_grant",
        indexes = {
            @Index(name = "ix_kb_grant_group_user", columnList = "group_id,user_id", unique = true),
            @Index(name = "ix_kb_grant_user", columnList = "user_id")
        })
public class KnowledgeBaseGrantEntity {
    @Id
    @Column(length = 64)
    private String id = UUID.randomUUID().toString();

    @Column(name = "group_id", length = 64, nullable = false)
    private String groupId;

    @Column(name = "user_id", length = 128, nullable = false)
    private String userId;

    @Column(length = 16, nullable = false)
    private String role;

    @Column(name = "granted_by", length = 128, nullable = false)
    private String grantedBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    public KnowledgeBaseGrantEntity() {}

    public KnowledgeBaseGrantEntity(String groupId, String userId) {
        this.groupId = groupId;
        this.userId = userId;
    }

    public String getGroupId() {
        return groupId;
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

    public void grant(String role, String actor) {
        this.role = role;
        grantedBy = actor;
        updatedAt = Instant.now();
    }
}
