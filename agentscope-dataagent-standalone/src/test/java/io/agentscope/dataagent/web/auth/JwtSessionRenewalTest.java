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

class JwtSessionRenewalTest {
    private static final String TEST_KEY = "jwt-renewal-fixture-key-32-bytes-long";
    private static final Instant SIGN_IN = Instant.parse("2026-11-04T06:00:00Z");

    @Test
    void activeSessionSlidesButCannotCrossItsOriginalDay() {
        var original = serviceAt(0).generate("user", "User", List.of("user"));
        var previous = original;
        for (int minute = 25; minute <= 1_425; minute += 25) {
            var service = serviceAt(minute);
            previous = service.renew(service.parse(previous), "User", List.of("user"));
            var renewed = service.parse(previous);
            assertThat(renewed.getIssuedAt().toInstant())
                    .isEqualTo(SIGN_IN.plusSeconds(minute * 60L));
            assertThat(renewed.getExpiration().toInstant())
                    .isEqualTo(SIGN_IN.plusSeconds(Math.min(minute + 30, 1_440) * 60L));
        }
        String capped = previous;
        assertThatThrownBy(() -> serviceAt(1_441).parse(capped)).isInstanceOf(JwtException.class);
    }

    @Test
    void inactivityCannotBeRenewedAndExistingTokensKeepTheirSignInTime() {
        var signingKey = Keys.hmacShaKeyFor(TEST_KEY.getBytes(StandardCharsets.UTF_8));
        var legacy =
                Jwts.builder()
                        .subject("user")
                        .issuedAt(Date.from(SIGN_IN))
                        .expiration(Date.from(SIGN_IN.plusSeconds(1_800)))
                        .signWith(signingKey)
                        .compact();
        var service = serviceAt(29);
        var claims = service.parse(legacy);
        var renewed = service.parse(service.renew(claims, "User", List.of("user")));
        assertThat(renewed.get("sessionStartedAt", Long.class)).isEqualTo(SIGN_IN.getEpochSecond());
        assertThatThrownBy(() -> serviceAt(30).renew(claims, "User", List.of("user")))
                .isInstanceOf(JwtException.class);
        assertThatThrownBy(() -> serviceAt(31).parse(legacy)).isInstanceOf(JwtException.class);
    }

    private JwtService serviceAt(int minutes) {
        return new JwtService(
                TEST_KEY, 1_800, Clock.fixed(SIGN_IN.plusSeconds(minutes * 60L), ZoneOffset.UTC));
    }
}
