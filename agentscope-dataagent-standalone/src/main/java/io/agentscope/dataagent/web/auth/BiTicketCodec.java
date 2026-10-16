package io.agentscope.dataagent.web.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Base64;
import java.util.UUID;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/** Decodes the versioned BI frontend envelope, not a proof of BI session identity. */
@Component
public class BiTicketCodec {
    private final ObjectMapper mapper = new ObjectMapper();
    private final SecretKeySpec key;
    private final Clock clock;

    @org.springframework.beans.factory.annotation.Autowired
    public BiTicketCodec(AuthSettings settings) {
        this(settings, Clock.systemUTC());
    }

    BiTicketCodec(AuthSettings settings, Clock clock) {
        this.clock = clock;
        if (!settings.biLogin()) {
            key = null;
            return;
        }
        try {
            byte[] bytes = Base64.getDecoder().decode(settings.sharedKey());
            if (bytes.length != 32) throw new IllegalArgumentException();
            key = new SecretKeySpec(bytes, "AES");
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("BI免登需要配置32字节Base64密钥 dataagent.auth.bi.shared-key");
        }
    }

    public record Ticket(String username, long issuedAt, String requestId) {}

    public Ticket decode(String envelope) {
        if (key == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        try {
            if (envelope == null || envelope.length() > 4096) throw new IllegalArgumentException();
            String[] parts = envelope.split("\\.", -1);
            if (parts.length != 3 || !parts[0].equals("v1")) throw new IllegalArgumentException();
            if (!parts[1].matches("[A-Za-z0-9_-]{16}") || !parts[2].matches("[A-Za-z0-9_-]+"))
                throw new IllegalArgumentException();
            byte[] iv = Base64.getUrlDecoder().decode(parts[1]);
            byte[] encrypted = Base64.getUrlDecoder().decode(parts[2]);
            if (encrypted.length < 16) throw new IllegalArgumentException();
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, iv));
            var node =
                    mapper.readTree(new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8));
            if (!node.isObject()
                    || node.size() != 3
                    || !node.path("username").isTextual()
                    || !node.path("issuedAt").isIntegralNumber()
                    || !node.path("issuedAt").canConvertToLong()
                    || !node.path("requestId").isTextual()) throw new IllegalArgumentException();
            String username = node.path("username").asText().strip();
            String requestId = node.path("requestId").asText();
            if (username.isEmpty()
                    || username.length() > 191
                    || username.codePoints().anyMatch(Character::isISOControl)
                    || !UUID.fromString(requestId).toString().equals(requestId))
                throw new IllegalArgumentException();
            long issuedAt = node.path("issuedAt").longValue();
            long now = clock.millis();
            if (issuedAt < now - 60_000 || issuedAt > now + 30_000)
                throw new ResponseStatusException(
                        HttpStatus.UNAUTHORIZED, "登录请求已过期或时间不正确，请重新从分析云进入");
            return new Ticket(username, issuedAt, requestId);
        } catch (ResponseStatusException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "免登验证失败，请重新从分析云进入");
        }
    }
}
