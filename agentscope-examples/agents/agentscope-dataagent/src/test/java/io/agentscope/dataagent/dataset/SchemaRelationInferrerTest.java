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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.dataagent.dataset.parser.ColumnSchema;
import io.agentscope.dataagent.web.persistence.jpa.DatasetEntity;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Deterministic relation-inference rules: PK/FK exact, shared column, suffix strip, dedupe. */
class SchemaRelationInferrerTest {

    private static DatasetEntity ds(String id, String name) {
        DatasetEntity e = new DatasetEntity();
        e.setId(id);
        e.setName(name);
        e.setTableName("ds_x_" + id);
        return e;
    }

    private static ColumnSchema col(String name, String type) {
        return new ColumnSchema(name, name, type, true, name);
    }

    private static GraphDto infer(Map<String, List<ColumnSchema>> cols) {
        List<DatasetEntity> datasets = cols.keySet().stream().map(id -> ds(id, id)).toList();
        return SchemaRelationInferrer.infer(datasets, d -> cols.get(d.getId()));
    }

    @Test
    void emptyInputYieldsEmptyGraph() {
        GraphDto g = SchemaRelationInferrer.infer(List.of(), d -> List.of());
        assertTrue(g.nodes().isEmpty());
        assertTrue(g.edges().isEmpty());
    }

    @Test
    void sharedColumnProducesHeuristicEdge() {
        GraphDto g =
                infer(
                        Map.of(
                                "a",
                                List.of(
                                        col("id", "BIGINT"),
                                        col("user_id", "BIGINT"),
                                        col("ts", "DATE")),
                                "b",
                                List.of(
                                        col("id", "BIGINT"),
                                        col("user_id", "BIGINT"),
                                        col("amount", "DECIMAL(18,6)"))));
        assertEquals(2, g.nodes().size());
        assertEquals(1, g.edges().size());
        assertEquals(SchemaRelationInferrer.HEURISTIC_SAME_NAME, g.edges().get(0).relationType());
        assertEquals(0.7, g.edges().get(0).confidence());
        assertEquals("user_id", g.edges().get(0).label());
    }

    @Test
    void exactPkFkEdge() {
        GraphDto g =
                infer(
                        Map.of(
                                "profile",
                                List.of(col("user_id", "BIGINT"), col("bio", "VARCHAR(1024)")),
                                "orders",
                                List.of(col("id", "BIGINT"), col("user_id", "BIGINT"))));
        assertEquals(1, g.edges().size());
        assertEquals(SchemaRelationInferrer.EXACT_PK_FK, g.edges().get(0).relationType());
        assertEquals(1.0, g.edges().get(0).confidence());
    }

    @Test
    void suffixStripEdge() {
        GraphDto g =
                infer(
                        Map.of(
                                "users",
                                List.of(col("id", "BIGINT"), col("name", "VARCHAR(1024)")),
                                "orders",
                                List.of(col("id", "BIGINT"), col("users_id", "BIGINT"))));
        assertEquals(1, g.edges().size());
        assertEquals(SchemaRelationInferrer.SUFFIX_STRIP, g.edges().get(0).relationType());
        assertEquals(0.6, g.edges().get(0).confidence());
    }

    @Test
    void dedupeKeepsHighestConfidencePerPair() {
        GraphDto g =
                infer(
                        Map.of(
                                "profile",
                                List.of(col("user_id", "BIGINT"), col("region", "VARCHAR(1024)")),
                                "orders",
                                List.of(
                                        col("id", "BIGINT"),
                                        col("user_id", "BIGINT"),
                                        col("region", "VARCHAR(1024)"))));
        // EXACT_PK_FK (1.0) and HEURISTIC_SAME_NAME (0.7) hit the same pair -> keep 1.0 only.
        assertEquals(1, g.edges().size());
        assertEquals(SchemaRelationInferrer.EXACT_PK_FK, g.edges().get(0).relationType());
    }
}
