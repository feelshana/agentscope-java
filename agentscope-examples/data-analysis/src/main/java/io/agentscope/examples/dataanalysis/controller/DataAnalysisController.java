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
package io.agentscope.examples.dataanalysis.controller;

import io.agentscope.examples.dataanalysis.client.DataApiClient;
import io.agentscope.examples.dataanalysis.dto.ChatMessageDto;
import io.agentscope.examples.dataanalysis.dto.DatasetInfo;
import io.agentscope.examples.dataanalysis.dto.PlanResponse;
import io.agentscope.examples.dataanalysis.dto.SessionHistoryResponse;
import io.agentscope.examples.dataanalysis.service.AnalysisPlanService;
import io.agentscope.examples.dataanalysis.service.ChatSessionService;
import io.agentscope.examples.dataanalysis.service.DataAnalysisAgentService;
import io.agentscope.examples.dataanalysis.service.SuggestedQuestionService;
import java.util.List;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * REST + SSE controller for the data analysis agent.
 *
 * <p>Endpoints:
 * <ul>
 *   <li>{@code GET  /api/datasets}               – list available datasets</li>
 *   <li>{@code GET  /api/sessions}               – list chat history sessions</li>
 *   <li>{@code DELETE /api/sessions/{sessionId}} – delete a session</li>
 *   <li>{@code GET  /api/chat}                   – stream chat response (SSE)</li>
 *   <li>{@code GET  /api/plan/stream}            – stream plan updates (SSE)</li>
 *   <li>{@code GET  /api/plan}                   – current plan snapshot</li>
 *   <li>{@code POST /api/reset}                  – reset agent and plan</li>
 *   <li>{@code GET  /api/health}                 – health check</li>
 * </ul>
 */
@RestController
@RequestMapping("/api")
public class DataAnalysisController {

    private final DataAnalysisAgentService agentService;
    private final AnalysisPlanService planService;
    private final DataApiClient dataApiClient;
    private final ChatSessionService chatSessionService;
    private final SuggestedQuestionService suggestedQuestionService;

    public DataAnalysisController(
            DataAnalysisAgentService agentService,
            AnalysisPlanService planService,
            DataApiClient dataApiClient,
            ChatSessionService chatSessionService,
            SuggestedQuestionService suggestedQuestionService) {
        this.agentService = agentService;
        this.planService = planService;
        this.dataApiClient = dataApiClient;
        this.chatSessionService = chatSessionService;
        this.suggestedQuestionService = suggestedQuestionService;
    }

    /**
     * Returns the list of enabled suggested questions from the database.
     * Questions can be updated directly in DB and take effect immediately without redeployment.
     */
    @GetMapping("/suggested-questions")
    public List<String> listSuggestedQuestions() {
        return suggestedQuestionService.listEnabledQuestions();
    }

    /**
     * Returns the list of available datasets.
     */
    @GetMapping("/datasets")
    public Mono<List<DatasetInfo>> listDatasets() {
        return dataApiClient.listDatasets();
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
     * Delete a session and all its messages.
     */
    @DeleteMapping("/sessions/{sessionId}")
    public Map<String, String> deleteSession(@PathVariable String sessionId) {
        agentService.reset(sessionId);
        chatSessionService.deleteSession(sessionId);
        return Map.of("status", "ok");
    }

    /**
     * Returns all messages of a given session for frontend history rendering.
     */
    @GetMapping("/sessions/{sessionId}/messages")
    public List<ChatMessageDto> getSessionMessages(@PathVariable String sessionId) {
        return chatSessionService.loadSessionMessagesAsDto(sessionId);
    }

    /**
     * Main chat endpoint – streams the agent's reasoning and final answer via SSE.
     *
     * @param message   the user's question
     * @param sessionId the session identifier (generated by frontend)
     * @param userName  the user identifier (from URL param)
     * @return SSE stream of text chunks
     */
    @GetMapping(path = "/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> chat(
            @RequestParam String message,
            @RequestParam String sessionId,
            @RequestParam(defaultValue = "") String account) {
        return agentService.chat(sessionId, message, account);
    }

    /**
     * SSE stream for plan state changes.
     */
    @GetMapping(path = "/plan/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<PlanResponse> planStream() {
        return planService.getPlanStream();
    }

    /**
     * Snapshot of the current plan.
     */
    @GetMapping("/plan")
    public PlanResponse currentPlan() {
        PlanResponse plan = planService.getCurrentPlan();
        return plan != null ? plan : new PlanResponse();
    }

    /**
     * Reset a session's agent and clear the plan.
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
