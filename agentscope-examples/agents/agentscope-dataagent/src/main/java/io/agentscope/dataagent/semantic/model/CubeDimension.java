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

/**
 * Cube 维度定义。描述可用于分组、过滤的非聚合列。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class CubeDimension {

    /** 维度名（LLM 引用的标识，如 "module_name"）。 */
    private String name;

    /** SQL 表达式（如 "module_name"），后端生成 GROUP BY 用。 */
    private String expression;

    /** 数据类型：VARCHAR, INTEGER 等。 */
    private String type;

    /** 业务描述。 */
    private String description;

    public CubeDimension() {}

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getExpression() {
        return expression;
    }

    public void setExpression(String expression) {
        this.expression = expression;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }
}
