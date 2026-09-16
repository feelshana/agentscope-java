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
import java.util.Map;

/**
 * 语义列定义。映射到数据库物理列，附带类型、标签、业务描述和聚合注意事项。
 * 支持计算列（expression）和关系引用列（relationship）。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class SemanticColumn {

    /** 列名（物理列名或逻辑别名）。 */
    private String name;

    /** 数据类型：VARCHAR, INTEGER, BIGINT, DOUBLE, TIMESTAMP 等。 */
    private String type;

    /** 中文标签。 */
    private String label;

    /** 业务描述（含聚合注意事项，如"已聚合列，PV对它求和"）。 */
    private String description;

    /** 是否非空。 */
    private boolean notNull;

    /** 计算列表达式（如 "click_user.company"），仅 isCalculated=true 时有效。 */
    private String expression;

    /** 是否计算列。 */
    private boolean isCalculated;

    /** 引用的关系名（如 "clickPerformedBy"），用于关系列。 */
    private String relationship;

    public SemanticColumn() {}

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
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

    public boolean isNotNull() {
        return notNull;
    }

    public void setNotNull(boolean notNull) {
        this.notNull = notNull;
    }

    public String getExpression() {
        return expression;
    }

    public void setExpression(String expression) {
        this.expression = expression;
    }

    @com.fasterxml.jackson.annotation.JsonProperty("isCalculated")
    public boolean isCalculated() {
        return isCalculated;
    }

    @com.fasterxml.jackson.annotation.JsonProperty("isCalculated")
    public void setCalculated(boolean calculated) {
        isCalculated = calculated;
    }

    public String getRelationship() {
        return relationship;
    }

    public void setRelationship(String relationship) {
        this.relationship = relationship;
    }

    // ---- WrenAI MDL 格式兼容 ----

    /** WrenAI: "properties": { "label": "...", "note": "..." }。 */
    @JsonSetter("properties")
    public void setWrenProperties(Map<String, String> properties) {
        if (properties == null) return;
        if (this.label == null && properties.containsKey("label")) {
            this.label = properties.get("label");
        }
        // WrenAI 的 note 映射为我们的 description
        if (this.description == null && properties.containsKey("note")) {
            this.description = properties.get("note");
        }
    }
}
