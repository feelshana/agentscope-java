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
import org.junit.jupiter.api.Test;

/**
 * Locks the deterministic schema-triple backbone: 包含字段 / 是(主键|时间|度量|维度) / 数据量级 / 粒度,
 * the stable, LLM-free half of the TC-style knowledge graph.
 */
class SchemaTripleExtractorTest {

    private static DatasetEntity ds() {
        DatasetEntity e = new DatasetEntity();
        e.setId("d1");
        e.setName("在订用户日统计");
        e.setTableName("ds_x_d1");
        e.setRowCount(1000);
        return e;
    }

    @Test
    void extractsBackboneTriples() {
        List<ColumnSchema> cols =
                List.of(
                        new ColumnSchema("date", "date", "DATE", false, "日期"),
                        new ColumnSchema("province", "province", "VARCHAR", true, "省份"),
                        new ColumnSchema("users", "users", "BIGINT", true, "在订用户数"));
        List<List<String>> rows =
                List.of(
                        List.of("2026-08-10", "北京市", "100"),
                        List.of("2026-08-11", "广东省", "200"),
                        List.of("2026-08-12", "北京市", "300"));
        List<SchemaTripleExtractor.Triple> triples =
                SchemaTripleExtractor.extract(ds(), cols, rows);

        assertTrue(
                triples.stream()
                        .anyMatch(t -> t.predicate().equals("包含字段") && t.object().equals("日期")),
                "field entity uses the semantic description, not the raw column id");
        assertTrue(
                triples.stream()
                        .anyMatch(t -> t.predicate().equals("示例") && t.object().equals("北京市")),
                "low-cardinality sample values become 示例 triples");
        assertTrue(
                triples.stream()
                        .anyMatch(t -> t.predicate().equals("是") && t.object().equals("时间字段")),
                "DATE column typed as 时间字段");
        assertTrue(
                triples.stream()
                        .anyMatch(t -> t.predicate().equals("是") && t.object().equals("度量字段")),
                "numeric column typed as 度量字段");
        assertTrue(
                triples.stream()
                        .anyMatch(t -> t.predicate().equals("是") && t.object().equals("维度字段")),
                "string column typed as 维度字段");
        assertTrue(
                triples.stream()
                        .anyMatch(t -> t.predicate().equals("数据量级") && t.object().contains("1000")),
                "row count becomes 数据量级");
        assertTrue(triples.stream().anyMatch(t -> t.predicate().equals("粒度")), "pk+dim yields 粒度");
        assertEquals(
                SchemaTripleExtractor.T_TABLE,
                triples.stream()
                        .filter(t -> t.predicate().equals("包含字段"))
                        .findFirst()
                        .orElseThrow()
                        .subjectType());
    }
}
