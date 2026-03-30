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
 */
public class ConfirmPlanToHint implements PlanToHint {

    /** The token the frontend watches for. */
    public static final String CONFIRM_TOKEN = "[CONFIRM_PLAN]";

    /**
     * The keyword inside the system-hint that signals a confirmation request.
     * Matches the phrase DefaultPlanToHint emits when needUserConfirm=true and
     * the plan is at the beginning (all subtasks TODO).
     */
    private static final String CONFIRMATION_PHRASE = "WAIT FOR USER CONFIRMATION";

    private final DefaultPlanToHint delegate = new DefaultPlanToHint();

    @Override
    public String generateHint(Plan plan, PlanNotebook planNotebook) {
        String hint = delegate.generateHint(plan, planNotebook);
        if (hint == null) {
            return null;
        }
        // When the hint instructs the LLM to wait for user confirmation,
        // also tell the LLM to append [CONFIRM_PLAN] at the very end of its reply
        // so the frontend can detect it and render the action buttons.
        if (planNotebook.isNeedUserConfirm() && hint.contains(CONFIRMATION_PHRASE)) {
            // Insert instruction before the closing </system-hint> tag
            String instruction =
                    "- UI signal: append the exact token "
                            + CONFIRM_TOKEN
                            + " on its own line at the very end of your reply (no text after it)."
                            + " This token is for frontend rendering – do NOT explain it to the"
                            + " user.\n";
            hint = hint.replace("</system-hint>", instruction + "</system-hint>");
        }
        return hint;
    }
}
