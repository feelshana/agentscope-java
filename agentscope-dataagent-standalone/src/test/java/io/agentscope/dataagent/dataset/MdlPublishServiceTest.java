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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.dataagent.dataset.parser.ColumnSchema;
import io.agentscope.dataagent.web.persistence.jpa.DatasetEntity;
import io.agentscope.dataagent.web.persistence.jpa.DatasetGroupEntity;
import io.agentscope.dataagent.web.persistence.jpa.DatasetGroupRepository;
import io.agentscope.dataagent.web.persistence.jpa.DatasetRepository;
import io.agentscope.dataagent.web.persistence.jpa.ExternalDataSourceEntity;
import io.agentscope.dataagent.web.persistence.jpa.ExternalDataSourceRepository;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Locks the workspace-as-truth publishing contract (specs/019 M2, ADR 0033): a publish means
 * reconcile → staging copy → validate --strict → build → per-view and per-cube dry-runs →
 * atomic snapshot swap; the official cube query argv is exercised, the manifest keeps the
 * {@code MdlCatalog} shape, failures preserve the previous snapshot, and the baseline funnel
 * coalesces via the assemble-start tick.
 */
class MdlPublishServiceTest {

    private DatasetRepository datasetRepository;
    private DatasetGroupRepository groupRepository;
    private ExternalDataSourceRepository externalSources;
    private final ObjectMapper mapper = new ObjectMapper();

    private final Map<String, DatasetEntity> datasetsById = new LinkedHashMap<>();
    private final Map<String, DatasetGroupEntity> groupsById = new HashMap<>();
    private final Map<String, ExternalDataSourceEntity> externalSourcesById = new LinkedHashMap<>();

    /** Scriptable probe outcome; most tests keep the default "everything reachable". */
    private WrenSourceProber.ProbeReport probeReport = WrenSourceProber.ProbeReport.success();

    private FakeWrenCli wren;
    private MdlSeeder seeder;
    private Path mdlHome;
    private Path ws;
    private Path groupRoot;
    private MdlPublishService service;

    @TempDir Path tempDir;

    @BeforeEach
    void setUp() {
        datasetRepository = mock(DatasetRepository.class);
        groupRepository = mock(DatasetGroupRepository.class);
        externalSources = mock(ExternalDataSourceRepository.class);
        wren = new FakeWrenCli();
        mdlHome = tempDir.resolve("mdl");
        WrenProperties wrenProps =
                new WrenProperties("wren", mdlHome.toString(), "dataagent", "mysql", 10);
        DatasetStoreProperties storeProps = mock(DatasetStoreProperties.class);
        when(storeProps.url()).thenReturn("jdbc:mysql://127.0.0.1:3306/data_agent?useSSL=false");
        when(storeProps.username()).thenReturn("root");
        when(storeProps.password()).thenReturn("p");
        MdlWorkspaceService workspaceService = new MdlWorkspaceService(wrenProps, wren);
        WrenTypeNormalizer normalizer = new WrenTypeNormalizer(wrenProps, wren, mapper);
        seeder = new MdlSeeder(datasetRepository, workspaceService, normalizer);
        MdlWorkspaceReader reader =
                new MdlWorkspaceReader(wrenProps, workspaceService, datasetRepository);
        service =
                new MdlPublishService(
                        datasetRepository,
                        groupRepository,
                        externalSources,
                        mapper,
                        wrenProps,
                        wren,
                        new WrenProfileHome(wrenProps, storeProps, externalSources),
                        (source, datasets) -> probeReport,
                        seeder,
                        workspaceService,
                        reader);
        ws = mdlHome.resolve("g1").resolve("workspace");
        groupRoot = mdlHome.resolve("g1");

        when(datasetRepository.findByGroupId(anyString()))
                .thenAnswer(
                        inv ->
                                datasetsById.values().stream()
                                        .filter(d -> d.getGroupId().equals(inv.getArgument(0)))
                                        .toList());
        when(datasetRepository.findById(anyString()))
                .thenAnswer(inv -> Optional.ofNullable(datasetsById.get(inv.getArgument(0))));
        when(groupRepository.findById(anyString()))
                .thenAnswer(inv -> Optional.ofNullable(groupsById.get(inv.getArgument(0))));
        when(groupRepository.save(any(DatasetGroupEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        when(externalSources.findById(anyString()))
                .thenAnswer(
                        inv -> Optional.ofNullable(externalSourcesById.get(inv.getArgument(0))));
        when(externalSources.findAll())
                .thenAnswer(inv -> new ArrayList<>(externalSourcesById.values()));
    }

    // ------------------------------------------------------------------ fixtures

    private DatasetGroupEntity registerGroup(String groupId) {
        DatasetGroupEntity g = new DatasetGroupEntity(groupId, "owner", "KB " + groupId, null);
        groupsById.put(groupId, g);
        return g;
    }

    private void registerDataset(String id, String groupId, String name, String[][] columns) {
        DatasetEntity d = new DatasetEntity();
        d.setId(id);
        d.setGroupId(groupId);
        d.setOwnerId("owner");
        d.setName(name);
        d.setTableName("ds_" + id);
        d.setSchemaName("data_agent");
        d.setDescription(name);
        d.setCreatedAt(Instant.parse("2026-09-01T00:00:00Z"));
        setColumns(d, columns);
        datasetsById.put(id, d);
    }

    /** Like {@link #registerDataset} but the table lives on an external datasource. */
    private void registerExternalDataset(
            String id, String groupId, String name, String sourceId, String[][] columns) {
        DatasetEntity d = new DatasetEntity();
        d.setId(id);
        d.setGroupId(groupId);
        d.setOwnerId("owner");
        d.setName(name);
        d.setTableName(name + "_tbl");
        d.setSchemaName("shop");
        d.setDescription(name);
        d.setOrigin("datasource");
        d.setExternalDataSourceId(sourceId);
        d.setCreatedAt(Instant.parse("2026-09-01T00:00:00Z"));
        setColumns(d, columns);
        datasetsById.put(id, d);
    }

    private void registerExternalSource(String id, String kind, String url) {
        externalSourcesById.put(
                id,
                new ExternalDataSourceEntity(
                        id, "owner", "源「" + id + "」", kind, url, "u", "p", true));
    }

    private void setColumns(DatasetEntity d, String[][] columns) {
        List<ColumnSchema> schema = new ArrayList<>();
        for (String[] c : columns) {
            schema.add(new ColumnSchema(c[0], c[0], c[1], true, c.length > 2 ? c[2] : null));
        }
        try {
            d.setColumnSchemaJson(mapper.writeValueAsString(schema));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private void write(String relative, String content) throws IOException {
        Path file = ws.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }

    private JsonNode manifestModel(JsonNode mdl, String name) {
        for (JsonNode node : mdl.get("models")) {
            if (name.equals(node.get("name").asText())) {
                return node;
            }
        }
        throw new AssertionError("model not in manifest: " + name);
    }

    // ------------------------------------------------------------------ end to end

    @Test
    void publishesWorkspaceEndToEnd() throws IOException {
        registerGroup("g1");
        registerDataset(
                "o1",
                "g1",
                "orders",
                new String[][] {
                    {"id", "bigint", "订单ID"},
                    {"customer_id", "bigint", null},
                    {"amount", "decimal", "金额"},
                    {"status", "varchar", null}
                });
        registerDataset(
                "c1",
                "g1",
                "customers",
                new String[][] {{"id", "bigint", "客户ID"}, {"name", "varchar", null}});

        MdlPublishService.MdlPublishResult first = service.publishBaseline("g1");
        assertTrue(first.ok(), String.valueOf(first.issues()));
        assertEquals("PUBLISHED", first.mdlState());
        assertEquals(1, first.mdlVersion());
        assertTrue(Files.exists(ws.resolve("models/orders/metadata.yml")));

        // The agent (or the HITL card) adds relationship/cube/view files on top of the seed.
        write(
                "relationships.yml",
                """
                relationships:
                - name: orders_customers
                  models:
                  - orders
                  - customers
                  join_type: MANY_TO_ONE
                  condition: orders.customer_id = customers.id
                """);
        write(
                "cubes/revenue/metadata.yml",
                """
                name: revenue
                base_object: orders
                measures:
                - name: total_valid_revenue
                  expression: SUM(amount)
                  type: DECIMAL
                  description: 有效金额合计
                dimensions:
                - name: status
                  expression: status
                  type: VARCHAR
                """);
        write("views/monthly/metadata.yml", "name: monthly\nproperties:\n  description: 月度销售额\n");
        write("views/monthly/sql.yml", "statement: >-\n  SELECT 1\n");

        MdlPublishService.MdlPublishResult second = service.publish("g1");
        assertTrue(second.ok(), String.valueOf(second.issues()));
        assertEquals(2, second.mdlVersion());

        // ADR 0033 D6: the cube dry-run used the official pure-SQL argv.
        assertTrue(
                wren.calls.stream()
                        .anyMatch(
                                c ->
                                        c.equals(
                                                "cube query --cube revenue --measures"
                                                        + " total_valid_revenue --sql-only")),
                String.valueOf(wren.calls));
        assertTrue(wren.calls.stream().anyMatch(c -> c.equals("context validate --strict")));
        assertTrue(wren.calls.stream().anyMatch(c -> c.equals("context build")));

        // Snapshot: build output present for the runtime, platform files never leave the
        // workspace, the pinned profile marker is added.
        assertTrue(
                Files.exists(groupRoot.resolve("published").resolve("target").resolve("mdl.json")));
        assertTrue(
                Files.exists(
                        groupRoot
                                .resolve("published")
                                .resolve("models")
                                .resolve("orders")
                                .resolve("metadata.yml")));
        assertEquals(
                "profile=dataagent\n",
                Files.readString(
                        groupRoot.resolve("published").resolve("wren-source.properties"),
                        StandardCharsets.UTF_8));
        try (Stream<Path> published = Files.walk(groupRoot.resolve("published"))) {
            assertFalse(published.anyMatch(p -> p.getFileName().toString().equals(".platform")));
        }

        // Manifest keeps the MdlCatalog shape.
        JsonNode mdl =
                mapper.readTree(
                        Files.readString(groupRoot.resolve("mdl.json"), StandardCharsets.UTF_8));
        assertEquals(2, mdl.get("version").asInt());
        JsonNode orders = manifestModel(mdl, "orders");
        assertEquals("o1", orders.get("datasetId").asText());
        JsonNode relation = mdl.get("relations").get(0);
        assertEquals("o1", relation.get("sourceDatasetId").asText());
        assertEquals("c1", relation.get("targetDatasetId").asText());
        assertEquals("customer_id", relation.get("sourceColumn").asText());
        assertEquals("id", relation.get("targetColumn").asText());
        JsonNode cube = mdl.get("cubes").get(0);
        assertEquals("revenue", cube.get("name").asText());
        assertEquals("total_valid_revenue", cube.get("measures").get(0).get("name").asText());
        assertEquals(1, mdl.get("views").size());

        // The diff card converges after publishing.
        MdlPublishService.MdlPreview preview = service.preview("g1");
        assertFalse(preview.changed());
        assertTrue(
                preview.publishedFiles().stream().noneMatch(f -> f.path().startsWith("target/")));
        assertTrue(
                preview.publishedFiles().stream()
                        .noneMatch(f -> f.path().equals("wren-source.properties")));
    }

    // ------------------------------------------------------------------ failure handling

    @Test
    void cubeDryRunFailureFailsFirstPublish() throws IOException {
        registerGroup("g1");
        registerDataset(
                "o1",
                "g1",
                "orders",
                new String[][] {{"id", "bigint", null}, {"amount", "decimal", "金额"}});
        assertTrue(seeder.reconcile("g1").issues().isEmpty());
        write(
                "cubes/broken/metadata.yml",
                """
                name: broken
                base_object: orders
                measures:
                - name: total
                  expression: SUM(amount)
                  type: DECIMAL
                """);
        wren.failCubeQuery = true;

        MdlPublishService.MdlPublishResult result = service.publishBaseline("g1");

        assertFalse(result.ok());
        assertTrue(
                result.issues().stream().anyMatch(i -> i.message().contains("Cube「broken」试跑失败")),
                String.valueOf(result.issues()));
        assertEquals("FAILED", result.mdlState());
        assertFalse(Files.exists(groupRoot.resolve("published")));
    }

    @Test
    void failedPublishKeepsPreviousSnapshot() throws IOException {
        registerGroup("g1");
        registerDataset(
                "o1",
                "g1",
                "orders",
                new String[][] {{"id", "bigint", null}, {"amount", "decimal", "金额"}});
        assertTrue(service.publishBaseline("g1").ok());
        assertFalse(Files.exists(groupRoot.resolve("published").resolve("cubes")));

        write(
                "cubes/broken/metadata.yml",
                """
                name: broken
                base_object: orders
                measures:
                - name: total
                  expression: SUM(amount)
                  type: DECIMAL
                """);
        wren.failCubeQuery = true;

        MdlPublishService.MdlPublishResult result = service.publish("g1");

        assertFalse(result.ok());
        // Workspace-as-truth (ADR 0033 D3): the old snapshot keeps serving, state stays
        // PUBLISHED and only the error message records the failure.
        assertEquals("PUBLISHED", result.mdlState());
        assertEquals(1, result.mdlVersion());
        assertFalse(Files.exists(groupRoot.resolve("published").resolve("cubes")));
        assertTrue(
                Files.exists(groupRoot.resolve("published").resolve("target").resolve("mdl.json")));
        DatasetGroupEntity group = groupsById.get("g1");
        assertNotNull(group.getMdlLastError());
        assertTrue(group.getMdlLastError().contains("Cube「broken」"));
    }

    @Test
    void coalescesRapidBaselineRequests() {
        registerGroup("g1");
        registerDataset(
                "o1",
                "g1",
                "orders",
                new String[][] {{"id", "bigint", null}, {"amount", "decimal", "金额"}});

        long tick = System.nanoTime();
        assertTrue(service.publishBaseline("g1", tick).ok());
        int callsAfterFirst = wren.calls.size();

        MdlPublishService.MdlPublishResult skipped = service.publishBaseline("g1", tick - 1);
        assertTrue(skipped.ok());
        assertEquals("PUBLISHED", skipped.mdlState());
        assertEquals(1, skipped.mdlVersion());
        assertEquals(callsAfterFirst, wren.calls.size(), "covered request must skip its run");

        assertTrue(service.publishBaseline("g1", System.nanoTime()).ok());
        assertEquals(2, groupsById.get("g1").getMdlVersion());
        assertTrue(wren.calls.size() > callsAfterFirst);
    }

    // ------------------------------------------------------------------ source selection

    @Test
    void refusesMixedSources() {
        registerGroup("g1");
        registerDataset(
                "o1",
                "g1",
                "orders",
                new String[][] {{"id", "bigint", null}, {"amount", "decimal", null}});
        registerExternalSource("src1", "mysql", "jdbc:mysql://127.0.0.1:3306/shop");
        registerExternalDataset(
                "e1", "g1", "ext_orders", "src1", new String[][] {{"id", "bigint", null}});

        MdlPublishService.MdlPublishResult result = service.publishBaseline("g1");

        assertFalse(result.ok());
        assertTrue(
                result.issues().stream().anyMatch(i -> i.message().contains("无法发布")),
                String.valueOf(result.issues()));
        assertEquals("FAILED", result.mdlState());
        assertFalse(Files.exists(groupRoot.resolve("published")));
    }

    @Test
    void emptyKnowledgeBaseFailsFast() {
        registerGroup("g1");

        MdlPublishService.MdlPublishResult result = service.publishBaseline("g1");

        assertFalse(result.ok());
        assertTrue(
                result.issues().stream().anyMatch(i -> i.message().contains("没有数据集")),
                String.valueOf(result.issues()));
        assertEquals("FAILED", result.mdlState());
        assertTrue(wren.calls.isEmpty(), "must fail before any wren invocation");
    }

    // ------------------------------------------------------------------ read-only surfaces

    @Test
    void previewDiffCardTracksPublication() throws IOException {
        registerGroup("g1");
        registerDataset(
                "o1",
                "g1",
                "orders",
                new String[][] {{"id", "bigint", null}, {"amount", "decimal", "金额"}});
        // Seed the workspace without publishing: the funnel shows a pending diff.
        assertTrue(seeder.reconcile("g1").issues().isEmpty());

        MdlPublishService.MdlPreview before = service.preview("g1");
        assertTrue(before.changed());
        assertTrue(before.publishedFiles().isEmpty());
        assertTrue(
                before.files().stream()
                        .anyMatch(f -> f.path().equals("models/orders/metadata.yml")));
        assertTrue(before.files().stream().noneMatch(f -> f.path().startsWith(".platform/")));

        assertTrue(service.publishBaseline("g1").ok());

        MdlPublishService.MdlPreview after = service.preview("g1");
        assertFalse(after.changed());
        assertEquals(
                after.files().stream().map(MdlPublishService.MdlFile::path).sorted().toList(),
                after.publishedFiles().stream()
                        .map(MdlPublishService.MdlFile::path)
                        .sorted()
                        .toList());
    }

    @Test
    void viewParsesWorkspaceFiles() throws IOException {
        registerGroup("g1");
        registerDataset(
                "o1",
                "g1",
                "orders",
                new String[][] {
                    {"id", "bigint", "订单ID"},
                    {"customer_id", "bigint", null},
                    {"tenant", "varchar", null},
                    {"amount", "decimal", "金额"},
                    {"status", "varchar", null}
                });
        registerDataset(
                "c1",
                "g1",
                "customers",
                new String[][] {{"id", "bigint", "客户ID"}, {"tenant", "varchar", null}});
        assertTrue(service.publishBaseline("g1").ok());
        write(
                "relationships.yml",
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
                "cubes/revenue/metadata.yml",
                """
                name: revenue
                base_object: orders
                description: 销售 cube
                measures:
                - name: total_valid_revenue
                  expression: SUM(amount)
                  type: DECIMAL
                dimensions:
                - name: status
                  expression: status
                  type: VARCHAR
                """);
        write("views/monthly/metadata.yml", "name: monthly\nproperties:\n  description: 月度销售额\n");
        write("views/monthly/sql.yml", "statement: >-\n  SELECT 1\n");

        MdlPublishService.MdlView view = service.view("g1");

        assertTrue(view.issues().isEmpty(), String.valueOf(view.issues()));
        assertEquals("PUBLISHED", view.state());
        assertEquals(1, view.version());
        assertEquals(2, view.models().size());
        assertEquals(1, view.relations().size());
        MdlPublishService.MdlRelationView relation = view.relations().get(0);
        assertEquals(List.of("customer_id", "tenant"), relation.sourceColumns());
        assertEquals(List.of("id", "tenant"), relation.targetColumns());
        assertEquals(1, view.cubes().size());
        MdlPublishService.MdlCubeView cube = view.cubes().get(0);
        assertEquals("orders", cube.baseModel());
        assertEquals("total_valid_revenue", cube.measures().get(0).name());
        assertEquals(1, view.views().size());
        assertEquals("月度销售额", view.views().get(0).description());
        assertTrue(view.views().get(0).sql().contains("SELECT 1"));
    }

    @Test
    void viewSeparatesDerivedModelsFromPhysical() throws IOException {
        registerGroup("g1");
        registerDataset(
                "o1",
                "g1",
                "orders",
                new String[][] {{"id", "bigint", null}, {"amount", "decimal", null}});
        assertTrue(service.publishBaseline("g1").ok());
        write(
                "models/monthly_active/metadata.yml",
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
                "models/monthly_active/ref_sql.sql",
                "SELECT DATE_FORMAT(created_at, '%Y-%m') AS month FROM orders GROUP BY 1\n");

        MdlPublishService.MdlView view = service.view("g1");

        assertTrue(view.issues().isEmpty(), String.valueOf(view.issues()));
        assertEquals(1, view.models().size());
        assertEquals("orders", view.models().get(0).modelName());
        assertNull(view.models().get(0).refSqlPath());
        assertEquals(1, view.derivedModels().size());
        MdlPublishService.MdlModelView derived = view.derivedModels().get(0);
        assertEquals("monthly_active", derived.modelName());
        assertEquals("月活用户口径", derived.description());
        assertEquals("models/monthly_active/ref_sql.sql", derived.refSqlPath());
        assertEquals(2, derived.columns().size());
    }

    @Test
    void publishDryRunsDerivedModelsAndMarksManifest() throws IOException {
        registerGroup("g1");
        registerDataset(
                "o1",
                "g1",
                "orders",
                new String[][] {{"id", "bigint", null}, {"amount", "decimal", null}});
        write(
                "models/monthly_active/metadata.yml",
                """
                name: monthly_active
                columns:
                - name: month
                  type: VARCHAR
                properties:
                  description: 月活口径
                """);
        write("models/monthly_active/ref_sql.sql", "SELECT '2026-10' AS month\n");

        MdlPublishService.MdlPublishResult result = service.publishBaseline("g1");

        assertTrue(result.ok(), String.valueOf(result.issues()));
        assertTrue(
                wren.calls.contains("dry-run -s SELECT * FROM \"monthly_active\""),
                String.valueOf(wren.calls));
        JsonNode manifest =
                mapper.readTree(
                        Files.readString(groupRoot.resolve("mdl.json"), StandardCharsets.UTF_8));
        JsonNode derived = null;
        JsonNode physical = null;
        for (JsonNode n : manifest.path("models")) {
            if ("monthly_active".equals(n.path("name").asText())) {
                derived = n;
            }
            if ("orders".equals(n.path("name").asText())) {
                physical = n;
            }
        }
        assertNotNull(derived, "manifest must carry the derived model");
        assertNotNull(physical, "manifest must carry the physical model");
        assertTrue(derived.path("derived").asBoolean(), derived.toString());
        assertFalse(physical.has("derived"), physical.toString());
    }

    @Test
    void derivedModelDryRunFailureAbortsPublish() throws IOException {
        registerGroup("g1");
        registerDataset(
                "o1",
                "g1",
                "orders",
                new String[][] {{"id", "bigint", null}, {"amount", "decimal", null}});
        write(
                "models/monthly_active/metadata.yml",
                """
                name: monthly_active
                columns:
                - name: month
                  type: VARCHAR
                """);
        write(
                "models/monthly_active/ref_sql.sql",
                "SELECT DATE_SUB(CURRENT_DATE, INTERVAL 30 DAY) AS d\n");
        wren.failDryRunFor = "monthly_active";

        MdlPublishService.MdlPublishResult result = service.publishBaseline("g1");

        assertFalse(result.ok());
        assertEquals("FAILED", result.mdlState());
        assertTrue(
                result.issues().stream()
                        .anyMatch(i -> i.message().contains("派生模型「monthly_active」试跑失败")),
                String.valueOf(result.issues()));
        assertFalse(Files.exists(groupRoot.resolve("published")));
        assertFalse(Files.exists(groupRoot.resolve("mdl.json")));
    }

    @Test
    void publishMaterialisesDerivedModelRefSqlIntoSnapshot() throws IOException {
        registerGroup("g1");
        registerDataset(
                "o1",
                "g1",
                "orders",
                new String[][] {{"id", "bigint", null}, {"amount", "decimal", null}});
        write(
                "models/monthly_active/metadata.yml",
                """
                name: monthly_active
                columns:
                - name: c
                  type: BIGINT
                """);
        write("models/monthly_active/ref_sql.sql", "SELECT COUNT(*) AS c FROM orders\n");
        // The build artifact is the engine's own view: physical models carry tableReference,
        // derived models carry the verbatim ref_sql body.
        wren.buildMdlJson =
                """
                {"catalog":"wren","schema":"wren","models":[
                  {"name":"orders","tableReference":{"catalog":"wren","schema":"data_agent","table":"ds_o1"},
                   "columns":[{"name":"id","type":"BIGINT"},{"name":"amount","type":"DECIMAL"}]},
                  {"name":"monthly_active","refSql":"SELECT COUNT(*) AS c FROM orders",
                   "columns":[{"name":"c","type":"BIGINT"}]}
                ]}
                """;

        MdlPublishService.MdlPublishResult result = service.publishBaseline("g1");

        assertTrue(result.ok(), String.valueOf(result.issues()));
        // The published snapshot carries the physically-qualified manifest the runtime loads.
        JsonNode built =
                mapper.readTree(
                        Files.readString(
                                groupRoot.resolve("published/target/mdl.json"),
                                StandardCharsets.UTF_8));
        assertEquals(
                "SELECT COUNT(*) AS c FROM data_agent.ds_o1",
                manifestModel(built, "monthly_active").path("refSql").asText());
        // The workspace copy keeps the human-readable logical name for editing.
        assertEquals(
                "SELECT COUNT(*) AS c FROM orders\n",
                Files.readString(
                        ws.resolve("models/monthly_active/ref_sql.sql"), StandardCharsets.UTF_8));
    }

    @Test
    void publishBlocksOnUnknownRefSqlReference() throws IOException {
        registerGroup("g1");
        registerDataset("o1", "g1", "orders", new String[][] {{"id", "bigint", null}});
        write(
                "models/broken/metadata.yml",
                """
                name: broken
                columns:
                - name: id
                  type: BIGINT
                """);
        write("models/broken/ref_sql.sql", "SELECT id FROM ghost_table\n");
        wren.buildMdlJson =
                """
                {"catalog":"wren","schema":"wren","models":[
                  {"name":"orders","tableReference":{"catalog":"wren","schema":"data_agent","table":"ds_o1"},
                   "columns":[{"name":"id","type":"BIGINT"}]},
                  {"name":"broken","refSql":"SELECT id FROM ghost_table",
                   "columns":[{"name":"id","type":"BIGINT"}]}
                ]}
                """;

        MdlPublishService.MdlPublishResult result = service.publishBaseline("g1");

        assertFalse(result.ok());
        assertEquals("FAILED", result.mdlState());
        assertTrue(
                result.issues().stream()
                        .anyMatch(i -> i.message().contains("不存在的逻辑模型「ghost_table」")),
                String.valueOf(result.issues()));
        assertFalse(Files.exists(groupRoot.resolve("published")));
        assertFalse(Files.exists(groupRoot.resolve("mdl.json")));
    }

    @Test
    void publishBlocksOnChainedDerivedModelReference() throws IOException {
        registerGroup("g1");
        registerDataset("o1", "g1", "orders", new String[][] {{"id", "bigint", null}});
        write(
                "models/first_stage/metadata.yml",
                """
                name: first_stage
                columns:
                - name: id
                  type: BIGINT
                """);
        write("models/first_stage/ref_sql.sql", "SELECT id FROM orders\n");
        write(
                "models/second_stage/metadata.yml",
                """
                name: second_stage
                columns:
                - name: id
                  type: BIGINT
                """);
        write("models/second_stage/ref_sql.sql", "SELECT id FROM first_stage\n");
        wren.buildMdlJson =
                """
                {"catalog":"wren","schema":"wren","models":[
                  {"name":"orders","tableReference":{"catalog":"wren","schema":"data_agent","table":"ds_o1"},
                   "columns":[{"name":"id","type":"BIGINT"}]},
                  {"name":"first_stage","refSql":"SELECT id FROM orders",
                   "columns":[{"name":"id","type":"BIGINT"}]},
                  {"name":"second_stage","refSql":"SELECT id FROM first_stage",
                   "columns":[{"name":"id","type":"BIGINT"}]}
                ]}
                """;

        MdlPublishService.MdlPublishResult result = service.publishBaseline("g1");

        assertFalse(result.ok());
        assertTrue(
                result.issues().stream()
                        .anyMatch(
                                i ->
                                        i.message()
                                                .contains(
                                                        "派生模型「second_stage」的 SQL"
                                                                + " 引用了其他派生模型「first_stage」")),
                String.valueOf(result.issues()));
        assertFalse(Files.exists(groupRoot.resolve("published")));
    }

    @Test
    void publishBlocksOnViewReferenceInRefSql() throws IOException {
        registerGroup("g1");
        registerDataset("o1", "g1", "orders", new String[][] {{"id", "bigint", null}});
        write(
                "models/broken/metadata.yml",
                """
                name: broken
                columns:
                - name: id
                  type: BIGINT
                """);
        write("models/broken/ref_sql.sql", "SELECT * FROM monthly\n");
        wren.buildMdlJson =
                """
                {"catalog":"wren","schema":"wren","models":[
                  {"name":"orders","tableReference":{"catalog":"wren","schema":"data_agent","table":"ds_o1"},
                   "columns":[{"name":"id","type":"BIGINT"}]},
                  {"name":"broken","refSql":"SELECT * FROM monthly",
                   "columns":[{"name":"id","type":"BIGINT"}]}
                ],"views":[{"name":"monthly","statement":"SELECT 1"}]}
                """;

        MdlPublishService.MdlPublishResult result = service.publishBaseline("g1");

        assertFalse(result.ok());
        assertTrue(
                result.issues().stream()
                        .anyMatch(i -> i.message().contains("派生模型「broken」的 SQL 引用了视图「monthly」")),
                String.valueOf(result.issues()));
    }

    @Test
    void deleteArtifactsRemovesGroupRoot() {
        registerGroup("g1");
        registerDataset(
                "o1",
                "g1",
                "orders",
                new String[][] {{"id", "bigint", null}, {"amount", "decimal", "金额"}});
        assertTrue(service.publishBaseline("g1").ok());
        assertTrue(Files.exists(groupRoot));

        service.deleteArtifacts("g1");

        assertFalse(Files.exists(groupRoot));
    }

    // ------------------------------------------------------------------ test double

    /** Scriptable wren CLI: fakes init/parse-types/validate/build/dry-run; cube query can fail. */
    private static final class FakeWrenCli implements WrenCli {

        final List<String> calls = new ArrayList<>();
        boolean failCubeQuery;

        /** When set, dry-run commands mentioning this substring fail (derived/view gate tests). */
        String failDryRunFor;

        /** When set, the build artifact carries this JSON instead of the empty default. */
        String buildMdlJson;

        static final Map<String, String> NORMALIZED =
                Map.of(
                        "bigint", "BIGINT",
                        "int", "INTEGER",
                        "varchar", "VARCHAR",
                        "decimal", "DECIMAL(18, 6)",
                        "date", "DATE",
                        "datetime", "TIMESTAMP");

        @Override
        public Result run(Path projectHome, Duration timeout, List<String> args) {
            String cmd = String.join(" ", args);
            calls.add(cmd);
            if (cmd.startsWith("context init")) {
                return new Result(0, "initialized");
            }
            if (cmd.startsWith("utils parse-types")) {
                return new Result(0, parseTypes(projectHome));
            }
            if (cmd.startsWith("context validate")) {
                return new Result(0, "0 warning(s), 0 error(s)");
            }
            if (cmd.startsWith("context build")) {
                writeBuildOutput(projectHome);
                return new Result(0, "built");
            }
            if (cmd.startsWith("dry-run")) {
                if (failDryRunFor != null && cmd.contains(failDryRunFor)) {
                    return new Result(1, "[ERROR] broken derived model");
                }
                return new Result(0, "planned");
            }
            if (cmd.startsWith("cube query")) {
                return failCubeQuery
                        ? new Result(1, "[ERROR] cube broken")
                        : new Result(0, "SELECT");
            }
            return new Result(0, "");
        }

        private void writeBuildOutput(Path projectHome) {
            try {
                Path target = projectHome.resolve("target");
                Files.createDirectories(target);
                Files.writeString(
                        target.resolve("mdl.json"),
                        buildMdlJson != null
                                ? buildMdlJson
                                : "{\"catalog\":\"wren\",\"schema\":\"public\",\"models\":[]}",
                        StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }

        private static String parseTypes(Path dir) {
            try {
                List<Map<String, Object>> input =
                        new ObjectMapper()
                                .readValue(
                                        Files.readString(
                                                dir.resolve("types_in.json"),
                                                StandardCharsets.UTF_8),
                                        new com.fasterxml.jackson.core.type.TypeReference<
                                                List<Map<String, Object>>>() {});
                List<Map<String, String>> out = new ArrayList<>();
                for (Map<String, Object> item : input) {
                    String raw = String.valueOf(item.get("raw_type"));
                    out.add(
                            Map.of(
                                    "column",
                                    String.valueOf(item.get("column")),
                                    "raw_type",
                                    raw,
                                    "type",
                                    NORMALIZED.getOrDefault(
                                            raw.toLowerCase(Locale.ROOT),
                                            raw.toUpperCase(Locale.ROOT))));
                }
                return new ObjectMapper().writeValueAsString(out);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }
    }
}
