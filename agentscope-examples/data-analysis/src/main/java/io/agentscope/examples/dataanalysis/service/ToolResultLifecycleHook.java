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

import io.agentscope.core.hook.Hook;
import io.agentscope.core.hook.HookEvent;
import io.agentscope.core.hook.PreReasoningEvent;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

/**
 * A Hook that manages tool_result lifecycle in the conversation history.
 *
 * <p>For query_dataset tool results:
 * <ul>
 *   <li>Iterates from most recent to earliest messages</li>
 *   <li>Finds the 5 most recent VALID results (non-empty, non-error, with actual data)</li>
 *   <li>Truncates valid results to 5000 chars each</li>
 *   <li>Error/empty results are kept as-is (unchanged)</li>
 *   <li>Results beyond the 5 valid limit are marked as [历史数据已过期，如需请重新查询]</li>
 * </ul>
 *
 * <p>This enables multi-turn data reuse while controlling context size.
 */
public class ToolResultLifecycleHook implements Hook {

    private static final Logger log = LoggerFactory.getLogger(ToolResultLifecycleHook.class);

    /** Number of most recent VALID query_dataset results to keep. */
    private static final int KEEP_RECENT_COUNT = 5;

    /** Maximum characters per tool result. */
    private static final int MAX_CHARS = 5000;

    /** Tool name to filter. */
    private static final String QUERY_DATASET_TOOL = "query_dataset";

    @Override
    public int priority() {
        // Run after ContextTrimHook (10) but before DatasetInjectionHook (20)
        return 15;
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

        // Process from most recent to earliest
        // Reverse the list to iterate from end to start
        List<Msg> reversed = new ArrayList<>(messages);
        Collections.reverse(reversed);

        int validCountFromEnd = 0;
        List<Msg> processedReversed = new ArrayList<>(messages.size());

        for (Msg msg : reversed) {
            List<ToolResultBlock> toolResults = msg.getContentBlocks(ToolResultBlock.class);

            // Check if this message contains query_dataset result
            if (toolResults.isEmpty()
                    || toolResults.stream()
                            .noneMatch(b -> QUERY_DATASET_TOOL.equals(b.getName()))) {
                // No query_dataset result in this message, keep as-is
                processedReversed.add(msg);
                continue;
            }

            // Process the message
            Msg processedMsg = processMessage(msg, toolResults, validCountFromEnd);
            processedReversed.add(processedMsg);

            // Update valid count after processing
            validCountFromEnd = updateValidCount(msg, toolResults, validCountFromEnd);
        }

        // Reverse back to original order
        Collections.reverse(processedReversed);
        pre.setInputMessages(processedReversed);
        return Mono.just(event);
    }

    /**
     * Update the valid count after processing a message.
     * Count only valid results (non-error, non-empty).
     */
    private int updateValidCount(Msg msg, List<ToolResultBlock> toolResults, int currentCount) {
        for (ToolResultBlock block : toolResults) {
            if (QUERY_DATASET_TOOL.equals(block.getName())) {
                String content = extractTextContent(block.getOutput());
                if (!shouldSkip(content)) {
                    currentCount++;
                }
            }
        }
        return currentCount;
    }

    /**
     * Process a message containing query_dataset tool results.
     *
     * @param msg the original message
     * @param toolResults list of tool result blocks
     * @param validCountFromEnd current count of valid results found so far (from end)
     * @return processed message
     */
    private Msg processMessage(Msg msg, List<ToolResultBlock> toolResults, int validCountFromEnd) {
        List<ContentBlock> newBlocks = new ArrayList<>();

        // Keep any non-tool-result blocks (like TextBlock)
        for (ContentBlock block : msg.getContent()) {
            if (!(block instanceof ToolResultBlock)) {
                newBlocks.add(block);
            }
        }

        // Process each tool_result block
        for (ToolResultBlock block : toolResults) {
            if (!QUERY_DATASET_TOOL.equals(block.getName())) {
                // Keep other tool results as-is
                newBlocks.add(block);
                continue;
            }

            String content = extractTextContent(block.getOutput());
            boolean isValid = !shouldSkip(content);

            if (!isValid) {
                // Error/empty result: keep as-is (unchanged)
                newBlocks.add(block);
                log.debug("[ToolResultLifecycle] Keeping invalid/empty result as-is");
            } else if (validCountFromEnd < KEEP_RECENT_COUNT) {
                // Within the 5 valid limit: truncate if needed
                if (content.length() > MAX_CHARS) {
                    String truncated = content.substring(0, MAX_CHARS) + "...[数据已截断]";
                    ToolResultBlock truncatedBlock =
                            ToolResultBlock.builder()
                                    .id(block.getId())
                                    .name(block.getName())
                                    .output(List.of(TextBlock.builder().text(truncated).build()))
                                    .build();
                    newBlocks.add(truncatedBlock);
                    log.debug(
                            "[ToolResultLifecycle] Truncated tool result from {} to {} chars",
                            content.length(),
                            MAX_CHARS);
                } else {
                    newBlocks.add(block);
                }
            } else {
                // Beyond the 5 valid limit: mark as expired
                ToolResultBlock expiredBlock =
                        ToolResultBlock.builder()
                                .id(block.getId())
                                .name(block.getName())
                                .output(
                                        List.of(
                                                TextBlock.builder()
                                                        .text("[历史数据已过期，如需请重新查询]")
                                                        .build()))
                                .build();
                newBlocks.add(expiredBlock);
                log.debug("[ToolResultLifecycle] Expired old valid tool result");
            }
        }

        return Msg.builder()
                .role(msg.getRole())
                .name(msg.getName())
                .content(newBlocks)
                .metadata(msg.getMetadata())
                .build();
    }

    /**
     * Extract text content from output blocks.
     */
    private String extractTextContent(List<ContentBlock> output) {
        if (output == null) return "";
        StringBuilder sb = new StringBuilder();
        for (ContentBlock block : output) {
            if (block instanceof TextBlock tb && tb.getText() != null) {
                sb.append(tb.getText());
            }
        }
        return sb.toString();
    }

    /**
     * Check if the content should be skipped (error/empty).
     */
    private boolean shouldSkip(String content) {
        if (content == null || content.isBlank()) {
            return true;
        }
        if (content.startsWith("Error")) {
            return true;
        }
        String lower = content.toLowerCase();
        return lower.contains("\"error\"")
                || lower.contains("not found")
                || lower.contains("no data")
                || lower.contains("result\":[]");
    }
}
