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
package io.agentscope.dataagent.runtime.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.agentscope.harness.agent.tools.McpServerConfig;

/**
 * Configuration entry for an ontology definition loaded from {@code ontologies.json}. Each entry
 * defines an isolated MCP-only agent that the user can select from the frontend. Ontology agents
 * are completely separate from knowledge-base agents — they have only MCP tools, no filesystem or
 * shell.
 */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
@JsonIgnoreProperties(ignoreUnknown = true)
public class OntologyConfigEntry {

    /** Display name shown in the frontend dropdown (e.g. "供应链本体"). */
    @JsonProperty("name")
    private String name;

    /** MCP server configuration for this ontology's data query/analysis tools. */
    @JsonProperty("mcpServer")
    private McpServerConfig mcpServer;

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public McpServerConfig getMcpServer() {
        return mcpServer;
    }

    public void setMcpServer(McpServerConfig mcpServer) {
        this.mcpServer = mcpServer;
    }
}
