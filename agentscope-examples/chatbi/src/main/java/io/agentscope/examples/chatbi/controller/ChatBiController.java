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
package io.agentscope.examples.chatbi.controller;

import io.agentscope.examples.chatbi.dto.ChatMessageDto;
import io.agentscope.examples.chatbi.dto.ChatRequest;
import io.agentscope.examples.chatbi.dto.PlanResponse;
import io.agentscope.examples.chatbi.dto.SessionHistoryResponse;
import io.agentscope.examples.chatbi.service.ChatBiAgentService;
import io.agentscope.examples.chatbi.service.ChatBiPlanService;
import io.agentscope.examples.chatbi.service.ChatSessionService;
import java.util.List;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * REST + SSE controller for the ChatBI agent.
 *
 * <p>Endpoints:
 * <ul>
 *   <li>{@code POST /api/chatbi/chat}                   – stream chat response (SSE)</li>
 *   <li>{@code GET  /api/chatbi/sessions}               – list chat history sessions</li>
 *   <li>{@code GET  /api/chatbi/sessions/{id}/messages} – get session messages</li>
 *   <li>{@code DELETE /api/chatbi/sessions/{id}}        – delete a session</li>
 *   <li>{@code POST /api/chatbi/reset}                  – reset session agent</li>
 *   <li>{@code GET  /api/chatbi/health}                 – health check</li>
 *   <li>{@code GET  /api/chatbi/plan/{sessionId}/stream} – stream plan updates (SSE)</li>
 *   <li>{@code GET  /api/chatbi/plan/{sessionId}}       – current plan snapshot</li>
 *   <li>{@code POST /api/chatbi/plan/{sessionId}/confirm} – user confirms plan execution</li>
 *   <li>{@code POST /api/chatbi/plan/{sessionId}/abandon} – user declines plan execution</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/chatbi")
public class ChatBiController {

    private final ChatBiAgentService agentService;
    private final ChatSessionService chatSessionService;
    private final ChatBiPlanService planService;

    public ChatBiController(
            ChatBiAgentService agentService,
            ChatSessionService chatSessionService,
            ChatBiPlanService planService) {
        this.agentService = agentService;
        this.chatSessionService = chatSessionService;
        this.planService = planService;
    }

    /**
     * Main chat endpoint – streams the agent's reasoning and final answer via SSE.
     *
     * <p>The request body carries all context needed for tool injection:
     * sessionId, message, userName, reportId, dashboardId, agentId, supersonicToken, param.
     *
     * @param req the full chat request
     * @return SSE stream of text chunks
     */
    @PostMapping(path = "/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<Map<String, String>>> chat(@RequestBody ChatRequest req) {
        return agentService.chat(req).map(this::toSseEvent);
    }

    /**
     * Returns paginated chat session history for the sidebar, filtered by userName.
     */
    @GetMapping("/sessions")
    public List<SessionHistoryResponse> listSessions(
            @RequestParam(defaultValue = "") String userName) {
        return chatSessionService.listSessions(userName);
    }

    /**
     * Returns all messages of a given session for frontend history rendering.
     */
    @GetMapping("/sessions/{sessionId}/messages")
    public List<ChatMessageDto> getSessionMessages(@PathVariable String sessionId) {
        return chatSessionService.loadSessionMessagesAsDto(sessionId);
    }

    /**
     * Delete a session and all its messages.
     */
    @DeleteMapping("/sessions/{sessionId}")
    public Map<String, String> deleteSession(@PathVariable String sessionId) {
        agentService.reset(sessionId);
        chatSessionService.deleteSession(sessionId);
        return Map.of("status", "ok");
    }

    /**
     * Reset a session's Agent (evict from memory, keep DB history).
     */
    @PostMapping("/reset")
    public Map<String, String> reset(@RequestParam(defaultValue = "default") String sessionId) {
        agentService.reset(sessionId);
        return Map.of("status", "ok", "message", "Session reset successfully");
    }

    /**
     * Health check endpoint.
     */
    @GetMapping("/health")
    public String health() {
        return "OK";
    }

    // ─────────────────── Plan Management APIs ───────────────────

    /**
     * SSE stream for plan state changes for a specific session.
     */
    @GetMapping(path = "/plan/{sessionId}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<PlanResponse> planStream(@PathVariable String sessionId) {
        return planService.getPlanStream(sessionId);
    }

    /**
     * Snapshot of the current plan for a specific session.
     */
    @GetMapping("/plan/{sessionId}")
    public PlanResponse getPlan(@PathVariable String sessionId) {
        PlanResponse plan = planService.getCurrentPlan(sessionId);
        return plan != null ? plan : new PlanResponse();
    }

    /**
     * User confirms plan execution.
     *
     * <p>Tells the backend to suppress further needConfirm=true broadcasts for this plan.
     */
    @PostMapping("/plan/{sessionId}/confirm")
    public Map<String, String> confirmPlan(@PathVariable String sessionId) {
        planService.markUserConfirmed(sessionId);
        return Map.of("status", "ok");
    }

    /**
     * User declines plan execution (clicks "不执行").
     *
     * <p>Immediately abandons the current plan so the PlanNotebook clears
     * {@code currentPlan}. This prevents subsequent LLM iterations from
     * receiving a stale "current plan" system-hint.
     */
    @PostMapping("/plan/{sessionId}/abandon")
    public Mono<Map<String, String>> abandonPlan(@PathVariable String sessionId) {
        return planService.abandonPlan(sessionId).thenReturn(Map.of("status", "ok"));
    }

    private ServerSentEvent<Map<String, String>> toSseEvent(String chunk) {
        if ("[STOPPED]".equals(chunk)) {
            return ServerSentEvent.<Map<String, String>>builder()
                    .event("done")
                    .data(Map.of("type", "done"))
                    .build();
        }
        if (chunk != null && chunk.startsWith("[TOOL:") && chunk.endsWith("]")) {
            String toolName = chunk.substring(6, chunk.length() - 1);
            return ServerSentEvent.<Map<String, String>>builder()
                    .event("tool")
                    .data(Map.of("type", "tool", "content", toolName))
                    .build();
        }
        if (chunk != null && chunk.startsWith("[THINKING]")) {
            String thinking = chunk.substring(10);
            return ServerSentEvent.<Map<String, String>>builder()
                    .event("thinking")
                    .data(Map.of("type", "thinking", "content", thinking))
                    .build();
        }
        return ServerSentEvent.<Map<String, String>>builder()
                .event("chunk")
                .data(Map.of("type", "chunk", "content", chunk == null ? "" : chunk))
                .build();
    }
}
