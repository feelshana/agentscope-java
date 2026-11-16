package io.agentscope.core.agent.accumulator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.ModelException;
import io.agentscope.core.util.JsonUtils;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class ReasoningContextToolArgumentsTest {
    private ChatResponse chunk(String raw, String finish) {
        return ChatResponse.builder()
                .content(
                        List.of(
                                ToolUseBlock.builder()
                                        .id("call_html")
                                        .name("write_file")
                                        .content(raw)
                                        .build()))
                .finishReason(finish)
                .build();
    }

    @Test
    void truncatedHtmlStopsBeforeEmptyParametersCanBeExecuted() {
        var context = new ReasoningContext("dashboard");
        context.processChunk(chunk("{\"content\":\"<html>unfinished", null));
        var error =
                assertThrows(
                        ModelException.class,
                        () ->
                                context.processChunk(
                                        ChatResponse.builder()
                                                .content(List.of())
                                                .finishReason("length")
                                                .build()));
        assertTrue(error.getMessage().contains("长度上限"));
        assertFalse(error.getMessage().contains("<html>"));
        assertEquals(1, context.outputDiagnostics().get("argumentCalls"));
    }

    @Test
    void completedMalformedArgumentsFailWithoutClaimingMissingProperties() {
        var context = new ReasoningContext("dashboard");
        context.processChunk(chunk("{\"content\":\"unterminated", "tool_calls"));
        assertThrows(ModelException.class, context::validateCompleteArguments);
    }

    @Test
    void completeLongHtmlKeepsPathAndContentAcrossFragments() {
        String html = "<html lang=\"zh-CN\">\n" + "<p>销售额与订单\\趋势</p>\n".repeat(1800) + "</html>";
        String raw =
                JsonUtils.getJsonCodec()
                        .toJson(Map.of("path", "apps/dashboard/index.html", "content", html));
        var context = new ReasoningContext("dashboard");
        for (int i = 0; i < raw.length(); i += 19) {
            context.processChunk(chunk(raw.substring(i, Math.min(i + 19, raw.length())), null));
        }
        context.processChunk(
                ChatResponse.builder().content(List.of()).finishReason("tool_calls").build());
        context.validateCompleteArguments();
        ToolUseBlock tool = (ToolUseBlock) context.buildFinalMessage().getContent().get(0);
        assertEquals(html, tool.getInput().get("content"));
        assertEquals("apps/dashboard/index.html", tool.getInput().get("path"));
        assertTrue(context.outputDiagnostics().get("argumentFragments") > 1000);
    }

    @Test
    void interruptedPartialMessageStillCanBeSavedWithoutBecomingExecutable() {
        var context = new ReasoningContext("dashboard");
        context.processChunk(chunk("{\"content\":\"partial", null));
        assertNotNull(context.buildFinalMessage());
        assertThrows(ModelException.class, context::validateCompleteArguments);
    }
}
