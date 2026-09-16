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
package io.agentscope.dataagent.semantic.parser;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.dataagent.semantic.model.SemanticModel;
import org.springframework.stereotype.Component;

/**
 * 将 ontology.json 文本解析为 {@link SemanticModel}，或将 SemanticModel 序列化为 JSON。
 * 参考 WrenAI 的 MDL 解析流程。
 */
@Component
public class SemanticModelParser {

    private final ObjectMapper jsonMapper;

    public SemanticModelParser() {
        this.jsonMapper = new ObjectMapper();
    }

    /**
     * 解析 ontology.json 文本为 SemanticModel。
     *
     * @param jsonText ontology.json 的完整 JSON 文本
     * @return 解析后的 SemanticModel
     * @throws IllegalArgumentException JSON 内容为空或格式错误
     */
    public SemanticModel parse(String jsonText) {
        if (jsonText == null || jsonText.isBlank()) {
            throw new IllegalArgumentException("ontology.json 内容不能为空");
        }
        try {
            return jsonMapper.readValue(jsonText, SemanticModel.class);
        } catch (Exception e) {
            throw new IllegalArgumentException("ontology.json 解析失败: " + e.getMessage(), e);
        }
    }

    /**
     * 将 SemanticModel 序列化为 JSON 字符串（用于持久化存储）。
     *
     * @param model 语义模型
     * @return JSON 字符串
     */
    public String toJson(SemanticModel model) {
        try {
            return jsonMapper.writeValueAsString(model);
        } catch (Exception e) {
            throw new RuntimeException("SemanticModel JSON 序列化失败: " + e.getMessage(), e);
        }
    }

    /**
     * 将 SemanticModel 序列化为格式化的 JSON 字符串（用于导出）。
     *
     * @param model 语义模型
     * @return 格式化的 JSON 字符串
     */
    public String toPrettyJson(SemanticModel model) {
        try {
            return jsonMapper.writerWithDefaultPrettyPrinter().writeValueAsString(model);
        } catch (Exception e) {
            throw new RuntimeException("SemanticModel JSON 格式化失败: " + e.getMessage(), e);
        }
    }

    /**
     * 从 JSON 字符串反序列化为 SemanticModel。
     *
     * @param json JSON 字符串
     * @return SemanticModel
     */
    public SemanticModel fromJson(String json) {
        try {
            return jsonMapper.readValue(json, SemanticModel.class);
        } catch (Exception e) {
            throw new RuntimeException("SemanticModel JSON 反序列化失败: " + e.getMessage(), e);
        }
    }
}
