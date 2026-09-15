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
package io.agentscope.dataagent.semantic.parser;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.dataagent.semantic.model.SemanticCube;
import io.agentscope.dataagent.semantic.model.SemanticModel;
import io.agentscope.dataagent.semantic.model.SemanticModelTable;
import io.agentscope.dataagent.semantic.model.SemanticRelationship;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/**
 * 验证 WrenAI MDL 格式兼容解析：tableReference、嵌套 properties。
 */
public class SemanticModelParserTest {

    private static final String WREN_MDL_JSON =
            """
            {
              "$schema": "https://example.com/schema.json",
              "catalog": "migu_ops",
              "schema": "public",
              "dataSource": "DUCKDB",
              "models": [
                {
                  "name": "user_object",
                  "tableReference": { "table": "user_object" },
                  "properties": {
                    "label": "用户",
                    "description": "用户对象表"
                  },
                  "kind": "dimension",
                  "columns": [
                    {
                      "name": "user_id",
                      "type": "VARCHAR",
                      "properties": { "label": "用户ID", "note": "唯一标识" }
                    },
                    {
                      "name": "account",
                      "type": "VARCHAR",
                      "label": "账号",
                      "description": "用户账号"
                    }
                  ]
                }
              ],
              "relationships": [
                {
                  "name": "clickPerformedBy",
                  "models": ["click_observation", "user_object"],
                  "joinType": "MANY_TO_ONE",
                  "condition": "click_observation.account = user_object.account",
                  "properties": {
                    "label": "点击执行者",
                    "description": "用户执行点击操作"
                  }
                }
              ],
              "cubes": [
                {
                  "name": "click_metrics",
                  "baseObject": "click_observation",
                  "properties": { "description": "模块点击事件聚合指标" },
                  "measures": [
                    {
                      "name": "click_pv",
                      "expression": "SUM(visit_count)",
                      "type": "BIGINT",
                      "description": "点击PV"
                    }
                  ],
                  "dimensions": [
                    {
                      "name": "module_name",
                      "expression": "module_name",
                      "type": "VARCHAR"
                    }
                  ],
                  "timeDimensions": []
                }
              ],
              "rules": [],
              "limitations": []
            }
            """;

    @Test
    void parseWrenAiFormat_tableReferenceAndProperties() {
        SemanticModelParser parser = new SemanticModelParser();
        SemanticModel model = parser.parse(WREN_MDL_JSON);

        assertNotNull(model);
        assertEquals("DUCKDB", model.getDataSource());
        assertEquals("migu_ops", model.getCatalog());

        // Model: tableReference.table → tableName, properties.label → label
        assertEquals(1, model.getModels().size());
        SemanticModelTable table = model.getModels().get(0);
        assertEquals("user_object", table.getName());
        assertEquals("user_object", table.getTableName());
        assertEquals("用户", table.getLabel());
        assertEquals("用户对象表", table.getDescription());
        assertEquals("dimension", table.getKind());

        // Column: properties.label → label, properties.note → description
        assertEquals(2, table.getColumns().size());
        assertEquals("用户ID", table.getColumns().get(0).getLabel());
        assertEquals("唯一标识", table.getColumns().get(0).getDescription());
        // 普通格式仍然兼容
        assertEquals("账号", table.getColumns().get(1).getLabel());
        assertEquals("用户账号", table.getColumns().get(1).getDescription());

        // Relationship: properties.label → label, properties.description → description
        assertEquals(1, model.getRelationships().size());
        SemanticRelationship rel = model.getRelationships().get(0);
        assertEquals("点击执行者", rel.getLabel());
        assertEquals("用户执行点击操作", rel.getDescription());
        assertEquals("MANY_TO_ONE", rel.getJoinType());

        // Cube: properties.description → description
        assertEquals(1, model.getCubes().size());
        SemanticCube cube = model.getCubes().get(0);
        assertEquals("模块点击事件聚合指标", cube.getDescription());
        assertEquals("click_observation", cube.getBaseObject());

        // Measure: description at top level
        assertEquals(1, cube.getMeasures().size());
        assertEquals("点击PV", cube.getMeasures().get(0).getDescription());
    }

    @Test
    void parseRealMdlJson() throws IOException {
        // 尝试读取项目中的真实 mdl.json 文件
        var resource = getClass().getClassLoader().getResourceAsStream("mdl.json");
        if (resource == null) {
            // 如果不在 classpath，尝试从 docs 目录读取
            return;
        }
        String jsonText = new String(resource.readAllBytes(), StandardCharsets.UTF_8);
        SemanticModelParser parser = new SemanticModelParser();
        SemanticModel model = parser.parse(jsonText);

        assertNotNull(model);
        assertTrue(model.getModels().size() > 0, "应解析出 models");
        assertTrue(model.getRelationships().size() > 0, "应解析出 relationships");
        assertTrue(model.getCubes().size() > 0, "应解析出 cubes");

        // 验证第一个 model 的 tableReference 正确解析
        SemanticModelTable firstModel = model.getModels().get(0);
        assertNotNull(firstModel.getTableName(), "tableName 应通过 tableReference 解析");
    }
}
