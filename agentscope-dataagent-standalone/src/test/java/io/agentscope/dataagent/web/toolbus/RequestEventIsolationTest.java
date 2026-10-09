package io.agentscope.dataagent.web.toolbus;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RequestEventIsolationTest {
    @Test
    void sameSessionDifferentTurnsDoNotReceiveEachOthersEvents() {
        ToolEventBus bus = new ToolEventBus();
        List<ToolEventBus.ToolEvent> received = new ArrayList<>();
        var subscription = bus.subscribeRequest("request-a").subscribe(received::add);
        bus.publish(
                ToolEventBus.ToolEvent.toolCall(
                        "same-session", "run_python", "other", Map.of(), "request-b", "run"));
        bus.publish(
                ToolEventBus.ToolEvent.toolCall(
                        "same-session", "run_python", "own", Map.of(), "request-a", "run"));
        bus.publish(
                ToolEventBus.ToolEvent.toolCall(
                        "same-session", "run_python", "legacy", Map.of(), null, "run"));
        subscription.dispose();
        assertThat(received).extracting(ToolEventBus.ToolEvent::toolCallId).containsExactly("own");
    }
}
