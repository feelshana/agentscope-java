package io.agentscope.dataagent.web.persistence.jpa;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.agentscope.dataagent.web.auth.AuthSettings;
import io.agentscope.dataagent.web.auth.BiLoginService;
import io.agentscope.dataagent.web.auth.BiTicketCodec;
import io.agentscope.dataagent.web.auth.UserStore;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@DataJpaTest
@TestPropertySource(
        properties = {
            "spring.sql.init.mode=never",
            "dataagent.auth.bi.enabled=true",
            "dataagent.auth.bi.shared-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
            "spring.jpa.show-sql=false"
        })
@Import({
    AuthSettings.class,
    BiTicketCodec.class,
    BiLoginService.class,
    BiLoginPersistenceTest.Config.class
})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class BiLoginPersistenceTest {
    @TestConfiguration
    static class Config {
        @Bean
        UserStore users(UserEntityRepository repository) {
            return new JpaUserStore(repository, false);
        }
    }

    @Autowired BiLoginService service;
    @Autowired UserStore users;
    @Autowired BiLoginTicketRepository tickets;

    static String ticket(String username) throws Exception {
        byte[] iv = new byte[12];
        new java.security.SecureRandom().nextBytes(iv);
        var cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(
                Cipher.ENCRYPT_MODE,
                new SecretKeySpec(new byte[32], "AES"),
                new GCMParameterSpec(128, iv));
        String json =
                "{\"username\":\""
                        + username
                        + "\",\"issuedAt\":"
                        + System.currentTimeMillis()
                        + ",\"requestId\":\""
                        + UUID.randomUUID()
                        + "\"}";
        var encoder = Base64.getUrlEncoder().withoutPadding();
        return "v1."
                + encoder.encodeToString(iv)
                + "."
                + encoder.encodeToString(cipher.doFinal(json.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void preservesRolesAndDurablyRejectsReplayAcrossServiceInstances() throws Exception {
        String username = "admin-" + UUID.randomUUID();
        var old =
                users.createUser(
                        UUID.randomUUID().toString(),
                        username,
                        "irrelevant",
                        List.of("user", "admin"));
        String ticket = ticket(username);
        var logged = service.login(ticket);
        assertThat(logged.userId()).isEqualTo(old.userId());
        assertThat(logged.roles()).contains("admin");
        var fresh =
                new BiLoginService(
                        new BiTicketCodec(
                                new AuthSettings(
                                        new org.springframework.mock.env.MockEnvironment()
                                                .withProperty("dataagent.auth.bi.enabled", "true")
                                                .withProperty(
                                                        "dataagent.auth.bi.shared-key",
                                                        "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="))),
                        tickets,
                        users);
        assertThatThrownBy(() -> fresh.login(ticket)).hasMessageContaining("已使用");
    }

    @Test
    void concurrentFirstLoginsShareOneOrdinaryUser() throws Exception {
        String username = "new-" + UUID.randomUUID();
        var pool = Executors.newFixedThreadPool(4);
        try {
            var requests = new java.util.ArrayList<Callable<UserStore.UserRecord>>();
            for (int i = 0; i < 4; i++) {
                String ticket = ticket(username);
                requests.add(() -> service.login(ticket));
            }
            var ids = new java.util.HashSet<String>();
            for (var result : pool.invokeAll(requests)) {
                var user = result.get();
                ids.add(user.userId());
                assertThat(user.roles()).containsExactly("user");
            }
            assertThat(ids).hasSize(1);
            assertThat(users.listAll().stream().filter(u -> u.username().equals(username)).count())
                    .isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }
}
