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
package io.agentscope.dataagent.ontology.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 本体模型顶层，映射 model.yaml。包含业务对象、关系、指标、规则和数据局限五部分。
 * 面向 LLM 暴露，用于数据集描述、NL2SQL 语义映射和查询元数据格式化展示。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class OntologyModel {

    private String version;
    private String name;
    private String base_uri;
    private Map<String, OntologyObject> objects = new LinkedHashMap<>();
    private Map<String, OntologyRelationship> relationships = new LinkedHashMap<>();
    private Map<String, OntologyMetric> metrics = new LinkedHashMap<>();
    private Map<String, OntologyRule> rules = new LinkedHashMap<>();
    private List<OntologyLimitation> limitations = new ArrayList<>();

    public String getVersion() {
        return version;
    }

    public void setVersion(String version) {
        this.version = version;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getBase_uri() {
        return base_uri;
    }

    public void setBase_uri(String base_uri) {
        this.base_uri = base_uri;
    }

    public Map<String, OntologyObject> getObjects() {
        return objects;
    }

    public void setObjects(Map<String, OntologyObject> objects) {
        this.objects = objects;
    }

    public Map<String, OntologyRelationship> getRelationships() {
        return relationships;
    }

    public void setRelationships(Map<String, OntologyRelationship> relationships) {
        this.relationships = relationships;
    }

    public Map<String, OntologyMetric> getMetrics() {
        return metrics;
    }

    public void setMetrics(Map<String, OntologyMetric> metrics) {
        this.metrics = metrics;
    }

    public Map<String, OntologyRule> getRules() {
        return rules;
    }

    public void setRules(Map<String, OntologyRule> rules) {
        this.rules = rules;
    }

    public List<OntologyLimitation> getLimitations() {
        return limitations;
    }

    public void setLimitations(List<OntologyLimitation> limitations) {
        this.limitations = limitations;
    }
}
