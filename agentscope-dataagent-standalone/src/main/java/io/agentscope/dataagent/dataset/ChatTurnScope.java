package io.agentscope.dataagent.dataset;

/** Runs scope validation and persistence only after this conversation's turn gate is acquired. */
public record ChatTurnScope(Runnable onAccepted) {
    public void accept() {
        onAccepted.run();
    }
}
