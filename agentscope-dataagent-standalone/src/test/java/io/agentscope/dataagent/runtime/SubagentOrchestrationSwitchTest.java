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
package io.agentscope.dataagent.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import io.agentscope.dataagent.web.config.DataAgentConfig;
import java.lang.reflect.Field;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

/**
 * Guards the subagent-orchestration switch of ADR 0009.
 *
 * <p>The harness injects a fixed {@code ## Subagents} system-prompt section (~6.2k chars, 45.8 % of
 * the observed system message) plus the {@code agent_*} / {@code task_*} tool schemas on every
 * turn, and this product shape never delegates. Turning the switch back on by accident therefore
 * silently burns ~1600-2000 tokens per turn, which no functional test would notice — hence these
 * assertions pin the <em>default</em> rather than the behaviour.
 *
 * <p>What is deliberately not tested here: that {@code disableSubagents()} leaves the gateway and
 * session routing intact. That contract lives in harness {@code
 * HarnessAgentBuilderSupport#buildSubagentEntries}, which is package-private, so asserting it would
 * require a same-package test in another module. It is covered by the runtime acceptance steps of
 * spec 003 instead (chat must keep working with the flag off) and documented in ADR 0009.
 *
 * <p>The API/UI surface of the switch is intentionally absent as well: this deployment only uses
 * the chat and knowledge-configuration pages, so {@code WorkspaceSummary} does not carry the flag
 * and the configure pages are untouched (spec 003 keeps that design for later).
 */
class SubagentOrchestrationSwitchTest {

    private static final String KEY = "dataagent.agent.subagents-enabled";

    /** The wiring-side default must stay {@code false} — the whole point of ADR 0009. */
    @Test
    void configFieldDefaultsSubagentsToDisabled() throws NoSuchFieldException {
        Field field = DataAgentConfig.class.getDeclaredField("subagentsEnabled");
        assertThat(field.getType()).isEqualTo(boolean.class);

        Value annotation = field.getAnnotation(Value.class);
        assertThat(annotation).as("%s must be read from configuration", KEY).isNotNull();
        assertThat(annotation.value())
                .as("subagents must default to disabled")
                .isEqualTo("${" + KEY + ":false}");
    }

    /**
     * The shipped YAML must agree with the {@code @Value} default. Two independent "false"
     * declarations that drift apart would leave the effective default ambiguous to anyone reading
     * only one of them.
     */
    @Test
    void applicationYmlDeclaresSubagentsDisabledByDefault() throws Exception {
        List<PropertySource<?>> sources =
                new YamlPropertySourceLoader()
                        .load("application", new ClassPathResource("application.yml"));

        Object raw = null;
        for (PropertySource<?> source : sources) {
            if (source.containsProperty(KEY)) {
                raw = source.getProperty(KEY);
                break;
            }
        }

        assertThat(raw).as("%s must be declared in application.yml", KEY).isNotNull();
        assertThat(String.valueOf(raw))
                .as("the env override must fall back to false")
                .startsWith("${DATAAGENT_SUBAGENTS_ENABLED")
                .endsWith(":false}");
    }
}
