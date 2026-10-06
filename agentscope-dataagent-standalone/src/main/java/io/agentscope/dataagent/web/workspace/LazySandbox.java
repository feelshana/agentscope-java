/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.dataagent.web.workspace;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.sandbox.ExecResult;
import io.agentscope.harness.agent.sandbox.Sandbox;
import io.agentscope.harness.agent.sandbox.SandboxState;
import java.io.InputStream;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Lazy-initializing {@link Sandbox} wrapper that defers container creation until the first actual
 * operation (exec, persistWorkspace, hydrateWorkspace).
 *
 * <p>This wrapper is used by {@link HarnessGateway#attachUserSandboxContext} for agents that may
 * not need a sandbox on every turn (e.g., pure SQL queries). The middleware calls {@link #start()}
 * on every agent call, but this wrapper treats start() as a no-op — the container is only created
 * when a tool actually executes a command or writes a file.
 *
 * <p>Lifecycle:
 * <ul>
 *   <li>{@link #start()} — no-op (container not created yet)
 *   <li>{@link #exec}, {@link #persistWorkspace}, {@link #hydrateWorkspace} — trigger container
 *       creation on first call via the supplier, then delegate to the real sandbox
 *   <li>{@link #close()}, {@link #stop()}, {@link #shutdown()} — no-op (registry manages lifecycle)
 *   <li>Subsequent calls within the registry's idle TTL reuse the same container (zero overhead)
 * </ul>
 *
 * <p>Thread safety: the supplier is called at most once per LazySandbox instance. The delegate
 * field is volatile to ensure visibility across threads, but the supplier itself should be
 * idempotent (e.g., {@link UserSandboxRegistry#borrow} which uses ConcurrentHashMap.compute).
 */
public final class LazySandbox implements Sandbox {

    private static final Logger log = LoggerFactory.getLogger(LazySandbox.class);

    private final Supplier<Sandbox> supplier;
    private volatile Sandbox delegate;

    /**
     * Creates a lazy sandbox that defers container creation until first use.
     *
     * @param supplier called on first operation to obtain the real sandbox; typically
     *     {@code () -> registry.borrow(userId, agentId)}
     */
    public LazySandbox(Supplier<Sandbox> supplier) {
        this.supplier = supplier;
    }

    /**
     * Returns the delegate sandbox, creating it on first call. Thread-safe via volatile field +
     * synchronized block.
     */
    private Sandbox ensureDelegate() throws Exception {
        Sandbox d = delegate;
        if (d != null) {
            return d;
        }
        synchronized (this) {
            if (delegate != null) {
                return delegate;
            }
            log.info("[lazy-sandbox] first operation — creating container now");
            delegate = supplier.get();
            try {
                delegate.start();
            } catch (Exception e) {
                log.error("[lazy-sandbox] failed to start delegate sandbox", e);
                throw e;
            }
            return delegate;
        }
    }

    @Override
    public void start() {
        // No-op: container creation is deferred until first exec/persist/hydrate.
        // The middleware calls start() on every agent turn; we skip it here to avoid
        // creating a container for pure SQL queries that never touch the sandbox.
    }

    @Override
    public void stop() {
        // No-op: registry manages lifecycle (idle TTL eviction).
    }

    @Override
    public void shutdown() {
        // No-op: registry manages lifecycle.
    }

    @Override
    public void close() {
        // No-op: registry manages lifecycle. Do NOT close the delegate here — the registry
        // owns it and will close it on idle eviction or JVM shutdown.
    }

    @Override
    public boolean isRunning() {
        Sandbox d = delegate;
        if (d == null) {
            return false;
        }
        try {
            return d.isRunning();
        } catch (Exception e) {
            log.warn("[lazy-sandbox] isRunning check failed", e);
            return false;
        }
    }

    @Override
    public SandboxState getState() {
        Sandbox d = delegate;
        if (d == null) {
            return null;
        }
        return d.getState();
    }

    @Override
    public ExecResult exec(RuntimeContext runtimeContext, String command, Integer timeoutSeconds)
            throws Exception {
        return ensureDelegate().exec(runtimeContext, command, timeoutSeconds);
    }

    @Override
    public InputStream persistWorkspace() throws Exception {
        return ensureDelegate().persistWorkspace();
    }

    @Override
    public void hydrateWorkspace(InputStream archive) throws Exception {
        ensureDelegate().hydrateWorkspace(archive);
    }
}
