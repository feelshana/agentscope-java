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

import io.agentscope.core.agent.Event;
import io.agentscope.core.agent.EventType;
import io.agentscope.core.agent.StreamOptions;
import io.agentscope.core.message.GenerateReason;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.examples.chatbi.dto.ChatRequest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.springframework.util.StreamUtils;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

/**
 * Core service that manages the ChatBI ReActAgent lifecycle.
 *
 * <p>Delegates to {@link SessionAgentManager} for per-session Agent isolation.
 *
 * <p>Each chat invocation:
 * <ol>
 *   <li>Ensures session exists in DB.</li>
 *   <li>Gets or creates the per-session Agent (with historical context pre-loaded).</li>
 *   <li>Saves the user message to DB.</li>
 *   <li>Streams the Agent response (SSE-friendly).</li>
 *   <li>Saves the complete assistant response to DB (fire-and-forget).</li>
 *   <li>Checks if history needs summarization (async).</li>
 * </ol>
 *
 * <p>The Agent is driven by a single ReAct loop with 6 registered tools covering all
 * 8 intents (da/re/in/gu/bu/cs/dl/ot). Intent routing is done by the LLM itself based
 * on the System Prompt rules, rather than hard-coded if-else branches.
 */
@Service
public class ChatBiAgentService implements InitializingBean {

    private static final Logger log = LoggerFactory.getLogger(ChatBiAgentService.class);

    private final SessionAgentManager sessionAgentManager;
    private final ChatSessionService chatSessionService;

    @Value("${openai.api-key:#{null}}")
    private String apiKeyFromConfig;

    @Value("${openai.base-url:#{null}}")
    private String baseUrlFromConfig;

    @Value("${agent.system-prompt-file:chatbi-system-prompt.txt}")
    private String systemPromptFile;

    @Value("${agent.query-rewrite-prompt-file:query-rewrite-prompt.txt}")
    private String queryRewritePromptFile;

    public ChatBiAgentService(
            SessionAgentManager sessionAgentManager, ChatSessionService chatSessionService) {
        this.sessionAgentManager = sessionAgentManager;
        this.chatSessionService = chatSessionService;
    }

    @Override
    public void afterPropertiesSet() {
        String apiKey = resolveApiKey();
        String baseUrl = resolveBaseUrl();
        String sysPrompt = loadPromptFile(systemPromptFile);
        String rewritePrompt = loadPromptFile(queryRewritePromptFile);
        sessionAgentManager.configure(apiKey, baseUrl, sysPrompt, rewritePrompt);
        log.info(
                "ChatBiAgentService initialized, systemPromptFile={}, rewritePromptFile={}",
                systemPromptFile,
                queryRewritePromptFile);
    }

    /**
     * Send a user message to the session's agent and receive a streaming response.
     *
     * <p>The ChatRequest carries rich context (agentId, supersonicToken, reportId, etc.)
     * that is injected into the Tool instances at session creation time.
     *
     * @param req the full chat request including all context parameters
     * @return Flux of streaming text chunks (SSE-friendly)
     */
    public Flux<String> chat(ChatRequest req) {
        String sessionId = req.getSessionId();
        String message = req.getMessage();
        String userName = req.getUserName();

        // Ensure session exists in DB (before loading history)
        chatSessionService.ensureSession(sessionId, userName);

        // Get or create session Agent (preloads history if session exists in DB but not memory)
        SessionAgentManager.SessionEntry entry = sessionAgentManager.getOrCreate(sessionId, req);

        // Save user message AFTER getOrCreate to avoid double-loading the message
        chatSessionService.saveUserMessage(sessionId, message);

        AtomicReference<StringBuilder> replyBuf = new AtomicReference<>(new StringBuilder());

        Msg userMsg =
                Msg.builder()
                        .role(MsgRole.USER)
                        .content(TextBlock.builder().text(message).build())
                        .build();

        return entry.agent.stream(userMsg, buildStreamOptions())
                .subscribeOn(Schedulers.boundedElastic())
                .map(
                        event -> {
                            String chunk = eventToString(event);
                            // Tool progress markers ([TOOL:xxx]) are streamed for real-time display
                            // but are NOT included in the saved assistant reply
                            if (!chunk.isEmpty()
                                    && !"[STOPPED]".equals(chunk)
                                    && !chunk.startsWith("[TOOL:")) {
                                replyBuf.get().append(chunk);
                            }
                            return chunk;
                        })
                .filter(text -> !text.isEmpty())
                .doOnComplete(
                        () -> {
                            String fullReply = replyBuf.get().toString();
                            if (!fullReply.isBlank()) {
                                chatSessionService.saveAssistantMessage(sessionId, fullReply);
                                chatSessionService.maybeCompressAsync(sessionId);
                            }
                        })
                .doOnError(
                        err ->
                                log.error(
                                        "ChatBI stream error for session {}: {}",
                                        sessionId,
                                        err.getMessage()));
    }

    /**
     * Reset a session: evict its Agent from memory so the next request creates a fresh one.
     * Does NOT delete DB history.
     */
    public void reset(String sessionId) {
        log.info("Resetting ChatBI session: {}", sessionId);
        sessionAgentManager.evict(sessionId);
    }

    // ─────────────────── Private helpers ───────────────────

    private String loadPromptFile(String fileName) {
        String path = "prompts/" + fileName;
        try {
            ClassPathResource resource = new ClassPathResource(path);
            return StreamUtils.copyToString(resource.getInputStream(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(
                    "无法加载提示词文件: " + path + "，请检查配置及 resources/prompts/ 目录", e);
        }
    }

    private String resolveApiKey() {
        if (apiKeyFromConfig != null && !apiKeyFromConfig.isBlank()) {
            return apiKeyFromConfig;
        }
        String envKey = System.getenv("OPENAI_API_KEY");
        if (envKey != null && !envKey.isBlank()) {
            return envKey;
        }
        throw new IllegalStateException(
                "OpenAI API key is required. Set 'openai.api-key' in application.yml"
                        + " or the OPENAI_API_KEY environment variable.");
    }

    private String resolveBaseUrl() {
        if (baseUrlFromConfig != null && !baseUrlFromConfig.isBlank()) {
            return baseUrlFromConfig;
        }
        String envUrl = System.getenv("OPENAI_BASE_URL");
        if (envUrl != null && !envUrl.isBlank()) {
            return envUrl;
        }
        return null;
    }

    private StreamOptions buildStreamOptions() {
        return StreamOptions.builder()
                .eventTypes(EventType.REASONING, EventType.TOOL_RESULT, EventType.AGENT_RESULT)
                .incremental(true)
                .build();
    }

    private String eventToString(Event event) {
        if (event.getType() == EventType.AGENT_RESULT) {
            Msg msg = event.getMessage();
            if (msg != null && msg.getGenerateReason() == GenerateReason.ACTING_STOP_REQUESTED) {
                return "[STOPPED]";
            }
            return "";
        }
        // Tool result completion: emit progress marker for frontend real-time display
        if (event.getType() == EventType.TOOL_RESULT && event.isLast()) {
            List<ToolResultBlock> toolBlocks =
                    event.getMessage().getContentBlocks(ToolResultBlock.class);
            if (!toolBlocks.isEmpty()) {
                String toolName = toolBlocks.get(0).getName();
                if (toolName != null && !toolName.isBlank()) {
                    return "[TOOL:" + toolName + "]";
                }
            }
            return "";
        }
        if (event.isLast()) {
            return "";
        }
        List<TextBlock> blocks = event.getMessage().getContentBlocks(TextBlock.class);
        return blocks.isEmpty() ? "" : blocks.get(0).getText();
    }
}
