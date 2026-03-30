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

import io.agentscope.core.agent.Event;
import io.agentscope.core.agent.EventType;
import io.agentscope.core.agent.StreamOptions;
import io.agentscope.core.message.GenerateReason;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.examples.dataanalysis.client.DataApiClient;
import io.agentscope.examples.dataanalysis.tool.DataAnalysisTool;

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
 * Core service that manages the data analysis ReActAgent lifecycle.
 *
 * <p>Delegates to {@link SessionAgentManager} for per-session Agent isolation.
 * Each chat invocation:
 * <ol>
 *   <li>Ensures session exists in DB.</li>
 *   <li>Gets or creates the per-session Agent (with historical context pre-loaded).</li>
 *   <li>Saves the user message to DB.</li>
 *   <li>Streams the Agent response.</li>
 *   <li>Saves the complete assistant response to DB (fire-and-forget).</li>
 *   <li>Checks if history needs summarization (async).</li>
 * </ol>
 */
@Service
public class DataAnalysisAgentService implements InitializingBean {

    private static final Logger log = LoggerFactory.getLogger(DataAnalysisAgentService.class);

    private final DataApiClient dataApiClient;
    private final AnalysisPlanService analysisPlanService;
    private final SessionAgentManager sessionAgentManager;
    private final ChatSessionService chatSessionService;

    @Value("${openai.api-key:#{null}}")
    private String apiKeyFromConfig;

    @Value("${openai.base-url:#{null}}")
    private String baseUrlFromConfig;

    @Value("${agent.system-prompt-file:prompt-V3.txt}")
    private String systemPromptFile;

    public DataAnalysisAgentService(
            DataApiClient dataApiClient,
            AnalysisPlanService analysisPlanService,
            SessionAgentManager sessionAgentManager,
            ChatSessionService chatSessionService) {
        this.dataApiClient = dataApiClient;
        this.analysisPlanService = analysisPlanService;
        this.sessionAgentManager = sessionAgentManager;
        this.chatSessionService = chatSessionService;
    }

    @Override
    public void afterPropertiesSet() {
        String apiKey = resolveApiKey();
        String baseUrl = resolveBaseUrl();
        sessionAgentManager.configure(apiKey, baseUrl, loadSystemPrompt());
        log.info("DataAnalysisAgentService initialized");
    }

    /**
     * Load system prompt from classpath file configured by {@code agent.system-prompt-file}.
     * The file must be placed under {@code resources/prompts/}.
     */
    private String loadSystemPrompt() {
        String path = "prompts/" + systemPromptFile;
        try {
            ClassPathResource resource = new ClassPathResource(path);
            return StreamUtils.copyToString(resource.getInputStream(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(
                    "无法加载系统提示词文件: " + path
                            + "，请检查 agent.system-prompt-file 配置及 resources/prompts/ 目录",
                    e);
        }
    }

    /**
     * Send a user message to the session's agent and receive a streaming response.
     *
     * @param sessionId the chat session identifier
     * @param message   the user's question
     * @param account  the user identifier (from URL param, used for session isolation)
     * @return Flux of streaming text chunks (SSE-friendly)
     */
    public Flux<String> chat(String sessionId, String message, String account) {
        // Synchronous DB operations before streaming starts
        chatSessionService.ensureSession(sessionId, account);
        chatSessionService.saveUserMessage(sessionId, message);
        SessionAgentManager.SessionEntry entry = sessionAgentManager.getOrCreate(sessionId);

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
                            if (!chunk.isEmpty() && !"[STOPPED]".equals(chunk)) {
                                replyBuf.get().append(chunk);
                            }
                            return chunk;
                        })
                .filter(text -> !text.isEmpty())
                .doOnComplete(
                        () -> {
                            String fullReply = replyBuf.get().toString();
                            if (!fullReply.isBlank()) {
                                // Fire-and-forget: save to DB and trigger async compression
                                chatSessionService.saveAssistantMessage(sessionId, fullReply);
                                chatSessionService.maybeCompressAsync(sessionId);
                            }
                        })
                .doOnError(
                        err ->
                                log.error(
                                        "Chat stream error for session {}: {}",
                                        sessionId,
                                        err.getMessage()));
    }

    /**
     * Reset a specific session: evict from memory.
     */
    public void reset(String sessionId) {
        log.info("Resetting session: {}", sessionId);
        sessionAgentManager.evict(sessionId);
        analysisPlanService.broadcastPlanChange();
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
        if (event.isLast()) {
            return "";
        }
        List<TextBlock> blocks = event.getMessage().getContentBlocks(TextBlock.class);
        return blocks.isEmpty() ? "" : blocks.get(0).getText();
    }
}
