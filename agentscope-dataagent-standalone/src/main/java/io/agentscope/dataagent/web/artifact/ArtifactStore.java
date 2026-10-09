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
package io.agentscope.dataagent.web.artifact;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.dataagent.web.persistence.jpa.ArtifactEntity;
import io.agentscope.dataagent.web.persistence.jpa.ArtifactRepository;
import io.agentscope.harness.agent.filesystem.sandbox.AbstractSandboxFilesystem;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.AbstractMap;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/** Durable, immutable artifacts. The root must be on a backed-up persistent volume. */
@Service
public class ArtifactStore {
    private final ArtifactRepository repository;
    private final Path root;
    private final io.agentscope.dataagent.web.persistence.jpa.SessionRegistryRepository sessions;
    private final long maxBytes;
    private final long quotaBytes;
    private final int maxSessionFiles;
    private final long totalQuotaBytes;
    private final long minFreeBytes;
    private final int restoreMaxFiles;
    private final long restoreMaxBytes;
    private final Object quotaLock = new Object();
    private long reservedBytes;
    private final java.util.concurrent.locks.ReentrantLock[] ownerLocks =
            java.util.stream.IntStream.range(0, 64)
                    .mapToObj(i -> new java.util.concurrent.locks.ReentrantLock())
                    .toArray(java.util.concurrent.locks.ReentrantLock[]::new);
    private final java.util.Set<String> activeFiles =
            java.util.concurrent.ConcurrentHashMap.newKeySet();
    private String cleanupCursor = "";
    private java.nio.file.DirectoryStream<Path> cleanupFiles;
    private java.util.Iterator<Path> cleanupIterator;
    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(ArtifactStore.class);

    private java.util.concurrent.locks.ReentrantLock ownerLock(String owner) {
        return ownerLocks[Math.floorMod(owner.hashCode(), ownerLocks.length)];
    }

    /** Expected, user-actionable storage limits; never exposes infrastructure exception details. */
    public static class CapacityException extends IOException {
        public CapacityException(String message) {
            super(message);
        }
    }

    private final java.util.concurrent.ScheduledExecutorService cleaner =
            java.util.concurrent.Executors.newSingleThreadScheduledExecutor(
                    r -> {
                        Thread t = new Thread(r, "artifact-cleanup");
                        t.setDaemon(true);
                        return t;
                    });

    @org.springframework.context.event.EventListener(
            org.springframework.boot.context.event.ApplicationReadyEvent.class)
    public void startCleanup() {
        cleaner.scheduleWithFixedDelay(
                this::cleanupOrphans, 1, 1, java.util.concurrent.TimeUnit.MINUTES);
    }

    @jakarta.annotation.PreDestroy
    public synchronized void stopCleanup() {
        cleaner.shutdownNow();
        closeCleanupFiles();
    }

    private void closeCleanupFiles() {
        if (cleanupFiles != null) {
            try {
                cleanupFiles.close();
            } catch (IOException e) {
                log.warn("Cannot close artifact scan", e);
            }
        }
        cleanupFiles = null;
        cleanupIterator = null;
    }

    /** Bounded work per tick; an active owner's files are retried on the next pass. */
    private synchronized void cleanupOrphans() {
        long cutoff = System.currentTimeMillis() - java.time.Duration.ofDays(1).toMillis();
        int removed = 0;
        try {
            var batch =
                    repository.findByIdGreaterThanOrderByIdAsc(
                            cleanupCursor, org.springframework.data.domain.PageRequest.of(0, 100));
            for (var a : batch) {
                cleanupCursor = a.id;
                var lock = ownerLock(a.ownerId);
                if (!lock.tryLock()) continue;
                try {
                    if (a.createdAtMs < cutoff
                            && !sessions.existsByUserIdAndSessionId(a.ownerId, a.sessionId)) {
                        Files.deleteIfExists(path(a.id));
                        repository.deleteById(a.id);
                        removed++;
                    }
                } catch (Exception e) {
                    log.warn("Artifact cleanup failed for {}; will retry", a.id, e);
                } finally {
                    lock.unlock();
                }
            }
            if (batch.size() < 100) cleanupCursor = "";
            if (cleanupFiles == null) {
                cleanupFiles = Files.newDirectoryStream(root);
                cleanupIterator = cleanupFiles.iterator();
            }
            for (int i = 0; i < 100 && cleanupIterator.hasNext(); i++) {
                Path file = cleanupIterator.next();
                try {
                    String name = file.getFileName().toString();
                    if (activeFiles.contains(name)
                            || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)
                            || Files.getLastModifiedTime(file).toMillis() >= cutoff) continue;
                    boolean temporary = name.startsWith(".upload-") && name.endsWith(".tmp");
                    boolean unregistered =
                            name.matches(
                                            "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
                                    && !repository.existsById(name);
                    if (temporary || unregistered) {
                        Files.deleteIfExists(file);
                        removed++;
                    }
                } catch (Exception e) {
                    log.warn("Artifact file cleanup failed; will retry", e);
                }
            }
            if (!cleanupIterator.hasNext()) closeCleanupFiles();
            long free = Files.getFileStore(root).getUsableSpace();
            if (free < minFreeBytes)
                log.warn(
                        "Artifact storage low: freeBytes={}, requiredReserve={}",
                        free,
                        minFreeBytes);
            if (removed > 0) log.info("Artifact cleanup removed {} orphan records/files", removed);
        } catch (Exception e) {
            closeCleanupFiles();
            log.warn("Artifact cleanup batch failed; will retry", e);
        }
    }

    public ArtifactStore(
            ArtifactRepository repository,
            io.agentscope.dataagent.web.persistence.jpa.SessionRegistryRepository sessions,
            @Value("${dataagent.artifacts.directory:./data/artifacts}") String directory,
            @Value("${dataagent.artifacts.max-file-mb:20}") long maxMb,
            @Value("${dataagent.artifacts.user-quota-mb:1024}") long quotaMb,
            @Value("${dataagent.artifacts.max-session-files:100}") int maxSessionFiles,
            @Value("${dataagent.artifacts.total-quota-mb:10240}") long totalMb,
            @Value("${dataagent.artifacts.min-free-disk-mb:2048}") long minFreeMb,
            @Value("${dataagent.artifacts.restore-max-files:10}") int restoreMaxFiles,
            @Value("${dataagent.artifacts.restore-max-mb:100}") long restoreMb)
            throws IOException {
        this.repository = repository;
        if (maxSessionFiles < 1) throw new IllegalArgumentException("Invalid session file limit");
        this.maxSessionFiles = maxSessionFiles;
        this.sessions = sessions;
        this.root = Path.of(directory).toAbsolutePath().normalize();
        this.maxBytes = Math.multiplyExact(maxMb, 1024L * 1024L);
        this.quotaBytes = Math.multiplyExact(quotaMb, 1024L * 1024L);
        if (maxBytes <= 0 || quotaBytes < maxBytes)
            throw new IllegalArgumentException("Invalid artifact limits");
        this.totalQuotaBytes = Math.multiplyExact(totalMb, 1024L * 1024L);
        this.minFreeBytes = Math.multiplyExact(minFreeMb, 1024L * 1024L);
        this.restoreMaxFiles = restoreMaxFiles;
        this.restoreMaxBytes = Math.multiplyExact(restoreMb, 1024L * 1024L);
        if (totalQuotaBytes < maxBytes
                || minFreeBytes < 0
                || restoreMaxFiles < 1
                || restoreMaxBytes < maxBytes)
            throw new IllegalArgumentException("Invalid total storage/restore limits");
        Files.createDirectories(root);
    }

    public long maxBytes() {
        return maxBytes;
    }

    public ArtifactEntity save(
            RuntimeContext rc,
            String runId,
            String name,
            String sourcePath,
            byte[] bytes,
            boolean inputData)
            throws IOException {
        if (rc == null || rc.getUserId() == null || rc.getSessionId() == null)
            throw new IllegalArgumentException("Artifact requires authenticated session");
        var lock = ownerLock(rc.getUserId());
        lock.lock();
        try {
            return saveLocked(rc, runId, name, sourcePath, bytes, inputData);
        } finally {
            lock.unlock();
        }
    }

    private ArtifactEntity saveLocked(
            RuntimeContext rc,
            String runId,
            String name,
            String sourcePath,
            byte[] bytes,
            boolean inputData)
            throws IOException {
        if (!sessions.existsByUserIdAndSessionId(rc.getUserId(), rc.getSessionId()))
            throw new IllegalStateException("会话已删除，无法保存附件");
        if (name == null
                || name.isBlank()
                || name.length() > 255
                || name.contains("/")
                || name.contains("\\")
                || name.chars().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException("Invalid artifact filename");
        if (bytes.length == 0 || bytes.length > maxBytes)
            throw new CapacityException("文件为空或超过单文件保存上限（" + maxBytes / 1048576 + " MB）");
        // Input CSV names are content-addressed: do not spend quota twice for the same result.
        var sessionFiles = repository.findByOwnerIdAndSessionId(rc.getUserId(), rc.getSessionId());
        if (inputData) {
            for (ArtifactEntity old : sessionFiles) {
                if (old.inputData
                        && old.sourcePath.equals(sourcePath)
                        && Files.isRegularFile(path(old.id))) return old;
            }
        }
        if (sessionFiles.size() >= maxSessionFiles)
            throw new CapacityException("当前会话附件数量已达 " + maxSessionFiles + " 个，请新建会话");
        if (repository.bytesUsed(rc.getUserId()) + bytes.length > quotaBytes)
            throw new CapacityException("个人附件配额已满（" + quotaBytes / 1048576 + " MB），请删除不需要的会话后重试");
        ArtifactEntity a = new ArtifactEntity();
        a.id = UUID.randomUUID().toString();
        a.ownerId = rc.getUserId();
        a.sessionId = rc.getSessionId();
        a.runId = runId;
        a.filename = name;
        a.sourcePath = sourcePath;
        a.sizeBytes = bytes.length;
        a.createdAtMs = System.currentTimeMillis();
        a.inputData = inputData;
        reserve(bytes.length);
        Path temporary = null;
        activeFiles.add(a.id);
        try {
            temporary = Files.createTempFile(root, ".upload-", ".tmp");
            activeFiles.add(temporary.getFileName().toString());
            Files.write(temporary, bytes);
            try {
                Files.move(temporary, path(a.id), StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temporary, path(a.id));
            }
            try {
                return repository.saveAndFlush(a);
            } catch (RuntimeException e) {
                Files.deleteIfExists(path(a.id));
                throw e;
            }
        } finally {
            try {
                if (temporary != null) Files.deleteIfExists(temporary);
            } finally {
                activeFiles.remove(a.id);
                if (temporary != null) activeFiles.remove(temporary.getFileName().toString());
                synchronized (quotaLock) {
                    reservedBytes -= bytes.length;
                }
            }
        }
    }

    private void reserve(long bytes) throws IOException {
        synchronized (quotaLock) {
            if (repository.totalBytesUsed() + reservedBytes + bytes > totalQuotaBytes) {
                log.warn("Artifact total quota reached: limitBytes={}", totalQuotaBytes);
                throw new CapacityException("附件总存储容量已达上限，请联系管理员清理或扩容");
            }
            if (Files.getFileStore(root).getUsableSpace() < minFreeBytes + reservedBytes + bytes) {
                log.warn("Artifact disk reserve reached: reserveBytes={}", minFreeBytes);
                throw new CapacityException("附件磁盘可用空间不足，请联系管理员清理或扩容");
            }
            reservedBytes += bytes;
        }
    }

    public ArtifactEntity require(String userId, String id) {
        ArtifactEntity a =
                repository
                        .findById(id)
                        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (!Objects.equals(userId, a.ownerId))
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        if (!sessions.existsByUserIdAndSessionId(userId, a.sessionId))
            throw new ResponseStatusException(HttpStatus.GONE, "附件所属会话已删除");
        return a;
    }

    public String restore(RuntimeContext rc, String id, AbstractSandboxFilesystem fs)
            throws IOException {
        var a = require(rc.getUserId(), id);
        String safeName = a.filename.replaceAll("[^a-zA-Z0-9_.-]", "_");
        String target =
                "/workspace/runpython/"
                        + rc.getSessionId().replaceAll("[^a-zA-Z0-9_-]", "_")
                        + "/data/"
                        + a.id
                        + "-"
                        + safeName;
        ensureRestoreSpace(rc, fs, a.sizeBytes);
        byte[] content = Files.readAllBytes(path(a.id));
        var results = fs.uploadFiles(rc, List.of(new AbstractMap.SimpleEntry<>(target, content)));
        if (results.isEmpty() || !results.get(0).isSuccess()) throw new IOException("无法恢复文件");
        // Save as a durable input for this session, so a later Python run can rehydrate it too.
        save(
                rc,
                UUID.randomUUID().toString(),
                a.id + "-" + safeName.substring(0, Math.min(180, safeName.length())),
                target,
                content,
                true);
        return "data/" + a.id + "-" + safeName;
    }

    public Path path(String id) {
        // Only server-generated IDs can address disk files; never accept user-supplied paths.
        if (!UUID.fromString(id).toString().equals(id))
            throw new IllegalArgumentException("Invalid artifact id");
        return root.resolve(id);
    }

    public String reference(ArtifactEntity a) {
        return "/api/artifacts/" + a.id + "/content";
    }

    private static String shellQuote(String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }

    private void ensureRestoreSpace(RuntimeContext rc, AbstractSandboxFilesystem fs, long incoming)
            throws IOException {
        String directory =
                "/workspace/runpython/"
                        + rc.getSessionId().replaceAll("[^a-zA-Z0-9_-]", "_")
                        + "/data";
        var result =
                fs.execute(
                        rc,
                        "mkdir -p "
                                + shellQuote(directory)
                                + " && du -sk "
                                + shellQuote(directory)
                                + " && df -Pk /workspace",
                        10);
        if (result == null || result.exitCode() != 0 || result.output() == null)
            throw new IOException("无法检查沙箱可用空间");
        try {
            String[] lines = result.output().strip().split("\\R");
            long used = Long.parseLong(lines[0].strip().split("\\s+")[0]) * 1024;
            String[] disk = lines[lines.length - 1].strip().split("\\s+");
            long available = Long.parseLong(disk[3]) * 1024;
            if (used + incoming > restoreMaxBytes || available < incoming + 32L * 1024 * 1024)
                throw new CapacityException(
                        "当前会话分析输入或沙箱空间不足，暂不恢复更多文件；可新建会话后使用 restore_artifact 恢复所需文件");
        } catch (NumberFormatException | IndexOutOfBoundsException e) {
            throw new IOException("无法解析沙箱可用空间", e);
        }
    }

    /** A simple bounded recent-file policy; permanent artifacts are never deleted here. */
    public String restoreInputs(RuntimeContext rc, AbstractSandboxFilesystem fs)
            throws IOException {
        var inputs =
                repository.findByOwnerIdAndSessionId(rc.getUserId(), rc.getSessionId()).stream()
                        .filter(a -> a.inputData)
                        .sorted(
                                java.util.Comparator.comparingLong(
                                                (ArtifactEntity a) -> a.createdAtMs)
                                        .reversed()
                                        .thenComparing(a -> a.id))
                        .toList();
        var recent = inputs.subList(0, Math.min(restoreMaxFiles, inputs.size()));
        if (recent.isEmpty()) return "";
        StringBuilder command = new StringBuilder();
        for (var a : recent)
            command.append("test -s ")
                    .append(shellQuote(a.sourcePath))
                    .append(" || echo ")
                    .append(shellQuote(a.id))
                    .append("; ");
        var check = fs.execute(rc, command.toString(), 10);
        if (check == null || check.exitCode() != 0 || check.output() == null)
            throw new IOException("无法检查分析输入");
        var missing =
                new java.util.HashSet<>(
                        java.util.Arrays.asList(check.output().strip().split("\\s+")));
        int omitted = inputs.size() - recent.size();
        long selectedBytes = 0;
        for (var a : recent) {
            if (selectedBytes + a.sizeBytes > restoreMaxBytes) {
                omitted++;
                continue;
            }
            selectedBytes += a.sizeBytes;
            if (!missing.contains(a.id)) continue;
            try {
                ensureRestoreSpace(rc, fs, a.sizeBytes);
            } catch (CapacityException e) {
                omitted++;
                continue;
            }
            // Upload one file at a time rather than holding an entire batch in JVM memory.
            var results =
                    fs.uploadFiles(
                            rc,
                            List.of(
                                    new AbstractMap.SimpleEntry<>(
                                            a.sourcePath, Files.readAllBytes(path(a.id)))));
            if (results.size() != 1 || !results.get(0).isSuccess())
                throw new IOException("无法恢复分析输入");
        }
        return omitted == 0
                ? ""
                : "分析输入恢复提示：仅自动准备最近 "
                        + restoreMaxFiles
                        + " 个文件，输入空间预算 "
                        + restoreMaxBytes / 1048576
                        + " MB。本次有 "
                        + omitted
                        + " 个输入未自动恢复；永久附件及下载不受影响。若代码引用的旧文件不存在，请调用 restore_artifact 恢复所需附件后重试。";
    }

    public void deleteSession(String owner, String sessionId) throws IOException {
        var lock = ownerLock(owner);
        lock.lock();
        try {
            for (ArtifactEntity a : repository.findByOwnerIdAndSessionId(owner, sessionId)) {
                Files.deleteIfExists(path(a.id));
                repository.deleteById(a.id);
            }
        } finally {
            lock.unlock();
        }
    }
}
