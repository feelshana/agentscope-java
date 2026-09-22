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

import static org.assertj.core.api.Assertions.assertThat;

import io.agentscope.core.message.ImageBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.message.URLSource;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Guards the human-readable shape of {@code logs/LLM.log}.
 *
 * <p>The log used to be a pretty-printed dump of the whole {@link Msg} list on every reasoning
 * turn, so each record carried ids, timestamps, token usage and metadata, the system prompt
 * repeated once per turn, and tool results arrived as JSON-encoded strings whose line breaks were
 * literal {@code \n} pairs. These tests pin the replacement contract: three fields per record
 * ({@code role} / {@code type} / {@code text}), readable tool arguments, decoded tool results, and
 * one write per message per session.
 */
class DebugLoggingMiddlewareTest {

    /** Fields of the old dump that must never come back. */
    private static final List<String> NOISE =
            List.of(
                    "\"id\"",
                    "\"timestamp\"",
                    "\"usage\"",
                    "\"metadata\"",
                    "session=",
                    "msgCount=",
                    "textLen=",
                    "toolNames=",
                    "RAW INPUT");

    @Test
    void rendersExactlyRoleTypeAndText() {
        Msg msg =
                Msg.builder()
                        .id("m1")
                        .role(MsgRole.SYSTEM)
                        .content(TextBlock.builder().text("# 角色\n\n你是数据分析助手").build())
                        .build();

        String rendered = DebugLoggingMiddleware.render(msg);

        assertThat(rendered).isEqualTo("role: SYSTEM\ntype: text\ntext:\n# 角色\n\n你是数据分析助手\n");
        assertThat(rendered).doesNotContain(NOISE.toArray(String[]::new));
    }

    /**
     * The SQL is what a human actually reads in this log, so tool arguments must keep their line
     * breaks instead of being collapsed into one escaped JSON string.
     */
    @Test
    void rendersToolUseArgumentsAsReadableLines() {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("source_id", "ea70a5a4");
        input.put(
                "sql",
                "WITH leaders AS (\n"
                        + "    SELECT DISTINCT account FROM users\n"
                        + ")\n"
                        + "SELECT * FROM leaders");
        input.put("row_limit", 200);
        Msg msg =
                Msg.builder()
                        .id("m2")
                        .role(MsgRole.ASSISTANT)
                        .content(
                                TextBlock.builder().text("先查领导名单").build(),
                                ToolUseBlock.builder()
                                        .id("call_1")
                                        .name("query_structured_data")
                                        .input(input)
                                        .build())
                        .build();

        String rendered = DebugLoggingMiddleware.render(msg);

        assertThat(rendered)
                .contains("role: ASSISTANT\ntype: text\ntext:\n先查领导名单\n")
                .contains("type: tool_use")
                .contains("query_structured_data")
                .contains("  source_id: ea70a5a4")
                .contains(
                        "  sql:\n    WITH leaders AS (\n        SELECT DISTINCT account FROM users")
                .contains("  row_limit: 200");
        assertThat(rendered).doesNotContain("\\n").doesNotContain(NOISE.toArray(String[]::new));
    }

    /**
     * Tool results reach the middleware as JSON-encoded strings; without decoding, the log shows
     * {@code \"## 标题\\n\\n...} and the schema tables inside are unreadable.
     */
    @Test
    void decodesJsonEncodedToolResults() {
        Msg msg =
                Msg.builder()
                        .id("m3")
                        .role(MsgRole.TOOL)
                        .content(
                                ToolResultBlock.builder()
                                        .id("call_1")
                                        .name("prepare_data_context")
                                        .output(
                                                TextBlock.builder()
                                                        .text("\"## 点击详情\\n\\n| 字段 | 类型 |\"")
                                                        .build())
                                        .build())
                        .build();

        String rendered = DebugLoggingMiddleware.render(msg);

        assertThat(rendered)
                .contains("role: TOOL")
                .contains("type: tool_result")
                .contains("prepare_data_context")
                .contains("## 点击详情\n\n| 字段 | 类型 |");
        assertThat(rendered).doesNotContain("\\n").doesNotContain("\\\"");
    }

    /**
     * Media payloads are replaced by a placeholder: a base64 image would otherwise bury the
     * conversation.
     */
    @Test
    void replacesMediaPayloadsWithPlaceholder() {
        Msg msg =
                Msg.builder()
                        .id("m4")
                        .role(MsgRole.USER)
                        .content(new ImageBlock(new URLSource("https://example.com/chart.png")))
                        .build();

        assertThat(DebugLoggingMiddleware.render(msg))
                .contains("role: USER")
                .contains("type: image")
                .contains("(image)")
                .doesNotContain("chart.png");
    }

    /**
     * The framework replays the whole history on every turn, so writing each message once per
     * session is what keeps the file a linear transcript. Messages of a different session — and
     * messages without an id, which cannot be tracked — are still written.
     */
    @Test
    void writesEachMessageOncePerSession() {
        DebugLoggingMiddleware middleware = new DebugLoggingMiddleware();
        Msg system = textMsg("s1", MsgRole.SYSTEM, "系统提示词");
        Msg question = textMsg("u1", MsgRole.USER, "有哪些领导访问了");

        assertThat(middleware.renderNew("session-a", List.of(system, question))).hasSize(2);
        // second reasoning turn of the same invocation: history replayed, nothing new to write
        assertThat(middleware.renderNew("session-a", List.of(system, question))).isEmpty();
        // another session writes its own copy even though the ids coincide
        assertThat(middleware.renderNew("session-b", List.of(system))).hasSize(1);
        // without a session key there is nothing to de-duplicate against
        assertThat(middleware.renderNew(null, List.of(system))).hasSize(1);
        assertThat(middleware.renderNew("session-a", List.of())).isEmpty();
    }

    private static Msg textMsg(String id, MsgRole role, String text) {
        return Msg.builder()
                .id(id)
                .role(role)
                .content(TextBlock.builder().text(text).build())
                .build();
    }
}
