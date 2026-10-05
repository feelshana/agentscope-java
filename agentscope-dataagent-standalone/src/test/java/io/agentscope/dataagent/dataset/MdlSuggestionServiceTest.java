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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.dataagent.dataset.parser.ColumnSchema;
import io.agentscope.dataagent.web.ai.AgentDraftService;
import io.agentscope.dataagent.web.persistence.jpa.DatasetEntity;
import io.agentscope.dataagent.web.persistence.jpa.DatasetRelationEntity;
import io.agentscope.dataagent.web.persistence.jpa.DatasetRelationRepository;
import io.agentscope.dataagent.web.persistence.jpa.DatasetRepository;
import io.agentscope.dataagent.web.persistence.jpa.SemanticCubeEntity;
import io.agentscope.dataagent.web.persistence.jpa.SemanticCubeRepository;
import io.agentscope.dataagent.web.persistence.jpa.SemanticTermEntity;
import io.agentscope.dataagent.web.persistence.jpa.SemanticTermRepository;
import io.agentscope.dataagent.web.persistence.jpa.SemanticViewEntity;
import io.agentscope.dataagent.web.persistence.jpa.SemanticViewRepository;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * Locks the modeling-suggestion contract: LLM candidates merge with rule edges (no duplicate
 * pairs), joinType comes from uniqueness probing rather than guessing, malformed LLM output is
 * tolerated item-by-item, and review operations are scoped to the owning group.
 */
class MdlSuggestionServiceTest {

    private DatasetRepository datasetRepository;
    private DatasetService datasetService;
    private DatasetRelationRepository relationRepository;
    private RelationInferenceService relationInference;
    private SemanticCubeRepository cubeRepository;
    private SemanticTermRepository termRepository;
    private SemanticViewRepository viewRepository;
    private AgentDraftService agentDraftService;
    private MdlWorkspaceService workspace;
    private MdlWorkspaceReader workspaceReader;

    @TempDir Path temp;

    private MdlSuggestionService service;
    private final ObjectMapper mapper = new ObjectMapper();

    private final Map<String, DatasetEntity> datasetsById = new HashMap<>();
    private final Map<String, List<ColumnSchema>> colsOf = new HashMap<>();
    private final Map<String, DatasetRelationEntity> relationsById = new LinkedHashMap<>();
    private final Map<String, SemanticCubeEntity> cubesById = new HashMap<>();
    private List<DatasetEntity> groupDatasets = List.of();

    @BeforeEach
    void setUp() {
        datasetRepository = mock(DatasetRepository.class);
        datasetService = mock(DatasetService.class);
        relationRepository = mock(DatasetRelationRepository.class);
        relationInference = mock(RelationInferenceService.class);
        cubeRepository = mock(SemanticCubeRepository.class);
        termRepository = mock(SemanticTermRepository.class);
        viewRepository = mock(SemanticViewRepository.class);
        agentDraftService = mock(AgentDraftService.class);
        workspace = mock(MdlWorkspaceService.class);
        workspaceReader = mock(MdlWorkspaceReader.class);
        service =
                new MdlSuggestionService(
                        datasetRepository,
                        datasetService,
                        relationRepository,
                        relationInference,
                        cubeRepository,
                        termRepository,
                        viewRepository,
                        agentDraftService,
                        workspace,
                        workspaceReader,
                        mapper);

        when(workspace.withWorkspaceLock(anyString(), any()))
                .thenAnswer(inv -> ((Supplier<?>) inv.getArgument(1)).get());
        when(workspace.workspaceRoot(anyString())).thenReturn(temp);
        when(workspaceReader.read(anyString())).thenAnswer(inv -> snapshotWithModels());
        when(datasetRepository.findByGroupId(anyString())).thenAnswer(inv -> groupDatasets);
        when(datasetRepository.findById(anyString()))
                .thenAnswer(inv -> Optional.ofNullable(datasetsById.get(inv.getArgument(0))));
        when(datasetService.readColumns(any(DatasetEntity.class)))
                .thenAnswer(
                        inv ->
                                colsOf.getOrDefault(
                                        ((DatasetEntity) inv.getArgument(0)).getId(), List.of()));
        when(relationRepository.findByGroupId(anyString()))
                .thenAnswer(
                        inv ->
                                relationsById.values().stream()
                                        .filter(r -> r.getGroupId().equals(inv.getArgument(0)))
                                        .toList());
        when(relationRepository.findById(anyString()))
                .thenAnswer(inv -> Optional.ofNullable(relationsById.get(inv.getArgument(0))));
        when(relationRepository.save(any(DatasetRelationEntity.class)))
                .thenAnswer(
                        inv -> {
                            DatasetRelationEntity e = inv.getArgument(0);
                            relationsById.put(e.getId(), e);
                            return e;
                        });
        when(relationRepository.saveAll(anyList()))
                .thenAnswer(
                        inv -> {
                            List<DatasetRelationEntity> saved = inv.getArgument(0);
                            for (DatasetRelationEntity e : saved) {
                                relationsById.put(e.getId(), e);
                            }
                            return saved;
                        });
        when(cubeRepository.findByGroupId(anyString()))
                .thenAnswer(
                        inv ->
                                cubesById.values().stream()
                                        .filter(c -> c.getGroupId().equals(inv.getArgument(0)))
                                        .toList());
        when(cubeRepository.findById(anyString()))
                .thenAnswer(inv -> Optional.ofNullable(cubesById.get(inv.getArgument(0))));
        when(cubeRepository.findByGroupIdAndName(anyString(), anyString()))
                .thenAnswer(
                        inv ->
                                cubesById.values().stream()
                                        .filter(
                                                c ->
                                                        c.getGroupId().equals(inv.getArgument(0))
                                                                && c.getName()
                                                                        .equals(inv.getArgument(1)))
                                        .findFirst()
                                        .orElse(null));
        when(cubeRepository.save(any(SemanticCubeEntity.class)))
                .thenAnswer(
                        inv -> {
                            SemanticCubeEntity c = inv.getArgument(0);
                            cubesById.put(c.getId(), c);
                            return c;
                        });
        when(agentDraftService.modelAvailable()).thenReturn(false);
        // Mirror the real extractJsonObject: keep the outermost {...} or fail non-JSON input.
        when(agentDraftService.extractJsonObject(anyString()))
                .thenAnswer(
                        inv -> {
                            String raw = inv.getArgument(0);
                            int first = raw.indexOf('{');
                            int last = raw.lastIndexOf('}');
                            if (first < 0 || last <= first) {
                                throw new ResponseStatusException(
                                        HttpStatus.BAD_GATEWAY, "non-JSON");
                            }
                            return raw.substring(first, last + 1);
                        });
        groupDatasets = List.of();
    }

    private void registerDataset(
            String id, String groupId, String name, String tableName, String[][] headerToPhysical) {
        DatasetEntity d = new DatasetEntity();
        d.setId(id);
        d.setGroupId(groupId);
        d.setOwnerId("owner");
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
        datasetsById.put(id, d);
        colsOf.put(id, cols);
        groupDatasets = new ArrayList<>(datasetsById.values());
    }

    private DatasetRelationEntity registerRelation(
            String id,
            String groupId,
            String sourceId,
            String sourceColumn,
            String targetId,
            String targetColumn,
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
                        "SAME_COLUMN",
                        null,
                        0.7,
                        origin);
        e.setStatus(status);
        relationsById.put(id, e);
        return e;
    }

    @Test
    void refreshMergesLlmCandidatesSkippingKnownPairs() {
        registerDataset(
                "ds_orders",
                "g1",
                "订单表",
                "ds_orders",
                new String[][] {{"订单id", "id"}, {"用户id", "id_2"}, {"订单金额", "col_3"}});
        registerDataset(
                "ds_users",
                "g1",
                "用户表",
                "ds_users",
                new String[][] {{"用户id", "id"}, {"用户名", "col_2"}, {"省份", "col_3"}});
        registerRelation(
                "r_rule", "g1", "ds_orders", "id_2", "ds_users", "id", "inferred", "PENDING");
        when(agentDraftService.modelAvailable()).thenReturn(true);
        when(agentDraftService.chatBlockingModeling(anyString()))
                .thenReturn(
                        "```json\n{\"relations\":["
                                // duplicate of the rule edge — must not be stored twice
                                + "{\"source_table\":\"订单表\",\"source_column\":\"id_2\","
                                + "\"target_table\":\"用户表\",\"target_column\":\"id\",\"reason\":\"重复\"},"
                                // new pair proposed from Chinese headers
                                + "{\"source_table\":\"订单表\",\"source_column\":\"订单金额\",\"target_table\":\"用户表\",\"target_column\":\"省份\",\"reason\":\"金额分省\"}]}\n"
                                + "```");
        // columns are not unique on either side -> probing leaves joinType null
        when(datasetService.columnProfile(anyString(), anyString(), anyString()))
                .thenReturn(new long[] {100, 40});

        service.refreshRelations("g1");

        assertEquals(2, relationsById.size(), "duplicate pair must not be stored twice");
        DatasetRelationEntity llm =
                relationsById.values().stream()
                        .filter(e -> "llm".equals(e.getOrigin()))
                        .findFirst()
                        .orElseThrow();
        assertEquals("PENDING", llm.getStatus());
        assertEquals("col_3", llm.getSourceColumn());
        assertEquals("col_3", llm.getTargetColumn());
        assertEquals("金额分省", llm.getDescription());
    }

    @Test
    void probeFillsManyToOneWhenSourceSideIsNotUnique() {
        registerDataset(
                "ds_users",
                "g1",
                "users",
                "users",
                new String[][] {{"user_id", "user_id"}, {"username", "username"}});
        registerDataset(
                "ds_orders",
                "g1",
                "orders",
                "orders",
                new String[][] {{"order_id", "order_id"}, {"user_id", "user_id"}});
        DatasetRelationEntity e =
                registerRelation(
                        "r1",
                        "g1",
                        "ds_orders",
                        "user_id",
                        "ds_users",
                        "user_id",
                        "inferred",
                        "PENDING");
        when(datasetService.columnProfile(anyString(), anyString(), anyString()))
                .thenAnswer(
                        inv -> {
                            String ds = inv.getArgument(1);
                            String col = inv.getArgument(2);
                            if ("ds_orders".equals(ds) && "user_id".equals(col)) {
                                return new long[] {100, 90}; // FK side: not unique
                            }
                            if ("ds_users".equals(ds) && "user_id".equals(col)) {
                                return new long[] {50, 50}; // PK side: unique
                            }
                            return new long[] {10, 10};
                        });

        service.refreshRelations("g1");

        assertEquals("MANY_TO_ONE", e.getJoinType());
    }

    @Test
    void probeLeavesJoinTypeNullWhenAmbiguousOrEmpty() {
        registerDataset("ds_a", "g1", "a", "a", new String[][] {{"x", "x"}, {"y", "y"}});
        registerDataset("ds_b", "g1", "b", "b", new String[][] {{"x", "x"}, {"z", "z"}});
        DatasetRelationEntity ambiguous =
                registerRelation("r1", "g1", "ds_a", "x", "ds_b", "x", "inferred", "PENDING");
        DatasetRelationEntity empty =
                registerRelation("r2", "g1", "ds_a", "y", "ds_b", "z", "inferred", "PENDING");
        when(datasetService.columnProfile(anyString(), anyString(), anyString()))
                .thenAnswer(
                        inv ->
                                "y".equals(inv.getArgument(2))
                                        ? new long[] {0, 0} // empty column
                                        : new long[] {100, 40}); // both sides not unique

        service.refreshRelations("g1");

        assertNull(ambiguous.getJoinType());
        assertNull(empty.getJoinType());
    }

    @Test
    void llmParsingToleratesMalformedItems() {
        registerDataset("ds_orders", "g1", "订单表", "orders", new String[][] {{"amount", "amount"}});
        registerDataset("ds_users", "g1", "用户表", "users", new String[][] {{"user_id", "user_id"}});
        when(agentDraftService.modelAvailable()).thenReturn(true);
        when(agentDraftService.chatBlockingModeling(anyString()))
                .thenReturn(
                        "{\"relations\":["
                                + "{\"source_table\":\"不存在表\",\"source_column\":\"a\","
                                + "\"target_table\":\"用户表\",\"target_column\":\"user_id\"},"
                                + "{\"source_table\":\"订单表\",\"source_column\":\"不存在的列\","
                                + "\"target_table\":\"用户表\",\"target_column\":\"user_id\"},"
                                + "{\"source_table\":\"订单表\",\"source_column\":\"amount\","
                                + "\"target_table\":\"用户表\",\"target_column\":\"user_id\","
                                + "\"reason\":\"有效\"}]}");
        when(datasetService.columnProfile(anyString(), anyString(), anyString()))
                .thenReturn(new long[] {100, 40});

        service.refreshRelations("g1");

        assertEquals(1, relationsById.size(), "only the valid candidate may be stored");
        DatasetRelationEntity e = relationsById.values().iterator().next();
        assertEquals("llm", e.getOrigin());
        assertEquals("有效", e.getDescription());
    }

    @Test
    void llmFailureDoesNotBreakRefresh() {
        registerDataset("ds_a", "g1", "a", "a", new String[][] {{"x", "x"}});
        registerDataset("ds_b", "g1", "b", "b", new String[][] {{"y", "y"}});
        when(agentDraftService.modelAvailable()).thenReturn(true);
        when(agentDraftService.chatBlockingModeling(anyString()))
                .thenThrow(new IllegalStateException("model down"));

        service.refreshRelations("g1");

        assertEquals(0, relationsById.size());
    }

    @Test
    void confirmAndRejectAreScopedToOwningGroup() {
        registerDataset("ds_a", "g1", "a", "a", new String[][] {{"x", "x"}});
        registerDataset("ds_b", "g1", "b", "b", new String[][] {{"y", "y"}});
        registerRelation("r1", "g1", "ds_a", "x", "ds_b", "y", "inferred", "PENDING");
        registerRelation("r2", "g2", "ds_c", "x", "ds_d", "y", "inferred", "PENDING");

        DatasetException ex =
                assertThrows(
                        DatasetException.class,
                        () -> service.confirmRelation("g1", "r2", null, false));
        assertEquals(404, ex.status());

        DatasetRelationEntity confirmed = service.confirmRelation("g1", "r1", null, false);
        assertEquals("CONFIRMED", confirmed.getStatus());
    }

    @Test
    void confirmWithSwapFlipsDirectionAndJoinType() {
        registerDataset("ds_a", "g1", "a", "a", new String[][] {{"x", "x"}});
        registerDataset("ds_b", "g1", "b", "b", new String[][] {{"y", "y"}});
        DatasetRelationEntity e =
                registerRelation("r1", "g1", "ds_a", "x", "ds_b", "y", "inferred", "PENDING");
        e.setJoinType("MANY_TO_ONE");

        DatasetRelationEntity swapped = service.confirmRelation("g1", "r1", null, true);

        assertEquals("ds_b", swapped.getSourceDatasetId());
        assertEquals("y", swapped.getSourceColumn());
        assertEquals("ds_a", swapped.getTargetDatasetId());
        assertEquals("x", swapped.getTargetColumn());
        assertEquals("ONE_TO_MANY", swapped.getJoinType());
        assertEquals("CONFIRMED", swapped.getStatus());
    }

    @Test
    void manualRelationUpgradesExistingEdgeOnSameColumnPair() {
        registerDataset("ds_a", "g1", "a", "a", new String[][] {{"x", "x"}});
        registerDataset("ds_b", "g1", "b", "b", new String[][] {{"y", "y"}});
        registerRelation("r1", "g1", "ds_b", "y", "ds_a", "x", "inferred", "PENDING");

        DatasetRelationEntity e =
                service.addManualRelation(
                        "g1",
                        new MdlSuggestionService.ManualRelationPayload(
                                "ds_a", "x", "ds_b", "y", "MANY_TO_ONE"));

        assertEquals(1, relationsById.size(), "same column pair must not be duplicated");
        assertEquals("r1", e.getId());
        assertEquals("manual", e.getOrigin());
        assertEquals("CONFIRMED", e.getStatus());
        assertEquals(1.0, e.getConfidence());
        assertEquals("MANY_TO_ONE", e.getJoinType());
    }

    @Test
    void manualRelationRejectsForeignDatasetAndUnknownColumn() {
        registerDataset("ds_a", "g1", "a", "a", new String[][] {{"x", "x"}});
        registerDataset("ds_b", "g1", "b", "b", new String[][] {{"y", "y"}});
        registerDataset("ds_foreign", "g2", "c", "c", new String[][] {{"x", "x"}});

        DatasetException foreign =
                assertThrows(
                        DatasetException.class,
                        () ->
                                service.addManualRelation(
                                        "g1",
                                        new MdlSuggestionService.ManualRelationPayload(
                                                "ds_a", "x", "ds_foreign", "x", null)));
        assertEquals(404, foreign.status());

        DatasetException unknownCol =
                assertThrows(
                        DatasetException.class,
                        () ->
                                service.addManualRelation(
                                        "g1",
                                        new MdlSuggestionService.ManualRelationPayload(
                                                "ds_a", "nope", "ds_b", "y", null)));
        assertEquals(400, unknownCol.status());
    }

    // --------------------------------------------- relationships.yml write side (M4)

    @Test
    void confirmWritesOfficialRelationshipEntry() throws IOException {
        registerDataset("ds_a", "g1", "a", "a", new String[][] {{"x", "x"}});
        registerDataset("ds_b", "g1", "b", "b", new String[][] {{"y", "y"}});
        registerRelation("r1", "g1", "ds_a", "x", "ds_b", "y", "inferred", "PENDING");

        service.confirmRelation("g1", "r1", "MANY_TO_ONE", false);

        String yamlText = fileText();
        assertTrue(yamlText.startsWith("relationships:\n"), yamlText);
        assertTrue(yamlText.contains("name: a_b"), yamlText);
        assertTrue(yamlText.contains("join_type: MANY_TO_ONE"), yamlText);
        assertTrue(yamlText.contains("condition: a.x = b.y"), yamlText);
    }

    @Test
    void reconfirmIsIdempotentOnTheFile() throws IOException {
        registerDataset("ds_a", "g1", "a", "a", new String[][] {{"x", "x"}});
        registerDataset("ds_b", "g1", "b", "b", new String[][] {{"y", "y"}});
        registerRelation("r1", "g1", "ds_a", "x", "ds_b", "y", "inferred", "PENDING");

        service.confirmRelation("g1", "r1", null, false);
        service.confirmRelation("g1", "r1", "ONE_TO_MANY", false);

        String yamlText = fileText();
        assertEquals(1, countEntries(yamlText), "a re-confirm must rewrite, not duplicate");
        assertTrue(yamlText.contains("join_type: ONE_TO_MANY"), yamlText);
    }

    @Test
    void swappedReconfirmReplacesTheSameEntryInsteadOfDuplicating() throws IOException {
        registerDataset("ds_a", "g1", "a", "a", new String[][] {{"x", "x"}});
        registerDataset("ds_b", "g1", "b", "b", new String[][] {{"y", "y"}});
        registerRelation("r1", "g1", "ds_a", "x", "ds_b", "y", "inferred", "PENDING");
        service.confirmRelation("g1", "r1", null, false);

        registerRelation("r2", "g1", "ds_b", "y", "ds_a", "x", "llm", "PENDING");
        service.confirmRelation("g1", "r2", "ONE_TO_MANY", false);

        String yamlText = fileText();
        assertEquals(1, countEntries(yamlText), "the reversed edge covers the same pair");
        assertTrue(yamlText.contains("condition: b.y = a.x"), yamlText);
    }

    @Test
    void rejectNeverTouchesTheFile() throws IOException {
        registerDataset("ds_a", "g1", "a", "a", new String[][] {{"x", "x"}});
        registerDataset("ds_b", "g1", "b", "b", new String[][] {{"y", "y"}});
        registerRelation("r1", "g1", "ds_a", "x", "ds_b", "y", "inferred", "PENDING");
        service.confirmRelation("g1", "r1", null, false);
        String before = fileText();

        registerRelation("r2", "g1", "ds_a", "x", "ds_b", "y", "llm", "PENDING");
        service.rejectRelation("g1", "r2");

        assertEquals(before, fileText(), "a rejection is review bookkeeping only");
    }

    @Test
    void addManualRelationAppendsAndThenGrowsCompositeKey() throws IOException {
        registerDataset("ds_a", "g1", "a", "a", new String[][] {{"x", "x"}, {"p", "p"}});
        registerDataset("ds_b", "g1", "b", "b", new String[][] {{"y", "y"}, {"q", "q"}});

        service.addManualRelation(
                "g1",
                new MdlSuggestionService.ManualRelationPayload("ds_a", "x", "ds_b", "y", null));
        service.addManualRelation(
                "g1",
                new MdlSuggestionService.ManualRelationPayload(
                        "ds_a",
                        "x",
                        "ds_b",
                        "y",
                        "MANY_TO_ONE",
                        List.of("x", "p"),
                        List.of("y", "q")));

        String yamlText = fileText();
        assertEquals(1, countEntries(yamlText), "the composite key grows the same entry");
        assertTrue(yamlText.contains("condition: a.x = b.y AND a.p = b.q"), yamlText);
        assertTrue(yamlText.contains("join_type: MANY_TO_ONE"), yamlText);
    }

    /** Workspace snapshot stand-in: every registered dataset maps to a seeded model. */
    private MdlWorkspaceReader.Snapshot snapshotWithModels() {
        List<MdlWorkspaceReader.WorkspaceModel> models = new ArrayList<>();
        for (DatasetEntity d : datasetsById.values()) {
            models.add(
                    new MdlWorkspaceReader.WorkspaceModel(
                            d.getName(),
                            d.getId(),
                            d.getName(),
                            "",
                            d.getTableName(),
                            null,
                            List.of(),
                            "models/" + d.getName() + "/metadata.yml",
                            null,
                            null));
        }
        return new MdlWorkspaceReader.Snapshot(
                List.of(), List.of(), models, List.of(), List.of(), List.of());
    }

    private Path relationsFile() {
        return temp.resolve("relationships.yml");
    }

    private String fileText() throws IOException {
        return Files.exists(relationsFile()) ? Files.readString(relationsFile()) : "";
    }

    private static int countEntries(String yamlText) {
        int count = 0;
        for (String line : yamlText.split("\n")) {
            if (line.startsWith("  - name: ")) {
                count++;
            }
        }
        return count;
    }

    @Test
    void cubeCreateValidatesNameUniquenessAndDatasetOwnership() {
        registerDataset("ds_a", "g1", "a", "a", new String[][] {{"x", "x"}});

        SemanticCubeEntity cube =
                service.createCube(
                        "g1",
                        new MdlSuggestionService.CubePayload(
                                "订单分析",
                                "ds_a",
                                null,
                                List.of(Map.of("name", "总额", "column", "x", "agg", "SUM")),
                                List.of(),
                                List.of()));
        assertNotNull(cube.getId());
        assertEquals("DRAFT", cube.getStatus());
        assertEquals(1, service.listCubes("g1").size());

        DatasetException duplicate =
                assertThrows(
                        DatasetException.class,
                        () ->
                                service.createCube(
                                        "g1",
                                        new MdlSuggestionService.CubePayload(
                                                "订单分析", "ds_a", null, null, null, null)));
        assertEquals(409, duplicate.status());

        DatasetException foreign =
                assertThrows(
                        DatasetException.class,
                        () ->
                                service.createCube(
                                        "g1",
                                        new MdlSuggestionService.CubePayload(
                                                "别的cube", "ds_missing", null, null, null, null)));
        assertEquals(404, foreign.status());
    }

    @Test
    void suggestCubesFiltersItemsWithUnknownColumns() {
        registerDataset(
                "ds_orders",
                "g1",
                "订单表",
                "orders",
                new String[][] {{"amount", "amount"}, {"province", "province"}});
        when(agentDraftService.modelAvailable()).thenReturn(true);
        when(agentDraftService.chatBlockingModeling(anyString()))
                .thenReturn(
                        "{\"cubes\":[{\"dataset\":\"订单表\",\"name\":\"订单分析\","
                            + "\"measures\":[{\"name\":\"总额\",\"column\":\"amount\",\"agg\":\"SUM\"},"
                            + "{\"name\":\"坏指标\",\"column\":\"ghost\",\"agg\":\"SUM\"}],"
                            + "\"dimensions\":[{\"name\":\"省份\",\"column\":\"province\"}],"
                            + "\"reason\":\"聚合\"},{\"dataset\":\"订单表\",\"name\":\"坏cube\","
                            + "\"measures\":[{\"name\":\"坏\",\"column\":\"ghost\",\"agg\":\"SUM\"}]}"
                            + "]}");

        List<MdlSuggestionService.CubeSuggestion> out = service.suggestCubes("g1");

        assertEquals(1, out.size());
        MdlSuggestionService.CubeSuggestion cube = out.get(0);
        assertEquals("订单分析", cube.name());
        assertEquals(1, cube.measures().size(), "unknown column must be filtered out");
        assertEquals("amount", cube.measures().get(0).get("column"));
        assertEquals(1, cube.dimensions().size());
    }

    @Test
    void termCreateValidatesLengthAndUniqueness() {
        when(termRepository.findByGroupIdAndTerm(anyString(), anyString()))
                .thenReturn(Optional.empty());
        when(termRepository.save(any(SemanticTermEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        SemanticTermEntity created = service.createTerm("g1", "高频用户", "近30天访问次数大于30的用户", null);
        assertEquals("高频用户", created.getTerm());
        assertEquals("g1", created.getGroupId());

        when(termRepository.findByGroupIdAndTerm("g1", "高频用户")).thenReturn(Optional.of(created));
        DatasetException duplicate =
                assertThrows(
                        DatasetException.class,
                        () -> service.createTerm("g1", "高频用户", "另一个口径", null));
        assertEquals(409, duplicate.status());

        // specs/026: every term must belong to exactly one knowledge base.
        DatasetException noGroup =
                assertThrows(
                        DatasetException.class, () -> service.createTerm(" ", "好用户", "口径", null));
        assertEquals(400, noGroup.status());

        DatasetException tooLong =
                assertThrows(
                        DatasetException.class,
                        () -> service.createTerm("g1", "x".repeat(31), "y", null));
        assertEquals(400, tooLong.status());

        DatasetException blankExp =
                assertThrows(
                        DatasetException.class, () -> service.createTerm("g1", "好用户", "  ", null));
        assertEquals(400, blankExp.status());
    }

    @Test
    void termDeleteRequiresExistence() {
        when(termRepository.findById("t_missing")).thenReturn(Optional.empty());
        DatasetException missing =
                assertThrows(DatasetException.class, () -> service.deleteTerm("g1", "t_missing"));
        assertEquals(404, missing.status());
    }

    @Test
    void viewCreateValidatesSqlShapeAndUniqueness() {
        when(viewRepository.findByGroupIdAndName(anyString(), anyString())).thenReturn(null);
        when(viewRepository.save(any(SemanticViewEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        registerDataset("ds_a", "g1", "a", "a", new String[][] {{"x", "x"}});

        SemanticViewEntity created =
                service.createView(
                        "g1",
                        new MdlSuggestionService.ViewPayload(
                                "high_frequency_users",
                                "ds_a",
                                "SELECT user_id FROM a GROUP BY user_id HAVING COUNT(*) > 30",
                                "高频用户"));
        assertEquals("high_frequency_users", created.getName());
        assertEquals("ds_a", created.getBaseDatasetId());

        when(viewRepository.findByGroupIdAndName("g1", "high_frequency_users")).thenReturn(created);
        DatasetException duplicate =
                assertThrows(
                        DatasetException.class,
                        () ->
                                service.createView(
                                        "g1",
                                        new MdlSuggestionService.ViewPayload(
                                                "high_frequency_users", "ds_a", "SELECT 1", null)));
        assertEquals(409, duplicate.status());

        DatasetException notSelect =
                assertThrows(
                        DatasetException.class,
                        () ->
                                service.createView(
                                        "g1",
                                        new MdlSuggestionService.ViewPayload(
                                                "v2", "ds_a", "DELETE FROM a", null)));
        assertEquals(400, notSelect.status());

        DatasetException multiStatement =
                assertThrows(
                        DatasetException.class,
                        () ->
                                service.createView(
                                        "g1",
                                        new MdlSuggestionService.ViewPayload(
                                                "v3", "ds_a", "SELECT 1; DROP TABLE a", null)));
        assertEquals(400, multiStatement.status());
    }

    @Test
    void viewDeleteRequiresExistence() {
        when(viewRepository.findById("v_missing")).thenReturn(Optional.empty());
        DatasetException missing =
                assertThrows(DatasetException.class, () -> service.deleteView("g1", "v_missing"));
        assertEquals(404, missing.status());
    }
}
