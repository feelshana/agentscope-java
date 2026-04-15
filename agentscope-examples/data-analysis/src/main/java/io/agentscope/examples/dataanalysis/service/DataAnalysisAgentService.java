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
     * <p>Strategy:
     * <ol>
     *   <li>Check memory {@code activeTurns} first — stream still running, replay from sink.</li>
     *   <li>Fall back to DB: if latest assistant message has {@code streaming_status = RUNNING},
     *       the server may have restarted and lost the in-memory sink. Return empty so the
     *       frontend falls back to loading history via {@code /api/chat/history}.</li>
     *   <li>No RUNNING message → stream already completed, return empty.</li>
     * </ol>
     */
    public Flux<String> resume(String sessionId) {
        // 1. In-memory hot sink: stream is still running.
        //    Return SNAPSHOT frame (full content so far) followed by live incremental chunks.
        //    This mirrors DeepSeek's resume_stream design:
        //      - First frame: "[SNAPSHOT]<all text generated so far>"
        //      - Subsequent frames: incremental chunks as they arrive
        //    Frontend replaces existing bubble content with SNAPSHOT, then appends new chunks.
        ActiveTurn turn = activeTurns.get(sessionId);
        if (turn != null) {
            log.info(
                    "[Resume] Found active turn for session {}, sending SNAPSHOT + live",
                    sessionId);
            // Capture current buffer snapshot and chunk count atomically (best-effort)
            String snapshot = turn.replyBuf.toString();
            long chunkCountAtSnapshot = turn.chunkCount.get();
            Flux<String> snapshotFrame = Flux.just("[SNAPSHOT]" + snapshot);
            // Live flux: skip chunks already replayed in the snapshot, only send new ones
            Flux<String> liveFlux = turn.chunkSink.asFlux().skip(chunkCountAtSnapshot);
            return snapshotFrame.concatWith(liveFlux);
        }
        // 2. No in-memory turn — check DB for RUNNING status
        //    (covers server-restart scenario or stream that completed just after activeTurns
        // removal)
        io.agentscope.examples.dataanalysis.entity.ChatMessage running =
                chatSessionService.findRunningMessage(sessionId);
        if (running != null) {
            // Stream was RUNNING in DB but in-memory sink is gone (e.g. server restart).
            // Push a SNAPSHOT of whatever was persisted, then mark COMPLETED.
            log.warn(
                    "[Resume] RUNNING message found in DB but no active sink for session {};"
                            + " pushing DB snapshot and marking COMPLETED",
                    sessionId);
            chatSessionService.completeAssistantDraft(
                    running.getId(), running.getContent(), sessionId);
            String dbContent = running.getContent();
            if (dbContent != null && !dbContent.isBlank()) {
                return Flux.just("[SNAPSHOT]" + dbContent);
            }
        }
        // 3. Stream already COMPLETED (active turn cleaned up after stream finished).
        //    Push the persisted content as a snapshot so the frontend can render
        //    the final state (including [CONFIRM_PLAN] buttons etc.).
        io.agentscope.examples.dataanalysis.entity.ChatMessage completed =
                chatSessionService.findLatestCompletedAssistantMessage(sessionId);
        if (completed != null) {
            String dbContent = completed.getContent();
            if (dbContent != null && !dbContent.isBlank()) {
                log.info(
                        "[Resume] Stream already COMPLETED for session {}; pushing persisted"
                                + " snapshot (len={})",
                        sessionId,
                        dbContent.length());
                return Flux.just("[SNAPSHOT]" + dbContent);
            }
        }
        return Flux.empty();
    }

    /**
     * Get the streaming status for a session.
     *
     * @return RUNNING if stream is in progress, DONE if completed, NONE if session not found
     */
    public String getStreamingStatus(String sessionId) {
        ActiveTurn turn = activeTurns.get(sessionId);
        if (turn != null) {
            return "RUNNING";
        }
        // Check if session exists in database
        if (chatSessionService.sessionExists(sessionId)) {
            return "DONE";
        }
        return "NONE";
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
        analysisPlanService.broadcastPlanChange(sessionId);
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
        turn.chunkCount.incrementAndGet();

        if (!"[STOPPED]".equals(chunk) && !chunk.startsWith("[TOOL:")) {
            turn.replyBuf.append(chunk);
            flushDraftIfNeeded(turn, false);
        }
    }

    private void handleError(ActiveTurn turn, Throwable err) {
        log.error("Chat stream error for session {}: {}", turn.sessionId, err.getMessage());
        // Finalize the draft message with COMPLETED status (error is also a terminal state)
        Long messageId = turn.draftMessageId.get();
        if (messageId != null) {
            chatSessionService.completeAssistantDraft(
                    messageId, turn.replyBuf.toString(), turn.sessionId);
        }
        completedRequestIds.put(turn.sessionId, turn.requestId);
        turn.completeSink();
        activeTurns.remove(turn.sessionId, turn);
    }

    private void handleComplete(ActiveTurn turn) {
        // Persist final content and mark COMPLETED atomically
        Long messageId = turn.draftMessageId.get();
        if (messageId != null) {
            chatSessionService.completeAssistantDraft(
                    messageId, turn.replyBuf.toString(), turn.sessionId);
        } else if (!turn.replyBuf.isEmpty()) {
            // Edge case: no draft row yet (very short reply that never triggered flush)
            chatSessionService.saveAssistantMessage(turn.sessionId, turn.replyBuf.toString());
        }
        String assistantReply = turn.replyBuf.toString();
        if (!assistantReply.isEmpty()) {
            chatSessionService.maybeCompressAsync(turn.sessionId);
            analysisPlanService.reconcilePlanAfterTurnComplete(turn.sessionId, assistantReply);
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
            // First flush: create draft row with RUNNING status
            messageId = chatSessionService.beginAssistantDraft(turn.sessionId, turn.requestId);
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

        /** Total number of chunks emitted to chunkSink so far (including control markers). */
        private final java.util.concurrent.atomic.AtomicInteger chunkCount =
                new java.util.concurrent.atomic.AtomicInteger(0);

        private ActiveTurn(String sessionId, String requestId) {
            this.sessionId = sessionId;
            this.requestId = requestId;
        }

        private void completeSink() {
            chunkSink.tryEmitComplete();
        }
    }
}
