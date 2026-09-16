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
 * Cube 预聚合指标定义。Cube 是语义建模的核心概念——将聚合逻辑预定义，
 * LLM 只需选择 Cube 中的 measure 和 dimension，不需要自己写 SUM/COUNT/GROUP BY。
 *
 * <p>一个 Cube 对应一个基础模型（baseObject），包含：
 * <ul>
 *   <li>measures — 度量指标（如 SUM(visit_count)、COUNT(DISTINCT account)）</li>
 *   <li>dimensions — 可用分组维度（如 module_name、event_company）</li>
 *   <li>timeDimensions — 时间维度（用于范围过滤和粒度分组）</li>
 * </ul>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class SemanticCube {

    /** Cube 名称（如 "click_metrics"）。 */
    private String name;

    /** 基础模型名（引用的 SemanticModelTable.name，如 "click_observation"）。 */
    private String baseObject;

    /** 中文标签。 */
    private String label;

    /** 业务描述（含聚合注意事项）。 */
    private String description;

    /** 度量列表。 */
    private List<CubeMeasure> measures = new ArrayList<>();

    /** 维度列表。 */
    private List<CubeDimension> dimensions = new ArrayList<>();

    /** 时间维度列表。 */
    private List<CubeTimeDimension> timeDimensions = new ArrayList<>();

    public SemanticCube() {}

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getBaseObject() {
        return baseObject;
    }

    public void setBaseObject(String baseObject) {
        this.baseObject = baseObject;
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

    public List<CubeMeasure> getMeasures() {
        return measures;
    }

    public void setMeasures(List<CubeMeasure> measures) {
        this.measures = measures;
    }

    public List<CubeDimension> getDimensions() {
        return dimensions;
    }

    public void setDimensions(List<CubeDimension> dimensions) {
        this.dimensions = dimensions;
    }

    public List<CubeTimeDimension> getTimeDimensions() {
        return timeDimensions;
    }

    public void setTimeDimensions(List<CubeTimeDimension> timeDimensions) {
        this.timeDimensions = timeDimensions;
    }

    // ---- WrenAI MDL 格式兼容 ----

    /** WrenAI: "properties": { "description": "..." }。 */
    @JsonSetter("properties")
    public void setWrenProperties(Map<String, String> properties) {
        if (properties == null) return;
        if (this.description == null && properties.containsKey("description")) {
            this.description = properties.get("description");
        }
    }
}
