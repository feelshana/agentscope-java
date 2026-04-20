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
package io.agentscope.examples.dataanalysis.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Utility class for recording performance metrics at key stages of the data-analysis pipeline.
 *
 * <p>Log format:
 *
 * <pre>
 * [PERF] session=xxx | stage=first_token | latency=1234ms
 * [PERF] session=xxx | stage=tool_call | tool=query_dataset | latency=5678ms | resultSize=1234
 * [PERF] session=xxx | stage=complete | total=15234ms
 * </pre>
 *
 * <p>All methods are static and safe to call from any thread.
 */
public final class PerformanceMonitor {

    private static final Logger log = LoggerFactory.getLogger(PerformanceMonitor.class);

    private PerformanceMonitor() {
        // Utility class – no instantiation
    }

    /**
     * Log the latency of a pipeline stage.
     *
     * @param sessionId the session identifier
     * @param stage a short label for the stage (e.g. {@code "first_token"}, {@code "complete"})
     * @param startTimeMs the epoch-millisecond timestamp when the stage started
     */
    public static void logLatency(String sessionId, String stage, long startTimeMs) {
        long latency = System.currentTimeMillis() - startTimeMs;
        log.info("[PERF] session={} | stage={} | latency={}ms", sessionId, stage, latency);
    }

    /**
     * Log the latency and result size of a tool call.
     *
     * @param sessionId the session identifier
     * @param toolName the name of the tool that was called
     * @param startTimeMs the epoch-millisecond timestamp when the tool call started
     * @param resultSize the character length of the tool result (for size awareness)
     */
    public static void logToolCall(
            String sessionId, String toolName, long startTimeMs, int resultSize) {
        long latency = System.currentTimeMillis() - startTimeMs;
        log.info(
                "[PERF] session={} | stage=tool_call | tool={} | latency={}ms | resultSize={}",
                sessionId,
                toolName,
                latency,
                resultSize);
    }

    /**
     * Log the total end-to-end latency when the assistant turn completes.
     *
     * @param sessionId the session identifier
     * @param startTimeMs the epoch-millisecond timestamp when the chat request was received
     */
    public static void logComplete(String sessionId, long startTimeMs) {
        long total = System.currentTimeMillis() - startTimeMs;
        log.info("[PERF] session={} | stage=complete | total={}ms", sessionId, total);
    }
}
