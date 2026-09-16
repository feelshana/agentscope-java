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
import java.util.Map;

/**
 * 业务规则定义。描述数据分析时的业务约束（如"活跃用户=近30天有访问"），
 * 系统提示词中注入 LLM 上下文，并在 SQL 生成时参考 sqlHint。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class SemanticRule {

    /** 规则 ID（如 "R1"）。 */
    private String id;

    /** 规则名称（如 "活跃用户定义"）。 */
    private String name;

    /** 规则描述。 */
    private String description;

    /** 规则参数（如 {"window_days": 30}）。 */
    private Map<String, Object> parameters;

    /** SQL 提示（如 "WHERE click_time >= CURRENT_DATE - INTERVAL '30 days'"），辅助 SQL 生成。 */
    private String sqlHint;

    public SemanticRule() {}

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public Map<String, Object> getParameters() {
        return parameters;
    }

    public void setParameters(Map<String, Object> parameters) {
        this.parameters = parameters;
    }

    public String getSqlHint() {
        return sqlHint;
    }

    public void setSqlHint(String sqlHint) {
        this.sqlHint = sqlHint;
    }
}
