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
package io.agentscope.dataagent.web.chat;

import jakarta.annotation.PostConstruct;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Schedulers;

/** Process-local question budget. Queued requests do not occupy a worker or a sandbox. */
@Component
public final class ChatCapacity {
    public static final class BusyException extends IllegalStateException {
        BusyException(String message) {
            super(message);
        }
    }

    @Value("${dataagent.chat.max-active:16}")
    private int maxActive = 16;

    @Value("${dataagent.chat.max-active-per-user:2}")
    private int maxPerUser = 2;

    @Value("${dataagent.chat.max-waiting:16}")
    private int maxWaiting = 16;

    @Value("${dataagent.chat.max-waiting-per-user:1}")
    private int maxWaitingPerUser = 1;

    @Value("${dataagent.chat.wait-seconds:30}")
    private int waitSeconds = 30;

    @Value("${dataagent.chat.execution-seconds:600}")
    private int executionSeconds = 600;

    private final ArrayDeque<Lease> waiting = new ArrayDeque<>();
    private final Map<String, Integer> activeUsers = new HashMap<>();
    private final Set<String> conversations = new HashSet<>();
    private int active;

    @PostConstruct
    void validate() {
        if (maxActive < 1
                || maxPerUser < 1
                || maxWaiting < 0
                || maxWaitingPerUser < 0
                || waitSeconds < 1
                || executionSeconds < 1)
            throw new IllegalArgumentException("Invalid chat capacity");
    }

    public <T> Flux<T> execute(
            String owner, String conversation, Supplier<Flux<T>> work, Function<String, T> status) {
        return Flux.defer(
                () -> {
                    Lease lease = request(owner, conversation);
                    Flux<T> queued =
                            lease.queued && status != null
                                    ? Flux.just(status.apply("queued"))
                                    : Flux.empty();
                    return queued.concatWith(
                                    lease.ready
                                            .asMono()
                                            .timeout(
                                                    Duration.ofSeconds(waitSeconds),
                                                    Mono.error(new BusyException("排队等待超时，请稍后重新提问")))
                                            .publishOn(Schedulers.boundedElastic())
                                            .flatMapMany(
                                                    acquired -> {
                                                        Flux<T> started =
                                                                status == null
                                                                        ? Flux.empty()
                                                                        : Flux.just(
                                                                                status.apply(
                                                                                        "running"));
                                                        return started.concatWith(
                                                                Flux.defer(work)
                                                                        .takeUntilOther(
                                                                                Mono.delay(
                                                                                                Duration
                                                                                                        .ofSeconds(
                                                                                                                executionSeconds))
                                                                                        .flatMap(
                                                                                                ignored ->
                                                                                                        Mono
                                                                                                                .error(
                                                                                                                        new TimeoutException(
                                                                                                                                "本次问答执行超时，请缩小问题范围后重试"))))
                                                                        .contextWrite(
                                                                                context ->
                                                                                        context.put(
                                                                                                Lease
                                                                                                        .class,
                                                                                                acquired)));
                                                    }))
                            .doFinally(signal -> lease.close());
                });
    }

    synchronized Lease request(String owner, String conversation) {
        String key = owner + ":" + conversation;
        if (conversations.contains(key)) throw new BusyException("当前会话正在回答或正在停止，请稍后重试");
        Lease lease = new Lease(owner, key);
        if (active < maxActive && activeUsers.getOrDefault(owner, 0) < maxPerUser) {
            conversations.add(key);
            start(lease);
        } else {
            long userWaiting = waiting.stream().filter(item -> item.owner.equals(owner)).count();
            if (waiting.size() >= maxWaiting || userWaiting >= maxWaitingPerUser)
                throw new BusyException("当前问答繁忙，等待名额已满，请稍后再试");
            conversations.add(key);
            lease.queued = true;
            waiting.addLast(lease);
        }
        return lease;
    }

    private void start(Lease lease) {
        lease.state = 1;
        active++;
        activeUsers.merge(lease.owner, 1, Integer::sum);
        lease.ready.tryEmitValue(lease);
    }

    private void drain() {
        var iterator = waiting.iterator();
        while (active < maxActive && iterator.hasNext()) {
            Lease next = iterator.next();
            if (activeUsers.getOrDefault(next.owner, 0) >= maxPerUser) continue;
            iterator.remove();
            start(next);
        }
    }

    private void finish(Lease lease) {
        if (lease.state == 3) return;
        if (lease.state == 0) waiting.remove(lease);
        else {
            active--;
            activeUsers.computeIfPresent(
                    lease.owner, (owner, count) -> count == 1 ? null : count - 1);
        }
        lease.state = 3;
        conversations.remove(lease.key);
        drain();
    }

    public final class Lease implements AutoCloseable {
        private final String owner;
        private final String key;
        private final Sinks.One<Lease> ready = Sinks.one();
        private boolean queued;
        private int state;
        private int operations;

        private Lease(String owner, String key) {
            this.owner = owner;
            this.key = key;
        }

        /** A cancelled HTTP request retains its budget until its real Python operation exits. */
        public AutoCloseable operation() {
            synchronized (ChatCapacity.this) {
                if (state != 1) throw new java.util.concurrent.CancellationException("本次问答已停止");
                operations++;
            }
            var closed = new java.util.concurrent.atomic.AtomicBoolean();
            return () -> {
                synchronized (ChatCapacity.this) {
                    if (!closed.compareAndSet(false, true)) return;
                    operations--;
                    if (state == 2 && operations == 0) finish(this);
                }
            };
        }

        @Override
        public void close() {
            synchronized (ChatCapacity.this) {
                if (state == 3 || state == 2) return;
                if (state == 1 && operations > 0) state = 2;
                else finish(this);
            }
        }
    }

    synchronized int activeCount() {
        return active;
    }

    synchronized int waitingCount() {
        return waiting.size();
    }
}
