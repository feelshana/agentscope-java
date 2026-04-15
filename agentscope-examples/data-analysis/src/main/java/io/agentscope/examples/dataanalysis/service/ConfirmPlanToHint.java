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

import io.agentscope.core.hook.Hook;
import io.agentscope.core.hook.HookEvent;
import io.agentscope.core.hook.PreReasoningEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.plan.PlanNotebook;
import io.agentscope.core.plan.hint.DefaultPlanToHint;
import io.agentscope.core.plan.hint.PlanToHint;
import io.agentscope.core.plan.model.Plan;
import io.agentscope.core.plan.model.PlanState;
import reactor.core.publisher.Mono;

/**
 * A PlanToHint decorator for the data-analysis example that appends a
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
     * Suppresses all "no plan" hints until the next genuine user message arrives,
     * so the LLM can freely generate the final report (and call any follow-up
     * tools like load_skill_through_path) without being pushed to create a new plan.
     *
     * <p>Unlike lastPlanWasAbandoned, this flag is NOT consumed on first use.
     * It is reset only when a new user message is detected in onEvent(), ensuring
     * that multi-iteration tool calls (e.g. load_skill_through_path after finish_plan)
     * are all covered by the suppression window.
     */
    private volatile boolean lastPlanWasDone = false;

    /**
     * Tracks the last user message content seen, used to detect a new user turn
     * and reset the lastPlanWasDone suppression window.
     */
    private volatile String lastSeenUserContent = null;

    /**
     * Set to true when the most recent tool_result came from a skill auxiliary tool
     * (e.g. load_skill_through_path). In that case, the plan state has not changed,
     * so we suppress the redundant plan hint for this reasoning round.
     * Reset to false after generateHint() consumes it once.
     */
    private volatile boolean lastToolWasSkillLoad = false;

    /**
     * Prefix that identifies built-in skill tools registered by SkillBox.
     * Any tool whose name starts with this prefix is considered a skill auxiliary tool.
     */
    private static final String SKILL_TOOL_NAME = "load_skill_through_path";

    /**
     * Custom NO_PLAN hint for follow-up questions after a plan was done.
     * Instead of aggressively pushing the LLM to create a plan, this version
     * provides clear guidance on when to create a plan vs. respond directly.
     */
    private static final String FOLLOW_UP_NO_PLAN_HINT =
            "<system-hint>For the user's query:\n"
                + "- If it requires NEW data queries (e.g. \"analyze xxx trend\", \"compare xxx\"),"
                + " create a plan by calling 'create_plan'.\n"
                + "- If it only adjusts PREVIOUS results or asks for clarification (e.g. \"change"
                + " chart to pie\", \"explain this number\", \"what does XX mean\"), respond"
                + " directly WITHOUT creating a plan.\n"
                + "When in doubt, prefer direct response over plan creation.</system-hint>";

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
     * Set to true when the user clicks the "执行" (Execute) button in the UI.
     * When true, confirmation-related hints should be suppressed because the user
     * has already approved execution.
     *
     * <p>This is reset to false when a new plan is created (detected via change-hook).
     */
    private volatile boolean userConfirmed = false;

    /**
     * Name of the plan that the user has confirmed. Used to detect if a new plan
     * was created after confirmation (in which case we need to ask for confirmation
     * again).
     */
    private volatile String confirmedPlanName = null;

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

                        // Detect if a NEW plan was created (user needs to confirm again)
                        // This happens when the plan name changes
                        String currentPlanName = plan.getName();
                        if (currentPlanName != null && !currentPlanName.equals(confirmedPlanName)) {
                            // New plan created, reset confirmation state
                            userConfirmed = false;
                        }
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
                        // Also reset confirmation state when plan finishes
                        userConfirmed = false;
                        confirmedPlanName = null;
                    }
                });
    }

    /**
     * Called by AnalysisPlanService when the user clicks "执行" (Execute) button.
     * This suppresses confirmation-related hints for subsequent reasoning rounds.
     *
     * @param planName the name of the plan being confirmed
     */
    public void setUserConfirmed(String planName) {
        this.userConfirmed = true;
        this.confirmedPlanName = planName;
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
        if (event instanceof PreReasoningEvent pre) {
            // ── hasToolResult tracking ───────────────────────────────────────
            if (!hasToolResult) {
                boolean found =
                        pre.getInputMessages().stream()
                                .filter(m -> MsgRole.TOOL.equals(m.getRole()))
                                .anyMatch(
                                        m -> !m.getContentBlocks(ToolResultBlock.class).isEmpty());
                if (found) {
                    hasToolResult = true;
                }
            }
            // ── Skill-load tool detection ────────────────────────────────────
            // If the most recent tool_result comes from a skill auxiliary tool
            // (e.g. load_skill_through_path), the plan state has not changed.
            // Mark the flag so generateHint() can suppress the redundant hint
            // for this round, regardless of which skill was loaded.
            var msgs = pre.getInputMessages();
            lastToolWasSkillLoad = false;
            for (int i = msgs.size() - 1; i >= 0; i--) {
                Msg m = msgs.get(i);
                if (MsgRole.TOOL.equals(m.getRole())) {
                    boolean isSkillTool =
                            m.getContentBlocks(ToolResultBlock.class).stream()
                                    .anyMatch(
                                            r ->
                                                    r.getName() != null
                                                            && r.getName().equals(SKILL_TOOL_NAME));
                    if (isSkillTool) {
                        lastToolWasSkillLoad = true;
                    }
                    break;
                }
            }
            // ── New user turn detection ──────────────────────────────────────
            // When a new genuine user message arrives (not a system-hint injected
            // by PlanNotebook), reset the lastPlanWasDone suppression window so
            // the next conversation turn can get plan hints normally.
            if (lastPlanWasDone) {
                // Find the last user-role message in the input
                for (int i = msgs.size() - 1; i >= 0; i--) {
                    Msg m = msgs.get(i);
                    if (MsgRole.USER.equals(m.getRole())) {
                        String content = m.getContent() != null ? m.getContent().toString() : "";
                        // A system-hint message contains <system-hint> tag; skip it
                        if (!content.contains("<system-hint>")) {
                            // If this user message differs from the last seen one,
                            // it means a new user turn has started → reset the window
                            if (!content.equals(lastSeenUserContent)) {
                                lastSeenUserContent = content;
                                lastPlanWasDone = false;
                            }
                        }
                        break;
                    }
                }
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
        // ── Skill-load guard ─────────────────────────────────────────────────
        // If the last tool_result was from a skill auxiliary tool (e.g. any skill
        // loaded via load_skill_through_path), the plan state has not changed at
        // all — suppress the hint for this round to avoid redundant system-hints.
        // Consume the flag immediately so subsequent rounds are unaffected.
        if (lastToolWasSkillLoad) {
            lastToolWasSkillLoad = false;
            return null;
        }
        // ── First-iteration guard ────────────────────────────────────────────
        // Suppress the "no plan" hint on the very first iteration (before any
        // tool has been called). This prevents the LLM from being prompted to
        // create a plan before it has had a chance to process the user's query.
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
        // ── Has-tool-result guard ────────────────────────────────────────────
        // If there's no plan but at least one tool has been called, the LLM
        // is already processing the user's query (e.g. query_dataset for precise
        // data fetching, get_dataset_detail for metadata, or a follow-up after
        // a completed plan). Return a gentle hint instead of the aggressive
        // NO_PLAN to let the LLM decide whether a new plan is needed.
        // Note: lastPlanWasDone is implicitly covered here because it implies
        // hasToolResult == true (a plan was executed before being done).
        if (plan == null && hasToolResult) {
            return FOLLOW_UP_NO_PLAN_HINT;
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

        // ── User has already confirmed execution ──────────────────────────────
        // If the user clicked "执行" button, remove all confirmation-related hints
        // and instructions about what to do when user declines.
        if (userConfirmed) {
            // Remove the entire RULE_WAIT_FOR_CONFIRMATION block
            // This block starts with "⚠️ WAIT FOR USER CONFIRMATION:" and ends
            // before the next rule or the closing tag
            hint = hint.replaceAll("⚠️ WAIT FOR USER CONFIRMATION:\\n(-[^\\n]*\\n?)*", "");

            // Remove AT_THE_BEGINNING options about user declining/unrelated tasks
            // In confirmed mode, just tell LLM to start executing
            hint =
                    hint.replace(
                            "- If the user asks you to do something unrelated to the plan,"
                                + " prioritize the completion of user's query first, and then"
                                + " return to the plan afterward.\n"
                                + "- If the user no longer wants to perform the current plan,"
                                + " confirm with the user and call the 'finish_plan' function.\n",
                            "- Start executing the first subtask by calling 'update_subtask_state'"
                                    + " with subtask_idx=0 and state='in_progress'.\n");

            // Clean up any remaining confirmation-related instructions we added earlier
            hint =
                    hint.replace(
                            "- If the user clicks '不执行' (Do Not Execute) button, call 'finish_plan'"
                                + " directly to terminate the plan – do NOT ask for confirmation or"
                                + " continue with other tasks.\n",
                            "");
            hint =
                    hint.replace(
                            "- If user clicks '不执行' (Do Not Execute) button, call 'finish_plan'"
                                    + " directly to terminate the plan\n",
                            "");

            return hint;
        }

        // ── Fix AT_THE_BEGINNING for UI confirmation mode ─────────────────────
        // In this UI, "不执行" button click should directly call finish_plan,
        // not "complete user's query first and return to plan".
        hint =
                hint.replace(
                        "- If the user asks you to do something unrelated to the plan, prioritize"
                            + " the completion of user's query first, and then return to the plan"
                            + " afterward.\n"
                            + "- If the user no longer wants to perform the current plan, confirm"
                            + " with the user and call the 'finish_plan' function.\n",
                        "- If the user clicks '不执行' (Do Not Execute) button, call 'finish_plan'"
                                + " directly to terminate the plan – do NOT ask for confirmation or"
                                + " continue with other tasks.\n");

        // ── Fix RULE_WAIT_FOR_CONFIRMATION for UI confirmation mode ───────────
        // In this UI, "不执行" button click should directly call finish_plan,
        // not just "respond accordingly but DO NOT start execution".
        hint =
                hint.replace(
                        "- If user says anything else (questions, modifications, unrelated topics),"
                                + " respond accordingly but DO NOT start execution\n",
                        "- If user clicks '不执行' (Do Not Execute) button, call 'finish_plan'"
                                + " directly to terminate the plan\n");

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
