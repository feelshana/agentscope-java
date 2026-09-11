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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
 *   <li>SAME_COLUMN (0.7): a non-primary column name shared by two datasets.
 *   <li>SUFFIX (0.6): a column whose name minus {@code _id}/{@code _key} equals another dataset's
 *       table/name (classic FK naming).
 *   <li>DOC (0.9): explicit relations written in the group's relationship document, matching
 *       {@code A.col = B.col} or {@code A 通过 col 关联 B}.
 * </ul>
 *
 * <p>LLM-based extraction (origin=llm) is intentionally left for a later milestone.
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
        relationRepository.deleteByGroupId(groupId);
        List<DatasetEntity> datasets = datasetRepository.findByGroupId(groupId);
        Map<String, List<ColumnSchema>> colsOf = new HashMap<>();
        for (DatasetEntity d : datasets) {
            colsOf.put(d.getId(), readColumns(d));
        }
        List<DatasetRelationEntity> edges = new ArrayList<>();
        Map<String, Boolean> seen = new HashMap<>();

        // SAME_COLUMN: shared non-primary column across datasets.
        Map<String, List<DatasetEntity>> byColumn = new LinkedHashMap<>();
        for (DatasetEntity d : datasets) {
            for (int i = 0; i < colsOf.get(d.getId()).size(); i++) {
                ColumnSchema c = colsOf.get(d.getId()).get(i);
                if (i == 0 || c.name().equals("id") || c.name().equals(d.getTableName() + "_id")) {
                    continue;
                }
                byColumn.computeIfAbsent(c.name(), k -> new ArrayList<>()).add(d);
            }
        }
        for (Map.Entry<String, List<DatasetEntity>> e : byColumn.entrySet()) {
            List<DatasetEntity> share = e.getValue();
            if (share.size() < 2) {
                continue;
            }
            for (int i = 0; i < share.size(); i++) {
                for (int j = i + 1; j < share.size(); j++) {
                    addEdge(
                            edges,
                            seen,
                            groupId,
                            share.get(i),
                            e.getKey(),
                            share.get(j),
                            e.getKey(),
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
