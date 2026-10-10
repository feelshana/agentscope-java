package io.agentscope.dataagent.dataset;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

/** Admission and cancellation gate shared by deletion, blocking workers and chat subscriptions. */
@Component
public class KnowledgeBaseOperations {
    private final WrenProperties properties;
    private final Map<String, State> states = new ConcurrentHashMap<>();

    private static class State {
        boolean blocked;
        final Map<Thread, Integer> threads = new HashMap<>();
        final Sinks.One<Boolean> cancellation = Sinks.one();
        final java.util.Set<ProcessHandle> processes = new java.util.HashSet<>();
    }

    public KnowledgeBaseOperations(WrenProperties properties) {
        this.properties = properties;
    }

    public Path journal(String groupId) {
        properties.groupRoot(groupId); // validate before constructing any filesystem path
        return properties.mdlRoot().resolve(".kb-deletions").resolve(groupId + ".json");
    }

    private Path resourceFile(String groupId) {
        properties.groupRoot(groupId);
        return properties.mdlRoot().resolve(".kb-resources").resolve(groupId + ".json");
    }

    /** Record before DDL: cancellation can roll back dataset metadata but not MySQL CREATE TABLE. */
    public void recordImportedTable(String groupId, String table) {
        State state = state(groupId);
        synchronized (state) {
            requireActive(groupId);
            var tables = new java.util.LinkedHashSet<>(importedTables(groupId));
            tables.add(table);
            Path file = resourceFile(groupId);
            Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
            try {
                Files.createDirectories(file.getParent());
                new com.fasterxml.jackson.databind.ObjectMapper()
                        .writeValue(temporary.toFile(), tables);
                Files.move(
                        temporary,
                        file,
                        java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            } catch (java.io.IOException e) {
                throw new DatasetException("无法记录导入数据表资源，导入已停止", e);
            }
        }
    }

    public java.util.List<String> importedTables(String groupId) {
        Path file = resourceFile(groupId);
        if (!Files.exists(file)) return java.util.List.of();
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper()
                    .readValue(
                            file.toFile(),
                            new com.fasterxml.jackson.core.type.TypeReference<
                                    java.util.List<String>>() {});
        } catch (java.io.IOException e) {
            throw new DatasetException("无法读取导入数据表资源清单", e);
        }
    }

    public void removeResourceFile(String groupId) {
        try {
            Files.deleteIfExists(resourceFile(groupId));
        } catch (java.io.IOException e) {
            throw new DatasetException("无法清理数据表资源清单", e);
        }
    }

    private State state(String groupId) {
        return states.computeIfAbsent(groupId, ignored -> new State());
    }

    public void requireActive(String groupId) {
        if (groupId == null || groupId.isBlank()) return;
        State state = state(groupId);
        synchronized (state) {
            if (state.blocked || Files.exists(journal(groupId))) {
                throw new DatasetException("知识库正在删除或已删除，不能继续操作", 409);
            }
        }
    }

    public String groupForProject(Path project) {
        Path root = properties.mdlRoot().toAbsolutePath().normalize();
        Path target = project.toAbsolutePath().normalize();
        if (!target.startsWith(root) || target.equals(root)) return null;
        String group = root.relativize(target).getName(0).toString();
        return group.startsWith(".") ? null : group;
    }

    public void trackProcess(String groupId, Process process) {
        if (groupId == null) return;
        State state = state(groupId);
        synchronized (state) {
            state.processes.removeIf(handle -> !handle.isAlive());
            state.processes.add(process.toHandle());
            process.descendants().forEach(state.processes::add);
            if (state.blocked) stopProcesses(state);
        }
    }

    private void stopProcesses(State state) {
        state.processes.removeIf(handle -> !handle.isAlive());
        var parents = java.util.List.copyOf(state.processes);
        for (ProcessHandle parent : parents) parent.descendants().forEach(state.processes::add);
        for (ProcessHandle handle : state.processes) if (handle.isAlive()) handle.destroyForcibly();
    }

    public AutoCloseable enter(String groupId) {

        if (groupId == null || groupId.isBlank()) return () -> {};
        State state = state(groupId);
        Thread thread = Thread.currentThread();
        synchronized (state) {
            requireActive(groupId);
            state.threads.merge(thread, 1, Integer::sum);
        }
        return () -> {
            synchronized (state) {
                state.threads.computeIfPresent(
                        thread, (key, count) -> count == 1 ? null : count - 1);
                state.notifyAll();
            }
        };
    }

    public boolean deletionPending(String groupId) {
        return Files.exists(journal(groupId));
    }

    public <T> T run(String groupId, Supplier<T> action) {
        try (AutoCloseable ignored = enter(groupId)) {
            return action.get();
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new DatasetException("知识库操作未完成", e);
        }
    }

    /** Persist the tombstone atomically while admission is locked, then cancel admitted work. */
    public void block(String groupId, Runnable persist) {
        State state = state(groupId);
        synchronized (state) {
            persist.run();
            state.blocked = true;
            state.cancellation.tryEmitValue(true);
            stopProcesses(state);
            for (Thread thread : state.threads.keySet()) {
                if (thread != Thread.currentThread()) thread.interrupt();
            }
        }
    }

    public void awaitStopped(String groupId, Duration timeout) {
        State state = state(groupId);
        long deadline = System.nanoTime() + timeout.toNanos();
        synchronized (state) {
            while (!state.threads.isEmpty()
                    || state.processes.stream().anyMatch(ProcessHandle::isAlive)) {
                stopProcesses(state);
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) {
                    throw new DatasetException("相关任务尚未停止，知识库删除待重试", 409);
                }
                try {
                    state.wait(Math.max(1, Math.min(1000, remaining / 1_000_000)));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new DatasetException("等待知识库任务停止被中断", e);
                }
            }
        }
    }

    /** Cancels only turns explicitly bound to this KB, never the user's shared sandbox. */
    public <T> Flux<T> watch(java.util.List<String> groupIds, Supplier<Flux<T>> action) {
        return Flux.defer(
                () -> {
                    var ids = groupIds == null ? java.util.List.<String>of() : groupIds;
                    ids.forEach(this::requireActive);
                    Mono<Boolean> deleted =
                            Mono.firstWithSignal(
                                    ids.stream()
                                            .map(id -> state(id).cancellation.asMono())
                                            .toList());
                    if (ids.isEmpty()) return action.get();
                    return action.get()
                            .takeUntilOther(
                                    deleted.flatMap(
                                            value ->
                                                    Mono.error(
                                                            new DatasetException(
                                                                    "所选知识库正在删除，本次执行已停止", 409))));
                });
    }
}
