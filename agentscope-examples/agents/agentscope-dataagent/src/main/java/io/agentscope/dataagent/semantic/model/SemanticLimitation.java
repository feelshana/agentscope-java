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
 * 数据局限定义。描述源数据无法支撑的正式回答，
 * 编译器附加到查询结果而非让自信的数字独立存在。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class SemanticLimitation {

    /** 局限 ID（如 "click_window"）。 */
    private String id;

    /** 局限名称（如 "点击数据时间范围"）。 */
    private String name;

    /** 局限描述（如 "实际记录只出现在9月1日到7日"）。 */
    private String description;

    public SemanticLimitation() {}

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
}
