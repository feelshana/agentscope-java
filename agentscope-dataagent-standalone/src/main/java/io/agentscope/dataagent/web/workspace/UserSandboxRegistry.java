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
package io.agentscope.dataagent.web.workspace;

import io.agentscope.harness.agent.sandbox.Sandbox;
import io.agentscope.harness.agent.sandbox.SandboxClient;
import io.agentscope.harness.agent.sandbox.WorkspaceSpec;
import io.agentscope.harness.agent.sandbox.impl.docker.DockerSandboxClientOptions;
import io.agentscope.harness.agent.sandbox.layout.WorkspaceEntry;
import io.agentscope.harness.agent.sandbox.layout.WorkspaceProjectionEntry;
import io.agentscope.harness.agent.sandbox.snapshot.NoopSnapshotSpec;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Owns the per-{@code (userId, agentId)} live {@link Sandbox} instances used by the DataAgent web
 * tier.
 *
 * <p>Both the browser workspace controllers and the agent runtime (via {@link
 * io.agentscope.harness.agent.sandbox.SandboxContext#getExternalSandbox()}) read and write through
 * the sandbox returned by {@link #borrow(String, String)}. Reusing a single container per user
 * across browser requests and agent turns is what makes the workspace user-isolated — every other
 * route the old {@link io.agentscope.harness.agent.filesystem.CompositeFilesystem} fell through to
 * a shared {@code LocalFilesystem}, leaking content across tenants.
 *
 * <p>Lifecycle: a sandbox is created+started lazily on the first {@link #borrow} for a key, kept
 * alive across subsequent borrows, and closed once it has gone idle for {@link #idleTtl}. {@link
 * #shutdownAll()} runs on bean destruction. Approval of a marketplace contribution calls
 * {@link #invalidate(String, String)} to evict all sandboxes whose shared layer has changed.
 *
 * <p>Threading: {@link ConcurrentHashMap#compute} serialises {@link #borrow} for the same key, so
 * {@link Sandbox#start()} is only invoked once per container. Concurrent borrows on different keys
 * are independent.
 *
 * <p>Multi-replica: this registry is in-memory only. Deployments with more than one replica must
 * use sticky load-balancing by {@code userId} to keep a user's traffic on the same pod (each pod
 * would otherwise spin up its own container for the same user, and the frontend would observe
 * non-deterministic state).
 */
public final class UserSandboxRegistry {

    private static final Logger log = LoggerFactory.getLogger(UserSandboxRegistry.class);

    private static final List<String> DEFAULT_PROJECTION_ROOTS =
            List.of("AGENTS.md", "skills", "subagents", "knowledge");

    private final SandboxClient<DockerSandboxClientOptions> client;
    private final Path hostWorkspaceRoot;
    private final Duration idleTtl;
    private final DockerSandboxClientOptions optionsTemplate;
    private final ConcurrentHashMap<Key, Entry> entries = new ConcurrentHashMap<>();
    private final ScheduledExecutorService evictor;
    private final ConcurrentHashMap<Entry, Key> pendingCleanup = new ConcurrentHashMap<>();
    private java.util.concurrent.Semaphore slots = new java.util.concurrent.Semaphore(8);
    private String instanceId = "standalone";
    private volatile boolean orphanCleanupComplete = true;
    private java.nio.channels.FileChannel instanceLockChannel;
    private java.nio.channels.FileLock instanceLock;

    private void lockInstance() {
        try {
            Path lock =
                    Path.of(
                            System.getProperty("java.io.tmpdir"),
                            "dataagent-sandbox-" + instanceId + ".lock");
            instanceLockChannel =
                    java.nio.channels.FileChannel.open(
                            lock,
                            java.nio.file.StandardOpenOption.CREATE,
                            java.nio.file.StandardOpenOption.WRITE);
            instanceLock = instanceLockChannel.tryLock();
            if (instanceLock == null)
                throw new IllegalStateException(
                        "Another local DataAgent instance uses sandbox.instance-id=" + instanceId);
        } catch (Exception e) {
            if (instanceLockChannel != null)
                try {
                    instanceLockChannel.close();
                } catch (Exception ignored) {
                }
            throw new IllegalStateException(
                    "Cannot acquire sandbox deployment lock; orphan cleanup aborted", e);
        }
    }

    public void configureLimits(String instanceId, int maxContainers) {
        if (instanceId == null || !instanceId.matches("[a-zA-Z0-9_.-]{1,64}") || maxContainers < 1)
            throw new IllegalArgumentException("Invalid sandbox instance/limit");
        this.instanceId = instanceId;
        this.slots = new java.util.concurrent.Semaphore(maxContainers);
    }

    public final class Lease implements AutoCloseable {
        private final Key key;
        private final Entry entry;
        private final java.util.concurrent.atomic.AtomicBoolean closed =
                new java.util.concurrent.atomic.AtomicBoolean();

        private Lease(Key key, Entry entry) {
            this.key = key;
            this.entry = entry;
        }

        public Sandbox sandbox() {
            return entry.sandbox;
        }

        @Override
        public void close() {
            if (!closed.compareAndSet(false, true)) return;
            entries.computeIfPresent(
                    key,
                    (k, current) -> {
                        if (current != entry) return current;
                        current.active--;
                        current.touch();
                        if (current.active == 0 && current.invalidated) {
                            closeQuietly(k, current, "deferred invalidate");
                            return null;
                        }
                        return current;
                    });
        }
    }

    /** Pins the container until the turn/operation finishes, including artifact persistence. */
    public Lease acquire(String userId, String agentId) {
        validateSegment("userId", userId);
        validateSegment("agentId", agentId);
        Key key = new Key(userId, agentId);
        Entry entry =
                entries.compute(
                        key,
                        (k, current) -> {
                            if (current == null) current = createAndStart(k);
                            current.active++;
                            current.touch();
                            return current;
                        });
        return new Lease(key, entry);
    }

    /**
     * @param client backend client used to {@link SandboxClient#create create} new sandboxes
     * @param hostWorkspaceRoot directory under which per-agent shared seed lives, organised as
     *     {@code <hostWorkspaceRoot>/agents/<agentId>/{AGENTS.md, skills/, subagents/, knowledge/}}.
     *     Each container is projected with the slice for its own {@code agentId} only, so two
     *     agents on the same host do not see each other's shared layer. May be {@code null} to
     *     skip projection entirely (every container starts empty).
     * @param idleTtl how long a sandbox may sit unused before {@link #evictIdle()} closes it
     * @param evictionPollInterval how often the background scheduler checks for idle sandboxes
     */
    public UserSandboxRegistry(
            SandboxClient<DockerSandboxClientOptions> client,
            Path hostWorkspaceRoot,
            Duration idleTtl,
            Duration evictionPollInterval) {
        this(client, hostWorkspaceRoot, idleTtl, evictionPollInterval, null);
    }

    /**
     * Same as {@link #UserSandboxRegistry(SandboxClient, Path, Duration, Duration)} but with an
     * explicit options template used for every container this registry creates. Pass {@code null}
     * to keep the client-side defaults (image {@code ubuntu:22.04}); pass a template carrying the
     * {@code dataagent.sandbox.image} so the Python analysis toolchain is available inside the
     * sandbox. The template is only read (never mutated) at container-creation time, so a shared
     * instance is safe under concurrent {@link #borrow} calls.
     *
     * @param optionsTemplate options template applied to every created sandbox; may be {@code null}
     */
    public UserSandboxRegistry(
            SandboxClient<DockerSandboxClientOptions> client,
            Path hostWorkspaceRoot,
            Duration idleTtl,
            Duration evictionPollInterval,
            DockerSandboxClientOptions optionsTemplate) {
        this.client = Objects.requireNonNull(client, "client");
        this.hostWorkspaceRoot = hostWorkspaceRoot;
        this.idleTtl = Objects.requireNonNull(idleTtl, "idleTtl");
        this.optionsTemplate = optionsTemplate;
        long pollMs =
                Math.max(
                        1_000L,
                        Objects.requireNonNull(evictionPollInterval, "evictionPollInterval")
                                .toMillis());
        this.evictor =
                Executors.newSingleThreadScheduledExecutor(
                        r -> {
                            Thread t = new Thread(r, "UserSandboxRegistry-evictor");
                            t.setDaemon(true);
                            return t;
                        });
        this.evictor.scheduleWithFixedDelay(
                this::evictIdleQuietly, pollMs, pollMs, TimeUnit.MILLISECONDS);
        log.info(
                "UserSandboxRegistry initialised: idleTtl={}, evictionPoll={},"
                        + " hostWorkspaceRoot={}",
                idleTtl,
                evictionPollInterval,
                hostWorkspaceRoot);
    }

    /**
     * Clean up orphaned containers from previous JVM runs that didn't shut down cleanly (e.g.,
     * killed IDE, crash, power loss). Without this, containers accumulate across restarts because
     * the in-memory {@link #entries} map is cleared on JVM exit but the Docker containers keep
     * running.
     *
     * <p>This method runs once on bean initialization, before any {@link #borrow} calls. It scans
     * Docker containers carrying this deployment's managed and instance labels (the naming
     * convention used by {@code DockerSandbox}) and removes them. Unlabelled legacy containers are preserved for manual recovery.
     * Safe to call even if Docker is not running (logs a warning and continues).
     */
    @PostConstruct
    synchronized void cleanupOrphanedContainers() {
        if (!(client
                instanceof io.agentscope.harness.agent.sandbox.impl.docker.DockerSandboxClient))
            return;
        if (instanceLock == null) lockInstance();
        orphanCleanupComplete = false;
        try {
            String output =
                    dockerCommand(
                                    "docker",
                                    "ps",
                                    "-aq",
                                    "--filter",
                                    "label=dataagent.managed=true",
                                    "--filter",
                                    "label=dataagent.instance=" + instanceId)
                            .strip();
            if (output.isEmpty()) {
                orphanCleanupComplete = true;
                return;
            }
            java.util.List<String> command =
                    new java.util.ArrayList<>(java.util.List.of("docker", "rm", "-f"));
            for (String id : output.split("\\s+")) {
                if (!id.matches("[0-9a-f]{12,64}"))
                    throw new IllegalStateException("Unexpected docker container id");
                command.add(id);
            }
            dockerCommand(command.toArray(String[]::new));
            orphanCleanupComplete = true;
            log.info(
                    "[sandbox-registry] removed {} orphan containers for instance {}",
                    command.size() - 3,
                    instanceId);
        } catch (Exception e) {
            log.warn(
                    "[sandbox-registry] orphan cleanup failed; startup continues: {}",
                    e.toString());
        }
    }

    private static String dockerCommand(String... args) throws Exception {
        Process process = new ProcessBuilder(args).redirectErrorStream(true).start();
        var reader =
                java.util.concurrent.Executors.newSingleThreadExecutor(
                        r -> {
                            Thread t = new Thread(r, "sandbox-cleanup-output");
                            t.setDaemon(true);
                            return t;
                        });
        try {
            var output =
                    reader.submit(
                            () ->
                                    new String(
                                            process.getInputStream().readAllBytes(),
                                            java.nio.charset.StandardCharsets.UTF_8));
            if (!process.waitFor(30, java.util.concurrent.TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new IllegalStateException("Docker cleanup timed out");
            }
            String text = output.get(5, java.util.concurrent.TimeUnit.SECONDS);
            if (process.exitValue() != 0) throw new IllegalStateException(text);
            return text;
        } finally {
            if (process.isAlive()) process.destroyForcibly();
            reader.shutdownNow();
        }
    }

    /**
     * Returns the live {@link Sandbox} for {@code (userId, agentId)}, creating + starting it on
     * first call. Subsequent calls within the {@link #idleTtl} window return the same instance
     * and bump its idle timer.
     */
    public Sandbox borrow(String userId, String agentId) {
        validateSegment("userId", userId);
        validateSegment("agentId", agentId);
        Key key = new Key(userId, agentId);
        Entry entry =
                entries.compute(
                        key,
                        (k, existing) -> {
                            if (existing != null) {
                                existing.touch();
                                return existing;
                            }
                            return createAndStart(k);
                        });
        return entry.sandbox;
    }

    /**
     * Returns the live {@link Sandbox} for {@code (userId, agentId)} if one is already cached.
     * Does NOT create a new container — useful for callers (e.g. file-tree rendering on a freshly
     * loaded UI) that should not pay the cold-start cost when nothing has happened yet.
     */
    public Optional<Sandbox> peek(String userId, String agentId) {
        validateSegment("userId", userId);
        validateSegment("agentId", agentId);
        Entry e = entries.get(new Key(userId, agentId));
        if (e == null) {
            return Optional.empty();
        }
        e.touch();
        return Optional.of(e.sandbox);
    }

    /**
     * Closes and removes cached sandboxes matching {@code (userId, agentId)}.
     *
     * <ul>
     *   <li>{@code userId} non-null: only that user's sandbox for the agent is evicted.
     *   <li>{@code userId} null: every user's sandbox for the agent is evicted — used by the
     *       contribution-approval flow so the next {@link #borrow} for any user of that agent
     *       reconstructs the container and picks up the newly approved shared content.
     * </ul>
     *
     * <p>Safe to call concurrently with other {@link #borrow} calls — they will simply re-create
     * the sandbox on the next access.
     */
    public void invalidate(String userId, String agentId) {
        validateSegment("agentId", agentId);
        int removed = 0;
        var it = entries.entrySet().iterator();
        while (it.hasNext()) {
            var e = it.next();
            Key k = e.getKey();
            if (!k.agentId().equals(agentId)) {
                continue;
            }
            if (userId != null && !userId.isBlank() && !k.userId().equals(userId)) {
                continue;
            }
            entries.computeIfPresent(
                    k,
                    (key, current) -> {
                        if (current.active > 0) {
                            current.invalidated = true;
                            return current;
                        }
                        closeQuietly(key, current, "invalidate");
                        return null;
                    });
            removed++;
        }
        if (removed > 0) {
            log.info(
                    "[sandbox-registry] invalidated {} sandbox(es) for agentId={}, userId={}",
                    removed,
                    agentId,
                    userId == null ? "(all)" : userId);
        }
    }

    /**
     * Visible for tests. Closes every sandbox whose last access time is older than {@link
     * #idleTtl}.
     */
    void evictIdle() {
        long cutoff = System.currentTimeMillis() - idleTtl.toMillis();
        for (Key key : entries.keySet()) {
            entries.computeIfPresent(
                    key,
                    (k, entry) -> {
                        if (entry.active > 0 || entry.lastAccessMs >= cutoff) return entry;
                        closeQuietly(k, entry, "idle");
                        return null;
                    });
        }
    }

    private void evictIdleQuietly() {
        try {
            pendingCleanup.forEach((entry, key) -> closeQuietly(key, entry, "retry"));
            evictIdle();
        } catch (RuntimeException ex) {
            log.warn("[sandbox-registry] eviction sweep failed: {}", ex.getMessage(), ex);
        }
    }

    @PreDestroy
    public void shutdownAll() {
        evictor.shutdownNow();
        for (Map.Entry<Key, Entry> e : entries.entrySet()) {
            closeQuietly(e.getKey(), e.getValue(), "shutdown");
        }
        entries.clear();
        pendingCleanup.forEach((entry, key) -> closeQuietly(key, entry, "shutdown retry"));
        try {
            if (instanceLock != null) instanceLock.release();
        } catch (Exception e) {
            log.warn("Failed to release instance lock", e);
        }
        try {
            if (instanceLockChannel != null) instanceLockChannel.close();
        } catch (Exception e) {
            log.warn("Failed to close instance lock", e);
        }
    }

    /** Retry startup cleanup after Docker becomes available, before admitting any container. */
    private synchronized void ensureOrphanCleanupComplete() {
        if (orphanCleanupComplete) return;
        cleanupOrphanedContainers();
        if (!orphanCleanupComplete)
            throw new IllegalStateException("Docker 尚未就绪或遗留容器清理失败，请确认 Docker 正常运行后重试");
    }

    private Entry createAndStart(Key key) {
        ensureOrphanCleanupComplete();
        if (!slots.tryAcquire()) throw new IllegalStateException("沙箱容量已满，请稍后重试");
        Entry entry = null;
        try {
            DockerSandboxClientOptions options =
                    optionsTemplate != null ? optionsTemplate : new DockerSandboxClientOptions();
            Sandbox sandbox =
                    client.create(buildWorkspaceSpec(key), new NoopSnapshotSpec(), options);
            entry = new Entry(sandbox);
            sandbox.start();
            log.info("[sandbox-registry] started sandbox for {}", key);
            return entry;
        } catch (Exception e) {
            if (entry == null) slots.release();
            else closeQuietly(key, entry, "start failure");
            throw new IllegalStateException("Failed to start sandbox for " + key, e);
        }
    }

    /**
     * Builds the workspace projection spec for {@code key}: the source root is the per-agent slice
     * under {@link #hostWorkspaceRoot} so the container only sees its own agent's shared layer.
     * The directory is created on demand to avoid a Docker mount failure when the agent's slice
     * doesn't exist yet.
     */
    private WorkspaceSpec buildWorkspaceSpec(Key key) {
        WorkspaceSpec spec = new WorkspaceSpec();
        if (hostWorkspaceRoot == null) {
            return spec;
        }
        Path agentSlice =
                hostWorkspaceRoot
                        .resolve("agents")
                        .resolve(key.agentId())
                        .toAbsolutePath()
                        .normalize();
        try {
            Files.createDirectories(agentSlice);
        } catch (IOException e) {
            throw new IllegalStateException(
                    "Failed to ensure per-agent shared dir " + agentSlice + ": " + e.getMessage(),
                    e);
        }
        WorkspaceProjectionEntry projection = new WorkspaceProjectionEntry();
        projection.setSourceRoot(agentSlice.toString());
        projection.setIncludeRoots(DEFAULT_PROJECTION_ROOTS);
        Map<String, WorkspaceEntry> es = new LinkedHashMap<>();
        es.put("__workspace_projection__", projection);
        spec.setEntries(es);
        return spec;
    }

    private void closeQuietly(Key key, Entry entry, String reason) {
        synchronized (entry) {
            if (entry.closed) return;
            try {
                entry.sandbox.close();
                // DockerSandbox.close logs removal errors without throwing: verify removal.
                if (entry.sandbox.getState()
                                instanceof
                                io.agentscope.harness.agent.sandbox.impl.docker.DockerSandboxState
                                        state
                        && state.getContainerId() != null
                        && !state.getContainerId().isBlank()) {
                    String id = state.getContainerId();
                    if (!id.matches("[0-9a-f]{12,64}"))
                        throw new IllegalStateException("Unexpected docker container id");
                    if (!dockerCommand("docker", "ps", "-aq", "--filter", "id=" + id).isBlank())
                        throw new IllegalStateException(
                                "Container still exists after close: " + id);
                }
                entry.closed = true;
                pendingCleanup.remove(entry);
                slots.release();
                log.info("[sandbox-registry] closed sandbox for {} ({})", key, reason);
            } catch (Exception e) {
                pendingCleanup.put(entry, key);
                log.warn(
                        "[sandbox-registry] cleanup pending for {} ({}); capacity retained: {}",
                        key,
                        reason,
                        e.toString());
            }
        }
    }

    private static void validateSegment(String label, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + " must not be null or blank");
        }
    }

    /** Composite key used to scope a sandbox to one user + agent. */
    public record Key(String userId, String agentId) {
        public Key {
            Objects.requireNonNull(userId, "userId");
            Objects.requireNonNull(agentId, "agentId");
        }
    }

    private static final class Entry {
        final Sandbox sandbox;
        volatile long lastAccessMs;
        int active;
        boolean invalidated;
        boolean closed;

        Entry(Sandbox sandbox) {
            this.sandbox = sandbox;
            this.lastAccessMs = System.currentTimeMillis();
        }

        void touch() {
            this.lastAccessMs = System.currentTimeMillis();
        }
    }
}
