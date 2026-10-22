package io.agentscope.dataagent.web.middleware;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.middleware.ActingInput;
import io.agentscope.core.middleware.AgentInput;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.middleware.ModelCallInput;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import reactor.core.publisher.Flux;

/** Invocation-local timings; durations are monotonic and overlapping spans are not summed. */
public final class ExecutionTimingMiddleware implements MiddlewareBase {
    private static final org.slf4j.Logger LOG =
            org.slf4j.LoggerFactory.getLogger("EXECUTION_TIMING");
    private static final com.fasterxml.jackson.databind.ObjectMapper JSON =
            new com.fasterxml.jackson.databind.ObjectMapper();

    private record Trace(String id, AtomicInteger models, AtomicInteger tools) {}

    public static String traceId(RuntimeContext context) {
        Trace trace = context == null ? null : context.get(Trace.class);
        return trace == null ? "" : trace.id();
    }

    @Override
    public int order() {
        return 50;
    }

    public static void record(
            RuntimeContext ctx, String phase, String call, long start, String status) {
        try {
            Trace trace = ctx == null ? null : ctx.get(Trace.class);
            Map<String, Object> fields = new LinkedHashMap<>();
            fields.put("at", java.time.Instant.now().toString());
            fields.put("traceId", trace == null ? "" : trace.id());
            fields.put("sessionId", ctx == null ? "" : Objects.toString(ctx.getSessionId(), ""));
            fields.put("phase", phase);
            fields.put("callId", call);
            fields.put("durationMs", (System.nanoTime() - start) / 1_000_000);
            fields.put("status", status);
            if (trace != null) {
                fields.put("modelCalls", trace.models().get());
                fields.put("toolCalls", trace.tools().get());
            }
            LOG.info(JSON.writeValueAsString(fields));
        } catch (Exception ignored) {
            /* Observability must not change execution. */
        }
    }

    @Override
    public Flux<AgentEvent> onAgent(
            Agent agent,
            RuntimeContext ctx,
            AgentInput input,
            Function<AgentInput, Flux<AgentEvent>> next) {
        return Flux.defer(
                () -> {
                    ctx.put(
                            Trace.class,
                            new Trace(
                                    UUID.randomUUID().toString(),
                                    new AtomicInteger(),
                                    new AtomicInteger()));
                    long start = System.nanoTime();
                    record(ctx, "turn.start", "", start, "START");
                    return Flux.defer(() -> next.apply(input))
                            .doFinally(s -> record(ctx, "turn.end", "", start, s.name()));
                });
    }

    @Override
    public Flux<AgentEvent> onModelCall(
            Agent agent,
            RuntimeContext ctx,
            ModelCallInput input,
            Function<ModelCallInput, Flux<AgentEvent>> next) {
        return Flux.defer(
                () -> {
                    Trace t = ctx.get(Trace.class);
                    String id = "model-" + (t == null ? 0 : t.models().incrementAndGet());
                    long start = System.nanoTime();
                    var first = new java.util.concurrent.atomic.AtomicBoolean();
                    record(ctx, "model.start", id, start, "START");
                    return next.apply(input)
                            .doOnNext(
                                    e -> {
                                        if (e instanceof TextBlockDeltaEvent
                                                && first.compareAndSet(false, true))
                                            record(ctx, "model.first_output", id, start, "OUTPUT");
                                    })
                            .doFinally(s -> record(ctx, "model.end", id, start, s.name()));
                });
    }

    @Override
    public Flux<AgentEvent> onActing(
            Agent agent,
            RuntimeContext ctx,
            ActingInput input,
            Function<ActingInput, Flux<AgentEvent>> next) {
        return Flux.defer(
                () -> {
                    Map<String, Long> active = new ConcurrentHashMap<>();
                    Map<String, String> names = new HashMap<>();
                    for (var tool : input.toolCalls()) {
                        long start = System.nanoTime();
                        active.put(tool.getId(), start);
                        names.put(tool.getId(), tool.getName());
                        Trace t = ctx.get(Trace.class);
                        if (t != null) t.tools().incrementAndGet();
                        record(
                                ctx,
                                "tool." + tool.getName() + ".start",
                                tool.getId(),
                                start,
                                "START");
                    }
                    return next.apply(input)
                            .doOnNext(
                                    e -> {
                                        if (e instanceof ToolResultEndEvent end) {
                                            Long start = active.remove(end.getToolCallId());
                                            if (start != null)
                                                record(
                                                        ctx,
                                                        "tool." + end.getToolCallName() + ".end",
                                                        end.getToolCallId(),
                                                        start,
                                                        end.getState().name());
                                        }
                                    })
                            .doFinally(
                                    s ->
                                            active.forEach(
                                                    (id, start) ->
                                                            record(
                                                                    ctx,
                                                                    "tool."
                                                                            + names.get(id)
                                                                            + ".end",
                                                                    id,
                                                                    start,
                                                                    s.name())));
                });
    }
}
