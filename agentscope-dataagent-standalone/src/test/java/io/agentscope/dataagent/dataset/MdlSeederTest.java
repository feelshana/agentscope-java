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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.dataagent.dataset.parser.ColumnSchema;
import io.agentscope.dataagent.web.persistence.jpa.DatasetEntity;
import io.agentscope.dataagent.web.persistence.jpa.DatasetRepository;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Locks the seeder contract (specs/019 M1, ADR 0033 D2): seeding is append-only, agent edits
 * survive, conflicts and dangling references surface as errors instead of silent rewrites, and
 * the platform-owned project file is re-converged without re-running init.
 */
class MdlSeederTest {

    private final Map<String, DatasetEntity> datasetsById = new LinkedHashMap<>();
    private final FakeCli wren = new FakeCli();
    private final ObjectMapper mapper = new ObjectMapper();

    @TempDir Path tempDir;

    private Path mdlHome;
    private Path workspace;
    private MdlSeeder seeder;

    @BeforeEach
    void setUp() {
        DatasetRepository datasetRepository = mock(DatasetRepository.class);
        when(datasetRepository.findByGroupId(anyString()))
                .thenAnswer(
                        inv ->
                                datasetsById.values().stream()
                                        .filter(d -> d.getGroupId().equals(inv.getArgument(0)))
                                        .toList());
        mdlHome = tempDir.resolve("mdl");
        WrenProperties props =
                new WrenProperties("wren", mdlHome.toString(), "dataagent", "mysql", 10);
        MdlWorkspaceService workspaceService = new MdlWorkspaceService(props, wren);
        WrenTypeNormalizer normalizer = new WrenTypeNormalizer(props, wren, mapper);
        seeder = new MdlSeeder(datasetRepository, workspaceService, normalizer);
        workspace = mdlHome.resolve("g1").resolve("workspace");
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

    private MdlSeeder.SeedResult reconcile() {
        return seeder.reconcile("g1");
    }

    private String modelYml(String dir) throws IOException {
        return Files.readString(
                workspace.resolve("models").resolve(dir).resolve("metadata.yml"),
                StandardCharsets.UTF_8);
    }

    @Test
    void seedsNewDatasetIntoWorkspace() throws IOException {
        registerDataset(
                "o1",
                "g1",
                "orders",
                new String[][] {{"id", "bigint", "订单ID"}, {"amount", "decimal", "金额"}});
        MdlSeeder.SeedResult result = reconcile();

        assertTrue(result.issues().isEmpty(), String.valueOf(result.issues()));
        assertTrue(result.changed());
        assertEquals(1, wren.countCalls("context init"));
        assertTrue(
                Files.readString(workspace.resolve("wren_project.yml"), StandardCharsets.UTF_8)
                        .contains("name: kb-g1"));
        assertTrue(
                Files.readString(workspace.resolve("wren_project.yml"), StandardCharsets.UTF_8)
                        .contains("data_source: mysql"));
        String yml = modelYml("orders");
        assertTrue(yml.contains("  table: ds_o1"));
        assertTrue(yml.contains("- name: id"));
        assertTrue(yml.contains("  type: BIGINT"));
        assertTrue(yml.contains("  type: DECIMAL(18, 6)"));
        assertTrue(yml.contains("    description: 订单ID"));
        Map<String, String> mapping =
                mapper.readValue(
                        Files.readString(
                                workspace.resolve(".platform").resolve("datasets.json"),
                                StandardCharsets.UTF_8),
                        new TypeReference<Map<String, String>>() {});
        assertEquals(Map.of("o1", "orders"), mapping);
    }

    @Test
    void reconcileIsIdempotent() throws IOException {
        registerDataset(
                "o1",
                "g1",
                "orders",
                new String[][] {{"id", "bigint", "订单ID"}, {"amount", "decimal", "金额"}});
        reconcile();
        String before = modelYml("orders");
        int inits = wren.countCalls("context init");

        MdlSeeder.SeedResult second = reconcile();

        assertTrue(second.issues().isEmpty(), String.valueOf(second.issues()));
        assertFalse(second.changed());
        assertEquals(inits, wren.countCalls("context init"));
        assertEquals(before, modelYml("orders"));
    }

    @Test
    void convergesTamperedProjectYmlWithoutReinitializing() throws IOException {
        registerDataset("o1", "g1", "orders", new String[][] {{"id", "bigint", null}});
        reconcile();
        Files.writeString(
                workspace.resolve("wren_project.yml"), "# garbage\n", StandardCharsets.UTF_8);
        int inits = wren.countCalls("context init");

        MdlSeeder.SeedResult result = reconcile();

        assertTrue(result.issues().isEmpty(), String.valueOf(result.issues()));
        assertEquals(inits, wren.countCalls("context init"));
        String project =
                Files.readString(workspace.resolve("wren_project.yml"), StandardCharsets.UTF_8);
        assertFalse(project.contains("garbage"));
        assertTrue(project.contains("name: kb-g1"));
    }

    @Test
    void appendsMissingColumnsPreservingAgentEdits() throws IOException {
        registerDataset(
                "o1",
                "g1",
                "orders",
                new String[][] {{"id", "bigint", null}, {"amount", "decimal", "金额"}});
        reconcile();
        Files.writeString(
                workspace.resolve("models").resolve("orders").resolve("metadata.yml"),
                """
                name: orders
                table_reference:
                  catalog: ''
                  schema: data_agent
                  table: ds_o1
                columns:
                  - name: id
                    type: BIGINT
                  - name: amount
                    type: DECIMAL(18, 6)
                  - name: amount_tax
                    is_calculated: true
                    type: DECIMAL(18, 6)
                    expression: amount * 1.13

                properties:
                  description: 订单明细表（agent 修订）
                """,
                StandardCharsets.UTF_8);
        registerDataset(
                "o1",
                "g1",
                "orders",
                new String[][] {
                    {"id", "bigint", null}, {"amount", "decimal", "金额"}, {"status", "varchar", null}
                });

        MdlSeeder.SeedResult result = reconcile();

        assertTrue(result.issues().isEmpty(), String.valueOf(result.issues()));
        assertTrue(result.changed());
        String after = modelYml("orders");
        assertTrue(after.contains("amount_tax"));
        assertTrue(after.contains("  - name: status"));
        assertTrue(after.contains("    type: VARCHAR"));
        int taxIndex = after.indexOf("expression: amount * 1.13");
        int statusIndex = after.indexOf("  - name: status");
        assertTrue(statusIndex > taxIndex, after);
    }

    @Test
    void reportsTypeConflictWithoutRewriting() throws IOException {
        registerDataset(
                "o1",
                "g1",
                "orders",
                new String[][] {{"id", "bigint", null}, {"amount", "decimal", "金额"}});
        reconcile();
        Path modelFile = workspace.resolve("models").resolve("orders").resolve("metadata.yml");
        String tampered =
                Files.readString(modelFile, StandardCharsets.UTF_8)
                        .replace("  type: DECIMAL(18, 6)", "  type: VARCHAR");
        Files.writeString(modelFile, tampered, StandardCharsets.UTF_8);

        MdlSeeder.SeedResult result = reconcile();

        assertTrue(result.hasErrors());
        assertTrue(result.errorSummary().contains("冲突"), result.errorSummary());
        assertEquals(tampered, Files.readString(modelFile, StandardCharsets.UTF_8));
    }

    @Test
    void reportsRemovedPhysicalColumn() throws IOException {
        registerDataset(
                "o1",
                "g1",
                "orders",
                new String[][] {{"id", "bigint", null}, {"amount", "decimal", "金额"}});
        reconcile();
        setColumns(datasetsById.get("o1"), new String[][] {{"id", "bigint", null}});

        MdlSeeder.SeedResult result = reconcile();

        assertTrue(result.hasErrors());
        assertTrue(result.errorSummary().contains("已从数据集中移除"), result.errorSummary());
        String yml = modelYml("orders");
        assertTrue(yml.contains("- name: amount"), "file must stay untouched");
    }

    @Test
    void removesModelFileForDeletedDatasetAndReportsDanglingRefs() throws IOException {
        registerDataset("o1", "g1", "orders", new String[][] {{"id", "bigint", null}});
        registerDataset("c1", "g1", "customers", new String[][] {{"id", "bigint", null}});
        reconcile();
        Files.createDirectories(workspace.resolve("cubes").resolve("monthly_revenue"));
        Files.writeString(
                workspace.resolve("relationships.yml"),
                """
                relationships:
                - name: orders_customers
                  models:
                  - orders
                  - customers
                  join_type: MANY_TO_ONE
                  condition: orders.customer_id = customers.id
                """,
                StandardCharsets.UTF_8);
        Files.writeString(
                workspace.resolve("cubes").resolve("monthly_revenue").resolve("metadata.yml"),
                """
                name: monthly_revenue
                base_object: orders
                measures:
                - name: total
                  expression: SUM(amount)
                  type: DECIMAL
                """,
                StandardCharsets.UTF_8);
        datasetsById.remove("o1");

        MdlSeeder.SeedResult result = reconcile();

        assertTrue(result.hasErrors());
        assertEquals(2, result.issues().stream().filter(i -> "error".equals(i.severity())).count());
        assertFalse(Files.exists(workspace.resolve("models").resolve("orders")));
        assertTrue(Files.exists(workspace.resolve("models").resolve("customers")));
        Map<String, String> mapping =
                mapper.readValue(
                        Files.readString(
                                workspace.resolve(".platform").resolve("datasets.json"),
                                StandardCharsets.UTF_8),
                        new TypeReference<Map<String, String>>() {});
        assertEquals(Map.of("c1", "customers"), mapping);
    }

    @Test
    void resolveWritableGuardsPlatformFilesAndTraversal() {
        MdlWorkspaceService ws =
                new MdlWorkspaceService(
                        new WrenProperties("wren", mdlHome.toString(), "dataagent", "mysql", 10),
                        wren);

        assertThrows(DatasetException.class, () -> ws.resolveWritable("g1", "../escape.yml"));
        assertThrows(DatasetException.class, () -> ws.resolveWritable("g1", "wren_project.yml"));
        assertThrows(
                DatasetException.class, () -> ws.resolveWritable("g1", ".platform/datasets.json"));
        assertThrows(DatasetException.class, () -> ws.resolveWritable("g1", "target/mdl.json"));
        Path allowed = ws.resolveWritable("g1", "models/orders/metadata.yml");
        assertEquals(
                mdlHome.resolve("g1")
                        .resolve("workspace")
                        .resolve("models/orders/metadata.yml")
                        .normalize(),
                allowed);
    }

    /** Scriptable wren CLI: records every call, fakes init and parse-types. */
    private static final class FakeCli implements WrenCli {

        final List<List<String>> calls = new ArrayList<>();

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
            calls.add(List.copyOf(args));
            String cmd = String.join(" ", args);
            if (cmd.startsWith("context init")) {
                return new Result(0, "initialized");
            }
            if (cmd.startsWith("utils parse-types")) {
                return new Result(0, parseTypes(projectHome));
            }
            return new Result(0, "");
        }

        int countCalls(String prefix) {
            return (int)
                    calls.stream()
                            .map(args -> String.join(" ", args))
                            .filter(c -> c.startsWith(prefix))
                            .count();
        }

        private static String parseTypes(Path dir) {
            try {
                List<Map<String, Object>> input =
                        new ObjectMapper()
                                .readValue(
                                        Files.readString(
                                                dir.resolve("types_in.json"),
                                                StandardCharsets.UTF_8),
                                        new TypeReference<List<Map<String, Object>>>() {});
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
