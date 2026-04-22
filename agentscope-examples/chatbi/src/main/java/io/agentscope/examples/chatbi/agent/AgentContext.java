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
package io.agentscope.examples.chatbi.agent;

import io.agentscope.core.model.OpenAIChatModel;

/**
 * Immutable per-session context passed to every {@link SubAgentFactory}.
 *
 * <p>Carries all session-scoped values that sub-Agents need at creation time:
 * the streaming/rewrite model instances, the SuperSonic token, report/dashboard IDs,
 * chart parameter, and the session identifier for log tagging.
 *
 * @param sessionId       Session identifier (used in log hooks).
 * @param streamModel     Shared streaming model for all sub-Agents.
 * @param rewriteModel    Non-streaming model used by QueryRewriteHook and ConfluenceFilterTool.
 * @param supersonicToken SuperSonic auth token from the original chat request.
 * @param agentId         SuperSonic agent ID from the original chat request.
 * @param reportId        Report ID from the original chat request (used by ReportScheduleAgent).
 * @param dashboardId     Dashboard ID from the original chat request (used by ReportScheduleAgent).
 * @param chartParam      Chart/visualization param from the original chat request (used by DataInterpretTool).
 * @param projectId       Project / tenant ID from the original chat request (used by DataLineageTool).
 * @param easyBiSession   EasyBi session ID from the original chat request (used by DataLineageTool).
 */
public record AgentContext(
        String sessionId,
        OpenAIChatModel streamModel,
        OpenAIChatModel rewriteModel,
        String supersonicToken,
        String agentId,
        String reportId,
        String dashboardId,
        String chartParam,
        String projectId,
        String easyBiSession) {}
