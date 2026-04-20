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

import io.agentscope.core.hook.Hook;
import io.agentscope.core.hook.HookEvent;
import io.agentscope.core.hook.PreReasoningEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatModelBase;
import io.agentscope.core.model.ChatResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

/**
 * Hook that performs query rewriting (指代消解) before each ReAct reasoning step.
 *
 * <p>When the user's question depends on conversation history (e.g. "再查一下昨天的" when
 * the previous turn discussed a specific report), this hook rewrites the latest USER
 * message to a self-contained question using a streaming LLM call. This mirrors the
 * "正在查看历史对话" node in the original ChatBI.yml workflow.
 *
 * <p>Runs at {@code priority=5} (before {@link ContextTrimHook} at priority=10),
 * so the rewrite happens on the full context, and trimming happens afterwards.
 *
 * <p><b>When rewriting is skipped:</b>
 * <ul>
 *   <li>No conversation history (first turn)</li>
 *   <li>The model or the rewrite prompt is not configured</li>
 *   <li>The LLM rewrite call fails (original message is preserved)</li>
 * </ul>
 *
 * <p><b>Error handling:</b>
 * <ul>
 *   <li>Timeout: logs warning, uses original question</li>
 *   <li>Interruption: logs warning, uses original question</li>
 *   <li>Other errors: logs warning with message, uses original question</li>
 * </ul>
 */
public class QueryRewriteHook implements Hook {

    private static final Logger log = LoggerFactory.getLogger(QueryRewriteHook.class);

    /** Streaming LLM model used for rewriting (avoids long blocking on non-streaming calls). */
    private final ChatModelBase rewriteModel;

    /** System prompt for the rewrite call (loaded from query-rewrite-prompt.txt). */
    private final String rewriteSystemPrompt;

    /** Max history turns (user+assistant pairs) to include in the rewrite context. */
    private static final int MAX_HISTORY_TURNS = 5;

    /** Max timeout in seconds for the rewrite LLM call. */
    private static final int REWRITE_TIMEOUT_SECONDS = 150;

    public QueryRewriteHook(ChatModelBase rewriteModel, String rewriteSystemPrompt) {
        this.rewriteModel = rewriteModel;
        this.rewriteSystemPrompt = rewriteSystemPrompt;
    }

    @Override
    public int priority() {
        // Run before ContextTrimHook (10) so we see the full history
        return 5;
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T extends HookEvent> Mono<T> onEvent(T event) {
        if (!(event instanceof PreReasoningEvent pre)) {
            return Mono.just(event);
        }

        List<Msg> messages = pre.getInputMessages();
        if (messages == null || messages.isEmpty()) {
            return Mono.just(event);
        }

        // Find the last USER message (the current question to potentially rewrite)
        int lastUserIdx = findLastUserIndex(messages);
        if (lastUserIdx < 0) {
            return Mono.just(event);
        }

        Msg lastUserMsg = messages.get(lastUserIdx);
        String currentQuestion = extractText(lastUserMsg);
        if (currentQuestion == null || currentQuestion.isBlank()) {
            return Mono.just(event);
        }

        // Only rewrite if there is at least one prior turn (history exists)
        boolean hasHistory = hasConversationHistory(messages, lastUserIdx);
        if (!hasHistory) {
            log.debug("[QueryRewriteHook] First turn, skipping rewrite.");
            return Mono.just(event);
        }

        // Build the rewrite prompt with condensed history
        String historyText = buildHistoryText(messages, lastUserIdx);
        String rewriteUserPrompt = buildRewriteUserPrompt(historyText, currentQuestion);

        List<Msg> rewriteMessages =
                List.of(
                        Msg.builder()
                                .role(MsgRole.SYSTEM)
                                .content(
                                        List.of(
                                                TextBlock.builder()
                                                        .text(rewriteSystemPrompt)
                                                        .build()))
                                .build(),
                        Msg.builder()
                                .role(MsgRole.USER)
                                .content(
                                        List.of(
                                                TextBlock.builder()
                                                        .text(rewriteUserPrompt)
                                                        .build()))
                                .build());

        return rewriteModel.stream(rewriteMessages, null, null)
                .collectList()
                .timeout(Duration.ofSeconds(REWRITE_TIMEOUT_SECONDS))
                .map(responses -> extractRewrittenQuestion(responses))
                .flatMap(
                        rewritten -> {
                            if (rewritten == null
                                    || rewritten.isBlank()
                                    || rewritten.equals(currentQuestion)) {
                                log.debug("[QueryRewriteHook] No rewrite needed or same question.");
                                return Mono.just(event);
                            }
                            log.info(
                                    "[QueryRewriteHook] Rewritten: '{}' → '{}'",
                                    currentQuestion,
                                    rewritten);
                            // Replace the last USER message with the rewritten question
                            List<Msg> updatedMessages = new ArrayList<>(messages);
                            Msg rewrittenMsg =
                                    Msg.builder()
                                            .id(lastUserMsg.getId())
                                            .role(MsgRole.USER)
                                            .content(
                                                    List.of(
                                                            TextBlock.builder()
                                                                    .text(rewritten)
                                                                    .build()))
                                            .timestamp(lastUserMsg.getTimestamp())
                                            .build();
                            updatedMessages.set(lastUserIdx, rewrittenMsg);
                            pre.setInputMessages(updatedMessages);
                            return Mono.just(event);
                        })
                .onErrorResume(
                        e -> {
                            if (e instanceof TimeoutException) {
                                log.warn("[QueryRewriteHook] Rewrite timeout after {}s, using original question",
                                        REWRITE_TIMEOUT_SECONDS);
                            } else if (isInterrupted(e)) {
                                log.warn("[QueryRewriteHook] Rewrite interrupted, using original question");
                            } else {
                                log.warn("[QueryRewriteHook] Rewrite failed, using original: {}",
                                        e.getMessage());
                            }
                            return Mono.just(event);
                        })
                .map(e -> (T) e);
    }

    // ─────────────────── Private helpers ───────────────────

    private int findLastUserIndex(List<Msg> messages) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (MsgRole.USER.equals(messages.get(i).getRole())) {
                return i;
            }
        }
        return -1;
    }

    private boolean hasConversationHistory(List<Msg> messages, int lastUserIdx) {
        // Check if there's at least one previous USER message (i.e., prior turns exist)
        for (int i = lastUserIdx - 1; i >= 0; i--) {
            if (MsgRole.USER.equals(messages.get(i).getRole())) {
                return true;
            }
        }
        return false;
    }

    private String extractText(Msg msg) {
        List<TextBlock> blocks = msg.getContentBlocks(TextBlock.class);
        if (blocks == null || blocks.isEmpty()) return null;
        String raw = blocks.stream().map(TextBlock::getText).reduce("", String::concat);
        // Strip system-hint injections
        return raw.replaceAll("(?s)<system-hint>.*?</system-hint>", "").strip();
    }

    /**
     * Build a condensed history text (last N user/assistant pairs before the current question).
     */
    private String buildHistoryText(List<Msg> messages, int lastUserIdx) {
        StringBuilder sb = new StringBuilder();
        int turnCount = 0;
        // Scan backwards from just before lastUserIdx, collect user+assistant turns
        List<String> turns = new ArrayList<>();
        for (int i = lastUserIdx - 1; i >= 0 && turnCount < MAX_HISTORY_TURNS; i--) {
            Msg msg = messages.get(i);
            if (msg.getRole() == null) continue;
            String text = extractText(msg);
            if (text == null || text.isBlank()) continue;
            String role = MsgRole.USER.equals(msg.getRole()) ? "用户" : "助手";
            turns.add(0, role + ": " + text);
            if (MsgRole.USER.equals(msg.getRole())) {
                turnCount++;
            }
        }
        turns.forEach(t -> sb.append(t).append("\n"));
        return sb.toString().strip();
    }

    private String buildRewriteUserPrompt(String historyText, String currentQuestion) {
        return "历史对话：\n" + historyText + "\n\n" + "当前用户问题：\n" + currentQuestion;
    }

    private String extractRewrittenQuestion(List<ChatResponse> responses) {
        if (responses == null || responses.isEmpty()) return null;
        StringBuilder sb = new StringBuilder();
        for (ChatResponse resp : responses) {
            if (resp.getContent() != null) {
                resp.getContent().stream()
                        .filter(b -> b instanceof TextBlock)
                        .map(b -> ((TextBlock) b).getText())
                        .filter(t -> t != null)
                        .forEach(sb::append);
            }
        }
        return sb.toString().strip();
    }

    /** Check if the error is caused by thread interruption. */
    private static boolean isInterrupted(Throwable err) {
        Throwable cause = err;
        while (cause != null) {
            if (cause instanceof InterruptedException) return true;
            String msg = cause.getMessage();
            if (msg != null && msg.toLowerCase().contains("interrupted")) return true;
            cause = cause.getCause();
        }
        return false;
    }
}
