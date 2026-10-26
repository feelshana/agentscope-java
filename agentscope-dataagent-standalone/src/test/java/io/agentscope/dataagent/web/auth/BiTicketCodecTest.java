package io.agentscope.dataagent.web.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class BiTicketCodecTest {
    static final String KEY = Base64.getEncoder().encodeToString(new byte[32]);
    static final long NOW = 1700000000000L;

    static AuthSettings settings() {
        return new AuthSettings(
                new MockEnvironment()
                        .withProperty("dataagent.auth.bi.enabled", "true")
                        .withProperty("dataagent.auth.bi.shared-key", KEY));
    }

    static String ticket(String username, long issuedAt, String requestId) throws Exception {
        return encrypt(Map.of("username", username, "issuedAt", issuedAt, "requestId", requestId));
    }

    static String encrypt(Map<String, Object> payload) throws Exception {
        byte[] iv = new byte[12];
        new java.security.SecureRandom().nextBytes(iv);
        var cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(
                Cipher.ENCRYPT_MODE,
                new SecretKeySpec(new byte[32], "AES"),
                new GCMParameterSpec(128, iv));
        var encoder = Base64.getUrlEncoder().withoutPadding();
        return "v1."
                + encoder.encodeToString(iv)
                + "."
                + encoder.encodeToString(
                        cipher.doFinal(
                                new ObjectMapper()
                                        .writeValueAsString(payload)
                                        .getBytes(StandardCharsets.UTF_8)));
    }

    private final BiTicketCodec codec =
            new BiTicketCodec(settings(), Clock.fixed(Instant.ofEpochMilli(NOW), ZoneOffset.UTC));

    @Test
    void decodesAndEnforcesTimeBounds() throws Exception {
        String id = UUID.randomUUID().toString();
        assertThat(codec.decode(ticket("liyufanwx", NOW, id)).username()).isEqualTo("liyufanwx");
        assertThat(codec.decode(ticket("liyufanwx", NOW - 60000, id)).issuedAt())
                .isEqualTo(NOW - 60000);
        assertThatThrownBy(() -> codec.decode(ticket("x", NOW - 60001, id)))
                .hasMessageContaining("过期");
        assertThatThrownBy(() -> codec.decode(ticket("x", NOW + 30001, id)))
                .hasMessageContaining("时间");
    }

    @Test
    void rejectsMalformedTamperedAndInjectedClaims() throws Exception {
        String ticket = ticket("x", NOW, UUID.randomUUID().toString());
        String[] parts = ticket.split("\\.");
        byte[] data = Base64.getUrlDecoder().decode(parts[2]);
        data[0] ^= 1;
        assertThatThrownBy(
                        () ->
                                codec.decode(
                                        parts[0]
                                                + "."
                                                + parts[1]
                                                + "."
                                                + Base64.getUrlEncoder()
                                                        .withoutPadding()
                                                        .encodeToString(data)))
                .hasMessageContaining("验证失败");
        for (String invalid : new String[] {"", "v2.a.b", "v1.a.b", "a".repeat(4097)})
            assertThatThrownBy(() -> codec.decode(invalid)).hasMessageContaining("验证失败");
        assertThatThrownBy(() -> codec.decode(ticket(" ", NOW, UUID.randomUUID().toString())))
                .hasMessageContaining("验证失败");
        assertThatThrownBy(
                        () ->
                                codec.decode(
                                        encrypt(
                                                Map.of(
                                                        "username",
                                                        "x",
                                                        "issuedAt",
                                                        NOW,
                                                        "requestId",
                                                        UUID.randomUUID().toString(),
                                                        "roles",
                                                        "admin"))))
                .hasMessageContaining("验证失败");
    }

    @Test
    void browserModuleVectorIsInteroperable() throws Exception {
        try (var stream = getClass().getResourceAsStream("/bi-login-browser-vector.json")) {
            var vector = new ObjectMapper().readTree(stream);
            assertThat(codec.decode(vector.path("ticket").asText()).username())
                    .isEqualTo("browser-vector");
        }
    }

    @Test
    void productionPolicyAndJwtExpiry() {
        var environment =
                new MockEnvironment().withProperty("dataagent.auth.password-login-enabled", "true");
        environment.setActiveProfiles("prod");
        var prod = new AuthSettings(environment);
        assertThat(prod.passwordLogin()).isFalse();
        assertThat(prod.biLogin()).isTrue();
        assertThatThrownBy(() -> new BiTicketCodec(prod)).hasMessageContaining("32字节");
        var dev = new AuthSettings(new MockEnvironment());
        assertThat(dev.passwordLogin()).isTrue();
        assertThat(dev.biLogin()).isFalse();
        var jwt = new JwtService("test-signing-key-with-at-least-32-bytes", 1800);
        var claims = jwt.parse(jwt.generate("stable-id", "x", java.util.List.of("user")));
        assertThat(claims.getExpiration().getTime() - claims.getIssuedAt().getTime())
                .isEqualTo(1800000);
        assertThat(claims.getSubject()).isEqualTo("stable-id");
    }

    @Test
    void developmentPasswordLoginRemainsAvailable() {
        var users = org.mockito.Mockito.mock(UserStore.class);
        var user =
                new UserStore.UserRecord("dev-user", "dev-user", "hash", java.util.List.of("user"));
        org.mockito.Mockito.when(users.findByUsername("dev-user"))
                .thenReturn(java.util.Optional.of(user));
        org.mockito.Mockito.when(users.verifyPassword(user, "password")).thenReturn(true);
        var controller =
                new AuthController(
                        users,
                        new JwtService("test-signing-key-with-at-least-32-bytes", 1800),
                        java.util.Optional.empty(),
                        new AuthSettings(new MockEnvironment()),
                        org.mockito.Mockito.mock(BiLoginService.class));
        var response =
                controller.login(new AuthController.LoginRequest("dev-user", "password")).block();
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody().userId()).isEqualTo("dev-user");
    }
}
