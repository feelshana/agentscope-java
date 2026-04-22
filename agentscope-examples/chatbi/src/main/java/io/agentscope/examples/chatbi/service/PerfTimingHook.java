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
import io.agentscope.core.hook.PostActingEvent;
import io.agentscope.core.hook.PostReasoningEvent;
import io.agentscope.core.hook.PreActingEvent;
import io.agentscope.core.hook.PreReasoningEvent;
import io.agentscope.core.message.ToolUseBlock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

/**
 * Hook that records fine-grained timing statistics for each LLM iteration and tool invocation.
 *
 * <p>All timing data is written exclusively to {@code logs/chatbi-perf.log} via the dedicated
 * {@code "perf.timing"} logger (configured in logback.xml, additivity=false).
 *
 * <p>Each log line includes:
 * <ul>
 *   <li>{@code session} — session ID (main or sub-agent session)</li>
 *   <li>{@code agent}   — agent name (RouterAgent / GuAgent / ...)</li>
 *   <li>{@code iter}    — ReAct iteration index (0-based)</li>
 *   <li>{@code tool}    — tool name (only for tool timing entries)</li>
 *   <li>{@code costMs}  — elapsed milliseconds</li>
 *   <li>{@code at}      — timestamp when the event completed</li>
 * </ul>
 *
 * <p>Summary statistics (total LLM time, total tool time, total session time) are emitted
 * when the agent finishes processing (on the first call after {@code maxIters} or when the
 * Flux completes). Since we cannot reliably detect "agent done" from a Hook, the summary
 * is written after each PostReasoning that results in no tool call (final answer iteration).
 */
public class PerfTimingHook implements Hook {

    private static final Logger perfLog = LoggerFactory.getLogger("perf.timing");

    private static final DateTimeFormatter TIMESTAMP_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

    private final String sessionId;
    private final String agentName;

    // ── Iteration tracking ──────────────────────────────────────────────────
    private final AtomicInteger iterIndex = new AtomicInteger(0);

    /** nanoTime when the current LLM call started (set in PreReasoning) */
    private volatile long llmStartNs;

    /** nanoTime when the current tool call started (set in PreActing) */
    private final ConcurrentHashMap<String, Long> toolStartNsMap = new ConcurrentHashMap<>();

    // ── Cumulative stats ────────────────────────────────────────────────────
    private final AtomicLong totalLlmMs = new AtomicLong(0);
    private final AtomicLong totalToolMs = new AtomicLong(0);

    /** nanoTime when this agent was first invoked (first PreReasoning) */
    private volatile long agentStartNs = 0;

    public PerfTimingHook(String sessionId, String agentName) {
        this.sessionId = sessionId;
        this.agentName = agentName;
    }

    @Override
    public int priority() {
        // Run very late — after all business hooks — so timing reflects the full hook chain.
        return 950;
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T extends HookEvent> Mono<T> onEvent(T event) {
        if (event instanceof PreReasoningEvent) {
            onPreReasoning();
        } else if (event instanceof PostReasoningEvent post) {
            onPostReasoning(post);
        } else if (event instanceof PreActingEvent pre) {
            onPreActing(pre);
        } else if (event instanceof PostActingEvent post) {
            onPostActing(post);
        }
        return Mono.just(event);
    }

    // ─────────────────── Event handlers ───────────────────

    private void onPreReasoning() {
        long now = System.nanoTime();
        llmStartNs = now;
        if (agentStartNs == 0) {
            agentStartNs = now;
        }
    }

    private void onPostReasoning(PostReasoningEvent post) {
        long costMs = nanosToMillis(System.nanoTime() - llmStartNs);
        totalLlmMs.addAndGet(costMs);
        int iter = iterIndex.getAndIncrement();

        // Check if this iteration produced tool calls
        List<ToolUseBlock> toolUses =
                post.getReasoningMessage() == null
                        ? List.of()
                        : post.getReasoningMessage().getContentBlocks(ToolUseBlock.class);

        String toolNames =
                toolUses.isEmpty()
                        ? "(final-answer)"
                        : toolUses.stream()
                                .map(ToolUseBlock::getName)
                                .reduce((a, b) -> a + "," + b)
                                .orElse("");

        perfLog.info(
                "[LLM-ITER] session={} agent={} iter={} costMs={} tool_decision={} at={}",
                sessionId,
                agentName,
                iter,
                costMs,
                toolNames,
                nowStr());

        // If final answer (no tools) — print session summary
        if (toolUses.isEmpty()) {
            printSummary(iter + 1);
        }
    }

    private void onPreActing(PreActingEvent pre) {
        String toolName = pre.getToolUse() == null ? "unknown" : pre.getToolUse().getName();
        toolStartNsMap.put(toolName, System.nanoTime());
        perfLog.info(
                "[TOOL-START] session={} agent={} tool={} at={}",
                sessionId,
                agentName,
                toolName,
                nowStr());
    }

    private void onPostActing(PostActingEvent post) {
        String toolName = pre(post);
        Long startNs = toolStartNsMap.remove(toolName);
        long costMs = startNs == null ? -1 : nanosToMillis(System.nanoTime() - startNs);
        totalToolMs.addAndGet(Math.max(costMs, 0));

        perfLog.info(
                "[TOOL-END]   session={} agent={} tool={} costMs={} at={}",
                sessionId,
                agentName,
                toolName,
                costMs,
                nowStr());
    }

    // ─────────────────── Summary ───────────────────

    private void printSummary(int totalIters) {
        long agentCostMs = agentStartNs == 0 ? 0 : nanosToMillis(System.nanoTime() - agentStartNs);
        perfLog.info(
                "[SUMMARY]    session={} agent={} totalIters={} llmTotalMs={} toolTotalMs={}"
                        + " agentTotalMs={} at={}",
                sessionId,
                agentName,
                totalIters,
                totalLlmMs.get(),
                totalToolMs.get(),
                agentCostMs,
                nowStr());
    }

    // ─────────────────── Helpers ───────────────────

    /** Extract tool name from PostActingEvent safely. */
    private static String pre(PostActingEvent post) {
        if (post.getToolUse() == null) return "unknown";
        String name = post.getToolUse().getName();
        return name == null ? "unknown" : name;
    }

    private static long nanosToMillis(long nanos) {
        return nanos / 1_000_000L;
    }

    private static String nowStr() {
        return LocalDateTime.ofInstant(Instant.now(), ZoneId.systemDefault()).format(TIMESTAMP_FMT);
    }
}
