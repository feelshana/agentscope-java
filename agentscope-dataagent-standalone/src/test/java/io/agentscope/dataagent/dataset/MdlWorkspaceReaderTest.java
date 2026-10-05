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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.agentscope.dataagent.web.persistence.jpa.DatasetEntity;
import io.agentscope.dataagent.web.persistence.jpa.DatasetRepository;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Locks the workspace reader contract (specs/019 M2): official YAML files parse into the
 * structured view the modeling page and the manifest need, agent-created models keep a null
 * dataset id, and malformed files surface as issues instead of exceptions.
 */
class MdlWorkspaceReaderTest {

    private final Map<String, DatasetEntity> datasetsById = new LinkedHashMap<>();

    @TempDir Path tempDir;

    private Path ws;
    private MdlWorkspaceReader reader;

    @BeforeEach
    void setUp() throws IOException {
        DatasetRepository datasetRepository = mock(DatasetRepository.class);
        when(datasetRepository.findById(anyString()))
                .thenAnswer(inv -> Optional.ofNullable(datasetsById.get(inv.getArgument(0))));
        WrenProperties props =
                new WrenProperties(
                        "wren", tempDir.resolve("mdl").toString(), "dataagent", "mysql", 10);
        MdlWorkspaceService workspace =
                new MdlWorkspaceService(props, (p, t, a) -> new WrenCli.Result(0, ""));
        reader = new MdlWorkspaceReader(props, workspace, datasetRepository);
        ws = tempDir.resolve("mdl").resolve("g1").resolve("workspace");
        Files.createDirectories(ws);
    }

    private void registerDataset(String id, String name) {
        DatasetEntity d = new DatasetEntity();
        d.setId(id);
        d.setGroupId("g1");
        d.setOwnerId("owner");
        d.setName(name);
        datasetsById.put(id, d);
    }

    private void write(Path relative, String content) throws IOException {
        Path file = ws.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }

    @Test
    void readsModelsRelationsCubesViews() throws IOException {
        registerDataset("o1", "orders");
        write(ws.resolve(".platform").resolve("datasets.json"), "{\"o1\": \"orders\"}");
        write(
                ws.resolve("models").resolve("orders").resolve("metadata.yml"),
                """
                name: orders
                table_reference:
                  catalog: ''
                  schema: data_agent
                  table: ds_o1
                columns:
                - name: id
                  type: BIGINT
                  properties:
                    description: 订单ID
                - name: customer
                  type: customers
                  relationship: orders_customers
                - name: amount_tax
                  is_calculated: true
                  type: DECIMAL(18, 6)
                  expression: amount * 1.13
                properties:
                  description: 订单明细表
                """);
        write(
                ws.resolve("relationships.yml"),
                """
                relationships:
                - name: orders_customers
                  models:
                  - orders
                  - customers
                  join_type: MANY_TO_ONE
                  condition: orders.customer_id = customers.id AND orders.tenant = customers.tenant
                """);
        write(
                ws.resolve("cubes").resolve("revenue").resolve("metadata.yml"),
                """
                name: revenue
                base_object: orders
                measures:
                - name: total
                  expression: SUM(amount)
                  type: DECIMAL
                  description: 合计
                dimensions:
                - name: status
                  expression: status
                  type: VARCHAR
                """);
        write(
                ws.resolve("views").resolve("monthly").resolve("metadata.yml"),
                """
                name: monthly
                properties:
                  description: 月度
                """);
        write(
                ws.resolve("views").resolve("monthly").resolve("sql.yml"),
                "statement: >-\n  SELECT 1\n");

        MdlWorkspaceReader.Snapshot s = reader.read("g1");

        assertTrue(s.issues().isEmpty(), String.valueOf(s.issues()));
        assertEquals(1, s.models().size());
        MdlWorkspaceReader.WorkspaceModel model = s.models().get(0);
        assertEquals("orders", model.name());
        assertEquals("o1", model.datasetId());
        assertEquals("orders", model.datasetName());
        assertEquals("data_agent", model.schemaName());
        assertEquals("ds_o1", model.tableName());
        assertEquals("订单明细表", model.description());
        assertEquals(3, model.columns().size());
        MdlWorkspaceReader.WorkspaceColumn physical = model.columns().get(0);
        assertEquals("订单ID", physical.description());
        assertNull(physical.relationship());
        assertFalse(physical.calculated());
        MdlWorkspaceReader.WorkspaceColumn relationship = model.columns().get(1);
        assertEquals("orders_customers", relationship.relationship());
        assertFalse(relationship.calculated());
        MdlWorkspaceReader.WorkspaceColumn calculated = model.columns().get(2);
        assertTrue(calculated.calculated());
        assertEquals("amount * 1.13", calculated.expression());
        assertEquals(1, s.relations().size());
        MdlWorkspaceReader.WorkspaceRelation relation = s.relations().get(0);
        assertEquals("orders_customers", relation.name());
        assertEquals("MANY_TO_ONE", relation.joinType());
        assertEquals(List.of("customer_id", "tenant"), relation.sourceColumns());
        assertEquals(List.of("id", "tenant"), relation.targetColumns());
        assertEquals(1, s.cubes().size());
        MdlWorkspaceReader.WorkspaceCube cube = s.cubes().get(0);
        assertEquals("orders", cube.baseModel());
        assertEquals(1, cube.measures().size());
        assertEquals("SUM(amount)", cube.measures().get(0).expression());
        assertEquals("合计", cube.measures().get(0).description());
        assertEquals(1, cube.dimensions().size());
        assertEquals(1, s.views().size());
        assertEquals("monthly", s.views().get(0).name());
        assertEquals("月度", s.views().get(0).description());
        assertTrue(s.views().get(0).sql().contains("SELECT 1"));
        assertTrue(s.files().stream().anyMatch(f -> f.path().equals("models/orders/metadata.yml")));
        assertFalse(s.files().stream().anyMatch(f -> f.path().startsWith(".platform/")));
    }

    @Test
    void untrackedModelKeepsNullDatasetId() throws IOException {
        write(
                ws.resolve("models").resolve("custom").resolve("metadata.yml"),
                """
                name: custom
                table_reference:
                  catalog: ''
                  schema: s
                  table: t
                columns:
                - name: id
                  type: BIGINT
                """);

        MdlWorkspaceReader.Snapshot s = reader.read("g1");

        assertTrue(s.issues().isEmpty(), String.valueOf(s.issues()));
        assertEquals(1, s.models().size());
        assertNull(s.models().get(0).datasetId());
        assertEquals("custom", s.models().get(0).datasetName());
    }

    @Test
    void missingWorkspaceYieldsEmpty() {
        MdlWorkspaceReader.Snapshot s = reader.read("nope");
        assertTrue(s.files().isEmpty());
        assertTrue(s.issues().isEmpty());
        assertTrue(s.models().isEmpty());
        assertTrue(reader.listFiles("nope").isEmpty());
    }

    @Test
    void malformedModelFileBecomesIssue() throws IOException {
        write(ws.resolve("models").resolve("orders").resolve("metadata.yml"), "columns: [broken");

        MdlWorkspaceReader.Snapshot s = reader.read("g1");

        assertTrue(
                s.issues().stream()
                        .anyMatch(
                                i ->
                                        "error".equals(i.severity())
                                                && i.message()
                                                        .contains("models/orders/metadata.yml")),
                String.valueOf(s.issues()));
        assertEquals(0, s.models().size());
    }

    @Test
    void readKnowledgeRulesConcatenatesRuleFilesSorted() throws IOException {
        write(ws.resolve("knowledge").resolve("rules").resolve("b_second.md"), "第二条规则。\n");
        write(ws.resolve("knowledge").resolve("rules").resolve("a_first.md"), "第一条规则。\n");
        write(ws.resolve("knowledge").resolve("rules").resolve("notes.txt"), "not a rule");

        String rules = reader.readKnowledgeRules("g1");

        assertEquals("第一条规则。\n\n第二条规则。", rules);
    }

    @Test
    void readKnowledgeRulesYieldsEmptyWithoutDirectory() {
        assertEquals("", reader.readKnowledgeRules("g1"));
        assertEquals("", reader.readKnowledgeRules("nope"));
    }

    // -------------------------------------------------------------- ref_sql models (specs/034)

    @Test
    void readsFileLayoutRefSqlModel() throws IOException {
        write(
                ws.resolve("models").resolve("monthly_active").resolve("metadata.yml"),
                """
                name: monthly_active
                columns:
                - name: month
                  type: VARCHAR
                - name: active_users
                  type: BIGINT
                properties:
                  description: 月活用户口径
                """);
        write(
                ws.resolve("models").resolve("monthly_active").resolve("ref_sql.sql"),
                "SELECT DATE_FORMAT(created_at, '%Y-%m') AS month, COUNT(DISTINCT user_id) AS"
                        + " active_users\n"
                        + "FROM orders GROUP BY 1\n");

        MdlWorkspaceReader.Snapshot s = reader.read("g1");

        assertTrue(s.issues().isEmpty(), String.valueOf(s.issues()));
        assertEquals(1, s.models().size());
        MdlWorkspaceReader.WorkspaceModel model = s.models().get(0);
        assertEquals("monthly_active", model.name());
        assertEquals("", model.tableName());
        assertNull(model.datasetId());
        assertEquals("月活用户口径", model.description());
        assertEquals(2, model.columns().size());
        assertTrue(model.refSql().contains("COUNT(DISTINCT user_id)"));
        assertEquals("models/monthly_active/ref_sql.sql", model.refSqlPath());
    }

    @Test
    void readsInlineRefSqlKeyAsFallback() throws IOException {
        write(
                ws.resolve("models").resolve("inline_derived").resolve("metadata.yml"),
                """
                name: inline_derived
                ref_sql: >-
                  SELECT id FROM orders
                columns:
                - name: id
                  type: BIGINT
                """);

        MdlWorkspaceReader.Snapshot s = reader.read("g1");

        assertTrue(s.issues().isEmpty(), String.valueOf(s.issues()));
        assertEquals(1, s.models().size());
        MdlWorkspaceReader.WorkspaceModel model = s.models().get(0);
        assertEquals("inline_derived", model.name());
        assertTrue(model.refSql().contains("SELECT id FROM orders"));
        assertEquals("models/inline_derived/metadata.yml", model.refSqlPath());
    }

    @Test
    void refSqlFileWinsOverInlineKey() throws IOException {
        write(
                ws.resolve("models").resolve("both_layouts").resolve("metadata.yml"),
                """
                name: both_layouts
                ref_sql: >-
                  SELECT id FROM orders
                columns:
                - name: id
                  type: BIGINT
                """);
        write(
                ws.resolve("models").resolve("both_layouts").resolve("ref_sql.sql"),
                "SELECT id FROM customers\n");

        MdlWorkspaceReader.Snapshot s = reader.read("g1");

        assertTrue(s.issues().isEmpty(), String.valueOf(s.issues()));
        assertEquals(1, s.models().size());
        assertTrue(s.models().get(0).refSql().contains("customers"));
        assertEquals("models/both_layouts/ref_sql.sql", s.models().get(0).refSqlPath());
    }

    @Test
    void physicalModelKeepsNullRefSql() throws IOException {
        write(
                ws.resolve("models").resolve("orders").resolve("metadata.yml"),
                """
                name: orders
                table_reference:
                  catalog: ''
                  schema: data_agent
                  table: ds_o1
                columns:
                - name: id
                  type: BIGINT
                """);

        MdlWorkspaceReader.Snapshot s = reader.read("g1");

        assertTrue(s.issues().isEmpty(), String.valueOf(s.issues()));
        assertEquals(1, s.models().size());
        assertNull(s.models().get(0).refSql());
        assertNull(s.models().get(0).refSqlPath());
    }

    @Test
    void modelDirMissingMetadataBecomesIssue() throws IOException {
        // Half-written pair (ref_sql.sql without metadata.yml) must be visible, not silently
        // skipped — the silent-skip blind spot once masked a vanished asset as a build failure.
        write(ws.resolve("models").resolve("ghost").resolve("ref_sql.sql"), "SELECT 1\n");

        MdlWorkspaceReader.Snapshot s = reader.read("g1");

        assertTrue(
                s.issues().stream()
                        .anyMatch(
                                i ->
                                        "error".equals(i.severity())
                                                && i.message()
                                                        .contains("models/ghost 缺少 metadata.yml")),
                String.valueOf(s.issues()));
        assertEquals(0, s.models().size());
    }
}
