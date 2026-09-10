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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Deterministically infers table-to-table relations inside a knowledge base from parsed column
 * metadata only (no LLM), mirroring the rule-based approach of opne-aureka's relation-inference:
 * exact PK/FK name matches, shared-column heuristics, and {@code _id}/{@code _key} suffix
 * stripping. Every edge carries a confidence so the UI can hide weak links.
 */
public final class SchemaRelationInferrer {

    public static final String EXACT_PK_FK = "EXACT_PK_FK";
    public static final String HEURISTIC_SAME_NAME = "HEURISTIC_SAME_NAME";
    public static final String SUFFIX_STRIP = "SUFFIX_STRIP";

    private SchemaRelationInferrer() {}

    private record TableMeta(String id, String name, List<ColumnSchema> cols) {}

    public static GraphDto infer(
            List<DatasetEntity> datasets, Function<DatasetEntity, List<ColumnSchema>> columnsOf) {
        List<TableMeta> tables = new ArrayList<>();
        List<GraphDto.Node> nodes = new ArrayList<>();
        for (DatasetEntity d : datasets) {
            List<ColumnSchema> cols = columnsOf.apply(d);
            String sanitizedName = Identifiers.sanitize(d.getName(), d.getTableName());
            tables.add(new TableMeta(d.getId(), sanitizedName, cols));
            List<GraphDto.Field> fields =
                    cols.stream()
                            .map(c -> new GraphDto.Field(c.name(), c.sqlType(), c.description()))
                            .toList();
            nodes.add(
                    new GraphDto.Node(
                            d.getId(),
                            d.getName(),
                            "table",
                            fields,
                            d.getOrigin() == null ? "upload" : d.getOrigin()));
        }

        // key = unordered table pair, value = best edge so far
        Map<String, GraphDto.Edge> best = new LinkedHashMap<>();
        for (TableMeta a : tables) {
            for (TableMeta b : tables) {
                if (a.id().equals(b.id())) {
                    continue;
                }
                // Rule 1: A's primary-key candidate column appears in B as a non-pk column.
                for (int ia = 0; ia < a.cols().size(); ia++) {
                    ColumnSchema pk = a.cols().get(ia);
                    if (!isPkCandidate(pk, ia, a)) {
                        continue;
                    }
                    for (int ib = 0; ib < b.cols().size(); ib++) {
                        ColumnSchema fk = b.cols().get(ib);
                        if (!fk.name().equals(pk.name()) || isPkCandidate(fk, ib, b)) {
                            continue;
                        }
                        if (typeCompatible(pk.sqlType(), fk.sqlType())) {
                            put(best, edge(a.id(), b.id(), EXACT_PK_FK, 1.0, pk.name()));
                        }
                    }
                }
                // Rule 3: column in A whose name minus _id/_key equals B's table name.
                for (ColumnSchema c : a.cols()) {
                    String base = stripSuffix(c.name());
                    if (!base.isEmpty() && base.equals(b.name())) {
                        put(best, edge(a.id(), b.id(), SUFFIX_STRIP, 0.6, c.name()));
                    }
                }
            }
        }
        // Rule 2: shared non-pk column name across >=2 tables.
        Map<String, List<TableMeta>> byColumn = new LinkedHashMap<>();
        for (TableMeta t : tables) {
            for (int i = 0; i < t.cols().size(); i++) {
                ColumnSchema c = t.cols().get(i);
                if (isPkCandidate(c, i, t)) {
                    continue;
                }
                byColumn.computeIfAbsent(c.name(), k -> new ArrayList<>()).add(t);
            }
        }
        for (Map.Entry<String, List<TableMeta>> e : byColumn.entrySet()) {
            List<TableMeta> share = e.getValue().stream().distinct().toList();
            if (share.size() < 2) {
                continue;
            }
            for (int i = 0; i < share.size(); i++) {
                for (int j = i + 1; j < share.size(); j++) {
                    put(
                            best,
                            edge(
                                    share.get(i).id(),
                                    share.get(j).id(),
                                    HEURISTIC_SAME_NAME,
                                    0.7,
                                    e.getKey()));
                }
            }
        }
        return new GraphDto(nodes, List.copyOf(best.values()));
    }

    private static GraphDto.Edge edge(
            String source, String target, String type, double confidence, String label) {
        return new GraphDto.Edge(
                source + "->" + target + ":" + type,
                source,
                target,
                type,
                confidence,
                label,
                "column-heuristic");
    }

    private static void put(Map<String, GraphDto.Edge> best, GraphDto.Edge e) {
        String key = pairKey(e.source(), e.target());
        GraphDto.Edge existing = best.get(key);
        if (existing == null || e.confidence() > existing.confidence()) {
            best.put(key, e);
        }
    }

    private static String pairKey(String a, String b) {
        return a.compareTo(b) <= 0 ? a + "|" + b : b + "|" + a;
    }

    private static boolean isPkCandidate(ColumnSchema c, int index, TableMeta t) {
        return index == 0 || c.name().equals("id") || c.name().equals(t.name() + "_id");
    }

    private static String stripSuffix(String columnName) {
        String n = columnName;
        if (n.endsWith("_id")) {
            n = n.substring(0, n.length() - 3);
        } else if (n.endsWith("_key")) {
            n = n.substring(0, n.length() - 4);
        }
        return n;
    }

    private static boolean typeCompatible(String a, String b) {
        return category(a).equals(category(b));
    }

    private static String category(String sqlType) {
        String t = sqlType == null ? "" : sqlType.toUpperCase();
        if (t.startsWith("VARCHAR") || t.startsWith("TEXT") || t.startsWith("CHAR")) {
            return "str";
        }
        if (t.contains("INT")
                || t.contains("DECIMAL")
                || t.contains("NUMERIC")
                || t.contains("BIGINT")) {
            return "num";
        }
        if (t.startsWith("DATE") || t.startsWith("DATETIME") || t.startsWith("TIMESTAMP")) {
            return "time";
        }
        return "other:" + t;
    }
}
