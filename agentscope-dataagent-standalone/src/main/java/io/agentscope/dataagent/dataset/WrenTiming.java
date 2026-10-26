package io.agentscope.dataagent.dataset;

import java.util.UUID;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Measures platform stages without serializing command arguments or business content. */
public final class WrenTiming implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(WrenTiming.class);
    private final String trace = UUID.randomUUID().toString();
    private final String operation;
    private final String group;
    private final long started = System.nanoTime();
    private boolean failed;

    public WrenTiming(String operation, String group) {
        this.operation = operation;
        this.group = group == null ? "none" : group;
    }

    public <T> T measure(String phase, Supplier<T> action) {
        long start = System.nanoTime();
        String outcome = "ok";
        try {
            return action.get();
        } catch (RuntimeException | Error e) {
            failed = true;
            outcome = "failed";
            throw e;
        } finally {
            record(phase, start, outcome);
        }
    }

    public void record(String phase, long start, String outcome) {
        if (!"ok".equals(outcome)) failed = true;
        log.info(
                "[wren-timing] trace={} operation={} group={} phase={} elapsedMs={} outcome={}",
                trace,
                operation,
                group,
                phase,
                (System.nanoTime() - start) / 1_000_000.0,
                outcome);
    }

    @Override
    public void close() {
        record("total", started, failed ? "failed" : "ok");
    }
}
