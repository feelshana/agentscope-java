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
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

/**
 * A Hook that trims the LLM input message list before each reasoning step to control token growth.
 *
 * <p>Two strategies are applied in order on every {@link PreReasoningEvent}:
 *
 * <ol>
 *   <li><b>Sliding window</b>: Only the most recent {@value #MAX_REAL_QUESTION_ROUNDS}
 *       <em>real question</em> rounds are kept. One round is defined as a USER message plus all
 *       ASSISTANT / TOOL messages that follow it up to (but not including) the next USER message.
 *       A round is counted as a "real question" only when the USER message contains actual user
 *       text after stripping {@code <system-hint>} blocks. Rounds driven purely by plan
 *       confirmation/rejection (e.g. "执行" / "不执行") are excluded from the count but are
 *       always retained in the context window so the LLM sees the full conversation flow.
 *       The SYSTEM message at the head is always preserved.
 *   <li><b>Tool-result truncation</b>: For rounds that are NOT the most recent round, any TOOL
 *       message whose text output exceeds {@value #TOOL_RESULT_MAX_CHARS} characters is truncated
 *       to that limit with a {@code [已截断]} suffix.
 * </ol>
 *
 * <p>The SYSTEM message and the current (latest) round are never modified.
 */
public class ContextTrimHook implements Hook {

    private static final Logger log = LoggerFactory.getLogger(ContextTrimHook.class);

    /**
     * Maximum number of <em>real question</em> rounds to retain in the sliding window.
     * Confirmation-only rounds ("执行" / "不执行" etc.) are not counted toward this limit
     * but are always kept in the context.
     */
    static final int MAX_REAL_QUESTION_ROUNDS = 5;

    /**
     * Maximum characters kept in a tool-result text block for non-latest rounds.
     * Content beyond this limit is replaced with a truncation notice.
     */
    static final int TOOL_RESULT_MAX_CHARS = 500;

    /**
     * Short confirmation/rejection keywords that do NOT count as real question rounds.
     * Compared case-insensitively after stripping whitespace.
     */
    private static final Set<String> CONFIRMATION_KEYWORDS =
            Set.of("执行", "不执行", "确认", "取消", "算了", "放弃", "好的", "ok", "yes", "no");

    @Override
    public int priority() {
        // Run before LlmDbHook (950) and ChatLogHook (900) so they see the trimmed list.
        // Also run before the plan-hint hooks (50/100) so trimming happens first.
        return 10;
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T extends HookEvent> Mono<T> onEvent(T event) {
        if (event instanceof PreReasoningEvent pre) {
            List<Msg> trimmed = trim(pre.getInputMessages());
            pre.setInputMessages(trimmed);
        }
        return Mono.just(event);
    }

    // ─────────────────── Core trimming logic ───────────────────

    /**
     * Apply sliding-window and tool-result truncation to the input message list.
     *
     * @param messages original input messages (SYSTEM + memory)
     * @return trimmed message list
     */
    List<Msg> trim(List<Msg> messages) {
        if (messages == null || messages.isEmpty()) {
            return messages;
        }

        // Separate the leading SYSTEM message from the rest
        int start = 0;
        Msg systemMsg = null;
        if (MsgRole.SYSTEM.equals(messages.get(0).getRole())) {
            systemMsg = messages.get(0);
            start = 1;
        }

        List<Msg> body = messages.subList(start, messages.size());

        // Split into rounds: each round starts at a USER message
        List<List<Msg>> rounds = splitIntoRounds(body);

        // Find the earliest round index to keep:
        // scan from the end, count real-question rounds until we reach the limit.
        int firstKeptIndex = findFirstKeptRoundIndex(rounds);

        if (firstKeptIndex > 0) {
            log.debug(
                    "ContextTrimHook: dropping {} old round(s), keeping {}/{} (real-question"
                            + " limit={})",
                    firstKeptIndex,
                    rounds.size() - firstKeptIndex,
                    rounds.size(),
                    MAX_REAL_QUESTION_ROUNDS);
        }

        List<List<Msg>> keptRounds = rounds.subList(firstKeptIndex, rounds.size());

        // Truncate tool results in all rounds except the last one
        List<Msg> result = new ArrayList<>();
        if (systemMsg != null) {
            result.add(systemMsg);
        }
        for (int i = 0; i < keptRounds.size(); i++) {
            boolean isLatestRound = (i == keptRounds.size() - 1);
            for (Msg msg : keptRounds.get(i)) {
                result.add(isLatestRound ? msg : truncateToolResults(msg));
            }
        }
        return result;
    }

    /**
     * Determine the index of the first round that should be kept.
     *
     * <p>We scan from the newest round toward the oldest, counting only "real question" rounds
     * (i.e. rounds whose USER message contains actual user text after stripping system-hint blocks
     * and is not a bare confirmation keyword). Once we have counted
     * {@value #MAX_REAL_QUESTION_ROUNDS} real-question rounds, everything older is dropped.
     * Confirmation-only rounds are always kept regardless of their position.
     *
     * @param rounds all rounds in chronological order
     * @return index into {@code rounds} of the first round to retain (0 = keep all)
     */
    private int findFirstKeptRoundIndex(List<List<Msg>> rounds) {
        int realQuestionCount = 0;
        // Scan from newest to oldest
        for (int i = rounds.size() - 1; i >= 0; i--) {
            List<Msg> round = rounds.get(i);
            if (isRealQuestionRound(round)) {
                realQuestionCount++;
                if (realQuestionCount >= MAX_REAL_QUESTION_ROUNDS) {
                    // This is the 5th real-question round; keep from here onward
                    return i;
                }
            }
            // Confirmation-only rounds: don't count, but always keep
        }
        // Fewer than MAX_REAL_QUESTION_ROUNDS real rounds found – keep everything
        return 0;
    }

    /**
     * Returns {@code true} if the round's leading USER message contains real user input
     * (after stripping system-hint blocks) and is not a bare confirmation keyword.
     */
    private boolean isRealQuestionRound(List<Msg> round) {
        if (round.isEmpty()) {
            return false;
        }
        Msg firstMsg = round.get(0);
        if (!MsgRole.USER.equals(firstMsg.getRole())) {
            return false;
        }
        // Extract raw USER text
        String raw =
                firstMsg.getContentBlocks(TextBlock.class).stream()
                        .map(TextBlock::getText)
                        .reduce("", String::concat);
        // Strip system-hint blocks
        String cleaned = raw.replaceAll("(?s)<system-hint>.*?</system-hint>", "").strip();
        if (cleaned.isEmpty()) {
            // Entirely driven by a hint injection (no real user text)
            return false;
        }
        // Check if the remaining text is just a confirmation keyword
        return !CONFIRMATION_KEYWORDS.contains(cleaned.toLowerCase());
    }

    /**
     * Split a flat message list into "rounds", where each round starts with a USER message.
     * Messages before the first USER are grouped into an initial pseudo-round.
     */
    private List<List<Msg>> splitIntoRounds(List<Msg> messages) {
        List<List<Msg>> rounds = new ArrayList<>();
        List<Msg> current = new ArrayList<>();

        for (Msg msg : messages) {
            if (MsgRole.USER.equals(msg.getRole()) && !current.isEmpty()) {
                rounds.add(current);
                current = new ArrayList<>();
            }
            current.add(msg);
        }
        if (!current.isEmpty()) {
            rounds.add(current);
        }
        return rounds;
    }

    /**
     * Return a (possibly new) Msg where every ToolResultBlock whose text output exceeds
     * {@value #TOOL_RESULT_MAX_CHARS} characters is replaced with a truncated version.
     * Other content blocks and non-TOOL messages are returned unchanged.
     */
    private Msg truncateToolResults(Msg msg) {
        if (!MsgRole.TOOL.equals(msg.getRole()) && !msg.hasContentBlocks(ToolResultBlock.class)) {
            return msg;
        }

        boolean modified = false;
        List<ContentBlock> newContent = new ArrayList<>();

        for (ContentBlock block : msg.getContent()) {
            if (block instanceof ToolResultBlock trb) {
                ToolResultBlock trimmed = truncateToolResultBlock(trb);
                newContent.add(trimmed);
                if (trimmed != trb) {
                    modified = true;
                }
            } else {
                newContent.add(block);
            }
        }

        if (!modified) {
            return msg;
        }

        return Msg.builder()
                .id(msg.getId())
                .name(msg.getName())
                .role(msg.getRole())
                .content(newContent)
                .metadata(msg.getMetadata())
                .timestamp(msg.getTimestamp())
                .build();
    }

    /**
     * Truncate the text output of a single ToolResultBlock if it exceeds the limit.
     */
    private ToolResultBlock truncateToolResultBlock(ToolResultBlock trb) {
        List<ContentBlock> output = trb.getOutput();
        boolean modified = false;
        List<ContentBlock> newOutput = new ArrayList<>();

        for (ContentBlock ob : output) {
            if (ob instanceof TextBlock tb) {
                String text = tb.getText();
                if (text != null && text.length() > TOOL_RESULT_MAX_CHARS) {
                    String truncated =
                            text.substring(0, TOOL_RESULT_MAX_CHARS)
                                    + "...[已截断，共"
                                    + text.length()
                                    + "字符]";
                    newOutput.add(TextBlock.builder().text(truncated).build());
                    modified = true;
                } else {
                    newOutput.add(ob);
                }
            } else {
                newOutput.add(ob);
            }
        }

        if (!modified) {
            return trb;
        }
        return ToolResultBlock.of(trb.getId(), trb.getName(), newOutput);
    }
}
