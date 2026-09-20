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
package io.agentscope.dataagent.web.catalog;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.model.Model;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.dataagent.runtime.DataAgentBootstrap;
import io.agentscope.dataagent.runtime.config.OntologyConfigEntry;
import io.agentscope.dataagent.runtime.gateway.HarnessGateway;
import io.agentscope.dataagent.web.toolbus.ToolEventBus;
import io.agentscope.dataagent.web.toolbus.ToolNotificationMiddleware;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.memory.compaction.CompactionConfig;
import io.agentscope.harness.agent.memory.compaction.ToolResultEvictionConfig;
import io.agentscope.harness.agent.tools.McpServerConfig;
import io.agentscope.harness.agent.tools.McpServerRegistrar;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Builds and registers isolated ontology agents from {@code ontologies.json}. Each ontology agent
 * has only MCP tools (no filesystem, shell, or knowledge-base middleware) and lives in its own
 * session namespace ({@code ontology-{id}}), completely separate from the knowledge-base agents.
 *
 * <p>Configuration is loaded from {@code ~/.agentscope/dataagent/ontologies.json}. When the file
 * does not exist, the service starts with an empty ontology list (backward-compatible).
 */
public class OntologyAgentService {

    private static final Logger log = LoggerFactory.getLogger(OntologyAgentService.class);

    /** Prefix for ontology agent IDs registered in the gateway. */
    public static final String ONTOLOGY_PREFIX = "ontology-";

    private final DataAgentBootstrap bootstrap;
    private final Model model;
    private final ToolEventBus toolEventBus;
    private final ObjectMapper objectMapper;

    /** Registered ontology entries keyed by ontology id (insertion order preserved). */
    private final Map<String, OntologyConfigEntry> ontologies = new LinkedHashMap<>();

    /** Gateway agent IDs that have been successfully registered. */
    private final ConcurrentHashMap<String, String> registeredGatewayIds =
            new ConcurrentHashMap<>();

    public OntologyAgentService(
            DataAgentBootstrap bootstrap, Model model, ToolEventBus toolEventBus) {
        this.bootstrap = bootstrap;
        this.model = model;
        this.toolEventBus = toolEventBus;
        this.objectMapper = new ObjectMapper().findAndRegisterModules();
    }

    /**
     * Loads {@code ontologies.json} and builds + registers an agent for each entry. Failures on
     * individual ontologies are logged but do not prevent other ontologies from registering.
     */
    public void buildAll() {
        Map<String, OntologyConfigEntry> loaded = loadConfig();
        if (loaded.isEmpty()) {
            log.info("No ontology configurations found; ontology agents disabled.");
            return;
        }
        for (Map.Entry<String, OntologyConfigEntry> entry : loaded.entrySet()) {
            String ontologyId = entry.getKey();
            OntologyConfigEntry cfg = entry.getValue();
            try {
                buildAndRegister(ontologyId, cfg);
                ontologies.put(ontologyId, cfg);
                log.info("Ontology agent '{}' registered successfully.", ontologyId);
            } catch (Exception e) {
                log.warn(
                        "Failed to build ontology agent '{}': {}. Skipping.",
                        ontologyId,
                        e.getMessage());
            }
        }
        log.info("Ontology agents built: {} of {} succeeded.", ontologies.size(), loaded.size());
    }

    /** Returns the list of successfully registered ontologies as {id, name} pairs. */
    public List<OntologySummary> listOntologies() {
        List<OntologySummary> result = new ArrayList<>();
        for (Map.Entry<String, OntologyConfigEntry> e : ontologies.entrySet()) {
            String name = e.getValue().getName() != null ? e.getValue().getName() : e.getKey();
            result.add(new OntologySummary(e.getKey(), name));
        }
        return Collections.unmodifiableList(result);
    }

    /** Returns {@code true} if the given agentId is an ontology agent. */
    public boolean isOntologyAgent(String agentId) {
        return agentId != null && agentId.startsWith(ONTOLOGY_PREFIX);
    }

    // -----------------------------------------------------------------
    //  Internals
    // -----------------------------------------------------------------

    private void buildAndRegister(String ontologyId, OntologyConfigEntry cfg) {
        String gatewayAgentId = ONTOLOGY_PREFIX + ontologyId;
        HarnessGateway gateway = bootstrap.gateway();

        HarnessAgent.Builder b = HarnessAgent.builder();
        String displayName = cfg.getName() != null ? cfg.getName() : ontologyId;
        b.name(displayName);
        b.sysPrompt(
                "你是一个专注于「"
                        + displayName
                        + "」的数据查询与分析助手。"
                        + "请仅使用提供的 MCP 工具进行数据查询和分析。"
                        + "不要使用文件系统或 shell 工具。");

        if (model != null) {
            b.model(model);
        }

        // Only MCP tools — disable built-in tools
        b.disableFilesystemTools();
        b.disableShellTool();

        // Register MCP server
        Toolkit toolkit = new Toolkit();
        McpServerConfig mcpServer = cfg.getMcpServer();
        if (mcpServer == null) {
            throw new IllegalArgumentException(
                    "Ontology '" + ontologyId + "' is missing 'mcpServer' configuration.");
        }
        Map<String, McpServerConfig> mcpServers = Map.of(ontologyId, mcpServer);
        McpServerRegistrar.register(toolkit, mcpServers);
        b.toolkit(toolkit);

        // Tool event notification middleware (for frontend SSE streaming)
        b.middleware(new ToolNotificationMiddleware(toolEventBus));

        // Context management: evict large tool results to files
        b.toolResultEviction(
                ToolResultEvictionConfig.builder().maxResultChars(4_000).previewChars(500).build());

        // Compaction: higher trigger + aggressive pruning
        b.compaction(
                CompactionConfig.builder()
                        .triggerMessages(80)
                        .keepMessages(20)
                        .prune(
                                CompactionConfig.PruneConfig.builder()
                                        .protectTokens(10_000)
                                        .minimumTokens(5_000)
                                        .maxOutputChars(1_000)
                                        .build())
                        .build());

        HarnessAgent agent = b.build();
        gateway.registerAgent(gatewayAgentId, agent);
        registeredGatewayIds.put(ontologyId, gatewayAgentId);
    }

    private Map<String, OntologyConfigEntry> loadConfig() {
        Path configFile = resolveConfigPath();
        if (configFile == null || !Files.exists(configFile)) {
            log.info("ontologies.json not found at {}; using empty ontology list.", configFile);
            return Map.of();
        }
        try {
            String json = Files.readString(configFile);
            return objectMapper.readValue(
                    json, new TypeReference<Map<String, OntologyConfigEntry>>() {});
        } catch (IOException e) {
            log.warn("Failed to read ontologies.json at {}: {}", configFile, e.getMessage());
            return Map.of();
        }
    }

    private Path resolveConfigPath() {
        // Use the same directory pattern as agentscope.json
        String userHome = System.getProperty("user.home");
        if (userHome != null) {
            Path p = Path.of(userHome, ".agentscope", "dataagent", "ontologies.json");
            if (Files.exists(p)) return p;
        }
        // Fallback: look in cwd/.agentscope/dataagent/
        Path cwd = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        return cwd.resolve(".agentscope").resolve("dataagent").resolve("ontologies.json");
    }

    /** Summary record returned by {@link #listOntologies()}. */
    public record OntologySummary(String id, String name) {}
}
