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
package io.agentscope.examples.dataanalysis.service;

import io.agentscope.core.plan.PlanNotebook;
import io.agentscope.examples.dataanalysis.dto.PlanResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

/**
 * Service for managing the PlanNotebook and broadcasting plan changes via SSE.
 */
@Service
public class AnalysisPlanService {

    private static final Logger log = LoggerFactory.getLogger(AnalysisPlanService.class);

    private final Sinks.Many<PlanResponse> planSink =
            Sinks.many().multicast().onBackpressureBuffer();

    private PlanNotebook planNotebook;

    /**
     * Once the user confirms (executes) or declines, suppress further needConfirm pushes
     * until a brand-new plan is created (tracked by plan name).
     */
    private volatile boolean confirmedByUser = false;

    /** Name of the plan for which confirmation was last shown, to detect new plans. */
    private volatile String lastConfirmedPlanName = null;

    public void setPlanNotebook(PlanNotebook planNotebook) {
        this.planNotebook = planNotebook;
    }

    public PlanNotebook getPlanNotebook() {
        return planNotebook;
    }

    /**
     * SSE stream – sends the current plan state immediately, then pushes each subsequent update.
     */
    public Flux<PlanResponse> getPlanStream() {
        return Flux.concat(
                Mono.fromCallable(
                        () -> {
                            PlanResponse current = getCurrentPlan();
                            return current != null ? current : new PlanResponse();
                        }),
                planSink.asFlux());
    }

    /**
     * Push the latest plan state to all SSE subscribers.
     * Sets needConfirm=true when the plan was just created (all subtasks TODO,
     * none in_progress/done) so the frontend can render confirm buttons without
     * relying on the LLM outputting a special token.
     *
     * <p>Once the user has confirmed or declined (confirmedByUser=true), needConfirm is
     * suppressed for the current plan to prevent the buttons from reappearing during
     * subsequent LLM turns that process the "不执行" reply.
     * The flag resets automatically when a brand-new plan (different name) is detected.
     */
    public void broadcastPlanChange() {
        PlanResponse response = getCurrentPlan();
        if (response == null) {
            response = new PlanResponse();
        } else {
            // Detect "plan just created, waiting for user confirmation" state
            boolean allTodo =
                    response.getSubtasks() != null
                            && !response.getSubtasks().isEmpty()
                            && response.getSubtasks().stream()
                                    .allMatch(s -> "todo".equals(s.getState()));
            boolean notebookNeedsConfirm = planNotebook != null && planNotebook.isNeedUserConfirm();

            // If a new plan has been created (different name), reset the confirmed flag
            String planName = response.getName();
            if (planName != null && !planName.equals(lastConfirmedPlanName)) {
                confirmedByUser = false;
                // Only update lastConfirmedPlanName once we show confirm (below)
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
                confirmedByUser = true;
                lastConfirmedPlanName = planName;
            }

            boolean shouldShowConfirm = allTodo && notebookNeedsConfirm && !confirmedByUser;
            if (shouldShowConfirm) {
                // Mark that we've shown confirm for this plan name
                lastConfirmedPlanName = planName;
            }
            response.setNeedConfirm(shouldShowConfirm);
        }
        planSink.tryEmitNext(response);
        log.debug(
                "Plan broadcast: {}, needConfirm={}, confirmedByUser={}",
                response.getName() != null ? response.getName() : "(empty)",
                response.isNeedConfirm(),
                confirmedByUser);
    }

    /**
     * Called when the user explicitly confirms or declines plan execution.
     * Suppresses needConfirm for the current plan going forward.
     */
    public void markUserConfirmed() {
        confirmedByUser = true;
        if (planNotebook != null && planNotebook.getCurrentPlan() != null) {
            lastConfirmedPlanName = planNotebook.getCurrentPlan().getName();
        }
    }

    /**
     * Snapshot of the current plan state.
     */
    public PlanResponse getCurrentPlan() {
        if (planNotebook == null) {
            return null;
        }
        return PlanResponse.fromPlan(planNotebook.getCurrentPlan());
    }
}
