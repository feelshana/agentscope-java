package io.agentscope.dataagent.runtime.context;

/** Stable invocation correlation keys shared by runtime observers and transport adapters. */
public final class InvocationContextKeys {
    public static final String REQUEST_ID = "toolEventRequestId";
    public static final String RUN_ID = "toolEventRunId";
    public static final String PARENT_CALL_ID = "toolEventParentCallId";

    private InvocationContextKeys() {}
}
