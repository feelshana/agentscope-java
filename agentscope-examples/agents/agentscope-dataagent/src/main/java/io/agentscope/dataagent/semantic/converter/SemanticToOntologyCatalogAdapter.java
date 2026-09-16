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

import io.agentscope.dataagent.semantic.model.CubeMeasure;
import io.agentscope.dataagent.semantic.model.SemanticColumn;
import io.agentscope.dataagent.semantic.model.SemanticCube;
import io.agentscope.dataagent.semantic.model.SemanticModel;
import io.agentscope.dataagent.semantic.model.SemanticModelTable;
import io.agentscope.dataagent.semantic.model.SemanticRelationship;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 将 SemanticModel 适配为旧本体目录形状（objects/relationships/metrics/rules/limitations），
 * 让对象目录等前端视图以与 legacy YAML 本体相同的数据契约消费语义模型。
 *
 * <p>关系不解析 condition 字符串：原文放入 note，join 置空列表，由前端直接展示原文。
 */
public final class SemanticToOntologyCatalogAdapter {

    private static final Pattern AGG_EXPR =
            Pattern.compile("^(SUM|COUNT|AVG|MAX|MIN)\\((.+)\\)$", Pattern.CASE_INSENSITIVE);

    private SemanticToOntologyCatalogAdapter() {}

    public static Map<String, Object> adapt(SemanticModel model) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("objects", adaptObjects(model));
        out.put("relationships", adaptRelationships(model));
        out.put("metrics", adaptMetrics(model));
        out.put("rules", adaptRules(model));
        out.put("limitations", adaptLimitations(model));
        return out;
    }

    private static Map<String, Object> adaptObjects(SemanticModel model) {
        Map<String, Object> objects = new LinkedHashMap<>();
        if (model.getModels() == null) {
            return objects;
        }
        for (SemanticModelTable table : model.getModels()) {
            Map<String, Object> obj = new LinkedHashMap<>();
            obj.put("label", table.getLabel() != null ? table.getLabel() : table.getName());
            obj.put("table", table.getTableName());
            obj.put("kind", table.getKind());
            obj.put("identity", table.getPrimaryKey());
            obj.put("description", table.getDescription());
            String timeField = null;
            Map<String, Object> properties = new LinkedHashMap<>();
            if (table.getColumns() != null) {
                for (SemanticColumn col : table.getColumns()) {
                    Map<String, Object> prop = new LinkedHashMap<>();
                    prop.put("column", col.getName());
                    prop.put("type", col.getType());
                    prop.put("label", col.getLabel());
                    prop.put("note", col.getDescription() != null ? col.getDescription() : "");
                    properties.put(col.getName(), prop);
                    if (timeField == null && isTimeType(col.getType())) {
                        timeField = col.getName();
                    }
                }
            }
            obj.put("time_field", timeField);
            obj.put("properties", properties);
            objects.put(table.getName(), obj);
        }
        return objects;
    }

    private static Map<String, Object> adaptRelationships(SemanticModel model) {
        Map<String, Object> relationships = new LinkedHashMap<>();
        if (model.getRelationships() == null) {
            return relationships;
        }
        for (SemanticRelationship rel : model.getRelationships()) {
            Map<String, Object> r = new LinkedHashMap<>();
            List<String> ends = rel.getModels() != null ? rel.getModels() : List.of();
            r.put("from", ends.size() > 0 ? ends.get(0) : null);
            r.put("to", ends.size() > 1 ? ends.get(1) : null);
            r.put("join", List.of());
            r.put(
                    "cardinality",
                    rel.getJoinType() != null ? rel.getJoinType().toLowerCase() : null);
            r.put("label", rel.getLabel());
            r.put("note", rel.getCondition());
            relationships.put(rel.getName(), r);
        }
        return relationships;
    }

    private static Map<String, Object> adaptMetrics(SemanticModel model) {
        Map<String, Object> metrics = new LinkedHashMap<>();
        if (model.getCubes() == null) {
            return metrics;
        }
        for (SemanticCube cube : model.getCubes()) {
            if (cube.getMeasures() == null) {
                continue;
            }
            for (CubeMeasure measure : cube.getMeasures()) {
                String aggregate;
                String column;
                if (measure.getExpression() != null) {
                    Matcher m = AGG_EXPR.matcher(measure.getExpression().trim());
                    if (m.matches()) {
                        aggregate = m.group(1).toUpperCase();
                        column = m.group(2);
                    } else {
                        aggregate = "custom";
                        column = measure.getExpression();
                    }
                } else {
                    aggregate = "custom";
                    column = "";
                }
                Map<String, Object> metric = new LinkedHashMap<>();
                metric.put("label", measure.getName());
                metric.put("object", cube.getBaseObject());
                metric.put("aggregate", aggregate);
                metric.put("column", column);
                metric.put("unit", "");
                metric.put("description", measure.getDescription());
                String key = measure.getName();
                if (metrics.containsKey(key)) {
                    key = cube.getName() + "." + measure.getName();
                }
                metrics.put(key, metric);
            }
        }
        return metrics;
    }

    private static Map<String, Object> adaptRules(SemanticModel model) {
        Map<String, Object> rules = new LinkedHashMap<>();
        if (model.getRules() == null) {
            return rules;
        }
        for (var rule : model.getRules()) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("statement", rule.getDescription());
            r.put("params", rule.getParameters());
            r.put("note", rule.getSqlHint());
            String key = rule.getId() != null ? rule.getId() : rule.getName();
            rules.put(key, r);
        }
        return rules;
    }

    private static List<Map<String, Object>> adaptLimitations(SemanticModel model) {
        List<Map<String, Object>> limitations = new ArrayList<>();
        if (model.getLimitations() == null) {
            return limitations;
        }
        for (var lim : model.getLimitations()) {
            Map<String, Object> l = new LinkedHashMap<>();
            l.put("id", lim.getId());
            l.put("applies_to", List.of());
            l.put("text", lim.getDescription());
            limitations.add(l);
        }
        return limitations;
    }

    private static boolean isTimeType(String type) {
        if (type == null) {
            return false;
        }
        String upper = type.toUpperCase();
        return upper.contains("TIMESTAMP") || upper.contains("DATE");
    }
}
