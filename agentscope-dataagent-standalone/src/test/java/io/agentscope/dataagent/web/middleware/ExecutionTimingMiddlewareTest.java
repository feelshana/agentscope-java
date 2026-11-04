package io.agentscope.dataagent.web.middleware;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.middleware.ActingInput;
import io.agentscope.core.middleware.AgentInput;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;

class ExecutionTimingMiddlewareTest {
    @Test
    void modelAndToolErrorsHaveCorrelatedTerminalTimings() {
        Logger logger = (Logger) LoggerFactory.getLogger("EXECUTION_TIMING");
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            var middleware = new ExecutionTimingMiddleware();
            var ctx =
                    RuntimeContext.builder()
                            .userId("a")
                            .sessionId("session")
                            .put(
                                    io.agentscope.dataagent.web.toolbus.ToolEventBus
                                            .REQUEST_ID_CONTEXT_KEY,
                                    "request-test")
                            .build();
            var tool =
                    ToolUseBlock.builder()
                            .id("tool-1")
                            .name("run_sql")
                            .input(java.util.Map.of())
                            .build();
            middleware
                    .onAgent(
                            null,
                            ctx,
                            new AgentInput(List.of()),
                            ignored ->
                                    middleware
                                            .onModelCall(
                                                    null,
                                                    ctx,
                                                    null,
                                                    call ->
                                                            Flux.just(
                                                                    new TextBlockDeltaEvent(
                                                                            "r", "b", "ok")))
                                            .concatWith(
                                                    middleware.onActing(
                                                            null,
                                                            ctx,
                                                            new ActingInput(List.of(tool)),
                                                            in ->
                                                                    Flux.just(
                                                                            new ToolResultEndEvent(
                                                                                    "r",
                                                                                    "tool-1",
                                                                                    "run_sql",
                                                                                    ToolResultState
                                                                                            .ERROR)))))
                    .blockLast();
            io.agentscope.core.util.CallTiming.run(ctx, "completion.state.save", () -> {});
            io.agentscope.core.util.CallTiming.record(
                    ctx,
                    "completion.transcript.segment_sync",
                    System.nanoTime(),
                    "SUCCESS",
                    java.util.Map.of("entriesMerged", 0));
            var records = appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
            assertThat(records).anyMatch(s -> s.contains("model.first_output"));
            assertThat(records).anyMatch(s -> s.contains("completion.model.tail"));
            assertThat(records)
                    .anyMatch(
                            s ->
                                    s.contains("completion.transcript.segment_sync")
                                            && s.contains("request-test")
                                            && s.contains("\"entriesMerged\":0"));
            assertThat(records)
                    .anyMatch(
                            s -> s.contains("completion.state.save") && s.contains("request-test"));
            assertThat(records)
                    .anyMatch(s -> s.contains("tool.run_sql.end") && s.contains("ERROR"));
            assertThat(records)
                    .anyMatch(s -> s.contains("turn.end") && s.contains("\"modelCalls\":1"));
            assertThat(records)
                    .allMatch(
                            s ->
                                    s.contains("\"sessionId\":\"session\"")
                                            && !s.contains("\"traceId\":\"\""));
            assertThat(records).noneMatch(s -> s.contains("SELECT"));
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }
}
