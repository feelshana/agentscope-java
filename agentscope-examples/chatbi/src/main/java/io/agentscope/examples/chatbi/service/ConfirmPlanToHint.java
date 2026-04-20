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

import io.agentscope.core.hook.Hook;
import io.agentscope.core.hook.HookEvent;
import io.agentscope.core.hook.PreReasoningEvent;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.plan.PlanNotebook;
import io.agentscope.core.plan.hint.DefaultPlanToHint;
import io.agentscope.core.plan.hint.PlanToHint;
import io.agentscope.core.plan.model.Plan;
import io.agentscope.core.plan.model.PlanState;
import reactor.core.publisher.Mono;

/**
 * A PlanToHint decorator for the ChatBI example that appends a
 * {@code [CONFIRM_PLAN]} marker to the hint whenever the LLM is expected to
 * ask the user for execution confirmation.
 *
 * <p>The marker is detected by the frontend to render ✅执行 / ❌不执行 action
 * buttons directly in the chat bubble, avoiding the need for the user to type
 * a reply manually.
 *
 * <p>This class delegates all hint generation to {@link DefaultPlanToHint} and
 * only post-processes the result, so the core library remains untouched.
 *
 * <p>Additionally, when the user declines to execute a plan (i.e. the plan is
 * finished with state {@code abandoned}), this class suppresses the next
 * "no plan" system-hint so the LLM is not prompted to create a new plan
 * immediately after the user's rejection.
 */
public class ConfirmPlanToHint implements PlanToHint, Hook {

    /** The token the frontend watches for. */
    public static final String CONFIRM_TOKEN = "[CONFIRM_PLAN]";

    /**
     * The keyword inside the system-hint that signals a confirmation request.
     * Matches the phrase DefaultPlanToHint emits when needUserConfirm=true and
     * the plan is at the beginning (all subtasks TODO).
     */
    private static final String CONFIRMATION_PHRASE = "WAIT FOR USER CONFIRMATION";

    private final DefaultPlanToHint delegate = new DefaultPlanToHint();

    /**
     * Tracks whether at least one tool result has appeared in the LLM context.
     *
     * <p>Updated in {@link #onEvent} before each reasoning step. When this is
     * {@code false} (i.e. the very first iteration, before any tool has been
     * called), the "no plan" system-hint is suppressed so the LLM is not
     * prompted to create a plan before it has had a chance to call
     * {@code list_datasets}.
     */
    private volatile boolean hasToolResult = false;

    /**
     * Set to true after a plan is finished with state ABANDONED.
     * The next call to generateHint(null, ...) will return null (suppress
     * the "no plan" hint) and then reset this flag.
     */
    private volatile boolean lastPlanWasAbandoned = false;

    /**
     * Set to true after a plan is finished with state DONE.
     * The next call to generateHint(null, ...) will return null (suppress
     * the "no plan" hint) so the LLM is free to generate the final report
     * without being prompted to create a new plan immediately.
     */
    private volatile boolean lastPlanWasDone = false;

    /**
     * Holds a reference to the last non-null Plan object seen by the change-hook.
     *
     * <p>When {@code finishPlan} is called, it mutates the Plan object's state
     * (e.g. to ABANDONED) and <em>then</em> sets {@code currentPlan = null} before
     * firing the hooks.  Because we still hold a reference here, we can read the
     * final state of the plan even after {@code currentPlan} has been cleared.
     */
    private volatile Plan lastSeenPlan = null;

    /**
     * Register a change-hook on the given PlanNotebook so we can automatically
     * detect when a plan is finished as ABANDONED.
     *
     * <p>Strategy: {@code finishPlan} mutates the Plan object's state to ABANDONED
     * and <em>then</em> sets {@code currentPlan = null} before triggering hooks.
     * By caching the Plan reference every time the hook fires with a non-null plan,
     * we retain access to the (now-mutated) Plan object when the hook fires next
     * with {@code plan == null}, allowing us to read its final state.
     *
     * <p>Call this once, right after constructing the PlanNotebook.
     */
    public void registerWith(PlanNotebook planNotebook) {
        planNotebook.addChangeHook(
                "confirmPlanToHint_abandonedDetector",
                (nb, plan) -> {
                    if (plan != null) {
                        // Keep a reference to the live Plan object.
                        // finishPlan will mutate its state before clearing currentPlan.
                        lastSeenPlan = plan;
                    } else {
                        // plan became null: finishPlan was just called.
                        // The Plan object referenced by lastSeenPlan already has its
                        // final state (DONE or ABANDONED) set by finishPlan.
                        if (lastSeenPlan != null) {
                            if (PlanState.ABANDONED.equals(lastSeenPlan.getState())) {
                                lastPlanWasAbandoned = true;
                            } else if (PlanState.DONE.equals(lastSeenPlan.getState())) {
                                lastPlanWasDone = true;
                            }
                        }
                        lastSeenPlan = null;
                    }
                });
    }

    /**
     * Hook callback: runs before every reasoning step.
     *
     * <p>Scans the incoming message list for any TOOL-role message containing a
     * {@link ToolResultBlock}.  Once found, {@code hasToolResult} is set to
     * {@code true} for the remainder of the session, meaning the LLM has
     * already performed at least one tool call and is ready for plan-related hints.
     *
     * <p>This hook must execute <em>before</em> the plan-hint hook so that the
     * flag is up-to-date when {@link #generateHint} is called.  Register this
     * instance with a priority lower than 100 (the default) — e.g. 50.
     */
    @Override
    @SuppressWarnings("unchecked")
    public <T extends HookEvent> Mono<T> onEvent(T event) {
        if (!hasToolResult && event instanceof PreReasoningEvent pre) {
            boolean found =
                    pre.getInputMessages().stream()
                            .filter(m -> MsgRole.TOOL.equals(m.getRole()))
                            .anyMatch(m -> !m.getContentBlocks(ToolResultBlock.class).isEmpty());
            if (found) {
                hasToolResult = true;
            }
        }
        return Mono.just(event);
    }

    /** Execute before the default plan-hint hook (priority 100). */
    @Override
    public int priority() {
        return 50;
    }

    @Override
    public String generateHint(Plan plan, PlanNotebook planNotebook) {
        // ── First-iteration guard ────────────────────────────────────────────
        // Suppress the "no plan" hint on the very first iteration (before any
        // tool has been called). This prevents the LLM from being prompted to
        // create a plan before it has had a chance to call list_datasets.
        if (plan == null && !hasToolResult) {
            return null;
        }
        // ── Abandoned-plan guard ─────────────────────────────────────────────
        // If the previous plan was abandoned and there is now no active plan,
        // suppress the default "no plan" hint to prevent the LLM from
        // immediately creating a new plan after the user's rejection.
        if (plan == null && lastPlanWasAbandoned) {
            lastPlanWasAbandoned = false; // consume the flag
            return null;
        }
        // ── Done-plan guard ──────────────────────────────────────────────────
        // If the previous plan finished normally (DONE) and there is now no
        // active plan, suppress the "no plan" hint so the LLM can freely
        // generate the final analysis report without being pushed to create
        // another plan immediately.
        if (plan == null && lastPlanWasDone) {
            lastPlanWasDone = false; // consume the flag
            return null;
        }
        // ─────────────────────────────────────────────────────────────────────

        String hint = delegate.generateHint(plan, planNotebook);
        if (hint == null) {
            return null;
        }
        // When the hint instructs the LLM to wait for user confirmation,
        // also tell the LLM to append [CONFIRM_PLAN] at the very end of its reply
        // so the frontend can detect it and render the action buttons.
        // Replace English confirmation question with Chinese
        hint = hint.replace("Should I proceed with this plan?", "是否继续执行此计划？");

        // Remove the "implied-intent bypass" clause that allows the LLM to skip
        // confirmation when the user's message contains words like "execute".
        // In this UI, confirmation is ALWAYS done via the ✅/❌ buttons – the LLM
        // must never self-authorize execution based on message content alone.
        hint =
                hint.replace(
                        "- If user's request already implies execution intent (e.g., \"execute\","
                                + " \"execute the plan\"), proceed directly without asking\n",
                        "");

        if (planNotebook.isNeedUserConfirm() && hint.contains(CONFIRMATION_PHRASE)) {
            // Insert instruction before the closing </system-hint> tag
            String instruction =
                    "- UI signal: append the exact token "
                            + CONFIRM_TOKEN
                            + " on its own line at the very end of your reply (no text after it)."
                            + " This token is for frontend rendering – do NOT explain it to the"
                            + " user.\n"
                            + "- CRITICAL: You MUST wait for the user to click the confirmation"
                            + " button. Do NOT start execution based on any previous message or"
                            + " implied intent. Only the explicit '执行' response from the UI"
                            + " button authorizes execution.\n";
            hint = hint.replace("</system-hint>", instruction + "</system-hint>");
        }
        return hint;
    }
}
