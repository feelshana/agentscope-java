package io.agentscope.dataagent.web.persistence.jpa;

import java.time.Instant;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

public interface BiLoginTicketRepository extends JpaRepository<BiLoginTicketEntity, String> {
    @Transactional
    @Modifying
    @Query("delete from BiLoginTicketEntity t where t.expiresAt < :now")
    int deleteExpired(Instant now);
}
