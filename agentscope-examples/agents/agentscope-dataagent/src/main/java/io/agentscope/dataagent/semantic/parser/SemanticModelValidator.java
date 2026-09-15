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

import io.agentscope.dataagent.semantic.model.CubeMeasure;
import io.agentscope.dataagent.semantic.model.SemanticCube;
import io.agentscope.dataagent.semantic.model.SemanticModel;
import io.agentscope.dataagent.semantic.model.SemanticModelTable;
import io.agentscope.dataagent.semantic.model.SemanticRelationship;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * 语义模型校验器。校验 SemanticModel 的结构完整性和引用一致性：
 * <ul>
 *   <li>model 必须有 name 和 tableName</li>
 *   <li>relationship 引用的两端 model 必须存在</li>
 *   <li>cube 引用的 baseObject 必须存在</li>
 *   <li>cube measure 的 expression 不能为空</li>
 *   <li>列名在同一个 model 内唯一</li>
 * </ul>
 */
@Component
public class SemanticModelValidator {

    /**
     * 校验语义模型，返回校验错误列表。空列表表示校验通过。
     *
     * @param model 语义模型
     * @return 校验错误信息列表
     */
    public List<String> validate(SemanticModel model) {
        List<String> errors = new ArrayList<>();

        if (model == null) {
            errors.add("语义模型不能为空");
            return errors;
        }

        // 收集 model 名称集合
        Set<String> modelNames = new HashSet<>();
        if (model.getModels() != null) {
            for (SemanticModelTable table : model.getModels()) {
                if (isBlank(table.getName())) {
                    errors.add("Model 缺少 name 字段");
                } else if (!modelNames.add(table.getName())) {
                    errors.add("Model name 重复: " + table.getName());
                }
                if (isBlank(table.getTableName())) {
                    errors.add("Model '" + table.getName() + "' 缺少 tableName（物理表名）");
                }
                // 校验列名唯一性
                validateColumnUniqueness(table, errors);
            }
        }

        // 校验 relationship 引用
        if (model.getRelationships() != null) {
            for (SemanticRelationship rel : model.getRelationships()) {
                if (isBlank(rel.getName())) {
                    errors.add("Relationship 缺少 name");
                }
                if (rel.getModels() == null || rel.getModels().size() != 2) {
                    errors.add("Relationship '" + rel.getName() + "' 的 models 必须包含两个元素");
                } else {
                    for (String ref : rel.getModels()) {
                        if (!modelNames.contains(ref)) {
                            errors.add(
                                    "Relationship '" + rel.getName() + "' 引用了不存在的 model: " + ref);
                        }
                    }
                }
                if (isBlank(rel.getCondition())) {
                    errors.add("Relationship '" + rel.getName() + "' 缺少 condition（JOIN 条件）");
                }
            }
        }

        // 校验 cube 引用
        if (model.getCubes() != null) {
            for (SemanticCube cube : model.getCubes()) {
                if (isBlank(cube.getName())) {
                    errors.add("Cube 缺少 name");
                }
                if (isBlank(cube.getBaseObject())) {
                    errors.add("Cube '" + cube.getName() + "' 缺少 baseObject");
                } else if (!modelNames.contains(cube.getBaseObject())) {
                    errors.add(
                            "Cube '"
                                    + cube.getName()
                                    + "' 引用了不存在的 baseObject: "
                                    + cube.getBaseObject());
                }
                // 校验 measure expression
                if (cube.getMeasures() != null) {
                    for (CubeMeasure measure : cube.getMeasures()) {
                        if (isBlank(measure.getName())) {
                            errors.add("Cube '" + cube.getName() + "' 的 measure 缺少 name");
                        }
                        if (isBlank(measure.getExpression())) {
                            errors.add(
                                    "Cube '"
                                            + cube.getName()
                                            + "' 的 measure '"
                                            + measure.getName()
                                            + "' 缺少 expression");
                        }
                    }
                }
            }
        }

        return errors;
    }

    /**
     * 校验 model 内列名唯一性。
     */
    private void validateColumnUniqueness(SemanticModelTable table, List<String> errors) {
        if (table.getColumns() == null) {
            return;
        }
        Set<String> columnNames = new HashSet<>();
        table.getColumns()
                .forEach(
                        col -> {
                            if (col.getName() != null && !columnNames.add(col.getName())) {
                                errors.add(
                                        "Model '" + table.getName() + "' 内列名重复: " + col.getName());
                            }
                        });
    }

    private boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
