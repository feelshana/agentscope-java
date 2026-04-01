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

import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.OpenAIChatModel;
import io.agentscope.examples.dataanalysis.dto.ChatMessageDto;
import io.agentscope.examples.dataanalysis.dto.SessionHistoryResponse;
import io.agentscope.examples.dataanalysis.entity.ChatMessage;
import io.agentscope.examples.dataanalysis.entity.ChatSession;
import io.agentscope.examples.dataanalysis.mapper.ChatMessageMapper;
import io.agentscope.examples.dataanalysis.mapper.ChatSessionMapper;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Service responsible for chat session lifecycle management:
 *
 * <ul>
 *   <li>Create / load sessions from MySQL via MyBatis-Plus</li>
 *   <li>Persist user and assistant messages</li>
 *   <li>Build historical Msg list for Agent context injection</li>
 *   <li>Auto-compress overlong histories via LLM summarization</li>
 * </ul>
 */
@Service
public class ChatSessionService {

    private static final Logger log = LoggerFactory.getLogger(ChatSessionService.class);

    /** Approx. character threshold before summarization kicks in (chars ≈ tokens * 2 for Chinese). */
    @Value("${chat.session.max-history-tokens:8000}")
    private int maxHistoryTokens;

    /** Number of most-recent messages to keep verbatim after summarization. */
    @Value("${chat.session.keep-recent-messages:6}")
    private int keepRecentMessages;

    private final ChatSessionMapper sessionMapper;
    private final ChatMessageMapper messageMapper;

    // Model reference injected from SessionAgentManager for summarization calls
    private OpenAIChatModel summaryModel;

    public ChatSessionService(ChatSessionMapper sessionMapper, ChatMessageMapper messageMapper) {
        this.sessionMapper = sessionMapper;
        this.messageMapper = messageMapper;
    }

    /** Called by SessionAgentManager to provide the LLM model for summarization. */
    public void setSummaryModel(OpenAIChatModel model) {
        this.summaryModel = model;
    }

    // ─────────────────── Session lifecycle ───────────────────

    /**
     * Create and persist a new ChatSession with the given id and userName.
     */
    @Transactional
    public ChatSession createSession(String sessionId, String userName) {
        ChatSession session = new ChatSession();
        session.setId(sessionId);
        session.setUserName(userName == null ? "" : userName);
        session.setTitle("新对话");
        session.setCreatedAt(LocalDateTime.now());
        session.setUpdatedAt(LocalDateTime.now());
        session.setMessageCount(0);
        session.setSummarized(false);
        sessionMapper.insert(session);
        log.info("Session created: {} for user: {}", sessionId, userName);
        return session;
    }

    /**
     * Ensure a session exists; create it if it doesn't.
     * userName is stored on creation and used for per-user isolation.
     */
    public ChatSession ensureSession(String sessionId, String userName) {
        ChatSession existing = sessionMapper.selectById(sessionId);
        if (existing != null) {
            return existing;
        }
        return createSession(sessionId, userName);
    }

    /**
     * Fetch all sessions for a specific user, ordered by last update descending.
     */
    public List<SessionHistoryResponse> listSessions(String userName) {
        return sessionMapper
                .findByUserNameOrderByUpdatedAtDesc(userName == null ? "" : userName)
                .stream()
                .map(SessionHistoryResponse::from)
                .collect(Collectors.toList());
    }

    /**
     * Delete a session and all its messages (cascade via FK or explicit delete).
     */
    @Transactional
    public void deleteSession(String sessionId) {
        messageMapper.deleteBySessionId(sessionId);
        sessionMapper.deleteById(sessionId);
        log.info("Session deleted: {}", sessionId);
    }

    /**
     * Load all messages for a session as Msg list (for Agent context injection).
     * If the session history is summarized, a leading system message with summary is prepended.
     */
    public List<Msg> loadSessionMessages(String sessionId) {
        ChatSession session = sessionMapper.selectById(sessionId);
        if (session == null) {
            return Collections.emptyList();
        }

        if (session.isSummarized() && session.getSummaryText() != null) {
            // Compressed mode: summary as SYSTEM message + last N full messages
            List<ChatMessage> recentDesc =
                    messageMapper.findRecentBySessionId(sessionId, keepRecentMessages);
            // findRecentBySessionId returns DESC; reverse to ASC
            List<ChatMessage> recentAsc = new ArrayList<>(recentDesc);
            Collections.reverse(recentAsc);

            List<Msg> result = new ArrayList<>();
            result.add(
                    Msg.builder()
                            .role(MsgRole.SYSTEM)
                            .content(
                                    TextBlock.builder()
                                            .text("[历史对话摘要]\n" + session.getSummaryText())
                                            .build())
                            .build());
            for (ChatMessage cm : recentAsc) {
                result.add(toMsg(cm));
            }
            return result;
        } else {
            // Full replay: load all messages in ASC order
            return messageMapper.findBySessionIdOrderByCreatedAtAsc(sessionId).stream()
                    .map(this::toMsg)
                    .collect(Collectors.toList());
        }
    }

    /**
     * Load all messages for a session as ChatMessageDto list (for frontend history rendering).
     */
    public List<ChatMessageDto> loadSessionMessagesAsDto(String sessionId) {
        return messageMapper.findBySessionIdOrderByCreatedAtAsc(sessionId).stream()
                .map(cm -> new ChatMessageDto(cm.getRole(), cm.getContent()))
                .collect(Collectors.toList());
    }

    // ─────────────────── Message persistence ───────────────────

    /**
     * Save a user message and update the session title (if first message).
     */
    @Transactional
    public void saveUserMessage(String sessionId, String content) {
        ChatMessage msg = new ChatMessage(sessionId, "user", content);
        messageMapper.insert(msg);
        updateSessionMeta(sessionId, content);
    }

    /**
     * Save an assistant message.
     * Strips {@code <chart>} HTML blocks before persisting so that
     * history replay does not contain chart rendering markup.
     * {@code <report>} blocks and plain-text conclusions are preserved as-is.
     * The full content remains in the Agent's in-memory context for the
     * current session, enabling style-edit follow-ups without DB overhead.
     */
    @Transactional
    public void saveAssistantMessage(String sessionId, String content) {
        String textOnly = stripDisplayBlocks(content);
        ChatMessage msg = new ChatMessage(sessionId, "assistant", textOnly);
        messageMapper.insert(msg);
        incrementMessageCount(sessionId);
    }

    /**
     * Remove only {@code <chart>...</chart>} blocks from the assistant reply before persisting.
     * {@code <report>} blocks and all other content are preserved.
     * Trailing/leading blank lines are also collapsed to avoid excessive whitespace.
     * Uses a simple regex so there is no external dependency.
     */
    private static String stripDisplayBlocks(String content) {
        if (content == null) return "";
        // Remove <chart>...</chart> blocks only (DOTALL so newlines are matched)
        String result = content.replaceAll("(?si)<chart[^>]*>.*?</chart>", "");
        // Collapse 3+ consecutive newlines into at most 2, then strip leading/trailing whitespace
        result = result.replaceAll("\\n{3,}", "\n\n");
        return result.strip();
    }

    // ─────────────────── Compression ───────────────────

    /**
     * Check if history is overlong; if so, trigger LLM summarization asynchronously.
     * This is fire-and-forget – does not block the chat response stream.
     */
    public void maybeCompressAsync(String sessionId) {
        CompletableFuture.runAsync(
                () -> {
                    try {
                        checkAndCompress(sessionId);
                    } catch (Exception e) {
                        log.warn(
                                "Compression failed for session {}: {}", sessionId, e.getMessage());
                    }
                });
    }

    private void checkAndCompress(String sessionId) {
        ChatSession session = sessionMapper.selectById(sessionId);
        if (session == null || session.isSummarized()) {
            return;
        }
        List<ChatMessage> messages = messageMapper.findBySessionIdOrderByCreatedAtAsc(sessionId);
        int totalChars = messages.stream().mapToInt(m -> m.getContent().length()).sum();
        // Rough token estimate: Chinese chars ≈ 1 token, English ≈ 4 chars/token
        int estimatedTokens = totalChars / 2;
        if (estimatedTokens < maxHistoryTokens) {
            return; // Not yet needed
        }
        log.info(
                "Session {} history ~{} tokens, triggering summarization",
                sessionId,
                estimatedTokens);
        summarizeHistory(sessionId, messages);
    }

    /**
     * Use LLM to summarize the conversation history, then persist the summary.
     * After summarization, keep only the N most recent messages in the DB.
     */
    private void summarizeHistory(String sessionId, List<ChatMessage> messages) {
        if (summaryModel == null) {
            log.warn("Summary model not set, skipping compression");
            return;
        }

        int keepCount = Math.min(keepRecentMessages, messages.size());
        List<ChatMessage> toSummarize = messages.subList(0, messages.size() - keepCount);
        if (toSummarize.isEmpty()) {
            return;
        }

        StringBuilder historyText = new StringBuilder();
        for (ChatMessage m : toSummarize) {
            historyText.append(m.getRole().equals("user") ? "用户: " : "助手: ");
            String content = m.getContent();
            if (content.length() > 800) {
                content = content.substring(0, 800) + "…[内容已截断]";
            }
            historyText.append(content).append("\n");
        }

        String prompt =
                """
                请将以下对话历史压缩成一段简洁的摘要，保留关键数据结论、用户意图和重要事实，去除推理过程细节。
                摘要应在300字以内，使用中文。

                对话历史：
                """
                        + historyText;

        Msg summaryRequest =
                Msg.builder()
                        .role(MsgRole.USER)
                        .content(TextBlock.builder().text(prompt).build())
                        .build();

        GenerateOptions options =
                GenerateOptions.builder().modelName("deepseek-chat").stream(false).build();

        try {
            // Use blockLast() to synchronously wait for the non-streaming response
            var response = summaryModel.stream(List.of(summaryRequest), null, options).blockLast();
            if (response == null
                    || response.getContent() == null
                    || response.getContent().isEmpty()) {
                return;
            }
            StringBuilder sb = new StringBuilder();
            response.getContent()
                    .forEach(
                            block -> {
                                if (block instanceof TextBlock tb) sb.append(tb.getText());
                            });
            String summaryText = sb.toString().trim();
            if (summaryText.isEmpty()) return;

            log.info("Session {} summarized: {} chars", sessionId, summaryText.length());
            // Persist summary and clean old messages
            sessionMapper.updateSummary(sessionId, summaryText);
            deleteOldMessages(messages, keepCount);
        } catch (Exception e) {
            log.error("Failed to summarize session {}: {}", sessionId, e.getMessage());
        }
    }

    /**
     * Delete messages that were summarized, keep the most recent keepCount messages.
     */
    private void deleteOldMessages(List<ChatMessage> allMessages, int keepCount) {
        int deleteUntilIdx = allMessages.size() - keepCount;
        List<Long> idsToDelete =
                allMessages.subList(0, deleteUntilIdx).stream()
                        .map(ChatMessage::getId)
                        .collect(Collectors.toList());
        if (idsToDelete.isEmpty()) return;
        messageMapper.deleteBatchIds(idsToDelete);
    }

    // ─────────────────── Private helpers ───────────────────

    private void updateSessionMeta(String sessionId, String firstUserMsg) {
        long count = messageMapper.countBySessionId(sessionId);
        String title =
                firstUserMsg.length() > 30 ? firstUserMsg.substring(0, 30) + "…" : firstUserMsg;
        if (count > 2) {
            // After first exchange, keep existing title but update count
            ChatSession existing = sessionMapper.selectById(sessionId);
            if (existing != null) {
                sessionMapper.updateTitleAndCount(sessionId, existing.getTitle(), (int) count);
            }
        } else {
            sessionMapper.updateTitleAndCount(sessionId, title, (int) count);
        }
    }

    private void incrementMessageCount(String sessionId) {
        long count = messageMapper.countBySessionId(sessionId);
        ChatSession session = sessionMapper.selectById(sessionId);
        if (session != null) {
            sessionMapper.updateTitleAndCount(sessionId, session.getTitle(), (int) count);
        }
    }

    private Msg toMsg(ChatMessage cm) {
        MsgRole role =
                switch (cm.getRole()) {
                    case "user" -> MsgRole.USER;
                    case "system" -> MsgRole.SYSTEM;
                    default -> MsgRole.ASSISTANT;
                };
        return Msg.builder()
                .role(role)
                .content(TextBlock.builder().text(cm.getContent()).build())
                .build();
    }
}
