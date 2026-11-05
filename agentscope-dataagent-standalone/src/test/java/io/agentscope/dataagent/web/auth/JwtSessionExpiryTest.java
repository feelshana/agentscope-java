package io.agentscope.dataagent.web.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.List;
import org.junit.jupiter.api.Test;

class JwtSessionExpiryTest {
    private static final String TEST_KEY = "jwt-expiry-fixture-key-32-bytes-long";
    private static final Instant SIGN_IN = Instant.parse("2026-11-05T06:00:00Z");

    @Test
    void useDoesNotMoveTheSignedDeadlineAndTheTokenExpiresAt24Hours() {
        var token = serviceAt(0).generate("user", "User", List.of("user"));
        var claims = serviceAt(86_399).parse(token);
        assertThat(claims.getIssuedAt().toInstant()).isEqualTo(SIGN_IN);
        assertThat(claims.getExpiration().toInstant()).isEqualTo(SIGN_IN.plusSeconds(86_400));
        assertThatThrownBy(() -> serviceAt(86_400).parse(token)).isInstanceOf(JwtException.class);
    }

    @Test
    void previouslyIssuedTokensKeepTheirSignedDeadline() {
        var signingKey = Keys.hmacShaKeyFor(TEST_KEY.getBytes(StandardCharsets.UTF_8));
        var legacy =
                Jwts.builder()
                        .subject("user")
                        .claim("sessionStartedAt", SIGN_IN.getEpochSecond())
                        .issuedAt(Date.from(SIGN_IN))
                        .expiration(Date.from(SIGN_IN.plusSeconds(1_800)))
                        .signWith(signingKey)
                        .compact();
        assertThat(serviceAt(1_799).parse(legacy).getExpiration().toInstant())
                .isEqualTo(SIGN_IN.plusSeconds(1_800));
        assertThatThrownBy(() -> serviceAt(1_800).parse(legacy)).isInstanceOf(JwtException.class);
    }

    private JwtService serviceAt(long seconds) {
        return new JwtService(TEST_KEY, Clock.fixed(SIGN_IN.plusSeconds(seconds), ZoneOffset.UTC));
    }
}
