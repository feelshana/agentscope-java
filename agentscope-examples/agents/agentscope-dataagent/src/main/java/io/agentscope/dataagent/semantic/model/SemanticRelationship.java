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
import java.util.List;
import java.util.Map;

/**
 * 语义关系定义。预定义 JOIN 条件，LLM 写查询时只需引用 model 名称，
 * 后端根据 relationship 自动展开 JOIN。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class SemanticRelationship {

    /** 关系名（如 "clickPerformedBy"）。 */
    private String name;

    /** 关系两端的 model 名称（如 ["click_observation", "user_object"]）。 */
    private List<String> models;

    /** JOIN 类型：MANY_TO_ONE | ONE_TO_MANY | MANY_TO_MANY | ONE_TO_ONE。 */
    private String joinType;

    /** JOIN 条件表达式（如 "click_observation.account = user_object.account"）。 */
    private String condition;

    /** 中文标签。 */
    private String label;

    /** 业务描述。 */
    private String description;

    public SemanticRelationship() {}

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public List<String> getModels() {
        return models;
    }

    public void setModels(List<String> models) {
        this.models = models;
    }

    public String getJoinType() {
        return joinType;
    }

    public void setJoinType(String joinType) {
        this.joinType = joinType;
    }

    public String getCondition() {
        return condition;
    }

    public void setCondition(String condition) {
        this.condition = condition;
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

    // ---- WrenAI MDL 格式兼容 ----

    /** WrenAI: "properties": { "label": "...", "description"/"note": "..." }。 */
    @JsonSetter("properties")
    public void setWrenProperties(Map<String, String> properties) {
        if (properties == null) return;
        if (this.label == null && properties.containsKey("label")) {
            this.label = properties.get("label");
        }
        if (this.description == null) {
            String desc = properties.get("description");
            if (desc == null) desc = properties.get("note");
            if (desc != null) this.description = desc;
        }
    }
}
