/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.dataagent.web.auth;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import javax.crypto.SecretKey;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * HS256 JWT service for the DataAgent web application.
 *
 * <p>Tokens contain the following claims:
 * <ul>
 *   <li>{@code sub} — {@code userId} (stable identity key for HarnessAgent namespace)
 *   <li>{@code username} — display name
 *   <li>{@code roles} — list of role strings
 * </ul>
 *
 * <p>The signing secret is read from {@code dataagent.jwt.secret}. Must be configured
 * in production — the application will fail to start if using the default dev secret.
 */
@Service
public class JwtService {

    private static final String DEV_DEFAULT_SECRET =
            "dataagent-default-dev-secret-change-in-production-32chars";

    private final SecretKey signingKey;
    private static final Duration SESSION_LIMIT = Duration.ofHours(24);
    private static final String SESSION_STARTED_AT = "sessionStartedAt";

    private final Duration idleTimeout;
    private final Clock clock;

    @org.springframework.beans.factory.annotation.Autowired
    public JwtService(
            @Value("${dataagent.jwt.secret}") String secret,
            @Value("${dataagent.jwt.ttl-seconds:1800}") long ttlSeconds) {
        this(secret, ttlSeconds, Clock.systemUTC());
    }

    JwtService(String secret, long ttlSeconds, Clock clock) {
        if (ttlSeconds <= 0 || ttlSeconds > 604800)
            throw new IllegalArgumentException("JWT有效期需为1到604800秒");
        this.idleTimeout = Duration.ofSeconds(ttlSeconds);
        this.clock = clock;
        if (secret == null || secret.isBlank() || DEV_DEFAULT_SECRET.equals(secret)) {
            throw new IllegalStateException(
                    "JWT secret 未配置，请在 application.yml 中设置 dataagent.jwt.secret（>=32字符）");
        }
        byte[] keyBytes = secret.getBytes(StandardCharsets.UTF_8);
        // JJWT requires >= 32 bytes for HS256
        if (keyBytes.length < 32) {
            throw new IllegalStateException("JWT secret 至少需要 32 个 UTF-8 字节，不能使用短密钥");
        }
        this.signingKey = Keys.hmacShaKeyFor(keyBytes);
    }

    /**
     * Generates a signed JWT for the given user.
     */
    public String generate(String userId, String username, List<String> roles) {
        Instant now = clock.instant().truncatedTo(ChronoUnit.SECONDS);
        return issue(userId, username, roles, now, now);
    }

    /** Extends an authenticated session while keeping its original sign-in time. */
    String renew(Claims claims, String username, List<String> roles) {
        Instant now = clock.instant();
        if (!now.isBefore(claims.getExpiration().toInstant())) {
            throw new JwtException("Session has expired");
        }
        Long startedAt = claims.get(SESSION_STARTED_AT, Long.class);
        Instant sessionStart =
                startedAt == null
                        ? claims.getIssuedAt().toInstant()
                        : Instant.ofEpochSecond(startedAt);
        return issue(
                claims.getSubject(),
                username,
                roles,
                now.truncatedTo(ChronoUnit.SECONDS),
                sessionStart);
    }

    private String issue(
            String userId, String username, List<String> roles, Instant now, Instant sessionStart) {
        Instant deadline = sessionStart.plus(SESSION_LIMIT);
        if (!now.isBefore(deadline)) throw new JwtException("Session limit reached");
        Instant idleExpiry = now.plus(idleTimeout);
        return Jwts.builder()
                .id(UUID.randomUUID().toString())
                .subject(userId)
                .claim("username", username)
                .claim("roles", roles)
                .claim(SESSION_STARTED_AT, sessionStart.getEpochSecond())
                .issuedAt(Date.from(now))
                .expiration(Date.from(idleExpiry.isBefore(deadline) ? idleExpiry : deadline))
                .signWith(signingKey)
                .compact();
    }

    /**
     * Parses and validates a JWT, returning its claims.
     *
     * @throws JwtException if the token is invalid or expired
     */
    public Claims parse(String token) {
        return Jwts.parser()
                .clock(() -> Date.from(clock.instant()))
                .verifyWith(signingKey)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    /**
     * Extracts the {@code userId} (subject) from a token without full validation (caller must
     * call {@link #parse} for secure extraction).
     */
    public String extractUserId(Claims claims) {
        return claims.getSubject();
    }

    @SuppressWarnings("unchecked")
    public List<String> extractRoles(Claims claims) {
        Object rolesObj = claims.get("roles");
        if (rolesObj instanceof List<?> list) {
            return list.stream().map(Object::toString).toList();
        }
        return List.of();
    }
}
