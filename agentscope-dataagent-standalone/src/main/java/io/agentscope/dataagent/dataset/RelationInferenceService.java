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

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.dataagent.dataset.parser.ColumnSchema;
import io.agentscope.dataagent.web.persistence.jpa.DatasetEntity;
import io.agentscope.dataagent.web.persistence.jpa.DatasetKnowledgeRepository;
import io.agentscope.dataagent.web.persistence.jpa.DatasetRelationEntity;
import io.agentscope.dataagent.web.persistence.jpa.DatasetRelationRepository;
import io.agentscope.dataagent.web.persistence.jpa.DatasetRepository;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Deterministically infers relation edges between the datasets of a knowledge base so the graph
 * tab and the agent's find_related_tables tool can reason about cross-dataset joins:
 *
 * <ul>
 *   <li>SAME_COLUMN (0.7): a column shared by two datasets, matched on the original header
 *       ({@code originalName}, falling back to the physical name) so Chinese headers join even
 *       though their sanitised physical names diverge (上传的「用户id」在两表分别清洗为 {@code id}
 *       与 {@code id_2}). A table's own PK-like column (leading column or {@code <table>_id})
 *       participates only when another dataset carries the same header — the FK-to-PK join
 *       pattern — while the universal PK name {@code id} never participates. Edges carry the
 *       physical column names so they remain directly usable in JOINs.
 *   <li>SUFFIX (0.6): a column whose name minus {@code _id}/{@code _key} equals another dataset's
 *       table/name (classic FK naming).
 *   <li>DOC (0.9): explicit relations written in the group's relationship document, matching
 *       {@code A.col = B.col} or {@code A 通过 col 关联 B}.
 * </ul>
 *
 * <p>LLM-based extraction (origin=llm) is intentionally left for a later milestone.
 *
 * <p>Rebuild semantics: {@link #reinferGroup} deletes and re-derives only untouched auto-derived
 * edges (origin inferred/doc, status PENDING/null). Manual/llm-origin edges and any edge a human
 * confirmed or rejected survive the rebuild, so uploading a new table never silently wipes
 * reviewed relations (specs/010 M1, ADR 0018 D2).
 */
@Service
public class RelationInferenceService {

    private static final Logger log = LoggerFactory.getLogger(RelationInferenceService.class);

    private static final Pattern EQ_PATTERN =
            Pattern.compile(
                    "([\\w\\u4e00-\\u9fa5]+)\\.([\\w\\u4e00-\\u9fa5]+)\\s*=\\s*"
                            + "([\\w\\u4e00-\\u9fa5]+)\\.([\\w\\u4e00-\\u9fa5]+)");
    private static final Pattern VIA_PATTERN =
            Pattern.compile(
                    "([\\w\\u4e00-\\u9fa5]+)\\s*通过\\s*([\\w\\u4e00-\\u9fa5]+)\\s*关联\\s*"
                            + "([\\w\\u4e00-\\u9fa5]+)");

    private final DatasetRelationRepository relationRepository;
    private final DatasetRepository datasetRepository;
    private final DatasetKnowledgeRepository knowledgeRepository;
    private final ObjectMapper mapper;

    public RelationInferenceService(
            DatasetRelationRepository relationRepository,
            DatasetRepository datasetRepository,
            DatasetKnowledgeRepository knowledgeRepository,
            ObjectMapper mapper) {
        this.relationRepository = relationRepository;
        this.datasetRepository = datasetRepository;
        this.knowledgeRepository = knowledgeRepository;
        this.mapper = mapper;
    }

    @Transactional
    public int reinferGroup(String groupId) {
        // Selective rebuild: drop only untouched auto-derived edges; manual/llm origins and
        // human-reviewed (CONFIRMED/REJECTED) records survive (specs/010 M1, ADR 0018 D2).
        List<DatasetRelationEntity> existing = relationRepository.findByGroupId(groupId);
        List<DatasetRelationEntity> kept = new ArrayList<>();
        List<DatasetRelationEntity> stale = new ArrayList<>();
        for (DatasetRelationEntity r : existing) {
            if (survivesRebuild(r)) {
                kept.add(r);
            } else {
                stale.add(r);
            }
        }
        if (!stale.isEmpty()) {
            relationRepository.deleteAllInBatch(stale);
        }
        List<DatasetEntity> datasets = datasetRepository.findByGroupId(groupId);
        Map<String, List<ColumnSchema>> colsOf = new HashMap<>();
        for (DatasetEntity d : datasets) {
            colsOf.put(d.getId(), readColumns(d));
        }
        List<DatasetRelationEntity> edges = new ArrayList<>();
        Map<String, Boolean> seen = new HashMap<>();
        for (DatasetRelationEntity r : kept) {
            // Seed the dedupe map so re-derived duplicates of kept edges are skipped.
            markSeen(
                    seen,
                    r.getSourceDatasetId(),
                    r.getSourceColumn(),
                    r.getTargetDatasetId(),
                    r.getTargetColumn());
        }

        // SAME_COLUMN: a column shared across datasets, matched on the original header so
        // Chinese headers join even though sanitised physical names diverge (「用户id」 becomes
        // `id` in one table and `id_2` in the other) and position-fallback names (col_2 vs col_3)
        // cannot create accidental matches. PK-like columns (leading column or <table>_id)
        // participate only when another dataset carries the same header — the FK-to-PK join
        // pattern (订单表.用户id ↔ 用户表.用户id), fixing the blind spot where dim-table PKs in
        // the leading column were systematically skipped (ADR 0018 D9). The universal PK name
        // "id" never participates: two unrelated tables sharing an "id" column is almost
        // always accidental. Edges carry physical column names so they are directly usable in
        // JOINs.
        Map<String, Integer> datasetsHaving = new LinkedHashMap<>();
        for (DatasetEntity d : datasets) {
            Set<String> keys = new HashSet<>();
            for (ColumnSchema c : colsOf.get(d.getId())) {
                if (keys.add(matchKey(c))) {
                    datasetsHaving.merge(matchKey(c), 1, Integer::sum);
                }
            }
        }
        Map<String, List<ColumnCandidate>> byColumn = new LinkedHashMap<>();
        for (DatasetEntity d : datasets) {
            List<ColumnSchema> cols = colsOf.get(d.getId());
            for (int i = 0; i < cols.size(); i++) {
                ColumnSchema c = cols.get(i);
                String key = matchKey(c);
                if (key.equals("id")) {
                    continue;
                }
                boolean likelyPrimaryKey = i == 0 || c.name().equals(d.getTableName() + "_id");
                if (likelyPrimaryKey && datasetsHaving.getOrDefault(key, 0) < 2) {
                    continue;
                }
                byColumn.computeIfAbsent(key, k -> new ArrayList<>())
                        .add(new ColumnCandidate(d, c.name()));
            }
        }
        for (Map.Entry<String, List<ColumnCandidate>> e : byColumn.entrySet()) {
            List<ColumnCandidate> share = e.getValue();
            if (share.size() < 2) {
                continue;
            }
            for (int i = 0; i < share.size(); i++) {
                for (int j = i + 1; j < share.size(); j++) {
                    ColumnCandidate a = share.get(i);
                    ColumnCandidate b = share.get(j);
                    if (a.dataset().getId().equals(b.dataset().getId())) {
                        continue;
                    }
                    addEdge(
                            edges,
                            seen,
                            groupId,
                            a.dataset(),
                            a.column(),
                            b.dataset(),
                            b.column(),
                            "SAME_COLUMN",
                            "共享列 " + e.getKey(),
                            0.7,
                            "inferred");
                }
            }
        }

        // SUFFIX: column base name equals another dataset's table/name.
        for (DatasetEntity a : datasets) {
            for (ColumnSchema c : colsOf.get(a.getId())) {
                String base = stripSuffix(c.name());
                if (base.isEmpty() || base.equals(c.name())) {
                    continue;
                }
                for (DatasetEntity b : datasets) {
                    if (b.getId().equals(a.getId())) {
                        continue;
                    }
                    if (base.equals(b.getTableName()) || base.equals(b.getName())) {
                        addEdge(
                                edges,
                                seen,
                                groupId,
                                a,
                                c.name(),
                                b,
                                null,
                                "SUFFIX",
                                c.name() + " → " + b.getName(),
                                0.6,
                                "inferred");
                    }
                }
            }
        }

        // DOC: explicit relations from the group's relationship document.
        String knowledge =
                knowledgeRepository
                        .findById(groupId)
                        .map(k -> k.getContent() == null ? "" : k.getContent())
                        .orElse("");
        if (!knowledge.isBlank()) {
            Map<String, DatasetEntity> byName = new HashMap<>();
            for (DatasetEntity d : datasets) {
                byName.put(d.getName().toLowerCase(), d);
                byName.put(d.getTableName().toLowerCase(), d);
            }
            Matcher eq = EQ_PATTERN.matcher(knowledge);
            while (eq.find()) {
                DatasetEntity a = byName.get(eq.group(1).toLowerCase());
                DatasetEntity b = byName.get(eq.group(3).toLowerCase());
                if (a != null && b != null && !a.getId().equals(b.getId())) {
                    addEdge(
                            edges,
                            seen,
                            groupId,
                            a,
                            eq.group(2),
                            b,
                            eq.group(4),
                            "DOC",
                            eq.group(0),
                            0.9,
                            "doc");
                }
            }
            Matcher via = VIA_PATTERN.matcher(knowledge);
            while (via.find()) {
                DatasetEntity a = byName.get(via.group(1).toLowerCase());
                DatasetEntity b = byName.get(via.group(3).toLowerCase());
                if (a != null && b != null && !a.getId().equals(b.getId())) {
                    addEdge(
                            edges,
                            seen,
                            groupId,
                            a,
                            via.group(2),
                            b,
                            null,
                            "DOC",
                            via.group(0),
                            0.9,
                            "doc");
                }
            }
        }

        relationRepository.saveAll(edges);
        log.info(
                "RelationInferenceService: inferred {} edge(s) for group {}",
                edges.size(),
                groupId);
        return edges.size();
    }

    private void addEdge(
            List<DatasetRelationEntity> edges,
            Map<String, Boolean> seen,
            String groupId,
            DatasetEntity a,
            String aCol,
            DatasetEntity b,
            String bCol,
            String type,
            String description,
            double confidence,
            String origin) {
        String key = a.getId() + "|" + aCol + "|" + b.getId() + "|" + bCol;
        String reverse = b.getId() + "|" + bCol + "|" + a.getId() + "|" + aCol;
        if (seen.putIfAbsent(key, Boolean.TRUE) != null
                || seen.putIfAbsent(reverse, Boolean.TRUE) != null) {
            return;
        }
        edges.add(
                new DatasetRelationEntity(
                        UUID.randomUUID().toString(),
                        groupId,
                        a.getId(),
                        aCol,
                        b.getId(),
                        bCol,
                        type,
                        description,
                        confidence,
                        origin));
    }

    /** True when the edge must survive a {@link #reinferGroup} rebuild. */
    private static boolean survivesRebuild(DatasetRelationEntity r) {
        String origin = r.getOrigin() == null ? "inferred" : r.getOrigin();
        if ("manual".equals(origin) || "llm".equals(origin)) {
            return true;
        }
        String status = r.getStatus();
        return "CONFIRMED".equals(status) || "REJECTED".equals(status);
    }

    /** Match key for SAME_COLUMN: the original header when present (preserves Chinese headers
     * whose sanitised physical names diverge across tables), else the physical name. */
    private static String matchKey(ColumnSchema c) {
        if (c.originalName() == null || c.originalName().isBlank()) {
            return c.name();
        }
        return c.originalName().trim().toLowerCase();
    }

    /** A SAME_COLUMN candidate: the owning dataset plus the physical column to put on the edge. */
    private record ColumnCandidate(DatasetEntity dataset, String column) {}

    private static void markSeen(
            Map<String, Boolean> seen, String aId, String aCol, String bId, String bCol) {
        seen.putIfAbsent(aId + "|" + aCol + "|" + bId + "|" + bCol, Boolean.TRUE);
        seen.putIfAbsent(bId + "|" + bCol + "|" + aId + "|" + aCol, Boolean.TRUE);
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

    private List<ColumnSchema> readColumns(DatasetEntity entity) {
        try {
            return mapper.readValue(
                    entity.getColumnSchemaJson(), new TypeReference<List<ColumnSchema>>() {});
        } catch (Exception e) {
            return List.of();
        }
    }
}
