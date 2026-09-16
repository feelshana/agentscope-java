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
package io.agentscope.dataagent.semantic.converter;

import io.agentscope.dataagent.ontology.model.ObjectProperty;
import io.agentscope.dataagent.ontology.model.OntologyLimitation;
import io.agentscope.dataagent.ontology.model.OntologyMetric;
import io.agentscope.dataagent.ontology.model.OntologyModel;
import io.agentscope.dataagent.ontology.model.OntologyObject;
import io.agentscope.dataagent.ontology.model.OntologyRelationship;
import io.agentscope.dataagent.ontology.model.OntologyRule;
import io.agentscope.dataagent.semantic.model.CubeMeasure;
import io.agentscope.dataagent.semantic.model.SemanticColumn;
import io.agentscope.dataagent.semantic.model.SemanticCube;
import io.agentscope.dataagent.semantic.model.SemanticLimitation;
import io.agentscope.dataagent.semantic.model.SemanticModel;
import io.agentscope.dataagent.semantic.model.SemanticModelTable;
import io.agentscope.dataagent.semantic.model.SemanticRelationship;
import io.agentscope.dataagent.semantic.model.SemanticRule;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * OntologyModel → SemanticModel 转换器。
 * 将现有的 model.yaml（OntologyModel 格式）自动映射为 SemanticModel（ontology.json 格式），
 * 实现向后兼容。
 *
 * <p>映射规则：
 * <ul>
 *   <li>OntologyObject → SemanticModelTable</li>
 *   <li>ObjectProperty → SemanticColumn</li>
 *   <li>OntologyRelationship → SemanticRelationship</li>
 *   <li>OntologyMetric → CubeMeasure（归入按 baseObject 分组的 Cube）</li>
 *   <li>OntologyRule → SemanticRule</li>
 *   <li>OntologyLimitation → SemanticLimitation</li>
 * </ul>
 */
@Component
public class OntologyToSemanticModelConverter {

    /**
     * 将 OntologyModel 转换为 SemanticModel。
     *
     * @param ontology 旧格式本体模型
     * @return 新格式语义模型
     */
    public SemanticModel convert(OntologyModel ontology) {
        if (ontology == null) {
            throw new IllegalArgumentException("OntologyModel 不能为空");
        }

        SemanticModel model = new SemanticModel();
        model.set$schema("https://agentscope.io/semantic-model/v1.json");
        model.setLayoutVersion(1);
        model.setDescription(
                "从 "
                        + (ontology.getName() != null ? ontology.getName() : "model.yaml")
                        + " 语义模型转换");
        model.setDataSource("MYSQL");

        // 1. Objects → Models
        List<SemanticModelTable> tables = new ArrayList<>();
        if (ontology.getObjects() != null) {
            for (Map.Entry<String, OntologyObject> entry : ontology.getObjects().entrySet()) {
                tables.add(convertObject(entry.getKey(), entry.getValue()));
            }
        }
        model.setModels(tables);

        // 2. Relationships → Relationships
        List<SemanticRelationship> relationships = new ArrayList<>();
        if (ontology.getRelationships() != null) {
            for (Map.Entry<String, OntologyRelationship> entry :
                    ontology.getRelationships().entrySet()) {
                relationships.add(convertRelationship(entry.getKey(), entry.getValue()));
            }
        }
        model.setRelationships(relationships);

        // 3. Metrics → Cubes（按 baseObject 分组）
        model.setCubes(convertMetrics(ontology.getMetrics()));

        // 4. Rules → Rules
        List<SemanticRule> rules = new ArrayList<>();
        if (ontology.getRules() != null) {
            int idx = 1;
            for (Map.Entry<String, OntologyRule> entry : ontology.getRules().entrySet()) {
                rules.add(convertRule("R" + idx++, entry.getValue()));
            }
        }
        model.setRules(rules);

        // 5. Limitations → Limitations
        List<SemanticLimitation> limitations = new ArrayList<>();
        if (ontology.getLimitations() != null) {
            for (OntologyLimitation lim : ontology.getLimitations()) {
                limitations.add(convertLimitation(lim));
            }
        }
        model.setLimitations(limitations);

        return model;
    }

    private SemanticModelTable convertObject(String name, OntologyObject obj) {
        SemanticModelTable table = new SemanticModelTable();
        table.setName(name);
        table.setTableName(obj.getTable() != null ? obj.getTable() : name);
        table.setLabel(obj.getLabel());
        table.setKind(obj.getKind());
        table.setDescription(obj.getDescription());
        table.setPrimaryKey(obj.getIdentity());

        List<SemanticColumn> columns = new ArrayList<>();
        if (obj.getProperties() != null) {
            for (Map.Entry<String, ObjectProperty> propEntry : obj.getProperties().entrySet()) {
                columns.add(convertProperty(propEntry.getKey(), propEntry.getValue()));
            }
        }
        table.setColumns(columns);
        return table;
    }

    private SemanticColumn convertProperty(String name, ObjectProperty prop) {
        SemanticColumn col = new SemanticColumn();
        col.setName(prop.getColumn() != null ? prop.getColumn() : name);
        col.setType(mapOntologyTypeToSql(prop.getType()));
        col.setLabel(prop.getLabel());
        col.setDescription(prop.getNote());
        return col;
    }

    private SemanticRelationship convertRelationship(String name, OntologyRelationship rel) {
        SemanticRelationship sr = new SemanticRelationship();
        sr.setName(name);
        sr.setLabel(rel.getLabel());
        sr.setDescription(rel.getNote());

        List<String> models = new ArrayList<>();
        if (rel.getFrom() != null) {
            models.add(rel.getFrom());
        }
        if (rel.getTo() != null) {
            models.add(rel.getTo());
        }
        sr.setModels(models);

        // 从 join 列表构建 condition
        sr.setJoinType(mapCardinalityToJoinType(rel.getCardinality()));
        if (rel.getJoin() != null && !rel.getJoin().isEmpty()) {
            StringBuilder condition = new StringBuilder();
            for (Map<String, String> joinEntry : rel.getJoin()) {
                if (condition.length() > 0) {
                    condition.append(" AND ");
                }
                String fromCol = joinEntry.get("from");
                String toCol = joinEntry.get("to");
                if (fromCol != null && toCol != null) {
                    condition
                            .append(rel.getFrom())
                            .append(".")
                            .append(fromCol)
                            .append(" = ")
                            .append(rel.getTo())
                            .append(".")
                            .append(toCol);
                }
            }
            sr.setCondition(condition.toString());
        }
        return sr;
    }

    /**
     * 将 OntologyMetric 列表转换为 Cube 列表。按 baseObject（object 字段）分组，
     * 每个 object 生成一个 Cube。
     */
    private List<SemanticCube> convertMetrics(Map<String, OntologyMetric> metrics) {
        if (metrics == null || metrics.isEmpty()) {
            return new ArrayList<>();
        }

        // 按 object 分组
        Map<String, List<Map.Entry<String, OntologyMetric>>> grouped =
                new java.util.LinkedHashMap<>();
        for (Map.Entry<String, OntologyMetric> entry : metrics.entrySet()) {
            String baseObj = entry.getValue().getObject();
            if (baseObj == null) {
                baseObj = "default";
            }
            grouped.computeIfAbsent(baseObj, k -> new ArrayList<>()).add(entry);
        }

        List<SemanticCube> cubes = new ArrayList<>();
        for (Map.Entry<String, List<Map.Entry<String, OntologyMetric>>> group :
                grouped.entrySet()) {
            SemanticCube cube = new SemanticCube();
            cube.setName(group.getKey() + "_metrics");
            cube.setBaseObject(group.getKey());
            cube.setLabel(group.getKey() + " 指标");

            List<CubeMeasure> measures = new ArrayList<>();
            for (Map.Entry<String, OntologyMetric> metricEntry : group.getValue()) {
                measures.add(convertMetric(metricEntry.getKey(), metricEntry.getValue()));
            }
            cube.setMeasures(measures);

            // 自动生成基础维度（留空，由自动建模补充）
            cube.setDimensions(new ArrayList<>());
            cube.setTimeDimensions(new ArrayList<>());

            cubes.add(cube);
        }
        return cubes;
    }

    private CubeMeasure convertMetric(String name, OntologyMetric metric) {
        CubeMeasure measure = new CubeMeasure();
        measure.setName(name);
        measure.setExpression(buildAggregateExpression(metric));
        measure.setType("BIGINT");
        measure.setDescription(metric.getDescription());
        return measure;
    }

    /**
     * 从 OntologyMetric 的 aggregate + column 构建 SQL 聚合表达式。
     */
    private String buildAggregateExpression(OntologyMetric metric) {
        String agg = metric.getAggregate();
        String col = metric.getColumn();
        if (agg == null) {
            return "COUNT(*)";
        }
        return switch (agg.toLowerCase()) {
            case "sum" -> "SUM(" + (col != null ? col : "*") + ")";
            case "count" -> "COUNT(" + (col != null ? col : "*") + ")";
            case "count_distinct_identity" ->
                    "COUNT(DISTINCT "
                            + (metric.getIdentity_object() != null
                                    ? metric.getIdentity_object()
                                    : col)
                            + ")";
            case "max" -> "MAX(" + (col != null ? col : "*") + ")";
            case "min" -> "MIN(" + (col != null ? col : "*") + ")";
            case "avg" -> "AVG(" + (col != null ? col : "*") + ")";
            default -> agg.toUpperCase() + "(" + (col != null ? col : "*") + ")";
        };
    }

    private SemanticRule convertRule(String id, OntologyRule rule) {
        SemanticRule sr = new SemanticRule();
        sr.setId(id);
        sr.setName(rule.getStatement());
        sr.setDescription(rule.getStatement());
        sr.setParameters(rule.getParams());
        return sr;
    }

    private SemanticLimitation convertLimitation(OntologyLimitation lim) {
        SemanticLimitation sl = new SemanticLimitation();
        sl.setId(lim.getId());
        sl.setName(lim.getId());
        sl.setDescription(lim.getText());
        return sl;
    }

    private String mapOntologyTypeToSql(String ontologyType) {
        if (ontologyType == null) {
            return "VARCHAR";
        }
        return switch (ontologyType.toLowerCase()) {
            case "integer", "int" -> "INTEGER";
            case "decimal", "double", "float" -> "DOUBLE";
            case "boolean", "bool" -> "BOOLEAN";
            case "date" -> "DATE";
            case "datetime", "timestamp" -> "TIMESTAMP";
            case "bigint", "long" -> "BIGINT";
            default -> "VARCHAR";
        };
    }

    private String mapCardinalityToJoinType(String cardinality) {
        if (cardinality == null) {
            return "MANY_TO_ONE";
        }
        return switch (cardinality.toLowerCase()) {
            case "one_to_many" -> "ONE_TO_MANY";
            case "many_to_many" -> "MANY_TO_MANY";
            case "one_to_one" -> "ONE_TO_ONE";
            default -> "MANY_TO_ONE";
        };
    }
}
