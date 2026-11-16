package io.agentscope.dataagent.runtime.auth;

/** Minimal read-only caller identity used by asset policies, independent of HTTP and passwords. */
public interface UserIdentity {
    boolean exists(String userId);

    boolean isAdministrator(String userId);
}
