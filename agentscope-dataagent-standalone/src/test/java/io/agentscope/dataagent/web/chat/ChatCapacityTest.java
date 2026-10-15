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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

class ChatCapacityTest {
    @Test
    void admitsSixteenActualUsersAndBoundsQueue() {
        ChatCapacity capacity = new ChatCapacity();
        List<ChatCapacity.Lease> leases = new ArrayList<>();
        for (int i = 0; i < 32; i++) leases.add(capacity.request("user" + i, "chat"));
        assertThat(capacity.activeCount()).isEqualTo(16);
        assertThat(capacity.waitingCount()).isEqualTo(16);
        assertThatThrownBy(() -> capacity.request("overflow", "chat")).hasMessageContaining("已满");
        leases.forEach(ChatCapacity.Lease::close);
        assertThat(capacity.activeCount()).isZero();
        assertThat(capacity.waitingCount()).isZero();
    }

    @Test
    void preventsOneUserAndOneConversationFromTakingAllSlots() {
        ChatCapacity capacity = new ChatCapacity();
        var a = capacity.request("a", "one");
        var b = capacity.request("a", "two");
        var c = capacity.request("a", "three");
        assertThat(capacity.activeCount()).isEqualTo(2);
        assertThat(capacity.waitingCount()).isEqualTo(1);
        assertThatThrownBy(() -> capacity.request("a", "one")).hasMessageContaining("当前会话");
        assertThatThrownBy(() -> capacity.request("a", "four")).hasMessageContaining("已满");
        var other = capacity.request("b", "one");
        assertThat(capacity.activeCount()).isEqualTo(3);
        a.close();
        assertThat(capacity.waitingCount()).isZero();
        b.close();
        c.close();
        other.close();
        assertThat(capacity.activeCount()).isZero();
    }

    @Test
    void cancelledPythonRetainsBudgetUntilRealOperationEnds() throws Exception {
        ChatCapacity capacity = new ChatCapacity();
        var lease = capacity.request("a", "one");
        var operation = lease.operation();
        lease.close();
        assertThat(capacity.activeCount()).isEqualTo(1);
        assertThatThrownBy(() -> capacity.request("a", "one")).hasMessageContaining("正在停止");
        assertThatThrownBy(lease::operation)
                .isInstanceOf(java.util.concurrent.CancellationException.class);
        operation.close();
        operation.close();
        lease.close();
        assertThat(capacity.activeCount()).isZero();
        capacity.request("a", "one").close();
    }

    @Test
    void waitingCancelNeverStartsWorkAndFreesConversation() {
        ChatCapacity capacity = new ChatCapacity();
        ReflectionTestUtils.setField(capacity, "maxActive", 1);
        var held = capacity.request("a", "one");
        StepVerifier.create(capacity.execute("b", "one", () -> Flux.just("answer"), state -> state))
                .expectNext("queued")
                .thenCancel()
                .verify();
        assertThat(capacity.waitingCount()).isZero();
        held.close();
        capacity.request("b", "one").close();
    }

    @Test
    void queueTimeoutDoesNotConsumeExecutionBudget() {
        ChatCapacity capacity = new ChatCapacity();
        ReflectionTestUtils.setField(capacity, "maxActive", 1);
        var held = capacity.request("a", "one");
        StepVerifier.withVirtualTime(
                        () ->
                                capacity.execute(
                                        "b", "one", () -> Flux.just("answer"), state -> state))
                .expectNext("queued")
                .thenAwait(Duration.ofSeconds(31))
                .expectErrorMessage("排队等待超时，请稍后重新提问")
                .verify();
        assertThat(capacity.waitingCount()).isZero();
        held.close();
    }

    @Test
    void executionDeadlineEndsWorkAndReleasesBudget() {
        ChatCapacity capacity = new ChatCapacity();
        ReflectionTestUtils.setField(capacity, "executionSeconds", 1);
        StepVerifier.create(capacity.execute("a", "one", Flux::never, state -> state))
                .expectNext("running")
                .expectErrorMessage("本次问答执行超时，请缩小问题范围后重试")
                .verify(Duration.ofSeconds(5));
        capacity.request("a", "two").close();
    }

    @Test
    void racingSubscriptionAndCancelLeaveNoSlotsOrConversationKeys() throws Exception {
        ChatCapacity capacity = new ChatCapacity();
        var executor = java.util.concurrent.Executors.newFixedThreadPool(8);
        try {
            for (int round = 0; round < 20; round++) {
                var subscriptions =
                        new java.util.concurrent.CopyOnWriteArrayList<reactor.core.Disposable>();
                var futures = new ArrayList<java.util.concurrent.Future<?>>();
                for (int index = 0; index < 50; index++) {
                    String user = "race" + index;
                    futures.add(
                            executor.submit(
                                    () ->
                                            subscriptions.add(
                                                    capacity.execute(
                                                                    user,
                                                                    "same",
                                                                    Flux::never,
                                                                    state -> state)
                                                            .subscribe(value -> {}, error -> {}))));
                }
                for (var future : futures) future.get(5, java.util.concurrent.TimeUnit.SECONDS);
                futures.clear();
                for (var subscription : subscriptions)
                    futures.add(executor.submit(subscription::dispose));
                for (var future : futures) future.get(5, java.util.concurrent.TimeUnit.SECONDS);
                assertThat(capacity.activeCount()).isZero();
                assertThat(capacity.waitingCount()).isZero();
            }
        } finally {
            executor.shutdownNow();
        }
    }
}
