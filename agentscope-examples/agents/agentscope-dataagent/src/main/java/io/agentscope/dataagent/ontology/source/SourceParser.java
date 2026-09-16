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
package io.agentscope.dataagent.ontology.source;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.dataagent.ontology.source.model.SourceManifest;
import org.springframework.stereotype.Component;

/**
 * 将 sources.json 文本解析为 {@link SourceManifest}。基于 Jackson JSON，忽略未知字段以保持前向兼容。
 */
@Component
public class SourceParser {

    private final ObjectMapper jsonMapper;

    public SourceParser() {
        this.jsonMapper = new ObjectMapper();
    }

    /**
     * 解析 sources.json 文本。
     *
     * @param jsonText sources.json 完整内容
     * @return 解析后的 SourceManifest
     * @throws IllegalArgumentException 当 JSON 格式不合法时
     */
    public SourceManifest parse(String jsonText) {
        if (jsonText == null || jsonText.isBlank()) {
            throw new IllegalArgumentException("sources.json 内容不能为空");
        }
        try {
            return jsonMapper.readValue(jsonText, SourceManifest.class);
        } catch (Exception e) {
            throw new IllegalArgumentException("sources.json 解析失败: " + e.getMessage(), e);
        }
    }

    /**
     * 将 SourceManifest 序列化为 JSON 文本。
     */
    public String toJson(SourceManifest manifest) {
        try {
            return jsonMapper.writerWithDefaultPrettyPrinter().writeValueAsString(manifest);
        } catch (Exception e) {
            throw new IllegalArgumentException("SourceManifest 序列化失败: " + e.getMessage(), e);
        }
    }
}
