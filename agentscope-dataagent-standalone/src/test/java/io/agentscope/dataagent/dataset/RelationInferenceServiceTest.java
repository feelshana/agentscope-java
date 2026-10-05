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
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.dataagent.dataset.parser.ColumnSchema;
import io.agentscope.dataagent.web.persistence.jpa.DatasetEntity;
import io.agentscope.dataagent.web.persistence.jpa.DatasetKnowledgeEntity;
import io.agentscope.dataagent.web.persistence.jpa.DatasetKnowledgeRepository;
import io.agentscope.dataagent.web.persistence.jpa.DatasetRelationEntity;
import io.agentscope.dataagent.web.persistence.jpa.DatasetRelationRepository;
import io.agentscope.dataagent.web.persistence.jpa.DatasetRepository;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Locks the selective-rebuild and SAME_COLUMN semantics of {@link RelationInferenceService}:
 * curated edges (manual/llm origin, CONFIRMED/REJECTED verdict) survive re-inference while
 * untouched auto-derived edges are re-derived; shared columns match on the original header so
 * the classic FK-to-PK pattern (dim-table PK in the leading column) and Chinese headers both
 * produce edges, which carry physical column names.
 */
class RelationInferenceServiceTest {

    private DatasetRelationRepository relationRepo;
    private DatasetRepository datasetRepo;
    private DatasetKnowledgeRepository knowledgeRepo;
    private final ObjectMapper mapper = new ObjectMapper();
    private RelationInferenceService service;

    /** In-memory stand-in for the relation table, keyed by groupId. */
    private final Map<String, List<DatasetRelationEntity>> relationTable = new HashMap<>();

    @BeforeEach
    void setUp() {
        relationRepo = mock(DatasetRelationRepository.class);
        datasetRepo = mock(DatasetRepository.class);
        knowledgeRepo = mock(DatasetKnowledgeRepository.class);
        service = new RelationInferenceService(relationRepo, datasetRepo, knowledgeRepo, mapper);

        when(relationRepo.findByGroupId(anyString()))
                .thenAnswer(
                        inv ->
                                new ArrayList<>(
                                        relationTable.getOrDefault(inv.getArgument(0), List.of())));
        when(relationRepo.saveAll(anyList()))
                .thenAnswer(
                        inv -> {
                            @SuppressWarnings("unchecked")
                            List<DatasetRelationEntity> saved = inv.getArgument(0);
                            for (DatasetRelationEntity e : saved) {
                                relationTable
                                        .computeIfAbsent(e.getGroupId(), k -> new ArrayList<>())
                                        .add(e);
                            }
                            return saved;
                        });
        doAnswer(
                        inv -> {
                            @SuppressWarnings("unchecked")
                            List<DatasetRelationEntity> removed = inv.getArgument(0);
                            for (List<DatasetRelationEntity> l : relationTable.values()) {
                                l.removeAll(removed);
                            }
                            return null;
                        })
                .when(relationRepo)
                .deleteAllInBatch(anyList());
        when(datasetRepo.findByGroupId(anyString())).thenReturn(List.of());
        when(knowledgeRepo.findById(anyString())).thenReturn(Optional.empty());
    }

    /**
     * Builds a dataset whose schema pairs each original header with its sanitised physical name,
     * mirroring the upload path (中文表头 e.g. 用户id collapses to {@code id}/{@code id_2}).
     */
    private DatasetEntity dataset(
            String id, String name, String tableName, String[][] headerToPhysical) {
        DatasetEntity d = new DatasetEntity();
        d.setId(id);
        d.setGroupId("g1");
        d.setName(name);
        d.setTableName(tableName);
        List<ColumnSchema> cols = new ArrayList<>();
        for (String[] p : headerToPhysical) {
            cols.add(new ColumnSchema(p[1], p[0], "VARCHAR", true, ""));
        }
        try {
            d.setColumnSchemaJson(mapper.writeValueAsString(cols));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        return d;
    }

    private static DatasetRelationEntity edge(
            String id,
            String groupId,
            String sourceId,
            String sourceColumn,
            String targetId,
            String targetColumn,
            String type,
            double confidence,
            String origin,
            String status) {
        DatasetRelationEntity e =
                new DatasetRelationEntity(
                        id,
                        groupId,
                        sourceId,
                        sourceColumn,
                        targetId,
                        targetColumn,
                        type,
                        null,
                        confidence,
                        origin);
        e.setStatus(status);
        return e;
    }

    private static Set<String> columnPairs(List<DatasetRelationEntity> edges) {
        Set<String> out = new HashSet<>();
        for (DatasetRelationEntity e : edges) {
            out.add(e.getSourceColumn() + "=" + e.getTargetColumn() + "@" + e.getRelationType());
        }
        return out;
    }

    @Test
    void chineseHeaderSharedColumnMatchesAcrossDivergentPhysicalNames() {
        // Uploaded Excel pair: 用户id is the leading (PK) column of 用户表 and a mere FK of
        // 订单表; sanitisation turns it into `id` vs `id_2` so only header matching can join them.
        when(datasetRepo.findByGroupId("g1"))
                .thenReturn(
                        List.of(
                                dataset(
                                        "ds_users",
                                        "用户表",
                                        "ds_users",
                                        new String[][] {
                                            {"用户id", "id"}, {"用户名", "col_2"}, {"省份", "col_3"}
                                        }),
                                dataset(
                                        "ds_orders",
                                        "订单表",
                                        "ds_orders",
                                        new String[][] {
                                            {"订单id", "id"},
                                            {"用户id", "id_2"},
                                            {"订单金额", "col_3"}
                                        })));

        service.reinferGroup("g1");

        List<DatasetRelationEntity> edges = relationTable.get("g1");
        assertEquals(1, edges.size(), "exactly the 用户id edge, no col_3 position false positive");
        DatasetRelationEntity e = edges.get(0);
        assertEquals("SAME_COLUMN", e.getRelationType());
        assertEquals(Set.of("id", "id_2"), Set.of(e.getSourceColumn(), e.getTargetColumn()));
        assertEquals("共享列 用户id", e.getDescription());
    }

    @Test
    void sharedPhysicalColumnWithLeadingPkAlsoProducesEdge() {
        // External-table shape: physical names equal headers; users.user_id leads its table.
        when(datasetRepo.findByGroupId("g1"))
                .thenReturn(
                        List.of(
                                dataset(
                                        "ds_users",
                                        "users",
                                        "users",
                                        new String[][] {
                                            {"user_id", "user_id"},
                                            {"username", "username"},
                                            {"province", "province"}
                                        }),
                                dataset(
                                        "ds_orders",
                                        "orders",
                                        "orders",
                                        new String[][] {
                                            {"order_id", "order_id"},
                                            {"user_id", "user_id"},
                                            {"amount", "amount"}
                                        })));

        service.reinferGroup("g1");

        List<DatasetRelationEntity> edges = relationTable.get("g1");
        assertEquals(1, edges.size());
        assertEquals(Set.of("user_id=user_id@SAME_COLUMN"), columnPairs(edges));
    }

    @Test
    void universalIdColumnAndPositionFallbackNamesNeverCreateEdges() {
        // Both tables share the header "id" (leading, sanitised form) and both have a second
        // column sanitised to col_2 — neither may produce an edge.
        when(datasetRepo.findByGroupId("g1"))
                .thenReturn(
                        List.of(
                                dataset(
                                        "ds_a",
                                        "表A",
                                        "ds_a",
                                        new String[][] {{"id", "id"}, {"名称", "col_2"}}),
                                dataset(
                                        "ds_b",
                                        "表B",
                                        "ds_b",
                                        new String[][] {{"id", "id"}, {"编码", "col_2"}})));

        service.reinferGroup("g1");

        assertTrue(
                relationTable.getOrDefault("g1", List.of()).isEmpty(),
                "no id↔id and no col_2↔col_2 false positive");
    }

    @Test
    void selectiveRebuildKeepsCuratedEdgesAndDropsUntouchedOnes() {
        relationTable
                .computeIfAbsent("g1", k -> new ArrayList<>())
                .addAll(
                        List.of(
                                edge(
                                        "r_manual",
                                        "g1",
                                        "ds_a",
                                        "x",
                                        "ds_b",
                                        "y",
                                        "SAME_COLUMN",
                                        1.0,
                                        "manual",
                                        "PENDING"),
                                edge(
                                        "r_confirmed",
                                        "g1",
                                        "ds_a",
                                        "x",
                                        "ds_b",
                                        "y",
                                        "SUFFIX",
                                        0.6,
                                        "inferred",
                                        "CONFIRMED"),
                                edge(
                                        "r_rejected",
                                        "g1",
                                        "ds_a",
                                        "x",
                                        "ds_b",
                                        "y",
                                        "DOC",
                                        0.9,
                                        "doc",
                                        "REJECTED"),
                                edge(
                                        "r_pending",
                                        "g1",
                                        "ds_a",
                                        "x",
                                        "ds_b",
                                        "y",
                                        "SAME_COLUMN",
                                        0.7,
                                        "inferred",
                                        "PENDING"),
                                edge(
                                        "r_legacy",
                                        "g1",
                                        "ds_a",
                                        "x",
                                        "ds_b",
                                        "y",
                                        "SAME_COLUMN",
                                        0.7,
                                        "inferred",
                                        null)));

        service.reinferGroup("g1");

        Set<String> ids = new HashSet<>();
        for (DatasetRelationEntity e : relationTable.get("g1")) {
            ids.add(e.getId());
        }
        assertEquals(Set.of("r_manual", "r_confirmed", "r_rejected"), ids);
    }

    @Test
    void rederivedDuplicateOfKeptEdgeIsNotSavedTwice() {
        relationTable
                .computeIfAbsent("g1", k -> new ArrayList<>())
                .add(
                        edge(
                                "r_confirmed",
                                "g1",
                                "ds_orders",
                                "user_id",
                                "ds_users",
                                "user_id",
                                "SAME_COLUMN",
                                0.7,
                                "inferred",
                                "CONFIRMED"));
        when(datasetRepo.findByGroupId("g1"))
                .thenReturn(
                        List.of(
                                dataset(
                                        "ds_users",
                                        "users",
                                        "users",
                                        new String[][] {
                                            {"user_id", "user_id"}, {"username", "username"}
                                        }),
                                dataset(
                                        "ds_orders",
                                        "orders",
                                        "orders",
                                        new String[][] {
                                            {"order_id", "order_id"}, {"user_id", "user_id"}
                                        })));

        service.reinferGroup("g1");

        List<DatasetRelationEntity> edges = relationTable.get("g1");
        assertEquals(1, edges.size(), "kept edge must not be re-added as a duplicate");
        assertEquals("r_confirmed", edges.get(0).getId());
    }

    @Test
    void docRelationsStillInferred() {
        when(datasetRepo.findByGroupId("g1"))
                .thenReturn(
                        List.of(
                                dataset(
                                        "ds_users",
                                        "用户表",
                                        "users",
                                        new String[][] {
                                            {"user_id", "user_id"}, {"username", "username"}
                                        }),
                                dataset(
                                        "ds_orders",
                                        "订单表",
                                        "orders",
                                        new String[][] {
                                            {"order_id", "order_id"}, {"amount", "amount"}
                                        })));
        when(knowledgeRepo.findById("g1"))
                .thenReturn(
                        Optional.of(
                                new DatasetKnowledgeEntity("g1", "订单表.username = 用户表.username")));

        service.reinferGroup("g1");

        List<DatasetRelationEntity> edges = relationTable.get("g1");
        assertEquals(1, edges.size());
        assertEquals("DOC", edges.get(0).getRelationType());
        assertEquals("doc", edges.get(0).getOrigin());
        assertEquals("username", edges.get(0).getSourceColumn());
        assertEquals("username", edges.get(0).getTargetColumn());
    }

    @Test
    void reinferOnlyTouchesItsOwnGroup() {
        relationTable
                .computeIfAbsent("g1", k -> new ArrayList<>())
                .add(
                        edge(
                                "r_g1",
                                "g1",
                                "ds_a",
                                "x",
                                "ds_b",
                                "y",
                                "SAME_COLUMN",
                                0.7,
                                "inferred",
                                "PENDING"));
        relationTable
                .computeIfAbsent("g2", k -> new ArrayList<>())
                .add(
                        edge(
                                "r_g2",
                                "g2",
                                "ds_c",
                                "x",
                                "ds_d",
                                "y",
                                "SAME_COLUMN",
                                0.7,
                                "inferred",
                                "PENDING"));

        service.reinferGroup("g1");

        assertTrue(relationTable.get("g1").isEmpty());
        assertEquals(1, relationTable.get("g2").size());
        assertEquals("r_g2", relationTable.get("g2").get(0).getId());
    }
}
