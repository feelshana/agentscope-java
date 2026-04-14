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
 * Hook that trims the LLM input message list before each reasoning step to control token growth.
 *
 * <p>Two strategies applied on every {@link PreReasoningEvent}:
 * <ol>
 *   <li><b>Sliding window</b>: Retains the most recent {@value #MAX_REAL_QUESTION_ROUNDS}
 *       real-question rounds. A SYSTEM message is always preserved at the head.</li>
 *   <li><b>Tool-result truncation</b>: For non-latest rounds, tool result text exceeding
 *       {@value #TOOL_RESULT_MAX_CHARS} characters is truncated with a {@code [已截断]} suffix.</li>
 * </ol>
 */
public class ContextTrimHook implements Hook {

    private static final Logger log = LoggerFactory.getLogger(ContextTrimHook.class);

    static final int MAX_REAL_QUESTION_ROUNDS = 5;
    static final int TOOL_RESULT_MAX_CHARS = 500;

    private static final Set<String> CONFIRMATION_KEYWORDS =
            Set.of("执行", "不执行", "确认", "取消", "算了", "放弃", "好的", "ok", "yes", "no");

    @Override
    public int priority() {
        return 10; // Run very early, before ChatLogHook (900)
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

    List<Msg> trim(List<Msg> messages) {
        if (messages == null || messages.isEmpty()) {
            return messages;
        }

        int start = 0;
        Msg systemMsg = null;
        if (MsgRole.SYSTEM.equals(messages.get(0).getRole())) {
            systemMsg = messages.get(0);
            start = 1;
        }

        List<Msg> body = messages.subList(start, messages.size());
        List<List<Msg>> rounds = splitIntoRounds(body);
        int firstKeptIndex = findFirstKeptRoundIndex(rounds);

        if (firstKeptIndex > 0) {
            log.debug(
                    "ContextTrimHook: dropping {} old round(s), keeping {}/{} (limit={})",
                    firstKeptIndex,
                    rounds.size() - firstKeptIndex,
                    rounds.size(),
                    MAX_REAL_QUESTION_ROUNDS);
        }

        List<List<Msg>> keptRounds = rounds.subList(firstKeptIndex, rounds.size());
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

    private int findFirstKeptRoundIndex(List<List<Msg>> rounds) {
        int realQuestionCount = 0;
        for (int i = rounds.size() - 1; i >= 0; i--) {
            if (isRealQuestionRound(rounds.get(i))) {
                realQuestionCount++;
                if (realQuestionCount >= MAX_REAL_QUESTION_ROUNDS) {
                    return i;
                }
            }
        }
        return 0;
    }

    private boolean isRealQuestionRound(List<Msg> round) {
        if (round.isEmpty()) return false;
        Msg firstMsg = round.get(0);
        if (!MsgRole.USER.equals(firstMsg.getRole())) return false;
        String raw =
                firstMsg.getContentBlocks(TextBlock.class).stream()
                        .map(TextBlock::getText)
                        .reduce("", String::concat);
        String cleaned = raw.replaceAll("(?s)<system-hint>.*?</system-hint>", "").strip();
        if (cleaned.isEmpty()) return false;
        return !CONFIRMATION_KEYWORDS.contains(cleaned.toLowerCase());
    }

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
                if (trimmed != trb) modified = true;
            } else {
                newContent.add(block);
            }
        }
        if (!modified) return msg;
        return Msg.builder()
                .id(msg.getId())
                .name(msg.getName())
                .role(msg.getRole())
                .content(newContent)
                .metadata(msg.getMetadata())
                .timestamp(msg.getTimestamp())
                .build();
    }

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
        if (!modified) return trb;
        return ToolResultBlock.of(trb.getId(), trb.getName(), newOutput);
    }
}
