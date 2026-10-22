package io.agentscope.dataagent.dataset;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.dataagent.web.middleware.ExecutionTimingMiddleware;
import jakarta.annotation.PreDestroy;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

/** Best-effort preparation of derived assets, independent of publication success. */
@Component
public final class WrenQueryPreparation {
    private final WrenPublishedProject projects;
    private final KnowledgeBaseOperations operations;
    private final Scheduler scheduler;
    private final org.springframework.beans.factory.ObjectProvider<
                    io.agentscope.dataagent.runtime.wren.WrenQueryGateway>
            gateways;
    private final DatasetGroupService groups;
    private final ConcurrentHashMap<String, Status> statuses = new ConcurrentHashMap<>();

    public record Status(String state, int version, String updatedAt, String message) {}

    public Status status(String actor, String group) {
        var current = groups.queryGroup(actor, group);
        Status value = statuses.get(group);
        if (value == null || value.version() != current.getMdlVersion())
            return new Status("NOT_READY", current.getMdlVersion(), null, "首次检索将按需准备");
        var gateway = gateways == null ? null : gateways.getIfAvailable();
        if ("READY".equals(value.state()) && (gateway == null || !gateway.recallWarm(group)))
            return new Status(
                    "NOT_READY", current.getMdlVersion(), value.updatedAt(), "查询实例已回收，首次检索将按需准备");
        return value;
    }

    private void update(
            PublishedQueryProjectChanged event, String state, int version, String message) {
        statuses.put(
                event.groupId(),
                new Status(state, version, java.time.Instant.now().toString(), message));
    }

    private final ConcurrentHashMap<String, Pending> pending = new ConcurrentHashMap<>();

    private static final class Pending {
        final AtomicBoolean dirty = new AtomicBoolean(true);
    }

    public WrenQueryPreparation(WrenPublishedProject projects, KnowledgeBaseOperations operations) {
        this(projects, operations, Schedulers.newBoundedElastic(2, 128, "wren-query-prepare"));
    }

    @org.springframework.beans.factory.annotation.Autowired
    public WrenQueryPreparation(
            WrenPublishedProject projects,
            KnowledgeBaseOperations operations,
            org.springframework.beans.factory.ObjectProvider<
                            io.agentscope.dataagent.runtime.wren.WrenQueryGateway>
                    gateways,
            DatasetGroupService groups) {
        this(
                projects,
                operations,
                Schedulers.newBoundedElastic(2, 128, "wren-query-prepare"),
                gateways,
                groups);
    }

    WrenQueryPreparation(
            WrenPublishedProject projects,
            KnowledgeBaseOperations operations,
            Scheduler scheduler) {
        this(projects, operations, scheduler, null, null);
    }

    WrenQueryPreparation(
            WrenPublishedProject projects,
            KnowledgeBaseOperations operations,
            Scheduler scheduler,
            org.springframework.beans.factory.ObjectProvider<
                            io.agentscope.dataagent.runtime.wren.WrenQueryGateway>
                    gateways,
            DatasetGroupService groups) {
        this.projects = projects;
        this.operations = operations;
        this.scheduler = scheduler;
        this.gateways = gateways;
        this.groups = groups;
    }

    @org.springframework.context.event.EventListener
    @org.springframework.core.annotation.Order(-100)
    public void invalidatePublished(PublishedQueryProjectChanged event) {
        if (gateways != null) {
            var gateway = gateways.getIfAvailable();
            if (gateway != null) gateway.invalidate(event.groupId());
        }
    }

    @TransactionalEventListener(fallbackExecution = true)
    public void changed(PublishedQueryProjectChanged event) {
        try {
            pending.compute(
                    event.groupId(),
                    (group, active) -> {
                        if (active != null) {
                            active.dirty.set(true);
                            return active;
                        }
                        Pending task = new Pending();
                        int version =
                                groups == null
                                        ? 0
                                        : groups.queryGroup(event.ownerId(), event.groupId())
                                                .getMdlVersion();
                        update(event, "QUEUED", version, "等待检索准备");
                        try {
                            scheduler.schedule(() -> prepare(event, task));
                            return task;
                        } catch (RuntimeException rejected) {
                            update(event, "FAILED", version, "准备队列繁忙，首次检索可重试");
                            logFailure(event, rejected);
                            return null;
                        }
                    });
        } catch (RuntimeException failure) {
            update(event, "FAILED", 0, "检索准备暂不可用，问数时可重试");
            logFailure(event, failure);
        }
    }

    private void prepare(PublishedQueryProjectChanged event, Pending task) {
        try {
            do {
                task.dirty.set(false);
                long started = System.nanoTime();
                RuntimeContext context =
                        RuntimeContext.builder()
                                .userId(event.ownerId())
                                .sessionId("query-preparation:" + event.groupId())
                                .build();
                int version = 0;
                try {
                    version =
                            groups == null
                                    ? 0
                                    : groups.queryGroup(event.ownerId(), event.groupId())
                                            .getMdlVersion();
                    update(event, "INDEXING", version, "正在准备检索索引");
                    final int expectedVersion = version;
                    operations.run(
                            event.groupId(),
                            () -> {
                                projects.indexed(event.ownerId(), event.groupId(), context);
                                if (gateways != null) {
                                    var gateway = gateways.getIfAvailable();
                                    if (gateway == null)
                                        throw new DatasetException("查询实例暂不可用", 503);
                                    update(event, "WARMING", expectedVersion, "正在预热检索");
                                    long warmStarted = System.nanoTime();
                                    try {
                                        gateway.warmRecall(
                                                event.groupId(),
                                                () -> {
                                                    var current =
                                                            groups.queryGroup(
                                                                    event.ownerId(),
                                                                    event.groupId());
                                                    if (current.getMdlVersion() != expectedVersion)
                                                        throw new DatasetException(
                                                                "发布版本已变化，等待重新准备", 409);
                                                });
                                        ExecutionTimingMiddleware.record(
                                                context,
                                                "recall.warmup",
                                                event.groupId(),
                                                warmStarted,
                                                "SUCCESS");
                                    } catch (RuntimeException failure) {
                                        ExecutionTimingMiddleware.record(
                                                context,
                                                "recall.warmup",
                                                event.groupId(),
                                                warmStarted,
                                                "ERROR");
                                        throw failure;
                                    }
                                }
                                return null;
                            });
                    update(event, "READY", version, "检索准备完成");
                    ExecutionTimingMiddleware.record(
                            context, "query_preparation", event.groupId(), started, "READY");
                } catch (RuntimeException failure) {
                    update(event, "FAILED", version, "检索准备失败，问数时可重试");
                    ExecutionTimingMiddleware.record(
                            context, "query_preparation", event.groupId(), started, "FAILED");
                    logFailure(event, failure);
                }
                AtomicBoolean repeat = new AtomicBoolean();
                pending.compute(
                        event.groupId(),
                        (group, active) -> {
                            if (active != task) return active;
                            if (task.dirty.get()) {
                                repeat.set(true);
                                return task;
                            }
                            return null;
                        });
                if (!repeat.get()) return;
            } while (!scheduler.isDisposed());
        } finally {
            pending.remove(event.groupId(), task);
        }
    }

    private static void logFailure(PublishedQueryProjectChanged event, RuntimeException failure) {
        LoggerFactory.getLogger(WrenQueryPreparation.class)
                .warn(
                        "Query preparation failed group={} type={}; on-demand preparation remains"
                                + " available",
                        event.groupId(),
                        failure.getClass().getSimpleName());
    }

    @PreDestroy
    public void close() {
        scheduler.dispose();
    }
}
