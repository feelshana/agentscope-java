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
import io.agentscope.core.plan.hint.DefaultPlanToHint;
import io.agentscope.core.plan.hint.PlanToHint;
import io.agentscope.core.plan.model.Plan;

/**
 * A PlanToHint decorator for the data-analysis example.
 *
 * <h2>Design principle: trust the system prompt</h2>
 *
 * <p>When {@code plan == null} (no active plan), this class returns {@code null} unconditionally —
 * <b>no system-hint is injected at all</b>. The system prompt (prompt-V8.txt §2) already provides
 * comprehensive guidance on when to create a plan vs. respond directly, so an additional
 * system-hint would only add noise and risk misleading the LLM into calling {@code create_plan}
 * inappropriately.
 *
 * <h3>When {@code plan != null}</h3>
 *
 * <p>Delegates to {@link DefaultPlanToHint} for plan-execution guidance (current subtask,
 * next steps, etc.) and post-processes the hint to:
 * <ul>
 *   <li>Append {@code [CONFIRM_PLAN]} marker for frontend button rendering</li>
 *   <li>Adapt confirmation/rejection instructions for the UI button mode</li>
 *   <li>Strip confirmation instructions after the user has clicked "执行"</li>
 * </ul>
 */
public class ConfirmPlanToHint implements PlanToHint {

    /** The token the frontend watches for. */
    public static final String CONFIRM_TOKEN = "[CONFIRM_PLAN]";

    /** Keyword inside the system-hint that signals a confirmation request. */
    private static final String CONFIRMATION_PHRASE = "WAIT FOR USER CONFIRMATION";

    private final DefaultPlanToHint delegate = new DefaultPlanToHint();

    // ─────────────────── Confirmation state ───────────────────

    /**
     * Holds a reference to the last non-null Plan object seen by the change-hook.
     * Used to detect new plan creation and reset {@code userConfirmed}.
     */
    private volatile Plan lastSeenPlan = null;

    /**
     * Set to true when the user clicks the "执行" button in the UI. When true, confirmation-related
     * hints are stripped so the LLM starts executing immediately. Reset when a new plan is created
     * or the current plan finishes.
     */
    private volatile boolean userConfirmed = false;

    /** Name of the plan that the user has confirmed, for new-plan detection. */
    private volatile String confirmedPlanName = null;

    // ─────────────────── Registration ───────────────────

    /**
     * Register a change-hook on the given PlanNotebook to track plan lifecycle events. Detects new
     * plan creation (to reset {@code userConfirmed}) and plan finish (to clean up state).
     *
     * <p>Call this once, right after constructing the PlanNotebook.
     */
    public void registerWith(PlanNotebook planNotebook) {
        planNotebook.addChangeHook(
                "confirmPlanToHint_stateTracker",
                (nb, plan) -> {
                    if (plan != null) {
                        lastSeenPlan = plan;
                        // New plan created → user needs to confirm again
                        String currentPlanName = plan.getName();
                        if (currentPlanName != null && !currentPlanName.equals(confirmedPlanName)) {
                            userConfirmed = false;
                        }
                    } else {
                        // Plan finished (DONE or ABANDONED) → reset confirmation state
                        lastSeenPlan = null;
                        userConfirmed = false;
                        confirmedPlanName = null;
                    }
                });
    }

    /**
     * Called by AnalysisPlanService when the user clicks "执行" (Execute) button. Suppresses
     * confirmation-related hints for subsequent reasoning rounds.
     */
    public void setUserConfirmed(String planName) {
        this.userConfirmed = true;
        this.confirmedPlanName = planName;
    }

    // ─────────────────── Hint generation ───────────────────

    @Override
    public String generateHint(Plan plan, PlanNotebook planNotebook) {
        // ── No active plan → no hint ─────────────────────────────────────────
        // The system prompt (prompt-V8.txt) already guides the LLM on when to
        // call create_plan vs. respond directly. No system-hint needed.
        if (plan == null) {
            return null;
        }

        // ── Active plan → delegate to DefaultPlanToHint ──────────────────────
        String hint = delegate.generateHint(plan, planNotebook);
        if (hint == null) {
            return null;
        }

        // Replace English confirmation question with Chinese
        hint = hint.replace("Should I proceed with this plan?", "是否继续执行此计划？");

        // Remove the "implied-intent bypass" clause — in this UI, confirmation
        // is ALWAYS done via ✅/❌ buttons, never by message content.
        hint =
                hint.replace(
                        "- If user's request already implies execution intent (e.g., \"execute\","
                                + " \"execute the plan\"), proceed directly without asking\n",
                        "");

        // ── Strip rules not applicable to data-analysis UI ───────────────────
        // In this UI, users cannot modify plans directly. They can only click
        // "不执行" to abandon, then provide new instructions for a fresh plan.

        // Remove revise_current_plan options (appears in multiple templates)
        hint =
                hint.replace(
                        "- Revise the plan by calling 'revise_current_plan' if necessary.\n", "");
        hint =
                hint.replace(
                        "- If the first subtask is not executable, analyze why and what you can do"
                                + " to advance the plan, e.g. ask user for more information, revise"
                                + " the plan by calling 'revise_current_plan'.\n",
                        "");

        // Remove "unrelated to plan" option (IN_PROGRESS / NO_SUBTASK / AT_THE_END)
        hint =
                hint.replace(
                        "- If the user asks you to do something unrelated to the plan, "
                                + "prioritize the completion of user's query first, and then return"
                                + " to the plan afterward.\n",
                        "");

        // Remove RULE_COMMON rules that don't apply (users cannot modify plans externally)
        hint =
                hint.replace(
                        "- Update before processing each subtask: When processing each subtask,"
                            + " call get_subtask_count and view_subtasks to confirm the latest"
                            + " information: get_subtask_count is used to confirm the total number"
                            + " of subtasks to avoid omissions;view_subtasks is used to query"
                            + " subtask information, execute subtasks strictly according to the"
                            + " latest information, and pay attention to ignoring the original"
                            + " request.\n",
                        "");
        hint =
                hint.replace(
                        "- User May Modify Plan: Users can directly add, edit, or delete subtasks"
                                + " without going through you.\n",
                        "");
        hint =
                hint.replace(
                        "- Only focus on the current content: Always follow the latest plan"
                                + " content, especially when the original plan conflicts with the"
                                + " latest queried plan, follow the latest queried plan without"
                                + " considering the initial requirements.\n",
                        "");
        hint =
                hint.replace(
                        "- Do not modify plan: Do not modify or amend the plan without a clear"
                                + " plan modification instruction from user\n",
                        "");

        // ── User has already confirmed execution ─────────────────────────────
        if (userConfirmed) {
            // Remove the RULE_WAIT_FOR_CONFIRMATION block
            hint = hint.replaceAll("⚠️ WAIT FOR USER CONFIRMATION:\\n(-[^\\n]*\\n?)*", "");

            // Replace "unrelated/cancel" options with "start executing"
            hint =
                    hint.replace(
                            "- If the user asks you to do something unrelated to the plan,"
                                + " prioritize the completion of user's query first, and then"
                                + " return to the plan afterward.\n"
                                + "- If the user no longer wants to perform the current plan,"
                                + " confirm with the user and call the 'finish_plan' function.\n",
                            "- Start executing the first subtask by calling 'update_subtask_state'"
                                    + " with subtask_idx=0 and state='in_progress'.\n");

            // Clean up any remaining "不执行" instructions
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

        // ── Awaiting confirmation → adapt for UI button mode ─────────────────

        // Replace "unrelated/cancel" with "不执行 button → finish_plan"
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

        // Replace "respond accordingly" with "不执行 button → finish_plan"
        hint =
                hint.replace(
                        "- If user says anything else (questions, modifications, unrelated topics),"
                                + " respond accordingly but DO NOT start execution\n",
                        "- If user clicks '不执行' (Do Not Execute) button, call 'finish_plan'"
                                + " directly to terminate the plan\n");

        // Append [CONFIRM_PLAN] token for frontend button rendering
        if (planNotebook.isNeedUserConfirm() && hint.contains(CONFIRMATION_PHRASE)) {
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
