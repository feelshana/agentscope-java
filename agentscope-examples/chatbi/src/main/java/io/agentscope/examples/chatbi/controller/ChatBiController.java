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
import io.agentscope.examples.chatbi.dto.SessionHistoryResponse;
import io.agentscope.examples.chatbi.service.ChatBiAgentService;
import io.agentscope.examples.chatbi.service.ChatSessionService;
import java.util.List;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

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
 * </ul>
 */
@RestController
@RequestMapping("/api/chatbi")
public class ChatBiController {

    private final ChatBiAgentService agentService;
    private final ChatSessionService chatSessionService;

    public ChatBiController(
            ChatBiAgentService agentService, ChatSessionService chatSessionService) {
        this.agentService = agentService;
        this.chatSessionService = chatSessionService;
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
    public Flux<String> chat(@RequestBody ChatRequest req) {
        return agentService.chat(req);
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
}
