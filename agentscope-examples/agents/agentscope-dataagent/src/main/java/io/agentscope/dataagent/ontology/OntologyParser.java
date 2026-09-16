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
package io.agentscope.dataagent.ontology;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.agentscope.dataagent.ontology.model.OntologyModel;
import org.springframework.stereotype.Component;

/**
 * 将 model.yaml 文本解析为 {@link OntologyModel}，或将 OntologyModel 序列化为 YAML。
 */
@Component
public class OntologyParser {

    private final ObjectMapper yamlMapper;
    private final ObjectMapper jsonMapper;

    public OntologyParser() {
        this.yamlMapper = new ObjectMapper(new YAMLFactory());
        this.jsonMapper = new ObjectMapper();
    }

    /**
     * 解析 model.yaml 文本为 OntologyModel。
     */
    public OntologyModel parse(String yamlText) {
        if (yamlText == null || yamlText.isBlank()) {
            throw new IllegalArgumentException("model.yaml 内容不能为空");
        }
        try {
            return yamlMapper.readValue(yamlText, OntologyModel.class);
        } catch (Exception e) {
            throw new IllegalArgumentException("model.yaml 解析失败: " + e.getMessage(), e);
        }
    }

    /**
     * 将 OntologyModel 序列化为 JSON 字符串（用于 parsed_json 存储）。
     */
    public String toJson(OntologyModel model) {
        try {
            return jsonMapper.writeValueAsString(model);
        } catch (Exception e) {
            throw new RuntimeException("OntologyModel JSON 序列化失败: " + e.getMessage(), e);
        }
    }

    /**
     * 从 JSON 字符串反序列化为 OntologyModel。
     */
    public OntologyModel fromJson(String json) {
        try {
            return jsonMapper.readValue(json, OntologyModel.class);
        } catch (Exception e) {
            throw new RuntimeException("OntologyModel JSON 反序列化失败: " + e.getMessage(), e);
        }
    }

    /**
     * 将 OntologyModel 序列化为 YAML 文本。
     */
    public String toYaml(OntologyModel model) {
        try {
            return yamlMapper.writerWithDefaultPrettyPrinter().writeValueAsString(model);
        } catch (Exception e) {
            throw new RuntimeException("OntologyModel YAML 序列化失败: " + e.getMessage(), e);
        }
    }
}
