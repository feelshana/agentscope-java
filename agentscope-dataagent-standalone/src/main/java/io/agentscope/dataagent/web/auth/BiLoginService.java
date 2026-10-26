package io.agentscope.dataagent.web.auth;

import io.agentscope.dataagent.web.persistence.jpa.BiLoginTicketEntity;
import io.agentscope.dataagent.web.persistence.jpa.BiLoginTicketRepository;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/** Uses the existing user store and durable unique inserts, without logging credentials. */
@Service
public class BiLoginService {
    private final BiTicketCodec codec;
    private final BiLoginTicketRepository tickets;
    private final UserStore users;

    public BiLoginService(BiTicketCodec codec, BiLoginTicketRepository tickets, UserStore users) {
        this.codec = codec;
        this.tickets = tickets;
        this.users = users;
    }

    public UserStore.UserRecord login(String envelope) {
        var ticket = codec.decode(envelope);
        // The unique username constraint arbitrates competing first-login transactions.
        var user =
                users.findByUsername(ticket.username())
                        .orElseGet(() -> createUser(ticket.username()));
        try {
            tickets.saveAndFlush(
                    new BiLoginTicketEntity(
                            ticket.requestId(), Instant.ofEpochMilli(ticket.issuedAt() + 120_000)));
        } catch (DataIntegrityViolationException exception) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "登录请求已使用，请重新从分析云进入");
        }
        return user;
    }

    private UserStore.UserRecord createUser(String username) {
        try {
            return users.createUser(
                    UUID.randomUUID().toString(),
                    username,
                    UUID.randomUUID().toString() + UUID.randomUUID(),
                    List.of("user"));
        } catch (DataIntegrityViolationException | IllegalArgumentException exception) {
            return users.findByUsername(username).orElseThrow(() -> exception);
        }
    }

    @Scheduled(fixedDelay = 300_000)
    public void cleanExpiredTickets() {
        tickets.deleteExpired(Instant.now());
    }
}
