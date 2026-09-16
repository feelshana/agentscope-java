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
package io.agentscope.dataagent.ontology.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 本体业务对象定义。kind 区分维度表（dimension）和事实表（fact）。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class OntologyObject {

    private String label;
    private String table;

    /** "dimension" 或 "fact"。 */
    private String kind;

    /** 身份键列名。 */
    private String identity;

    private String description;

    /** 仅事实表有：时间字段名。 */
    private String time_field;

    /** 仅事实表有：来源批次 ID 列表。 */
    private List<String> source_batches;

    /** 快照粒度标记（如 "snapshot"）。 */
    private String grain;

    private Map<String, ObjectProperty> properties = new LinkedHashMap<>();

    public String getLabel() {
        return label;
    }

    public void setLabel(String label) {
        this.label = label;
    }

    public String getTable() {
        return table;
    }

    public void setTable(String table) {
        this.table = table;
    }

    public String getKind() {
        return kind;
    }

    public void setKind(String kind) {
        this.kind = kind;
    }

    public String getIdentity() {
        return identity;
    }

    public void setIdentity(String identity) {
        this.identity = identity;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getTime_field() {
        return time_field;
    }

    public void setTime_field(String time_field) {
        this.time_field = time_field;
    }

    public List<String> getSource_batches() {
        return source_batches;
    }

    public void setSource_batches(List<String> source_batches) {
        this.source_batches = source_batches;
    }

    public String getGrain() {
        return grain;
    }

    public void setGrain(String grain) {
        this.grain = grain;
    }

    public Map<String, ObjectProperty> getProperties() {
        return properties;
    }

    public void setProperties(Map<String, ObjectProperty> properties) {
        this.properties = properties;
    }
}
