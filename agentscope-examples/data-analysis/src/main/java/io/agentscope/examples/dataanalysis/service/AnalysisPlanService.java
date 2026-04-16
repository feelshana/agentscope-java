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
import io.agentscope.core.plan.model.Plan;
import io.agentscope.core.plan.model.SubTask;
import io.agentscope.core.plan.model.SubTaskState;
import io.agentscope.examples.dataanalysis.dto.PlanResponse;
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
 * Service for managing per-session PlanNotebook and broadcasting plan changes via SSE.
 */
@Service
public class AnalysisPlanService {

    private static final Logger log = LoggerFactory.getLogger(AnalysisPlanService.class);

    private final Map<String, SessionPlanState> sessionStates = new ConcurrentHashMap<>();

    private SessionPlanState getOrCreateState(String sessionId) {
        return sessionStates.computeIfAbsent(
                normalizeSessionId(sessionId), key -> new SessionPlanState());
    }

    private String normalizeSessionId(String sessionId) {
        return (sessionId == null || sessionId.isBlank()) ? "default" : sessionId;
    }

    public void registerPlanNotebook(String sessionId, PlanNotebook planNotebook) {
        registerPlanNotebook(sessionId, planNotebook, null);
    }

    /**
     * Register a PlanNotebook and its associated ConfirmPlanToHint for a session.
     *
     * @param sessionId the session ID
     * @param planNotebook the PlanNotebook instance
     * @param confirmPlanToHint the ConfirmPlanToHint instance (may be null for non-UI sessions)
     */
    public void registerPlanNotebook(
            String sessionId, PlanNotebook planNotebook, ConfirmPlanToHint confirmPlanToHint) {
        SessionPlanState state = getOrCreateState(sessionId);
        state.planNotebook = planNotebook;
        state.confirmPlanToHint = confirmPlanToHint;
        state.confirmedByUser = false;
        state.lastConfirmedPlanName = null;
    }

    public PlanNotebook getPlanNotebook(String sessionId) {
        return getOrCreateState(sessionId).planNotebook;
    }

    public void clearSession(String sessionId) {
        sessionStates.remove(normalizeSessionId(sessionId));
    }

    /**
     * SSE stream – sends the current plan state immediately, then pushes each subsequent update.
     * A heartbeat (empty PlanResponse) is sent every 25 seconds to keep the connection alive
     * and prevent idle timeout disconnections.
     */
    public Flux<PlanResponse> getPlanStream(String sessionId) {
        SessionPlanState state = getOrCreateState(sessionId);
        Flux<PlanResponse> heartbeat =
                Flux.interval(Duration.ofSeconds(25)).map(tick -> new PlanResponse());
        return Flux.concat(
                Mono.fromCallable(
                        () -> {
                            // Prefer lastRenderablePlan: it captures the final state even after
                            // finish_plan clears currentPlan (e.g. stream completed in background).
                            PlanResponse snap = state.lastRenderablePlan;
                            if (snap != null) return snap;
                            PlanResponse current = getCurrentPlan(sessionId);
                            return current != null ? current : new PlanResponse();
                        }),
                Flux.merge(state.planSink.asFlux(), heartbeat));
    }

    /**
     * Push the latest plan state to all SSE subscribers in the specified session.
     */
    public void broadcastPlanChange(String sessionId) {
        SessionPlanState state = getOrCreateState(sessionId);
        PlanResponse response =
                PlanResponse.fromPlan(
                        state.planNotebook != null ? state.planNotebook.getCurrentPlan() : null);

        if (response == null) {
            // PlanNotebook 在 finish_plan 后会清空 currentPlan。
            // 若前端因后台切换丢了“完成态”SSE，则这里回放最近一次可渲染快照，
            // 避免 UI 长期卡在旧的 in_progress。
            response = clonePlanResponse(state.lastRenderablePlan);
            if (response == null) {
                response = new PlanResponse();
            }
        } else {
            boolean allTodo =
                    response.getSubtasks() != null
                            && !response.getSubtasks().isEmpty()
                            && response.getSubtasks().stream()
                                    .allMatch(s -> "todo".equals(s.getState()));
            boolean notebookNeedsConfirm =
                    state.planNotebook != null && state.planNotebook.isNeedUserConfirm();

            String planName = response.getName();
            if (planName != null && !planName.equals(state.lastConfirmedPlanName)) {
                state.confirmedByUser = false;
            }

            boolean anyStarted =
                    response.getSubtasks() != null
                            && response.getSubtasks().stream()
                                    .anyMatch(
                                            s ->
                                                    "in_progress".equals(s.getState())
                                                            || "done".equals(s.getState()));
            if (anyStarted) {
                state.confirmedByUser = true;
                state.lastConfirmedPlanName = planName;
            }

            boolean shouldShowConfirm = allTodo && notebookNeedsConfirm && !state.confirmedByUser;
            if (shouldShowConfirm) {
                state.lastConfirmedPlanName = planName;
            }
            response.setNeedConfirm(shouldShowConfirm);
            state.lastRenderablePlan = clonePlanResponse(response);
        }

        state.planSink.tryEmitNext(response);
        log.debug(
                "Plan broadcast: session={}, plan={}, needConfirm={}, confirmedByUser={}",
                normalizeSessionId(sessionId),
                response.getName() != null ? response.getName() : "(empty)",
                response.isNeedConfirm(),
                state.confirmedByUser);
    }

    /**
     * Called when the user explicitly confirms or declines plan execution.
     * Suppresses needConfirm for the current plan in this session going forward.
     */
    public void markUserConfirmed(String sessionId) {
        SessionPlanState state = getOrCreateState(sessionId);
        state.confirmedByUser = true;
        if (state.planNotebook != null && state.planNotebook.getCurrentPlan() != null) {
            String planName = state.planNotebook.getCurrentPlan().getName();
            state.lastConfirmedPlanName = planName;
            // Also notify ConfirmPlanToHint so it can suppress confirmation hints
            if (state.confirmPlanToHint != null) {
                state.confirmPlanToHint.setUserConfirmed(planName);
            }
        }
    }

    /**
     * Called when the user clicks "不执行" (decline execution).
     *
     * @return Mono that completes after the plan has been abandoned
     */
    public Mono<Void> abandonPlan(String sessionId) {
        SessionPlanState state = getOrCreateState(sessionId);
        markUserConfirmed(sessionId);

        // Frontend should clear panel on explicit decline instead of replaying stale plan snapshot.
        state.lastRenderablePlan = null;
        PlanResponse abandonedSignal = new PlanResponse();
        abandonedSignal.setState("abandoned");
        abandonedSignal.setNeedConfirm(false);
        state.planSink.tryEmitNext(abandonedSignal);

        if (state.planNotebook == null || state.planNotebook.getCurrentPlan() == null) {
            return Mono.empty();
        }
        return state.planNotebook
                .finishPlan("abandoned", "User declined execution via UI button")
                .then();
    }

    /**
     * Snapshot of the current plan state in the specified session.
     */
    public PlanResponse getCurrentPlan(String sessionId) {
        SessionPlanState state = getOrCreateState(sessionId);
        if (state.planNotebook == null) {
            return clonePlanResponse(state.lastRenderablePlan);
        }

        PlanResponse current = PlanResponse.fromPlan(state.planNotebook.getCurrentPlan());
        if (current != null) {
            state.lastRenderablePlan = clonePlanResponse(current);
            return current;
        }
        return clonePlanResponse(state.lastRenderablePlan);
    }

    /**
     * Reconcile stale running plan after assistant turn completion.
     *
     * <p>In production we observed cases where the model narrates "子任务已完成" and outputs
     * a full report, but misses one or more plan tool calls (finish_subtask / finish_plan),
     * leaving the UI stuck on an in_progress subtask. This method applies a conservative
     * server-side convergence: only when a report marker is present, force unfinished
     * subtasks to DONE and finish the plan.
     */
    public void reconcilePlanAfterTurnComplete(String sessionId, String assistantReply) {
        if (assistantReply == null || assistantReply.isBlank()) {
            return;
        }

        String text = assistantReply.toLowerCase();
        boolean hasReportTag = text.contains("<report>") || text.contains("</report>");
        boolean hasReportKeyword =
                text.contains("分析报告")
                        || text.contains("报告如下")
                        || text.contains("结论")
                        || text.contains("总结")
                        || text.contains("report");
        boolean hasReportLikeStructure =
                text.contains("###") || text.contains("|---") || text.contains("```mermaid");
        boolean hasReport = hasReportTag || (hasReportKeyword && hasReportLikeStructure);
        if (!hasReport) {
            return;
        }

        SessionPlanState state = getOrCreateState(sessionId);
        PlanNotebook notebook = state.planNotebook;
        if (notebook == null) {
            return;
        }

        Plan current = notebook.getCurrentPlan();
        if (current == null) {
            return;
        }

        if (current.getSubtasks() != null) {
            for (SubTask subTask : current.getSubtasks()) {
                if (subTask.getState() == SubTaskState.TODO
                        || subTask.getState() == SubTaskState.IN_PROGRESS) {
                    String outcome = subTask.getOutcome();
                    if (outcome == null || outcome.isBlank()) {
                        outcome = "报告已输出，系统自动收敛为已完成";
                    }
                    subTask.finish(outcome);
                }
            }
        }

        PlanResponse finalSnapshot = PlanResponse.fromPlan(current);
        if (finalSnapshot != null) {
            finalSnapshot.setState("done");
            finalSnapshot.setNeedConfirm(false);
            state.lastRenderablePlan = clonePlanResponse(finalSnapshot);
            state.planSink.tryEmitNext(finalSnapshot);
        }

        try {
            notebook.finishPlan("done", "报告已输出，系统自动完成计划收尾").block();
        } catch (Exception e) {
            log.warn(
                    "Plan reconcile failed: session={}, error={}",
                    normalizeSessionId(sessionId),
                    e.getMessage());
            return;
        }

        if (state.lastRenderablePlan != null) {
            state.planSink.tryEmitNext(clonePlanResponse(state.lastRenderablePlan));
        }
        log.info("Plan reconciled on turn completion: session={}", normalizeSessionId(sessionId));
        broadcastPlanChange(sessionId);
    }

    private PlanResponse clonePlanResponse(PlanResponse src) {
        if (src == null) {
            return null;
        }
        PlanResponse copy = new PlanResponse();
        copy.setId(src.getId());
        copy.setName(src.getName());
        copy.setDescription(src.getDescription());
        copy.setExpectedOutcome(src.getExpectedOutcome());
        copy.setState(src.getState());
        copy.setCreatedAt(src.getCreatedAt());
        copy.setNeedConfirm(src.isNeedConfirm());

        if (src.getSubtasks() != null) {
            var subtasks =
                    new java.util.ArrayList<
                            io.agentscope.examples.dataanalysis.dto.SubTaskResponse>();
            for (var s : src.getSubtasks()) {
                io.agentscope.examples.dataanalysis.dto.SubTaskResponse c =
                        new io.agentscope.examples.dataanalysis.dto.SubTaskResponse();
                c.setIndex(s.getIndex());
                c.setName(s.getName());
                c.setDescription(s.getDescription());
                c.setExpectedOutcome(s.getExpectedOutcome());
                c.setState(s.getState());
                c.setOutcome(s.getOutcome());
                c.setCreatedAt(s.getCreatedAt());
                subtasks.add(c);
            }
            copy.setSubtasks(subtasks);
        }
        return copy;
    }

    private static final class SessionPlanState {
        // replay(1): new subscribers (e.g. after mobile background reconnect) immediately
        // receive the latest plan state instead of waiting for the next broadcast.
        private final Sinks.Many<PlanResponse> planSink = Sinks.many().replay().limit(1);
        private volatile PlanNotebook planNotebook;
        private volatile ConfirmPlanToHint confirmPlanToHint;
        private volatile boolean confirmedByUser = false;
        private volatile String lastConfirmedPlanName = null;
        private volatile PlanResponse lastRenderablePlan;
    }
}
