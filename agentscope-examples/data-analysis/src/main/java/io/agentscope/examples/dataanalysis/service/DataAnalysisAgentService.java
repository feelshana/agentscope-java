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
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.springframework.util.StreamUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Schedulers;

/**
 * Core service that manages the data analysis ReActAgent lifecycle.
 *
 * <p>Delegates to {@link SessionAgentManager} for per-session Agent isolation.
 */
@Service
public class DataAnalysisAgentService implements InitializingBean {

    private static final Logger log = LoggerFactory.getLogger(DataAnalysisAgentService.class);

    /** Incremental DB flush threshold for assistant draft content. */
    private static final int DRAFT_FLUSH_CHARS = 120;

    /** Max interval (ms) between draft flushes. */
    private static final long DRAFT_FLUSH_INTERVAL_MS = 1200L;

    private final DataApiClient dataApiClient;
    private final AnalysisPlanService analysisPlanService;
    private final SessionAgentManager sessionAgentManager;
    private final ChatSessionService chatSessionService;

    /** sessionId -> active generating turn (decoupled from frontend SSE lifecycle). */
    private final ConcurrentHashMap<String, ActiveTurn> activeTurns = new ConcurrentHashMap<>();

    /** sessionId -> last completed requestId (for short-window deduplication). */
    private final ConcurrentHashMap<String, String> completedRequestIds = new ConcurrentHashMap<>();

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
                    "无法加载系统提示词文件: "
                            + path
                            + "，请检查 agent.system-prompt-file 配置及 resources/prompts/ 目录",
                    e);
        }
    }

    /**
     * Send a user message and return stream chunks.
     *
     * <p>Important: model generation is executed in a backend-owned subscription (decoupled
     * from the frontend SSE connection). So even if the mobile WebView/background kills the
     * client connection, backend generation and DB incremental persistence continue.
     */
    public Flux<String> chat(String sessionId, String message, String account, String requestId) {
        String normalizedRequestId =
                (requestId == null || requestId.isBlank()) ? "req-unknown" : requestId.trim();

        // Idempotency: if the same request is already running (typically EventSource reconnect),
        // return existing stream and do NOT save user message or start generation again.
        ActiveTurn existing = activeTurns.get(sessionId);
        if (existing != null && normalizedRequestId.equals(existing.requestId)) {
            return existing.chunkSink.asFlux();
        }

        // If same request has completed very recently, do not execute it again.
        String completedRequestId = completedRequestIds.get(sessionId);
        if (normalizedRequestId.equals(completedRequestId)) {
            log.info(
                    "Duplicate completed request ignored: sessionId={}, requestId={}",
                    sessionId,
                    normalizedRequestId);
            return Flux.empty();
        }

        // Synchronous DB operations before generation starts
        chatSessionService.ensureSession(sessionId, account);
        // getOrCreate BEFORE saveUserMessage: avoid duplicate context injection for new sessions
        SessionAgentManager.SessionEntry entry = sessionAgentManager.getOrCreate(sessionId);
        chatSessionService.saveUserMessage(sessionId, message);

        Msg userMsg =
                Msg.builder()
                        .role(MsgRole.USER)
                        .content(TextBlock.builder().text(message).build())
                        .build();

        ActiveTurn turn = new ActiveTurn(sessionId, normalizedRequestId);
        ActiveTurn previous = activeTurns.put(sessionId, turn);
        if (previous != null) {
            previous.completeSink();
        }

        startGeneration(entry, userMsg, turn);
        return turn.chunkSink.asFlux();
    }

    /**
     * Resume active stream for a session.
     *
     * <p>Uses replay sink, so late subscribers can receive chunks already generated in this turn.
     */
    public Flux<String> resume(String sessionId) {
        ActiveTurn turn = activeTurns.get(sessionId);
        return turn == null ? Flux.empty() : turn.chunkSink.asFlux();
    }

    /**
     * Reset a specific session: evict from memory and stop active stream if exists.
     */
    public void reset(String sessionId) {
        log.info("Resetting session: {}", sessionId);
        sessionAgentManager.evict(sessionId);
        ActiveTurn turn = activeTurns.remove(sessionId);
        if (turn != null) {
            turn.completeSink();
        }
        completedRequestIds.remove(sessionId);
        analysisPlanService.broadcastPlanChange();
    }

    private void startGeneration(
            SessionAgentManager.SessionEntry entry, Msg userMsg, ActiveTurn turn) {
        Flux<String> source =
                entry.agent.stream(userMsg, buildStreamOptions())
                        .subscribeOn(Schedulers.boundedElastic())
                        .map(this::eventToString)
                        .filter(text -> !text.isEmpty());

        source.subscribe(
                chunk -> handleChunk(turn, chunk),
                err -> handleError(turn, err),
                () -> handleComplete(turn));
    }

    private void handleChunk(ActiveTurn turn, String chunk) {
        turn.chunkSink.tryEmitNext(chunk);

        if (!"[STOPPED]".equals(chunk) && !chunk.startsWith("[TOOL:")) {
            turn.replyBuf.append(chunk);
            flushDraftIfNeeded(turn, false);
        }
    }

    private void handleError(ActiveTurn turn, Throwable err) {
        log.error("Chat stream error for session {}: {}", turn.sessionId, err.getMessage());
        flushDraftIfNeeded(turn, true);
        completedRequestIds.put(turn.sessionId, turn.requestId);
        turn.completeSink();
        activeTurns.remove(turn.sessionId, turn);
    }

    private void handleComplete(ActiveTurn turn) {
        flushDraftIfNeeded(turn, true);
        if (!turn.replyBuf.isEmpty()) {
            chatSessionService.maybeCompressAsync(turn.sessionId);
        }
        completedRequestIds.put(turn.sessionId, turn.requestId);
        turn.completeSink();
        activeTurns.remove(turn.sessionId, turn);
    }

    private void flushDraftIfNeeded(ActiveTurn turn, boolean force) {
        int len = turn.replyBuf.length();
        if (len <= 0) {
            return;
        }
        long now = System.currentTimeMillis();
        if (!force
                && len - turn.lastPersistedLen < DRAFT_FLUSH_CHARS
                && now - turn.lastPersistedAt < DRAFT_FLUSH_INTERVAL_MS) {
            return;
        }

        Long messageId = turn.draftMessageId.get();
        if (messageId == null) {
            messageId = chatSessionService.beginAssistantDraft(turn.sessionId);
            turn.draftMessageId.set(messageId);
        }

        chatSessionService.updateAssistantDraft(
                messageId, turn.replyBuf.toString(), turn.sessionId);
        turn.lastPersistedLen = len;
        turn.lastPersistedAt = now;
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
        // For tool result events (isLast=true marks completion), emit a progress marker
        // so the frontend can show a real-time status line instead of a blank loading indicator.
        // The marker format is [TOOL:<toolName>] and is intentionally excluded from DB persistence.
        if (event.getType() == EventType.TOOL_RESULT && event.isLast()) {
            List<io.agentscope.core.message.ToolResultBlock> toolBlocks =
                    event.getMessage()
                            .getContentBlocks(io.agentscope.core.message.ToolResultBlock.class);
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

    /** Per-session active generation state. */
    private static final class ActiveTurn {
        private final String sessionId;
        private final String requestId;
        private final Sinks.Many<String> chunkSink = Sinks.many().replay().all();
        private final StringBuilder replyBuf = new StringBuilder();
        private final AtomicReference<Long> draftMessageId = new AtomicReference<>();
        private volatile int lastPersistedLen = 0;
        private volatile long lastPersistedAt = 0L;

        private ActiveTurn(String sessionId, String requestId) {
            this.sessionId = sessionId;
            this.requestId = requestId;
        }

        private void completeSink() {
            chunkSink.tryEmitComplete();
        }
    }
}
