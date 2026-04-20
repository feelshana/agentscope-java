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
 * Hook that logs the full LLM message exchange for each reasoning iteration.
 *
 * <p>LLM chat messages are routed to {@code logs/chatbi-chat.log} via the dedicated
 * {@code "chat.llm.messages"} logger configured in logback.xml (additivity=false).
 *
 * <p>RouterAgent intent routing decisions (calls to {@code call_*_agent} tools) are additionally
 * logged at INFO level via the standard logger so they appear in {@code logs/chatbi.log} and
 * console without requiring DEBUG to be enabled.
 */
public class ChatLogHook implements Hook {

    /** Standard logger – INFO level, appears in chatbi.log and console */
    private static final Logger log = LoggerFactory.getLogger(ChatLogHook.class);

    /** Same logger name as logback.xml route – all output goes exclusively to chatbi-chat.log */
    private static final Logger chatLogger = LoggerFactory.getLogger("chat.llm.messages");

    /** Tool name prefix used by RouterAgent to call sub-agents */
    private static final String SUB_AGENT_TOOL_PREFIX = "call_";

    private static final int MAX_CONTENT_LEN = 500;

    private final String sessionId;
    private final String agentDescription;
    private final String sysPrompt;
    private final AtomicInteger iterCounter = new AtomicInteger(0);

    public ChatLogHook(String sessionId) {
        this(sessionId, null, null);
    }

    /**
     * @param sessionId        session or sub-session identifier
     * @param agentDescription human-readable description logged when the agent starts (iter=0)
     */
    public ChatLogHook(String sessionId, String agentDescription) {
        this(sessionId, agentDescription, null);
    }

    /**
     * @param sessionId        session or sub-session identifier
     * @param agentDescription human-readable description logged when the agent starts (iter=0)
     * @param sysPrompt        system prompt printed at INFO level on first invocation (iter=0)
     */
    public ChatLogHook(String sessionId, String agentDescription, String sysPrompt) {
        this.sessionId = sessionId;
        this.agentDescription = agentDescription;
        this.sysPrompt = sysPrompt;
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

        // ── Agent entry log (INFO, iter=0 only) ─────────────────────────────
        if (iter == 0) {
            String agentName = extractAgentName(sessionId);
            if (agentDescription != null && !agentDescription.isBlank()) {
                log.info("[AgentEnter] session={} agent={} -> {}",
                        sessionId, agentName, agentDescription);
            }
            if (sysPrompt != null && !sysPrompt.isBlank()) {
                log.info("[SysPrompt] session={} agent={}\n{}",
                        sessionId, agentName, sysPrompt);
            }
        }
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

            // SYSTEM messages: print only length, not full content
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
                sb.append(" ").append(truncate(text));
            }

            // ToolUseBlock
            for (ToolUseBlock u : m.getContentBlocks(ToolUseBlock.class)) {
                String inputStr = u.getInput() == null ? "{}" : u.getInput().toString();
                sb.append("\n       [tool_use] ")
                        .append(u.getName())
                        .append(" input=")
                        .append(inputStr);
            }

            // ToolResultBlock
            for (ToolResultBlock r : m.getContentBlocks(ToolResultBlock.class)) {
                String output =
                        r.getOutput().stream()
                                .filter(b -> b instanceof TextBlock)
                                .map(b -> ((TextBlock) b).getText())
                                .reduce("", String::concat);
                sb.append("\n       [tool_result] ")
                        .append(r.getName())
                        .append(" output=")
                        .append(truncate(output));
            }
        }
        sb.append("\n-----------------------------------------");
        chatLogger.debug(sb.toString());
    }

    private void logResponse(Msg msg) {
        if (msg == null) return;
        StringBuilder sb = new StringBuilder();
        sb.append("\n----- <<< LLM RESPONSE for session ").append(sessionId).append(" -----");

        List<TextBlock> textBlocks = msg.getContentBlocks(TextBlock.class);
        if (!textBlocks.isEmpty()) {
            String text = textBlocks.stream().map(TextBlock::getText).reduce("", String::concat);
            sb.append("\n").append(text);
        }

        for (ToolUseBlock u : msg.getContentBlocks(ToolUseBlock.class)) {
            String inputStr = u.getInput() == null ? "{}" : u.getInput().toString();
            sb.append("\n[tool_use] ").append(u.getName()).append(" input=").append(inputStr);

            // ── Intent routing log (INFO) ────────────────────────────────────────
            // RouterAgent decision: call_*_agent tools represent intent routing.
            // Log at INFO so it shows in console/chatbi.log without DEBUG enabled.
            if (u.getName() != null && u.getName().startsWith(SUB_AGENT_TOOL_PREFIX)) {
                String intentAgent = u.getName();
                Object queryObj = u.getInput() == null ? null : u.getInput().get("query");
                String query = queryObj == null ? "(no query param)" : queryObj.toString();
                log.info("[RouterAgent] session={} intent_route={} query={}",
                        sessionId, intentAgent, truncate(query));
            }
        }

        sb.append("\n========================================");
        chatLogger.debug(sb.toString());
    }

    private String truncate(String s) {
        if (s == null) return "";
        return s.length() > MAX_CONTENT_LEN ? s.substring(0, MAX_CONTENT_LEN) + "..." : s;
    }

    /**
     * Extract a readable agent label from the session suffix.
     * e.g. "session123-da" -> "DataQueryAgent", "session123" -> "RouterAgent"
     */
    private static String extractAgentName(String sessionId) {
        if (sessionId == null) return "UnknownAgent";
        int dash = sessionId.lastIndexOf('-');
        if (dash < 0) return "RouterAgent";
        return switch (sessionId.substring(dash + 1)) {
            case "da" -> "DataQueryAgent";
            case "kb" -> "KnowledgeAgent";
            case "re" -> "ReportQueryAgent";
            case "dl" -> "DataLineageAgent";
            case "cs" -> "ReportScheduleAgent";
            default -> "Agent-" + sessionId.substring(dash + 1);
        };
    }
}
