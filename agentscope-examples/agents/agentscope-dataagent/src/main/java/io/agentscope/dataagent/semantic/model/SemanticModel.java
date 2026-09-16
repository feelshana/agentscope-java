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
import java.util.ArrayList;
import java.util.List;

/**
 * 统一语义模型顶层，映射 ontology.json（参考 WrenAI MDL 格式）。
 * 包含六大部分：逻辑表（models）、关系（relationships）、Cube 预聚合指标（cubes）、
 * 业务规则（rules）、数据局限（limitations）和元信息。
 *
 * <p>面向 LLM 暴露，用于数据集描述、NL2SQL 语义映射和查询元数据格式化展示。
 * Agent 通过 get_semantic_context 工具获取本模型的 schema 描述和业务规则。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class SemanticModel {

    /** JSON Schema 标识。 */
    private String $schema;

    /** 布局版本号。 */
    private int layoutVersion;

    /** 数据库目录名（如 "migu_ops"）。 */
    private String catalog;

    /** 数据库 schema 名（如 "public"）。 */
    private String schema;

    /** 模型描述。 */
    private String description;

    /** 数据源类型：MYSQL / POSTGRESQL / H2 等。 */
    private String dataSource;

    /** 逻辑表列表。 */
    private List<SemanticModelTable> models = new ArrayList<>();

    /** 关系列表。 */
    private List<SemanticRelationship> relationships = new ArrayList<>();

    /** Cube 预聚合指标列表。 */
    private List<SemanticCube> cubes = new ArrayList<>();

    /** 业务规则列表。 */
    private List<SemanticRule> rules = new ArrayList<>();

    /** 数据局限列表。 */
    private List<SemanticLimitation> limitations = new ArrayList<>();

    public SemanticModel() {}

    // ---- 元信息 ----

    public String get$schema() {
        return $schema;
    }

    public void set$schema(String $schema) {
        this.$schema = $schema;
    }

    public int getLayoutVersion() {
        return layoutVersion;
    }

    public void setLayoutVersion(int layoutVersion) {
        this.layoutVersion = layoutVersion;
    }

    public String getCatalog() {
        return catalog;
    }

    public void setCatalog(String catalog) {
        this.catalog = catalog;
    }

    public String getSchema() {
        return schema;
    }

    public void setSchema(String schema) {
        this.schema = schema;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getDataSource() {
        return dataSource;
    }

    public void setDataSource(String dataSource) {
        this.dataSource = dataSource;
    }

    // ---- 核心集合 ----

    public List<SemanticModelTable> getModels() {
        return models;
    }

    public void setModels(List<SemanticModelTable> models) {
        this.models = models;
    }

    public List<SemanticRelationship> getRelationships() {
        return relationships;
    }

    public void setRelationships(List<SemanticRelationship> relationships) {
        this.relationships = relationships;
    }

    public List<SemanticCube> getCubes() {
        return cubes;
    }

    public void setCubes(List<SemanticCube> cubes) {
        this.cubes = cubes;
    }

    public List<SemanticRule> getRules() {
        return rules;
    }

    public void setRules(List<SemanticRule> rules) {
        this.rules = rules;
    }

    public List<SemanticLimitation> getLimitations() {
        return limitations;
    }

    public void setLimitations(List<SemanticLimitation> limitations) {
        this.limitations = limitations;
    }

    // ---- 辅助查询方法 ----

    /** 按逻辑名查找模型表。 */
    public SemanticModelTable findModel(String name) {
        if (name == null || models == null) {
            return null;
        }
        return models.stream().filter(m -> name.equals(m.getName())).findFirst().orElse(null);
    }

    /** 按名称查找 Cube。 */
    public SemanticCube findCube(String name) {
        if (name == null || cubes == null) {
            return null;
        }
        return cubes.stream().filter(c -> name.equals(c.getName())).findFirst().orElse(null);
    }

    /** 按名称查找关系。 */
    public SemanticRelationship findRelationship(String name) {
        if (name == null || relationships == null) {
            return null;
        }
        return relationships.stream()
                .filter(r -> name.equals(r.getName()))
                .findFirst()
                .orElse(null);
    }
}
