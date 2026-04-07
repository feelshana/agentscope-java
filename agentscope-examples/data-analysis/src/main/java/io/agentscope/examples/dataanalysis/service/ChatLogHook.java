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
import io.agentscope.core.hook.PostReasoningEvent;
import io.agentscope.core.hook.PreReasoningEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

/**
 * A Hook that logs the full message list sent to LLM before each reasoning iteration. Output is
 * routed to logs/chat.log via the dedicated "chat.llm.messages" logger configured in logback.xml
 * (additivity=false, so it only writes to chat.log).
 */
public class ChatLogHook implements Hook {

    /** Same logger name as logback.xml route – all output goes exclusively to chat.log */
    private static final Logger chatLogger = LoggerFactory.getLogger("chat.llm.messages");

    private static final int MAX_CONTENT_LEN = 500;

    private final String sessionId;
    private final AtomicInteger iterCounter = new AtomicInteger(0);

    public ChatLogHook(String sessionId) {
        this.sessionId = sessionId;
    }

    @Override
    public int priority() {
        return 900; // Low priority – logging only, runs after business hooks
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T extends HookEvent> Mono<T> onEvent(T event) {
        if (event instanceof PreReasoningEvent pre) {
            logMessages(pre.getInputMessages());
        } else if (event instanceof PostReasoningEvent post) {
            logResponse(post.getReasoningMessage());
        }
        return Mono.just(event);
    }

    private void logMessages(List<Msg> messages) {
        int iter = iterCounter.getAndIncrement();
        StringBuilder sb = new StringBuilder();
        sb.append("\n===== [iter=")
                .append(iter)
                .append("] >>> LLM REQUEST for session ")
                .append(sessionId)
                .append(" (")
                .append(messages.size())
                .append(" msgs) =====");

        for (int i = 0; i < messages.size(); i++) {
            Msg m = messages.get(i);

            // SYSTEM 消息只打标识，不打全文
            if (MsgRole.SYSTEM.equals(m.getRole())) {
                List<TextBlock> sysBlocks = m.getContentBlocks(TextBlock.class);
                int sysLen =
                        sysBlocks.stream()
                                .mapToInt(b -> b.getText() == null ? 0 : b.getText().length())
                                .sum();
                sb.append("\n[")
                        .append(i)
                        .append("] SYSTEM | [system prompt, len=")
                        .append(sysLen)
                        .append("]");
                continue;
            }

            sb.append("\n[").append(i).append("] ").append(m.getRole()).append(" |");

            // TextBlock
            List<TextBlock> textBlocks = m.getContentBlocks(TextBlock.class);
            if (!textBlocks.isEmpty()) {
                String text =
                        textBlocks.stream().map(TextBlock::getText).reduce("", String::concat);
                sb.append(" ").append(text);
            }

            // ToolUseBlock（工具调用：名称 + 入参）
            for (ToolUseBlock u : m.getContentBlocks(ToolUseBlock.class)) {
                String inputStr = u.getInput() == null ? "{}" : u.getInput().toString();
                sb.append("\n       [tool_use] ")
                        .append(u.getName())
                        .append(" input=")
                        .append(inputStr);
            }

            // ToolResultBlock（工具结果）
            for (ToolResultBlock r : m.getContentBlocks(ToolResultBlock.class)) {
                String output =
                        r.getOutput().stream()
                                .filter(b -> b instanceof TextBlock)
                                .map(b -> ((TextBlock) b).getText())
                                .reduce("", String::concat);
                sb.append("\n       [tool_result] ")
                        .append(r.getName())
                        .append(" output=")
                        .append(output);
            }
        }
        sb.append("\n-----------------------------------------");
        chatLogger.debug(sb.toString());
    }

    private void logResponse(Msg msg) {
        if (msg == null) return;
        StringBuilder sb = new StringBuilder();
        sb.append("\n----- <<< LLM RESPONSE for session ").append(sessionId).append(" -----");

        // TextBlock（思考/回复文本）
        List<TextBlock> textBlocks = msg.getContentBlocks(TextBlock.class);
        if (!textBlocks.isEmpty()) {
            String text = textBlocks.stream().map(TextBlock::getText).reduce("", String::concat);
            sb.append("\n").append(text);
        }

        // ToolUseBlock（工具调用）
        for (ToolUseBlock u : msg.getContentBlocks(ToolUseBlock.class)) {
            String inputStr = u.getInput() == null ? "{}" : u.getInput().toString();
            sb.append("\n[tool_use] ").append(u.getName()).append(" input=").append(inputStr);
        }

        sb.append("\n========================================");
        chatLogger.debug(sb.toString());
    }

    private String truncate(String s) {
        if (s == null) return "";
        return s.length() > MAX_CONTENT_LEN ? s.substring(0, MAX_CONTENT_LEN) + "..." : s;
    }
}
