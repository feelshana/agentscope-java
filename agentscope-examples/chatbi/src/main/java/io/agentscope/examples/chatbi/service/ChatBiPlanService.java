/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.examples.chatbi.service;

import io.agentscope.core.plan.PlanNotebook;
import io.agentscope.examples.chatbi.dto.PlanResponse;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

/**
 * Service for managing per-session PlanNotebook instances and broadcasting plan changes via SSE.
 *
 * <p>Since ChatBI has multiple sessions, each with its own DataQueryAgent and PlanNotebook,
 * this service maintains a map of sessionId -> PlanNotebook references.
 */
@Service
public class ChatBiPlanService {

    private static final Logger log = LoggerFactory.getLogger(ChatBiPlanService.class);

    private final Map<String, PlanNotebook> planNotebooks = new ConcurrentHashMap<>();
    private final Map<String, Sinks.Many<PlanResponse>> planSinks = new ConcurrentHashMap<>();
    private final Map<String, Boolean> confirmedByUser = new ConcurrentHashMap<>();
    private final Map<String, String> lastConfirmedPlanName = new ConcurrentHashMap<>();

    /** Tracks whether execution is paused waiting for user confirmation. */
    private final Map<String, Boolean> waitingForUser = new ConcurrentHashMap<>();

    /**
     * Register a PlanNotebook for a session.
     */
    public void registerPlanNotebook(String sessionId, PlanNotebook planNotebook) {
        planNotebooks.put(sessionId, planNotebook);
        planSinks.put(sessionId, Sinks.many().multicast().onBackpressureBuffer());
        confirmedByUser.put(sessionId, false);
        lastConfirmedPlanName.put(sessionId, ""); // ConcurrentHashMap does not allow null values
        waitingForUser.put(sessionId, false);
        log.info("Registered PlanNotebook for session={}", sessionId);
    }

    /**
     * Unregister a PlanNotebook when session is evicted.
     */
    public void unregisterPlanNotebook(String sessionId) {
        planNotebooks.remove(sessionId);
        Sinks.Many<PlanResponse> sink = planSinks.remove(sessionId);
        if (sink != null) {
            sink.tryEmitComplete();
        }
        confirmedByUser.remove(sessionId);
        lastConfirmedPlanName.remove(sessionId);
        waitingForUser.remove(sessionId);
        log.info("Unregistered PlanNotebook for session={}", sessionId);
    }

    /**
     * Get the PlanNotebook for a session.
     */
    public PlanNotebook getPlanNotebook(String sessionId) {
        return planNotebooks.get(sessionId);
    }

    /**
     * SSE stream – sends the current plan state immediately, then pushes each subsequent update.
     */
    public Flux<PlanResponse> getPlanStream(String sessionId) {
        Sinks.Many<PlanResponse> sink = planSinks.get(sessionId);
        if (sink == null) {
            return Flux.empty();
        }
        // Heartbeat carries the current waiting-for-user state so the frontend
        // always knows whether to show the waiting indicator.
        Flux<PlanResponse> heartbeat =
                Flux.interval(Duration.ofSeconds(25))
                        .map(
                                tick -> {
                                    PlanResponse hb = new PlanResponse();
                                    hb.setWaitingForUser(
                                            waitingForUser.getOrDefault(sessionId, false));
                                    return hb;
                                });
        return Flux.concat(
                Mono.fromCallable(
                        () -> {
                            PlanResponse current = getCurrentPlan(sessionId);
                            return current != null ? current : new PlanResponse();
                        }),
                Flux.merge(sink.asFlux(), heartbeat));
    }

    /**
     * Push the latest plan state to all SSE subscribers for a session.
     */
    public void broadcastPlanChange(String sessionId) {
        Sinks.Many<PlanResponse> sink = planSinks.get(sessionId);
        PlanNotebook planNotebook = planNotebooks.get(sessionId);
        if (sink == null || planNotebook == null) {
            return;
        }

        PlanResponse response = getCurrentPlan(sessionId);
        if (response == null) {
            response = new PlanResponse();
        } else {
            // Detect "plan just created, waiting for user confirmation" state
            boolean allTodo =
                    response.getSubtasks() != null
                            && !response.getSubtasks().isEmpty()
                            && response.getSubtasks().stream()
                                    .allMatch(s -> "todo".equals(s.getState()));
            boolean notebookNeedsConfirm = planNotebook.isNeedUserConfirm();

            // If a new plan has been created (different name), reset the confirmed flag
            String planName = response.getName();
            String lastPlanName = lastConfirmedPlanName.getOrDefault(sessionId, "");
            if (planName != null && !planName.equals(lastPlanName)) {
                confirmedByUser.put(sessionId, false);
            }

            // Once any subtask moves to in_progress/done, the user confirmed execution
            boolean anyStarted =
                    response.getSubtasks() != null
                            && response.getSubtasks().stream()
                                    .anyMatch(
                                            s ->
                                                    "in_progress".equals(s.getState())
                                                            || "done".equals(s.getState()));
            if (anyStarted) {
                confirmedByUser.put(sessionId, true);
                lastConfirmedPlanName.put(sessionId, planName != null ? planName : "");
            }

            Boolean confirmed = confirmedByUser.getOrDefault(sessionId, false);
            boolean shouldShowConfirm = allTodo && notebookNeedsConfirm && !confirmed;
            if (shouldShowConfirm) {
                lastConfirmedPlanName.put(sessionId, planName != null ? planName : "");
                waitingForUser.put(sessionId, true);
            } else if (anyStarted || planName == null || planName.isBlank()) {
                waitingForUser.put(sessionId, false);
            }
            response.setNeedConfirm(shouldShowConfirm);
            response.setWaitingForUser(waitingForUser.getOrDefault(sessionId, false));
        }
        sink.tryEmitNext(response);
        log.debug(
                "Plan broadcast for session={}: {}, needConfirm={}, waitingForUser={}",
                sessionId,
                response.getName() != null ? response.getName() : "(empty)",
                response.isNeedConfirm(),
                response.isWaitingForUser());
    }

    /**
     * Called when the user explicitly confirms plan execution.
     */
    public void markUserConfirmed(String sessionId) {
        confirmedByUser.put(sessionId, true);
        waitingForUser.put(sessionId, false);
        PlanNotebook planNotebook = planNotebooks.get(sessionId);
        if (planNotebook != null && planNotebook.getCurrentPlan() != null) {
            String planName = planNotebook.getCurrentPlan().getName();
            lastConfirmedPlanName.put(sessionId, planName != null ? planName : "");
        }
    }

    /**
     * Called when the user clicks "不执行" (decline execution).
     */
    public Mono<Void> abandonPlan(String sessionId) {
        markUserConfirmed(sessionId);
        PlanNotebook planNotebook = planNotebooks.get(sessionId);
        if (planNotebook == null || planNotebook.getCurrentPlan() == null) {
            return Mono.empty();
        }
        return planNotebook.finishPlan("abandoned", "User declined execution via UI button").then();
    }

    /**
     * Snapshot of the current plan state for a session.
     */
    public PlanResponse getCurrentPlan(String sessionId) {
        PlanNotebook planNotebook = planNotebooks.get(sessionId);
        if (planNotebook == null) {
            return null;
        }
        return PlanResponse.fromPlan(planNotebook.getCurrentPlan());
    }
}
