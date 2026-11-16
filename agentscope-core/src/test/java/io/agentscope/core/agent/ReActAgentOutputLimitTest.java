package io.agentscope.core.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import io.agentscope.core.tool.Toolkit;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

@Tag("unit")
class ReActAgentOutputLimitTest {
    public static final class Writes {
        final AtomicInteger count = new AtomicInteger();

        @Tool(description = "write")
        public String write_file(
                @ToolParam(name = "path", description = "path") String path,
                @ToolParam(name = "content", description = "content") String content) {
            count.incrementAndGet();
            return "written";
        }
    }

    @Test
    void outputLimitTerminatesOnceWithoutExecutingOrRetryingAnIncompleteWrite() {
        AtomicInteger calls = new AtomicInteger();
        Model model =
                new Model() {
                    public String getModelName() {
                        return "limit-fixture";
                    }

                    public Flux<ChatResponse> stream(
                            List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
                        calls.incrementAndGet();
                        return Flux.just(
                                ChatResponse.builder()
                                        .content(
                                                List.of(
                                                        ToolUseBlock.builder()
                                                                .id("call_html")
                                                                .name("write_file")
                                                                .content(
                                                                        "{\"content\":\"<html>unfinished")
                                                                .build()))
                                        .build(),
                                ChatResponse.builder()
                                        .content(List.of())
                                        .finishReason("length")
                                        .build());
                    }
                };
        var writes = new Writes();
        var toolkit = new Toolkit();
        toolkit.registerTool(writes);
        var agent =
                ReActAgent.builder()
                        .name("limit-test")
                        .model(model)
                        .toolkit(toolkit)
                        .maxIters(35)
                        .build();
        StepVerifier.create(agent.streamEvents(List.of(new UserMessage("generate"))))
                .thenConsumeWhile(event -> true)
                .expectErrorMatches(error -> error.getMessage().contains("长度上限"))
                .verify(Duration.ofSeconds(5));
        assertEquals(1, calls.get());
        assertEquals(0, writes.count.get());
    }
}
