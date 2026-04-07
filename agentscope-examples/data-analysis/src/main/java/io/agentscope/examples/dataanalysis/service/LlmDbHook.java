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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.agentscope.core.hook.Hook;
import io.agentscope.core.hook.HookEvent;
import io.agentscope.core.hook.PostReasoningEvent;
import io.agentscope.core.hook.PreReasoningEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

/**
 * A Hook that persists each LLM interaction round to MySQL via {@link LlmInteractionLogService}.
 *
 * <p>On {@link PreReasoningEvent}: caches iter index, serialized messages and current user
 * question – nothing is written to the DB yet.
 *
 * <p>On {@link PostReasoningEvent}: combines the cached data with the LLM response and
 * inserts a single complete record. This avoids the duplicate-row problem that arises when
 * inserting a placeholder on Pre and a full row on Post.
 */
public class LlmDbHook implements Hook {

    private static final Logger log = LoggerFactory.getLogger(LlmDbHook.class);

    private static final ObjectMapper JSON = new ObjectMapper();

    private final String sessionId;
    private final LlmInteractionLogService logService;

    /** Tracks the current iter count (mirrors ChatLogHook's counter). */
    private final AtomicInteger iterCounter = new AtomicInteger(0);

    /** Cache the pending iter so PostReasoning can write the correct iter value. */
    private final AtomicInteger pendingIter = new AtomicInteger(-1);

    /** Cache the pending messagesJson for the case where we need to re-insert. */
    private final AtomicReference<String> pendingMessagesJson = new AtomicReference<>();

    /**
     * The user question for the current ReActAgent.call() invocation.
     * Updated whenever a new user question is detected (i.e. the latest USER message in the
     * input list differs from the previously recorded question), which reliably marks the start
     * of a new call() regardless of the global iterCounter value.
     */
    private final AtomicReference<String> currentUserQuestion = new AtomicReference<>();

    public LlmDbHook(String sessionId, LlmInteractionLogService logService) {
        this.sessionId = sessionId;
        this.logService = logService;
    }

    @Override
    public int priority() {
        // Run alongside ChatLogHook (900); DB write is independent of other hooks
        return 950;
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T extends HookEvent> Mono<T> onEvent(T event) {
        if (event instanceof PreReasoningEvent pre) {
            handlePreReasoning(pre);
        } else if (event instanceof PostReasoningEvent post) {
            handlePostReasoning(post);
        }
        return Mono.just(event);
    }

    // ─────────────────── PreReasoning ───────────────────

    private void handlePreReasoning(PreReasoningEvent pre) {
        int iter = iterCounter.getAndIncrement();
        List<Msg> messages = pre.getInputMessages();

        // Detect the start of a new ReActAgent.call() by comparing the latest USER message
        // with the previously recorded question. iterCounter is global and never resets to 0
        // for subsequent calls, so iter==0 cannot be used as a reliable sentinel.
        String latestUserMsg = extractUserQuestion(messages);
        if (latestUserMsg != null && !latestUserMsg.equals(currentUserQuestion.get())) {
            currentUserQuestion.set(latestUserMsg);
        }
        String userQuestion = currentUserQuestion.get();
        String messagesJson = serializeMessages(messages);

        // Only cache; DB write happens in PostReasoning once the LLM response is available.
        pendingIter.set(iter);
        pendingMessagesJson.set(messagesJson);
    }

    // ─────────────────── PostReasoning ───────────────────

    private void handlePostReasoning(PostReasoningEvent post) {
        Msg response = post.getReasoningMessage();
        if (response == null) return;

        int iter = pendingIter.get();
        String messagesJson = pendingMessagesJson.get();
        String userQuestion = currentUserQuestion.get();
        String llmResponse = serializeResponse(response);

        logService.save(sessionId, iter, userQuestion, messagesJson, llmResponse);
    }

    // ─────────────────── Serialization helpers ───────────────────

    /**
     * Serialize the message list to a JSON array.
     * SYSTEM messages are replaced with a compact placeholder object.
     */
    private String serializeMessages(List<Msg> messages) {
        ArrayNode array = JSON.createArrayNode();
        for (Msg msg : messages) {
            ObjectNode node = JSON.createObjectNode();
            String role = msg.getRole() != null ? msg.getRole().name().toLowerCase() : "unknown";
            node.put("role", role);

            if (MsgRole.SYSTEM.equals(msg.getRole())) {
                // Only store a compact identifier for SYSTEM messages
                List<TextBlock> sysBlocks = msg.getContentBlocks(TextBlock.class);
                int sysLen =
                        sysBlocks.stream()
                                .mapToInt(b -> b.getText() == null ? 0 : b.getText().length())
                                .sum();
                node.put("content", "[system-prompt, len=" + sysLen + "]");
                array.add(node);
                continue;
            }

            // Text blocks
            List<TextBlock> textBlocks = msg.getContentBlocks(TextBlock.class);
            if (!textBlocks.isEmpty()) {
                String text =
                        textBlocks.stream().map(TextBlock::getText).reduce("", String::concat);
                node.put("content", text);
            } else {
                node.put("content", "");
            }

            // Tool use blocks
            List<ToolUseBlock> toolUseBlocks = msg.getContentBlocks(ToolUseBlock.class);
            if (!toolUseBlocks.isEmpty()) {
                ArrayNode toolUseArray = JSON.createArrayNode();
                for (ToolUseBlock u : toolUseBlocks) {
                    ObjectNode toolNode = JSON.createObjectNode();
                    toolNode.put("type", "tool_use");
                    toolNode.put("name", u.getName());
                    toolNode.put("input", u.getInput() == null ? "{}" : u.getInput().toString());
                    toolUseArray.add(toolNode);
                }
                node.set("tool_use", toolUseArray);
            }

            // Tool result blocks
            List<ToolResultBlock> toolResultBlocks = msg.getContentBlocks(ToolResultBlock.class);
            if (!toolResultBlocks.isEmpty()) {
                ArrayNode resultArray = JSON.createArrayNode();
                for (ToolResultBlock r : toolResultBlocks) {
                    ObjectNode resultNode = JSON.createObjectNode();
                    resultNode.put("type", "tool_result");
                    resultNode.put("name", r.getName());
                    String output =
                            r.getOutput().stream()
                                    .filter(b -> b instanceof TextBlock)
                                    .map(b -> ((TextBlock) b).getText())
                                    .reduce("", String::concat);
                    resultNode.put("output", output);
                    resultArray.add(resultNode);
                }
                node.set("tool_result", resultArray);
            }

            array.add(node);
        }
        try {
            return JSON.writeValueAsString(array);
        } catch (Exception e) {
            log.warn("Failed to serialize messages to JSON: {}", e.getMessage());
            return "[]";
        }
    }

    /**
     * Serialize the LLM response message to a compact JSON string.
     */
    private String serializeResponse(Msg msg) {
        ObjectNode node = JSON.createObjectNode();
        String role = msg.getRole() != null ? msg.getRole().name().toLowerCase() : "assistant";
        node.put("role", role);

        // Text blocks
        List<TextBlock> textBlocks = msg.getContentBlocks(TextBlock.class);
        if (!textBlocks.isEmpty()) {
            String text = textBlocks.stream().map(TextBlock::getText).reduce("", String::concat);
            node.put("content", text);
        } else {
            node.put("content", "");
        }

        // Tool use blocks
        List<ToolUseBlock> toolUseBlocks = msg.getContentBlocks(ToolUseBlock.class);
        if (!toolUseBlocks.isEmpty()) {
            ArrayNode toolUseArray = JSON.createArrayNode();
            for (ToolUseBlock u : toolUseBlocks) {
                ObjectNode toolNode = JSON.createObjectNode();
                toolNode.put("name", u.getName());
                toolNode.put("input", u.getInput() == null ? "{}" : u.getInput().toString());
                toolUseArray.add(toolNode);
            }
            node.set("tool_use", toolUseArray);
        }

        try {
            return JSON.writeValueAsString(node);
        } catch (Exception e) {
            log.warn("Failed to serialize LLM response to JSON: {}", e.getMessage());
            return "{}";
        }
    }

    /**
     * Extract the most recent USER message text as the "user question".
     * Scans from the end of the message list to find the last USER role message.
     *
     * <p>The text is stripped of any {@code <system-hint>...</system-hint>} blocks that may have
     * been injected by plan-hint hooks (e.g. {@code ConfirmPlanToHint}), leaving only the
     * raw user input.
     */
    private String extractUserQuestion(List<Msg> messages) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            Msg msg = messages.get(i);
            if (MsgRole.USER.equals(msg.getRole())) {
                List<TextBlock> textBlocks = msg.getContentBlocks(TextBlock.class);
                if (!textBlocks.isEmpty()) {
                    String raw = textBlocks.stream().map(TextBlock::getText).reduce("", String::concat);
                    // Strip injected system-hint blocks, keep only the user's original text
                    String cleaned = raw.replaceAll("(?s)<system-hint>.*?</system-hint>", "").strip();
                    return cleaned.isEmpty() ? null : cleaned;
                }
            }
        }
        return null;
    }
}
