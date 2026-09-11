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

import io.agentscope.dataagent.dataset.parser.ColumnSchema;
import io.agentscope.dataagent.web.persistence.jpa.DatasetEntity;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Deterministic (rule-based) triple extraction over a dataset's parsed schema plus a sample of its
 * rows. Produces the stable backbone of the TC-style knowledge graph without any LLM call:
 * 包含字段 / 是主键之一 / 是(时间|度量|维度)字段 / 数据量级 / 粒度, and — because TC's graph nodes are
 * actual table data — 示例 (low-cardinality distinct values) and 范围 (numeric min~max) triples
 * derived from sampled rows. Field entities use the human-readable description/original name, not
 * the raw column identifier.
 */
public final class SchemaTripleExtractor {

    public static final String T_TABLE = "表";
    public static final String T_FIELD = "字段";
    public static final String T_FIELD_TYPE = "字段类型";
    public static final String T_VALUE = "取值";
    public static final String T_CONCEPT = "业务概念";

    private static final int MAX_DISTINCT_VALUES = 8;
    private static final int MAX_VALUE_TRIPLES = 6;

    /** A (subject, predicate, object) triple plus the entity types of its two endpoints. */
    public record Triple(
            String subject,
            String subjectType,
            String predicate,
            String object,
            String objectType) {}

    private SchemaTripleExtractor() {}

    public static List<Triple> extract(
            DatasetEntity ds, List<ColumnSchema> cols, List<List<String>> sampleRows) {
        List<Triple> out = new ArrayList<>();
        String table = ds.getName();
        List<String> pkFields = new ArrayList<>();
        List<String> dimFields = new ArrayList<>();

        for (int i = 0; i < cols.size(); i++) {
            ColumnSchema c = cols.get(i);
            String field = displayName(c);
            out.add(new Triple(table, T_TABLE, "包含字段", field, T_FIELD));
            boolean pk = isPkCandidate(c, i, ds);
            if (pk) {
                pkFields.add(field);
                out.add(new Triple(field, T_FIELD, "是", "主键", T_FIELD_TYPE));
            }
            String cat = category(c.sqlType());
            if ("time".equals(cat)) {
                out.add(new Triple(field, T_FIELD, "是", "时间字段", T_FIELD_TYPE));
            } else if ("num".equals(cat)) {
                out.add(new Triple(field, T_FIELD, "是", "度量字段", T_FIELD_TYPE));
            } else {
                dimFields.add(field);
                out.add(new Triple(field, T_FIELD, "是", "维度字段", T_FIELD_TYPE));
            }
            addValueTriples(out, field, cat, columnValues(sampleRows, i));
        }
        if (ds.getRowCount() > 0) {
            out.add(new Triple(table, T_TABLE, "数据量级", "约" + ds.getRowCount() + "行", T_CONCEPT));
        }
        if (!pkFields.isEmpty()) {
            out.add(new Triple(table, T_TABLE, "粒度", String.join("x", pkFields), T_CONCEPT));
        }
        return out;
    }

    /** Emits 示例 triples for low-cardinality columns and a 范围 triple for wide numeric columns. */
    private static void addValueTriples(
            List<Triple> out, String field, String cat, List<String> values) {
        if (values.isEmpty()) {
            return;
        }
        if (values.size() <= MAX_DISTINCT_VALUES) {
            int n = 0;
            for (String v : values) {
                if (n++ >= MAX_VALUE_TRIPLES) {
                    break;
                }
                out.add(new Triple(field, T_FIELD, "示例", v, T_VALUE));
            }
        } else if ("num".equals(cat)) {
            double min = Double.MAX_VALUE;
            double max = -Double.MAX_VALUE;
            for (String v : values) {
                Double d = parseNum(v);
                if (d == null) {
                    continue;
                }
                min = Math.min(min, d);
                max = Math.max(max, d);
            }
            if (min <= max) {
                out.add(new Triple(field, T_FIELD, "范围", fmt(min) + "~" + fmt(max), T_CONCEPT));
            }
        }
    }

    private static List<String> columnValues(List<List<String>> rows, int col) {
        Set<String> distinct = new LinkedHashSet<>();
        for (List<String> row : rows) {
            if (col < row.size()) {
                String v = row.get(col);
                if (v != null && !v.isBlank()) {
                    distinct.add(v.trim());
                }
            }
        }
        return new ArrayList<>(distinct);
    }

    private static String displayName(ColumnSchema c) {
        if (c.description() != null && !c.description().isBlank()) {
            return c.description().trim();
        }
        if (c.originalName() != null && !c.originalName().isBlank()) {
            return c.originalName().trim();
        }
        return c.name();
    }

    private static Double parseNum(String s) {
        try {
            return Double.parseDouble(s.replace(",", ""));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String fmt(double d) {
        return d == Math.rint(d) ? String.valueOf((long) d) : String.valueOf(d);
    }

    private static boolean isPkCandidate(ColumnSchema c, int index, DatasetEntity ds) {
        return index == 0 || c.name().equals("id") || c.name().equals(ds.getTableName() + "_id");
    }

    private static String category(String sqlType) {
        String t = sqlType == null ? "" : sqlType.toUpperCase();
        if (t.startsWith("DATE") || t.startsWith("DATETIME") || t.startsWith("TIMESTAMP")) {
            return "time";
        }
        if (t.contains("INT")
                || t.contains("DECIMAL")
                || t.contains("NUMERIC")
                || t.contains("BIGINT")
                || t.contains("FLOAT")
                || t.contains("DOUBLE")) {
            return "num";
        }
        return "str";
    }
}
