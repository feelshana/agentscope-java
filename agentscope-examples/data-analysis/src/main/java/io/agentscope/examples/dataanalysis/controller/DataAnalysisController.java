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
import io.agentscope.examples.dataanalysis.dto.DatasetInfo;
import io.agentscope.examples.dataanalysis.dto.PlanResponse;
import io.agentscope.examples.dataanalysis.service.AnalysisPlanService;
import io.agentscope.examples.dataanalysis.service.DataAnalysisAgentService;
import java.util.List;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
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
 *   <li>{@code GET  /api/datasets}       – list available datasets</li>
 *   <li>{@code GET  /api/chat}           – stream chat response (SSE)</li>
 *   <li>{@code GET  /api/plan/stream}    – stream plan updates (SSE)</li>
 *   <li>{@code GET  /api/plan}           – current plan snapshot</li>
 *   <li>{@code POST /api/reset}          – reset agent and plan</li>
 *   <li>{@code GET  /api/health}         – health check</li>
 * </ul>
 */
@RestController
@RequestMapping("/api")
public class DataAnalysisController {

    private final DataAnalysisAgentService agentService;
    private final AnalysisPlanService planService;
    private final DataApiClient dataApiClient;

    public DataAnalysisController(
            DataAnalysisAgentService agentService,
            AnalysisPlanService planService,
            DataApiClient dataApiClient) {
        this.agentService = agentService;
        this.planService = planService;
        this.dataApiClient = dataApiClient;
    }

    /**
     * Returns the list of available datasets (id + description).
     * Called by the frontend when the page loads to populate the dataset selector.
     */
    @GetMapping("/datasets")
    public Mono<List<DatasetInfo>> listDatasets() {
        return dataApiClient.listDatasets();
    }

    /**
     * Main chat endpoint – streams the agent's reasoning and final answer via SSE.
     *
     * @param message   the user's question or analysis request
     * @param sessionId optional session identifier (reserved for future multi-session support)
     * @return SSE stream of text chunks
     */
    @GetMapping(path = "/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> chat(
            @RequestParam String message,
            @RequestParam(defaultValue = "default") String sessionId) {
        return agentService.chat(message);
    }

    /**
     * SSE stream for plan state changes.
     * The frontend subscribes to this to render the task breakdown panel in real-time.
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
     * Reset the agent and clear all plans.
     */
    @PostMapping("/reset")
    public Map<String, String> reset() {
        agentService.reset();
        return Map.of("status", "ok", "message", "Agent reset successfully");
    }

    /**
     * Health check endpoint.
     */
    @GetMapping("/health")
    public String health() {
        return "OK";
    }
}
