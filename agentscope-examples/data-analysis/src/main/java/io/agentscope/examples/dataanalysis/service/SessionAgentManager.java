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

import io.agentscope.core.ReActAgent;
import io.agentscope.core.formatter.openai.OpenAIChatFormatter;
import io.agentscope.core.memory.InMemoryMemory;
import io.agentscope.core.message.Msg;
import io.agentscope.core.model.OpenAIChatModel;
import io.agentscope.core.plan.PlanNotebook;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.examples.dataanalysis.client.DataApiClient;
import io.agentscope.examples.dataanalysis.tool.DataAnalysisTool;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Manages per-session (Agent + Memory + PlanNotebook) instances.
 *
 * <p>Each chat session gets its own isolated Agent so conversations don't pollute each other.
 * Agents are kept in an in-memory map; their conversation state is persisted externally via
 * {@link ChatSessionService}.
 *
 * <p>On session resume, the historical messages are loaded from DB and pre-loaded into the
 * Agent's InMemoryMemory before processing the new user message.
 */
@Component
public class SessionAgentManager {

    private static final Logger log = LoggerFactory.getLogger(SessionAgentManager.class);

    /** Holds live Agent instances keyed by session ID. */
    private final Map<String, SessionEntry> agents = new ConcurrentHashMap<>();

    private final DataApiClient dataApiClient;
    private final AnalysisPlanService analysisPlanService;
    private final ChatSessionService chatSessionService;

    private String apiKey;
    private String baseUrl;
    private String sysPrompt;

    public SessionAgentManager(
            DataApiClient dataApiClient,
            AnalysisPlanService analysisPlanService,
            ChatSessionService chatSessionService) {
        this.dataApiClient = dataApiClient;
        this.analysisPlanService = analysisPlanService;
        this.chatSessionService = chatSessionService;
    }

    public void configure(String apiKey, String baseUrl, String sysPrompt) {
        this.apiKey = apiKey;
        this.baseUrl = baseUrl;
        this.sysPrompt = sysPrompt;
        // Create a non-streaming model for summarization
        OpenAIChatModel.Builder builder =
                OpenAIChatModel.builder().apiKey(apiKey).modelName("deepseek-chat").stream(false)
                        .formatter(new OpenAIChatFormatter());
        if (baseUrl != null) {
            builder.baseUrl(baseUrl);
        }
        chatSessionService.setSummaryModel(builder.build());
    }

    /**
     * Get or create an Agent session entry (synchronous).
     * On first access (new session), creates a fresh Agent.
     * On resume (session exists in DB but not in memory map), loads history and pre-warms.
     */
    public SessionEntry getOrCreate(String sessionId) {
        if (agents.containsKey(sessionId)) {
            return agents.get(sessionId);
        }
        // New in-memory entry; load historical messages from DB if session exists
        List<Msg> historyMsgs = chatSessionService.loadSessionMessages(sessionId);
        SessionEntry entry = createEntry(sessionId);
        if (!historyMsgs.isEmpty()) {
            log.info(
                    "Resuming session {} with {} historical messages",
                    sessionId,
                    historyMsgs.size());
            preloadHistory(entry, historyMsgs);
        }
        agents.put(sessionId, entry);
        return entry;
    }

    /**
     * Remove a session from the in-memory map (called on reset).
     */
    public void evict(String sessionId) {
        agents.remove(sessionId);
        log.info("Session evicted from memory: {}", sessionId);
    }

    /**
     * Get the current PlanNotebook for a session (for SSE plan stream).
     * Falls back to the global analysisPlanService notebook if session not loaded.
     */
    public PlanNotebook getPlanNotebook(String sessionId) {
        SessionEntry entry = agents.get(sessionId);
        return entry != null ? entry.planNotebook : analysisPlanService.getPlanNotebook();
    }

    // ─────────────────── Internal helpers ───────────────────

    private SessionEntry createEntry(String sessionId) {
        InMemoryMemory memory = new InMemoryMemory();

        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(new DataAnalysisTool(dataApiClient));

        PlanNotebook planNotebook = PlanNotebook.builder().build();
        // Broadcast plan changes through the global analysisPlanService (for SSE stream)
        planNotebook.addChangeHook(
                "planBroadcast", (nb, plan) -> analysisPlanService.broadcastPlanChange());
        // Keep local reference updated
        analysisPlanService.setPlanNotebook(planNotebook);

        OpenAIChatModel.Builder modelBuilder =
                OpenAIChatModel.builder().apiKey(apiKey).modelName("deepseek-chat").stream(true)
                        .formatter(new OpenAIChatFormatter());
        if (baseUrl != null) {
            modelBuilder.baseUrl(baseUrl);
        }

        ReActAgent agent =
                ReActAgent.builder()
                        .name("DataAnalysisAgent-" + sessionId)
                        .sysPrompt(sysPrompt)
                        .model(modelBuilder.build())
                        .memory(memory)
                        .toolkit(toolkit)
                        .planNotebook(planNotebook)
                        .maxIters(40)
                        .build();

        return new SessionEntry(agent, memory, planNotebook);
    }

    /**
     * Pre-load historical Msg list into the Agent's InMemoryMemory so the LLM sees the context.
     */
    private void preloadHistory(SessionEntry entry, List<Msg> historyMsgs) {
        for (Msg msg : historyMsgs) {
            entry.memory.addMessage(msg);
        }
    }

    // ─────────────────── Session entry ───────────────────

    /**
     * Holds the trio of Agent, Memory, and PlanNotebook for one session.
     */
    public static class SessionEntry {
        public final ReActAgent agent;
        public final InMemoryMemory memory;
        public final PlanNotebook planNotebook;

        public SessionEntry(ReActAgent agent, InMemoryMemory memory, PlanNotebook planNotebook) {
            this.agent = agent;
            this.memory = memory;
            this.planNotebook = planNotebook;
        }
    }
}
