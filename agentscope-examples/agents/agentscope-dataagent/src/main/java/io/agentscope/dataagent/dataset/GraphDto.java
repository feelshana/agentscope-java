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
package io.agentscope.dataagent.dataset;

import java.util.List;

/**
 * Payload for the deterministic dataset-relationship graph rendered in the KB "知识图谱" tab.
 * Nodes are tables (one per dataset); edges are inferred table relations with a confidence score
 * so the UI can filter weak links.
 */
public record GraphDto(List<Node> nodes, List<Edge> edges) {

    /** {@code origin} is {@code upload} or {@code datasource} (dataset provenance). */
    public record Node(String id, String label, String type, List<Field> fields, String origin) {}

    public record Field(String name, String sqlType, String description) {}

    /**
     * {@code origin} marks how the relation was discovered: {@code column-heuristic} for live
     * schema inference, or the persisted {@code SAME_COLUMN}/{@code SUFFIX}/{@code DOC}/{@code LLM}
     * relation type stored by {@code RelationInferenceService}.
     */
    public record Edge(
            String id,
            String source,
            String target,
            String relationType,
            double confidence,
            String label,
            String origin) {}
}
