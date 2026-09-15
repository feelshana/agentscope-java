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
package io.agentscope.dataagent.semantic.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonSetter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 语义逻辑表定义。对应一张物理表，但 Agent 以业务名称引用。
 * kind 区分维度表（dimension）和事实表（fact）。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class SemanticModelTable {

    /** 逻辑名（Agent 引用的名字，如 "click_observation"）。 */
    private String name;

    /** 物理表名（如 "ds_click_observation"）。 */
    private String tableName;

    /** 主键列名。 */
    private String primaryKey;

    /** 中文标签（如 "点击观察"）。 */
    private String label;

    /** 业务描述（含聚合注意事项）。 */
    private String description;

    /** "dimension"（维度表）| "fact"（事实表）。 */
    private String kind;

    /** 列定义列表。 */
    private List<SemanticColumn> columns = new ArrayList<>();

    public SemanticModelTable() {}

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getTableName() {
        return tableName;
    }

    public void setTableName(String tableName) {
        this.tableName = tableName;
    }

    public String getPrimaryKey() {
        return primaryKey;
    }

    public void setPrimaryKey(String primaryKey) {
        this.primaryKey = primaryKey;
    }

    public String getLabel() {
        return label;
    }

    public void setLabel(String label) {
        this.label = label;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getKind() {
        return kind;
    }

    public void setKind(String kind) {
        this.kind = kind;
    }

    public List<SemanticColumn> getColumns() {
        return columns;
    }

    public void setColumns(List<SemanticColumn> columns) {
        this.columns = columns;
    }

    // ---- WrenAI MDL 格式兼容 ----

    /** WrenAI: "tableReference": { "table": "user_object" } → tableName。 */
    @JsonSetter("tableReference")
    public void setTableReference(Map<String, String> tableReference) {
        if (tableReference != null && tableReference.containsKey("table")) {
            this.tableName = tableReference.get("table");
        }
    }

    /** WrenAI: "properties": { "label": "...", "description": "..." }。 */
    @JsonSetter("properties")
    public void setWrenProperties(Map<String, String> properties) {
        if (properties == null) return;
        if (this.label == null && properties.containsKey("label")) {
            this.label = properties.get("label");
        }
        if (this.description == null && properties.containsKey("description")) {
            this.description = properties.get("description");
        }
    }
}
