/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.examples.dataanalysis.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Represents a dataset metadata item returned by the list-datasets API.
 */
public class DatasetInfo {

    @JsonProperty("id")
    private String id;

    @JsonProperty("description")
    private String description;

    @JsonProperty("agentId")
    private String agentId;

    public DatasetInfo() {}

    public DatasetInfo(String id, String description) {
        this.id = id;
        this.description = description;
    }

    public DatasetInfo(String id, String description, String agentId) {
        this.id = id;
        this.description = description;
        this.agentId = agentId;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getAgentId() {
        return agentId;
    }

    public void setAgentId(String agentId) {
        this.agentId = agentId;
    }

    @Override
    public String toString() {
        return "DatasetInfo{id='" + id + "', description='" + description + "', agentId='" + agentId + "'}";
    }
}
