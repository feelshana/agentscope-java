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

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Verifies {@link MdlCatalog} reads the publish manifest ({@code <mdlRoot>/<groupId>/mdl.json},
 * the same file the wren server compiles) with the exact field shape {@code
 * MdlPublishService#writeMetaJson} writes, and degrades to empty — never throws — for missing,
 * malformed or path-unsafe inputs so prompt building cannot fail because of one bad manifest.
 */
class MdlCatalogTest {

    private static final String FULL_MANIFEST =
            """
            {
              "groupId": "gA",
              "groupName": "订单分析",
              "version": 3,
              "publishedAt": "2026-09-26T10:00:00",
              "dataSource": "mysql",
              "models": [
                {
                  "name": "订单",
                  "datasetId": "ds_a",
                  "datasetName": "订单表",
                  "description": "订单明细",
                  "columns": [
                    {"name": "城市", "type": "VARCHAR", "description": "开通城市"},
                    {"name": "金额", "type": "DECIMAL(18,6)", "calculated": true,
                     "expression": "SUM(金额)"}
                  ]
                },
                {
                  "name": "客户",
                  "datasetId": "ds_b",
                  "datasetName": "客户表",
                  "columns": [{"name": "客户ID", "type": "BIGINT"}]
                }
              ],
              "relations": [
                {
                  "name": "订单_客户",
                  "leftModel": "订单",
                  "rightModel": "客户",
                  "joinType": "MANY_TO_ONE",
                  "condition": "订单.客户ID = 客户.客户ID",
                  "sourceDatasetId": "ds_a",
                  "targetDatasetId": "ds_b"
                }
              ],
              "cubes": [
                {
                  "name": "销售Cube",
                  "baseModel": "订单",
                  "description": "销售指标",
                  "measures": [{"name": "销售额", "expression": "SUM(金额)", "type": "decimal",
                                "description": "成交金额"}],
                  "dimensions": [{"name": "城市", "expression": "城市", "type": "varchar",
                                  "description": "开通城市"}],
                  "timeDimensions": [{"name": "下单日期", "expression": "下单日期", "type": "date",
                                      "description": "下单时间"}]
                }
              ],
              "views": [
                {
                  "name": "近30天用户行为分层",
                  "sql": "SELECT user_segment, visit_count FROM 订单",
                  "description": "按低频用户和高频用户汇总行为偏好"
                }
              ]
            }
            """;

    @TempDir Path tmp;

    private MdlCatalog catalog;

    @BeforeEach
    void setUp() {
        catalog =
                new MdlCatalog(
                        new WrenProperties("wren", tmp.toString(), "dataagent", "mysql", 10));
    }

    @Test
    void loadParsesEveryManifestSection() throws Exception {
        writeManifest("gA", FULL_MANIFEST);

        Optional<MdlCatalog.GroupMdl> loaded = catalog.load("gA");

        assertThat(loaded).isPresent();
        MdlCatalog.GroupMdl mdl = loaded.get();
        assertThat(mdl.groupId()).isEqualTo("gA");
        assertThat(mdl.groupName()).isEqualTo("订单分析");
        assertThat(mdl.version()).isEqualTo(3);
        // models + columns; the calculated flag and expression survive for prompt rendering
        assertThat(mdl.models()).extracting(MdlCatalog.Model::name).containsExactly("订单", "客户");
        MdlCatalog.Model order = mdl.models().get(0);
        assertThat(order.description()).isEqualTo("订单明细");
        assertThat(order.columns()).extracting(MdlCatalog.Column::name).containsExactly("城市", "金额");
        assertThat(order.columns().get(1).calculated()).isTrue();
        assertThat(order.columns().get(1).expression()).isEqualTo("SUM(金额)");
        // relations
        assertThat(mdl.relations()).hasSize(1);
        assertThat(mdl.relations().get(0).condition()).isEqualTo("订单.客户ID = 客户.客户ID");
        assertThat(mdl.relations().get(0).joinType()).isEqualTo("MANY_TO_ONE");
        // cubes with all three member lists
        assertThat(mdl.cubes()).hasSize(1);
        MdlCatalog.Cube cube = mdl.cubes().get(0);
        assertThat(cube.name()).isEqualTo("销售Cube");
        assertThat(cube.baseModel()).isEqualTo("订单");
        assertThat(cube.measures()).extracting(MdlCatalog.Member::name).containsExactly("销售额");
        assertThat(cube.dimensions()).extracting(MdlCatalog.Member::name).containsExactly("城市");
        assertThat(cube.timeDimensions())
                .extracting(MdlCatalog.Member::name)
                .containsExactly("下单日期");
        // views are part of the same immutable published snapshot used by query routing
        assertThat(mdl.views()).hasSize(1);
        MdlCatalog.View view = mdl.views().get(0);
        assertThat(view.name()).isEqualTo("近30天用户行为分层");
        assertThat(view.sql()).contains("FROM 订单");
        assertThat(view.description()).contains("低频用户", "高频用户");
    }

    @Test
    void coveredDatasetIdsDrivesThePerDatasetPromptSplit() throws Exception {
        writeManifest("gA", FULL_MANIFEST);

        MdlCatalog.GroupMdl mdl = catalog.load("gA").orElseThrow();

        assertThat(mdl.coveredDatasetIds()).containsExactlyInAnyOrder("ds_a", "ds_b");
    }

    @Test
    void loadReturnsEmptyForMissingManifest() {
        assertThat(catalog.load("nope")).isEmpty();
    }

    @Test
    void loadReturnsEmptyForBlankOrPathUnsafeGroupId() {
        assertThat(catalog.load(null)).isEmpty();
        assertThat(catalog.load("  ")).isEmpty();
        // Path traversal must be swallowed, not thrown, so prompt building stays resilient.
        assertThat(catalog.load("../etc")).isEmpty();
    }

    @Test
    void loadReturnsEmptyForMalformedJson() throws Exception {
        writeManifest("gBad", "{ not json");

        assertThat(catalog.load("gBad")).isEmpty();
    }

    @Test
    void loadToleratesMinimalManifestWithoutRelationsOrCubes() throws Exception {
        writeManifest(
                "gMin", "{\"groupId\":\"gMin\",\"groupName\":\"Min\",\"version\":1,\"models\":[]}");

        MdlCatalog.GroupMdl mdl = catalog.load("gMin").orElseThrow();

        assertThat(mdl.models()).isEmpty();
        assertThat(mdl.relations()).isEmpty();
        assertThat(mdl.cubes()).isEmpty();
        assertThat(mdl.views()).isEmpty();
        assertThat(mdl.coveredDatasetIds()).isEmpty();
    }

    private void writeManifest(String groupId, String json) throws Exception {
        Path dir = tmp.resolve(groupId);
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("mdl.json"), json, StandardCharsets.UTF_8);
    }
}
