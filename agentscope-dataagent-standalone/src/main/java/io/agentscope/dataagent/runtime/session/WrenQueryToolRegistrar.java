package io.agentscope.dataagent.runtime.session;

import io.agentscope.dataagent.runtime.DataAgentBootstrap;
import io.agentscope.harness.agent.HarnessAgent;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Puts the {@link WrenToolForwarder} stand-ins on the shared query agent toolkit once at
 * startup, after the bootstrap has built every agent (same pattern as the modeling registrar).
 */
@Component
public class WrenQueryToolRegistrar {
    public static final String QUERY_AGENT_ID = "data-agent";
    private static final Logger log = LoggerFactory.getLogger(WrenQueryToolRegistrar.class);
    private final DataAgentBootstrap bootstrap;

    public WrenQueryToolRegistrar(DataAgentBootstrap bootstrap) {
        this.bootstrap = bootstrap;
    }

    @PostConstruct
    public void register() {
        HarnessAgent query = bootstrap.agents().get(QUERY_AGENT_ID);
        if (query == null) {
            log.warn(
                    "WrenQueryToolRegistrar: agent '{}' not found; official Wren tools stay"
                            + " unavailable",
                    QUERY_AGENT_ID);
            return;
        }
        WrenToolForwarder.registerAll(query.getDelegate().getToolkit());
        log.info("Registered official Wren tool stand-ins onto agent '{}'", query.getName());
    }
}
