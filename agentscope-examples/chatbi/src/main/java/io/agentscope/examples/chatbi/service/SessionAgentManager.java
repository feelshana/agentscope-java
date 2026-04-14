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

import io.agentscope.core.ReActAgent;
import io.agentscope.core.formatter.openai.OpenAIChatFormatter;
import io.agentscope.core.memory.InMemoryMemory;
import io.agentscope.core.message.Msg;
import io.agentscope.core.model.OpenAIChatModel;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.examples.chatbi.client.ConfluenceApiClient;
import io.agentscope.examples.chatbi.client.SupersonicApiClient;
import io.agentscope.examples.chatbi.dto.ChatRequest;
import io.agentscope.examples.chatbi.tool.DataInterpretTool;
import io.agentscope.examples.chatbi.tool.DataLineageTool;
import io.agentscope.examples.chatbi.tool.DataQueryTool;
import io.agentscope.examples.chatbi.tool.KnowledgeSearchTool;
import io.agentscope.examples.chatbi.tool.ReportQueryTool;
import io.agentscope.examples.chatbi.tool.ReportScheduleTool;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Manages per-session (Agent + Memory) instances for ChatBI.
 *
 * <p>Each chat session gets its own isolated Agent. The Agent is created with all 6 tools
 * registered and per-session context (agentId, supersonicToken, reportId, etc.) injected
 * into the tool instances at creation time.
 *
 * <p>On session resume, the historical messages are loaded from DB and pre-loaded into the
 * Agent's InMemoryMemory before processing the new user message.
 *
 * <p>When a new request arrives for an existing session with different context parameters
 * (e.g. a different reportId), the session's tools are refreshed by re-creating the Agent entry.
 */
@Component
public class SessionAgentManager {

    private static final Logger log = LoggerFactory.getLogger(SessionAgentManager.class);

    private final Map<String, SessionEntry> agents = new ConcurrentHashMap<>();

    private final SupersonicApiClient supersonicClient;
    private final ConfluenceApiClient confluenceClient;
    private final ChatSessionService chatSessionService;

    // Configured at startup by ChatBiAgentService
    private String apiKey;
    private String baseUrl;
    private String sysPrompt;
    private String rewritePrompt;

    public SessionAgentManager(
            SupersonicApiClient supersonicClient,
            ConfluenceApiClient confluenceClient,
            ChatSessionService chatSessionService) {
        this.supersonicClient = supersonicClient;
        this.confluenceClient = confluenceClient;
        this.chatSessionService = chatSessionService;
    }

    /** Called by ChatBiAgentService after Spring context is ready with LLM config. */
    public void configure(String apiKey, String baseUrl, String sysPrompt, String rewritePrompt) {
        this.apiKey = apiKey;
        this.baseUrl = baseUrl;
        this.sysPrompt = sysPrompt;
        this.rewritePrompt = rewritePrompt;

        // Provide summary model to session service for context compression
        OpenAIChatModel.Builder summaryBuilder =
                OpenAIChatModel.builder().apiKey(apiKey).modelName("deepseek-chat").stream(false)
                        .formatter(new OpenAIChatFormatter());
        if (baseUrl != null) {
            summaryBuilder.baseUrl(baseUrl);
        }
        chatSessionService.setSummaryModel(summaryBuilder.build());
    }

    /**
     * Get or create an Agent session entry.
     *
     * <p>If a session entry already exists in memory, it is returned as-is (tools retain the
     * context from when they were first created for this session). If not, a new Agent is
     * created with the current request's context injected into all tools.
     */
    public SessionEntry getOrCreate(String sessionId, ChatRequest req) {
        if (agents.containsKey(sessionId)) {
            return agents.get(sessionId);
        }
        List<Msg> historyMsgs = chatSessionService.loadSessionMessages(sessionId);
        SessionEntry entry = createEntry(sessionId, req);
        if (!historyMsgs.isEmpty()) {
            log.info(
                    "Resuming ChatBI session {} with {} historical messages",
                    sessionId,
                    historyMsgs.size());
            preloadHistory(entry, historyMsgs);
        }
        agents.put(sessionId, entry);
        return entry;
    }

    /**
     * Remove a session from the in-memory map (called on reset or eviction).
     */
    public void evict(String sessionId) {
        agents.remove(sessionId);
        log.info("ChatBI session evicted from memory: {}", sessionId);
    }

    // ─────────────────── Internal helpers ───────────────────

    private SessionEntry createEntry(String sessionId, ChatRequest req) {
        InMemoryMemory memory = new InMemoryMemory();

        // Extract per-session context from request
        String agentId = req.getAgentId();
        String supersonicToken = req.getSupersonicToken();
        String reportId = req.getReportId();
        String dashboardId = req.getDashboardId();
        String chartParam = req.getParam();

        // Register all 6 tools with per-session context
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(new DataQueryTool(supersonicClient, agentId, supersonicToken));
        toolkit.registerTool(new KnowledgeSearchTool(confluenceClient));
        toolkit.registerTool(new ReportQueryTool(supersonicClient, agentId, supersonicToken));
        toolkit.registerTool(new DataInterpretTool(chartParam));
        toolkit.registerTool(new DataLineageTool(supersonicClient, supersonicToken));
        toolkit.registerTool(
                new ReportScheduleTool(supersonicClient, reportId, dashboardId, supersonicToken));

        // Build non-streaming model for QueryRewriteHook
        OpenAIChatModel.Builder rewriteModelBuilder =
                OpenAIChatModel.builder().apiKey(apiKey).modelName("deepseek-chat").stream(false)
                        .formatter(new OpenAIChatFormatter());
        if (baseUrl != null) {
            rewriteModelBuilder.baseUrl(baseUrl);
        }
        OpenAIChatModel rewriteModel = rewriteModelBuilder.build();

        // Build streaming model for main ReActAgent
        OpenAIChatModel.Builder modelBuilder =
                OpenAIChatModel.builder().apiKey(apiKey).modelName("deepseek-chat").stream(true)
                        .formatter(new OpenAIChatFormatter());
        if (baseUrl != null) {
            modelBuilder.baseUrl(baseUrl);
        }

        ReActAgent agent =
                ReActAgent.builder()
                        .name("ChatBiAgent-" + sessionId)
                        .sysPrompt(sysPrompt)
                        .model(modelBuilder.build())
                        .memory(memory)
                        .toolkit(toolkit)
                        .maxIters(20)
                        .hook(new QueryRewriteHook(rewriteModel, rewritePrompt)) // priority=5
                        .hook(new ContextTrimHook()) // priority=10
                        .hook(new ChatLogHook(sessionId)) // priority=900
                        .build();

        log.info(
                "Created ChatBI agent for session={}, agentId={}, reportId={}",
                sessionId,
                agentId,
                reportId);
        return new SessionEntry(agent, memory);
    }

    private void preloadHistory(SessionEntry entry, List<Msg> historyMsgs) {
        for (Msg msg : historyMsgs) {
            entry.memory.addMessage(msg);
        }
    }

    // ─────────────────── Session entry ───────────────────

    /**
     * Holds the Agent + Memory for one session.
     */
    public static class SessionEntry {
        public final ReActAgent agent;
        public final InMemoryMemory memory;

        public SessionEntry(ReActAgent agent, InMemoryMemory memory) {
            this.agent = agent;
            this.memory = memory;
        }
    }
}
