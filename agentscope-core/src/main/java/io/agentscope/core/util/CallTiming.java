package io.agentscope.core.util;

import io.agentscope.core.agent.RuntimeContext;
import java.util.Map;

/** Optional per-call diagnostics; observers must never affect execution. */
public final class CallTiming {
    private CallTiming() {}

    @FunctionalInterface
    public interface Observer {
        void record(String phase, long startedAtNanos, String status, Map<String, Integer> counts);
    }

    public static void record(RuntimeContext context, String phase, long start, String status) {
        record(context, phase, start, status, Map.of());
    }

    public static void record(
            RuntimeContext context,
            String phase,
            long start,
            String status,
            Map<String, Integer> counts) {
        try {
            Observer observer = context == null ? null : context.get(Observer.class);
            if (observer != null) observer.record(phase, start, status, counts);
        } catch (Exception ignored) {
            // Diagnostics cannot change completion, failure, or resource cleanup.
        }
    }

    public static void run(RuntimeContext context, String phase, Runnable operation) {
        long start = System.nanoTime();
        String status = "ERROR";
        try {
            operation.run();
            status = "SUCCESS";
        } finally {
            record(context, phase, start, status);
        }
    }
}
