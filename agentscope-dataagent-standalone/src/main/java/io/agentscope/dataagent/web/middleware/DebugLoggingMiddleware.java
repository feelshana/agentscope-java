/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.dataagent.web.middleware;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.message.AudioBlock;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.DataBlock;
import io.agentscope.core.message.HintBlock;
import io.agentscope.core.message.ImageBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ThinkingBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.message.VideoBlock;
import io.agentscope.core.middleware.AgentInput;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.middleware.ModelCallInput;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;

/**
 * Debug middleware that writes {@code logs/LLM.log} as a plain transcript meant to be read by a
 * human.
 *
 * <p>Each record carries exactly three fields — {@code role}, {@code type}, {@code text} — one
 * record per content block, and nothing else: no session ids, no iteration counters, no token
 * statistics, no JSON envelopes. Tool arguments are printed as {@code key: value} lines so
 * multi-line SQL keeps its line breaks, and JSON-encoded tool results are unescaped back into real
 * text.
 *
 * <p>The framework replays the whole message history on every reasoning turn, so a message is
 * written only the first time its id shows up within a session. The file therefore reads as one
 * continuous conversation instead of repeating the system prompt once per turn. Media blocks are
 * reduced to a placeholder — their payload would otherwise flood the log.
 */
public class DebugLoggingMiddleware implements MiddlewareBase {

    private static final Logger log = LoggerFactory.getLogger("LLM_DEBUG");

    /** Compact mapper, only used for non-string tool argument values. */
    private static final ObjectMapper MAPPER =
            new ObjectMapper().disable(SerializationFeature.FAIL_ON_EMPTY_BEANS);

    /** Bound on tracked sessions; the map is cleared past it (worst case: a message repeats). */
    private static final int MAX_TRACKED_SESSIONS = 64;

    /** Bound on tracked message ids per session; the set is cleared past it. */
    private static final int MAX_TRACKED_MESSAGES = 2048;

    private final Map<String, Set<String>> writtenIds = new HashMap<>();

    private final AtomicReference<StreamedReply> lastStreamedReply = new AtomicReference<>();

    private final AtomicBoolean resultWritten = new AtomicBoolean(false);

    @Override
    public Flux<AgentEvent> onAgent(
            Agent agent,
            RuntimeContext ctx,
            AgentInput input,
            Function<AgentInput, Flux<AgentEvent>> next) {
        String sessionId = ctx != null ? ctx.getSessionId() : null;
        resultWritten.set(false);
        return next.apply(input)
                .doOnNext(
                        event -> {
                            if (event instanceof AgentResultEvent result
                                    && result.getResult() != null) {
                                resultWritten.set(true);
                                write(sessionId, List.of(result.getResult()));
                            }
                        })
                .doFinally(
                        sig -> {
                            // Safety net for agents whose stream never carries an AgentResultEvent:
                            // the streamed reply is written only when no result message arrived, so
                            // the final answer is neither lost nor written twice.
                            if (resultWritten.get()) {
                                lastStreamedReply.set(null);
                                return;
                            }
                            StreamedReply reply = lastStreamedReply.getAndSet(null);
                            if (reply != null
                                    && Objects.equals(reply.sessionId(), sessionId)
                                    && !reply.text().isEmpty()) {
                                log.info(record(MsgRole.ASSISTANT, "text", reply.text()));
                            }
                        });
    }

    @Override
    public Flux<AgentEvent> onModelCall(
            Agent agent,
            RuntimeContext ctx,
            ModelCallInput input,
            Function<ModelCallInput, Flux<AgentEvent>> next) {
        String sessionId = ctx != null ? ctx.getSessionId() : null;
        write(sessionId, input.messages());

        StringBuilder reply = new StringBuilder();
        return next.apply(input)
                .doOnNext(
                        event -> {
                            if (event instanceof TextBlockDeltaEvent delta) {
                                reply.append(delta.getDelta());
                            }
                        })
                .doOnComplete(
                        () -> {
                            if (!reply.isEmpty()) {
                                lastStreamedReply.set(
                                        new StreamedReply(sessionId, reply.toString()));
                            }
                        });
    }

    /** Writes every message of {@code msgs} that has not been written yet for this session. */
    private void write(String sessionId, List<Msg> msgs) {
        for (String rendered : renderNew(sessionId, msgs)) {
            log.info(rendered);
        }
    }

    /**
     * Renders the messages that are new for {@code sessionId}, marking them as written. Package
     * private so the de-duplication can be asserted directly.
     */
    List<String> renderNew(String sessionId, List<Msg> msgs) {
        List<String> records = new ArrayList<>();
        if (msgs == null || msgs.isEmpty()) {
            return records;
        }
        synchronized (writtenIds) {
            Set<String> seen = seenIds(sessionId);
            for (Msg msg : msgs) {
                if (msg == null) {
                    continue;
                }
                if (seen != null) {
                    String id = msg.getId();
                    // A message without an id cannot be tracked, so it is always written.
                    if (id != null && !seen.add(id)) {
                        continue;
                    }
                }
                records.add(renderSafely(msg));
            }
        }
        return records;
    }

    /** Caller must hold the lock on {@link #writtenIds}. */
    private Set<String> seenIds(String sessionId) {
        if (sessionId == null) {
            return null;
        }
        if (!writtenIds.containsKey(sessionId) && writtenIds.size() >= MAX_TRACKED_SESSIONS) {
            writtenIds.clear();
        }
        Set<String> ids = writtenIds.computeIfAbsent(sessionId, key -> new HashSet<>());
        if (ids.size() >= MAX_TRACKED_MESSAGES) {
            ids.clear();
        }
        return ids;
    }

    private static String renderSafely(Msg msg) {
        try {
            return render(msg);
        } catch (Exception e) {
            return record(msg.getRole(), "text", "[render error: " + e.getMessage() + "]");
        }
    }

    /** Renders one message as a sequence of {@code role / type / text} records. */
    static String render(Msg msg) {
        List<ContentBlock> blocks = msg.getContent();
        if (blocks == null || blocks.isEmpty()) {
            return record(msg.getRole(), "text", "");
        }
        StringBuilder sb = new StringBuilder();
        for (ContentBlock block : blocks) {
            if (block == null) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(record(msg.getRole(), typeOf(block), textOf(block)));
        }
        return sb.toString();
    }

    private static String record(MsgRole role, String type, String text) {
        return "role: "
                + (role == null ? "UNKNOWN" : role.name())
                + "\ntype: "
                + type
                + "\ntext:\n"
                + (text == null ? "" : text)
                + "\n";
    }

    /** The block type names are the ones used by the framework's JSON discriminator. */
    private static String typeOf(ContentBlock block) {
        if (block instanceof TextBlock) {
            return "text";
        }
        if (block instanceof ThinkingBlock) {
            return "thinking";
        }
        if (block instanceof ToolUseBlock) {
            return "tool_use";
        }
        if (block instanceof ToolResultBlock) {
            return "tool_result";
        }
        if (block instanceof HintBlock) {
            return "hint";
        }
        if (block instanceof ImageBlock) {
            return "image";
        }
        if (block instanceof AudioBlock) {
            return "audio";
        }
        if (block instanceof VideoBlock) {
            return "video";
        }
        if (block instanceof DataBlock) {
            return "data";
        }
        return block.getClass().getSimpleName();
    }

    private static String textOf(ContentBlock block) {
        if (block instanceof TextBlock b) {
            return plain(b.getText());
        }
        if (block instanceof ThinkingBlock b) {
            return plain(b.getThinking());
        }
        if (block instanceof HintBlock b) {
            return plain(b.getHint());
        }
        if (block instanceof ToolUseBlock b) {
            return renderToolUse(b);
        }
        if (block instanceof ToolResultBlock b) {
            return renderToolResult(b);
        }
        if (block instanceof ImageBlock) {
            return "(image)";
        }
        if (block instanceof AudioBlock) {
            return "(audio)";
        }
        if (block instanceof VideoBlock) {
            return "(video)";
        }
        if (block instanceof DataBlock b) {
            return b.getName() == null ? "(data)" : "(data: " + b.getName() + ")";
        }
        return String.valueOf(block);
    }

    /** Tool name on the first line, then one indented line per argument. */
    private static String renderToolUse(ToolUseBlock block) {
        StringBuilder sb = new StringBuilder(nullToEmpty(block.getName()));
        Map<String, Object> input = block.getInput();
        if (input != null) {
            for (Map.Entry<String, Object> entry : input.entrySet()) {
                String value = stringify(entry.getValue());
                sb.append("\n  ").append(entry.getKey()).append(':');
                if (value.indexOf('\n') >= 0) {
                    sb.append('\n').append(indent(value));
                } else {
                    sb.append(' ').append(value);
                }
            }
        }
        return sb.toString();
    }

    /** Tool name on the first line, then the raw output text. */
    private static String renderToolResult(ToolResultBlock block) {
        StringBuilder sb = new StringBuilder(nullToEmpty(block.getName()));
        List<ContentBlock> output = block.getOutput();
        if (output != null) {
            for (ContentBlock out : output) {
                if (out != null) {
                    sb.append('\n').append(textOf(out));
                }
            }
        }
        return sb.toString();
    }

    private static String stringify(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof String s) {
            return plain(s);
        }
        if (value instanceof Number || value instanceof Boolean || value instanceof Character) {
            return value.toString();
        }
        try {
            return MAPPER.writeValueAsString(value);
        } catch (Exception e) {
            return String.valueOf(value);
        }
    }

    /**
     * Tool results reach us as JSON-encoded strings, so their line breaks are literal {@code \n}
     * pairs. Anything that looks like an encoded string is decoded back into readable text.
     */
    private static String plain(String text) {
        if (text == null) {
            return "";
        }
        if (text.length() < 2
                || text.charAt(0) != '"'
                || text.charAt(text.length() - 1) != '"'
                || text.indexOf('\\') < 0) {
            return text;
        }
        try {
            return MAPPER.readValue(text, String.class);
        } catch (Exception e) {
            return text;
        }
    }

    private static String indent(String text) {
        String[] lines = text.split("\n", -1);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            if (i > 0) {
                sb.append('\n');
            }
            sb.append("    ").append(lines[i]);
        }
        return sb.toString();
    }

    private static String nullToEmpty(String text) {
        return text == null ? "" : text;
    }

    /** The streamed reply of the latest model call, kept only as a fallback. */
    private record StreamedReply(String sessionId, String text) {}
}
