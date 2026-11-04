package io.agentscope.core.util;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.agent.RuntimeContext;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class CallTimingTest {
    @Test
    void observerFailureDoesNotChangeOperationOrItsFailure() {
        var context =
                RuntimeContext.builder()
                        .put(
                                CallTiming.Observer.class,
                                (phase, start, status, counts) -> {
                                    throw new IllegalStateException("observer");
                                })
                        .build();
        assertDoesNotThrow(() -> CallTiming.run(context, "save", () -> {}));
        var original = new IllegalArgumentException("operation");
        assertSame(
                original,
                assertThrows(
                        IllegalArgumentException.class,
                        () ->
                                CallTiming.run(
                                        context,
                                        "save",
                                        () -> {
                                            throw original;
                                        })));
        assertDoesNotThrow(() -> CallTiming.run(null, "save", () -> {}));
    }

    @Test
    void observersAreIsolatedAndReceiveMonotonicSpans() {
        List<String> first = new ArrayList<>();
        List<String> second = new ArrayList<>();
        var one =
                RuntimeContext.builder()
                        .put(
                                CallTiming.Observer.class,
                                (phase, start, status, counts) -> {
                                    assertTrue(start <= System.nanoTime());
                                    first.add(phase + status);
                                })
                        .build();
        var two =
                RuntimeContext.builder()
                        .put(
                                CallTiming.Observer.class,
                                (phase, start, status, counts) -> second.add(phase + status))
                        .build();
        CallTiming.run(one, "save", () -> {});
        assertThrows(
                IllegalStateException.class,
                () ->
                        CallTiming.run(
                                two,
                                "read",
                                () -> {
                                    throw new IllegalStateException();
                                }));
        assertEquals(List.of("saveSUCCESS"), first);
        assertEquals(List.of("readERROR"), second);
    }
}
