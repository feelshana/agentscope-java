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
 * Cube 度量定义。描述一个预聚合指标（如 SUM(visit_count)），LLM 只需引用度量名，
 * 后端 CubeQueryToSqlConverter 自动生成聚合表达式。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class CubeMeasure {

    /** 度量名（LLM 引用的标识，如 "click_pv"）。 */
    private String name;

    /** SQL 聚合表达式（如 "SUM(visit_count)"），后端生成 SQL 用。 */
    private String expression;

    /** 返回值类型：BIGINT, DOUBLE, DECIMAL 等。 */
    private String type;

    /** 业务描述（含常见错误警告，如"用行数代替是错的"）。 */
    private String description;

    public CubeMeasure() {}

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
