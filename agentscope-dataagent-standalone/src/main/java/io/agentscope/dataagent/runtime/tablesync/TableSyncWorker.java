package io.agentscope.dataagent.runtime.tablesync;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Base64;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Restricted subprocess protocol for independently executing table-copy jobs. */
@Component
public class TableSyncWorker {
    private static final Logger log = LoggerFactory.getLogger(TableSyncWorker.class);
    private static final int MAX_OUTPUT = 4 * 1024 * 1024;
    private static final String ENV_PREFIX = "DATAAGENT_TABLE_SYNC_";
    private static final byte[] AAD = "dataagent:table-sync:v1".getBytes(StandardCharsets.UTF_8);
    private static final Set<String> SYSTEM_ENV =
            Set.of(
                    "PATH",
                    "Path",
                    "SystemRoot",
                    "SYSTEMROOT",
                    "WINDIR",
                    "TEMP",
                    "TMP",
                    "LANG",
                    "LC_ALL",
                    "LD_LIBRARY_PATH");
    private static final Set<String> WORKER_ENV =
            Set.of(
                    "ALLOWED_SOURCES",
                    "SOURCE_SSL_MODE",
                    "SOURCE_SSL_CA",
                    "TARGET_HOST",
                    "TARGET_PORT",
                    "TARGET_USER",
                    "TARGET_PASSWORD",
                    "TARGET_SSL_MODE",
                    "TARGET_SSL_CA");

    public enum Action {
        LIST("list"),
        DETAIL("detail"),
        TABLES("tables"),
        COLUMNS("columns"),
        PREVIEW("preview"),
        TEST_DATE("test-date"),
        CREATE("create"),
        SCHEDULE("schedule"),
        RUN_NOW("run-now"),
        RERUN("rerun"),
        SET_TARGET("target");

        private final String command;

        Action(String command) {
            this.command = command;
        }
    }

    public static class Failure extends RuntimeException {
        public Failure(String code) {
            super(code);
        }
    }

    private final ObjectMapper mapper;
    private final Environment settings;
    private final boolean legacyEnabled;
    private final String python;
    private final String encodedKey;
    private final TableSyncTargetStore targetStore;
    private final AtomicBoolean polling = new AtomicBoolean();
    private final AtomicBoolean dispatching = new AtomicBoolean();
    private final ExecutorService dispatch =
            Executors.newFixedThreadPool(
                    2,
                    task -> {
                        Thread thread = new Thread(task, "table-sync-dispatch");
                        thread.setDaemon(true);
                        return thread;
                    });
    private Path script;

    public TableSyncWorker(
            ObjectMapper mapper,
            Environment settings,
            TableSyncTargetStore targetStore,
            @Value("${DATAAGENT_TABLE_SYNC_ENABLED:false}") boolean enabled,
            @Value("${DATAAGENT_TABLE_SYNC_PYTHON:python}") String python,
            @Value("${DATAAGENT_TABLE_SYNC_KEY:}") String encodedKey) {
        this.mapper = mapper;
        this.settings = settings;
        this.legacyEnabled = enabled;
        this.python = python;
        this.encodedKey = encodedKey;
        this.targetStore = targetStore;
    }

    public JsonNode invoke(Action action, JsonNode input, String actor) {
        if (input == null || !input.isObject()) throw new Failure("INVALID_INPUT");
        if (action == Action.SET_TARGET) return saveTarget(input, actor);
        ObjectNode target = configuredTarget();
        if (action == Action.LIST) return list(target, actor);
        if (target == null
                && action != Action.TABLES
                && action != Action.COLUMNS
                && action != Action.TEST_DATE) {
            throw new Failure("NOT_CONFIGURED");
        }
        return call(action.command, (ObjectNode) input, target, actor, Duration.ofSeconds(90));
    }

    private ObjectNode list(ObjectNode target, String actor) {
        ObjectNode result = mapper.createObjectNode();
        result.put("today", LocalDate.now(ZoneId.of("Asia/Shanghai")).toString());
        result.putArray("tasks");
        if (target == null) return result;
        result.set("target", publicTarget(target));
        try {
            result.set(
                    "tasks",
                    call("list", mapper.createObjectNode(), target, actor, Duration.ofSeconds(30))
                            .path("tasks"));
        } catch (Failure error) {
            result.put("targetError", true);
        }
        return result;
    }

    private ObjectNode configuredTarget() {
        ObjectNode saved = targetStore.read();
        if (saved != null) return saved.put("controlSchema", targetStore.platformSchema());
        if (!legacyEnabled) return null;
        String host = legacy("TARGET_HOST", "");
        String username = legacy("TARGET_USER", "");
        String password = legacy("TARGET_PASSWORD", "");
        if (host.isBlank() || username.isBlank() || password.isEmpty()) return null;
        try {
            return mapper.createObjectNode()
                    .put("host", host)
                    .put("port", Integer.parseInt(legacy("TARGET_PORT", "3306")))
                    .put("username", username)
                    .put("password", password)
                    .put("sslMode", legacy("TARGET_SSL_MODE", "VERIFY_IDENTITY"))
                    .put("controlSchema", targetStore.platformSchema());
        } catch (NumberFormatException error) {
            return null;
        }
    }

    private String legacy(String key, String fallback) {
        String name = ENV_PREFIX + key;
        String value = System.getenv(name);
        return value == null ? settings.getProperty(name, fallback) : value;
    }

    private ObjectNode publicTarget(ObjectNode target) {
        return mapper.createObjectNode()
                .put("host", target.path("host").asText())
                .put("port", target.path("port").asInt())
                .put("username", target.path("username").asText())
                .put("sslMode", target.path("sslMode").asText());
    }

    private synchronized JsonNode saveTarget(JsonNode input, String actor) {
        String host = input.path("host").asText("").strip().toLowerCase(Locale.ROOT);
        if ("localhost".equals(host)) host = "127.0.0.1";
        JsonNode port = input.path("port");
        if (!port.isIntegralNumber() || !port.canConvertToInt()) throw new Failure("INVALID_INPUT");
        ObjectNode proposed = mapper.createObjectNode().put("host", host).put("port", port.asInt());
        for (String key : new String[] {"username", "password", "sslMode"}) {
            if (!input.path(key).isTextual()) throw new Failure("INVALID_INPUT");
            proposed.set(key, input.path(key));
        }
        ObjectNode current = configuredTarget();
        if (current != null) {
            String previous = current.path("host").asText();
            if ("localhost".equalsIgnoreCase(previous)) previous = "127.0.0.1";
            if (!host.equalsIgnoreCase(previous) || port.asInt() != current.path("port").asInt()) {
                throw new Failure("TARGET_ENDPOINT_LOCKED");
            }
        }
        proposed.put("controlSchema", targetStore.platformSchema());
        call("check-target", mapper.createObjectNode(), proposed, actor, Duration.ofSeconds(30));
        targetStore.save(proposed);
        log.info("Table sync target settings saved by {}", actor);
        return publicTarget(proposed);
    }

    @Scheduled(fixedDelay = 5000)
    public void schedule() {
        if (!polling.compareAndSet(false, true)) return;
        dispatch.execute(
                () -> {
                    try {
                        ObjectNode target = configuredTarget();
                        if (target == null) return;
                        JsonNode pending =
                                call(
                                        "next-job",
                                        mapper.createObjectNode()
                                                .put("dispatch", !dispatching.get()),
                                        target,
                                        "scheduler",
                                        Duration.ofSeconds(30));
                        if (pending.hasNonNull("id") && dispatching.compareAndSet(false, true)) {
                            dispatch.execute(() -> executeJob(pending, target));
                        }
                    } catch (Failure failure) {
                        log.debug("Table sync dispatch unavailable ({})", failure.getMessage());
                    } finally {
                        polling.set(false);
                    }
                });
    }

    private void executeJob(JsonNode pending, ObjectNode target) {
        try {
            ObjectNode run = mapper.createObjectNode().put("id", pending.path("id").asText());
            try {
                String stored = pending.path("credential").asText();
                run.put(
                        "password",
                        "plain".equals(pending.path("credential_format").asText())
                                ? stored
                                : decrypt(stored));
            } catch (Failure failure) {
                run.put("credentialError", true);
            }
            call("run-job", run, target, "scheduler", Duration.ofHours(12));
        } catch (Failure failure) {
            log.debug("Table sync job interrupted ({})", failure.getMessage());
        } finally {
            dispatching.set(false);
        }
    }

    private JsonNode call(
            String command, ObjectNode input, ObjectNode target, String actor, Duration timeout) {
        Process process = null;
        try {
            ProcessBuilder builder = new ProcessBuilder(python, "-X", "utf8", script().toString());
            builder.redirectError(ProcessBuilder.Redirect.DISCARD);
            configureWorkerEnvironment(builder.environment());
            process = builder.start();
            Process child = process;
            CompletableFuture<byte[]> output =
                    CompletableFuture.supplyAsync(
                            () -> {
                                try (var stream = child.getInputStream()) {
                                    return stream.readNBytes(MAX_OUTPUT + 1);
                                } catch (IOException error) {
                                    throw new Failure("WORKER_UNAVAILABLE");
                                }
                            });
            try (var stream = process.getOutputStream()) {
                ObjectNode request =
                        mapper.createObjectNode().put("action", command).put("actor", actor);
                request.set("input", input);
                if (target != null) request.set("target", target);
                mapper.writeValue(stream, request);
            }
            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                throw new Failure("WORKER_TIMEOUT");
            }
            byte[] bytes = output.get(5, TimeUnit.SECONDS);
            if (bytes.length > MAX_OUTPUT || process.exitValue() != 0) {
                throw new Failure("WORKER_UNAVAILABLE");
            }
            JsonNode reply = mapper.readTree(bytes);
            if (reply.has("error")) throw new Failure(reply.path("error").asText());
            if (!reply.has("body")) throw new Failure("WORKER_UNAVAILABLE");
            return reply.path("body");
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new Failure("WORKER_UNAVAILABLE");
        } catch (Failure error) {
            throw error;
        } catch (Exception error) {
            throw new Failure("WORKER_UNAVAILABLE");
        } finally {
            if (process != null && process.isAlive()) process.destroyForcibly();
        }
    }

    void configureWorkerEnvironment(Map<String, String> environment) {
        environment
                .keySet()
                .removeIf(
                        name ->
                                !SYSTEM_ENV.contains(name)
                                        && (!name.startsWith(ENV_PREFIX)
                                                || !WORKER_ENV.contains(
                                                        name.substring(ENV_PREFIX.length()))));
        for (String suffix : WORKER_ENV) {
            environment.computeIfAbsent(ENV_PREFIX + suffix, settings::getProperty);
        }
    }

    private synchronized Path script() throws IOException {
        if (script == null) {
            Path extracted = Files.createTempFile("dataagent-table-sync-", ".py");
            try (var bundled = new ClassPathResource("table-sync/worker.py").getInputStream()) {
                Files.copy(bundled, extracted, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
            extracted.toFile().deleteOnExit();
            script = extracted;
        }
        return script;
    }

    private Cipher cipher(int mode, byte[] nonce) throws GeneralSecurityException {
        byte[] key = Base64.getDecoder().decode(encodedKey.trim());
        if (key.length != 32 || nonce.length != 12) throw new GeneralSecurityException();
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(mode, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
        cipher.updateAAD(AAD);
        return cipher;
    }

    private String decrypt(String value) {
        try {
            String[] parts = value.split("\\.", -1);
            if (parts.length != 3 || !"v1".equals(parts[0])) throw new GeneralSecurityException();
            var base64 = Base64.getDecoder();
            return new String(
                    cipher(Cipher.DECRYPT_MODE, base64.decode(parts[1]))
                            .doFinal(base64.decode(parts[2])),
                    StandardCharsets.UTF_8);
        } catch (IllegalArgumentException | GeneralSecurityException error) {
            throw new Failure("CREDENTIAL_ERROR");
        }
    }

    @PreDestroy
    void stop() {
        dispatch.shutdownNow();
    }
}
