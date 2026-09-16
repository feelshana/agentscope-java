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
package io.agentscope.dataagent.ontology.source.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.ArrayList;
import java.util.List;

/**
 * sources.json 顶层模型。
 *
 * <pre>
 * { "batches": [...], "derived": [...], "relationships": [...] }
 * </pre>
 *
 * <p>{@code relationships} 为可选段，声明对象间 JOIN 关系（写入 DatasetRelationEntity）。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class SourceManifest {

    /** 数据批次列表。 */
    private List<SourceBatch> batches = new ArrayList<>();

    /** 派生表列表，按声明顺序执行。 */
    private List<DerivedTable> derived = new ArrayList<>();

    /** 对象间关系声明（可选）。 */
    private List<ManifestRelationship> relationships = new ArrayList<>();

    public List<SourceBatch> getBatches() {
        return batches;
    }

    public void setBatches(List<SourceBatch> batches) {
        this.batches = batches;
    }

    public List<DerivedTable> getDerived() {
        return derived;
    }

    public void setDerived(List<DerivedTable> derived) {
        this.derived = derived;
    }

    public List<ManifestRelationship> getRelationships() {
        return relationships;
    }

    public void setRelationships(List<ManifestRelationship> relationships) {
        this.relationships = relationships;
    }

    /**
     * 单条关系声明：source→target，通过 source_column=target_column 关联。
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ManifestRelationship {
        private String source;
        private String sourceColumn;
        private String target;
        private String targetColumn;
        private String joinType;
        private String label;

        public String getSource() {
            return source;
        }

        public void setSource(String source) {
            this.source = source;
        }

        public String getSourceColumn() {
            return sourceColumn;
        }

        public void setSourceColumn(String sourceColumn) {
            this.sourceColumn = sourceColumn;
        }

        public String getTarget() {
            return target;
        }

        public void setTarget(String target) {
            this.target = target;
        }

        public String getTargetColumn() {
            return targetColumn;
        }

        public void setTargetColumn(String targetColumn) {
            this.targetColumn = targetColumn;
        }

        public String getJoinType() {
            return joinType;
        }

        public void setJoinType(String joinType) {
            this.joinType = joinType;
        }

        public String getLabel() {
            return label;
        }

        public void setLabel(String label) {
            this.label = label;
        }
    }
}
