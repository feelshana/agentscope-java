package io.agentscope.dataagent.web.persistence.jpa;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.time.Instant;
import org.springframework.data.domain.Persistable;

/** Insert-only consumed tickets survive service restarts; no credentials are stored. */
@Entity
@Table(
        name = "dataagent_bi_login_ticket",
        indexes = @Index(name = "ix_bi_ticket_expiry", columnList = "expires_at"))
public class BiLoginTicketEntity implements Persistable<String> {
    @Id
    @Column(name = "request_id", length = 36, nullable = false)
    private String requestId;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    protected BiLoginTicketEntity() {}

    public BiLoginTicketEntity(String requestId, Instant expiresAt) {
        this.requestId = requestId;
        this.expiresAt = expiresAt;
    }

    @Override
    public String getId() {
        return requestId;
    }

    @Override
    @jakarta.persistence.Transient
    public boolean isNew() {
        return true;
    }
}
