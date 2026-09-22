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

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.dataagent.dataset.parser.ColumnSchema;
import io.agentscope.dataagent.web.ai.AgentDraftService;
import io.agentscope.dataagent.web.persistence.jpa.DatasetEntity;
import io.agentscope.dataagent.web.persistence.jpa.DatasetRepository;
import io.agentscope.dataagent.web.persistence.jpa.KnowledgeGraphBuildTaskEntity;
import io.agentscope.dataagent.web.persistence.jpa.KnowledgeGraphBuildTaskRepository;
import io.agentscope.dataagent.web.persistence.jpa.KnowledgeGraphBuildUnitEntity;
import io.agentscope.dataagent.web.persistence.jpa.KnowledgeGraphBuildUnitRepository;
import io.agentscope.dataagent.web.persistence.jpa.KnowledgeGraphEntityEntity;
import io.agentscope.dataagent.web.persistence.jpa.KnowledgeGraphEntityRepository;
import io.agentscope.dataagent.web.persistence.jpa.KnowledgeGraphRelationEntity;
import io.agentscope.dataagent.web.persistence.jpa.KnowledgeGraphRelationRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Yuxi-style LLM GraphRAG pipeline for a knowledge base's semantic graph (TC "知识图谱" analogue).
 * Each build splits the KB into extraction units (the knowledge document plus one unit per dataset
 * schema), asks the model for entities/relations in a fixed JSON schema, and persists deduplicated
 * nodes/edges with per-unit processing status so the UI can poll progress.
 */
@Service
public class KnowledgeGraphService {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeGraphService.class);

    private static final int MAX_SCHEMA_CHARS = 4000;

    /** Doc is split into chunks of this size; each chunk is one small, fast extraction call. */
    private static final int CHUNK_CHARS = 1500;

    /** How many extraction units run in parallel (TC/Yuxi-style concurrent chunk processing). */
    private static final int BUILD_CONCURRENCY = 8;

    private static final String SYSTEM_PROMPT =
            "你是数据资产语义图谱三元组抽取器。仅用以下本体抽取，禁止自创类型/谓词。"
                    + "实体类型(label)仅限：表|字段|字段类型|取值|业务概念|指标|维度|业务术语。"
                    + "谓词(relations.label)仅限：包含字段|是|是主键之一|示例|粒度|时间范围|覆盖范围|"
                    + "不包括|数据量级|日变化率|范围|占比|关联|同义|依赖字段|计算口径。"
                    + "其中：指标=可被提问的度量(如流失率/GMV)，用依赖字段指向其来源字段，用计算口径描述口径；"
                    + "业务术语=企业黑话/别名，用同义指向其对应的字段或指标。"
                    + "仅返回 JSON："
                    + "{\"entities\":[{\"text\":\"显示名\",\"label\":\"实体类型\",\"attributes\":{}}],"
                    + "\"relations\":[{\"source\":\"源text\",\"target\":\"目标text\","
                    + "\"text\":\"关系描述\",\"label\":\"谓词\"}]}。"
                    + "无额外文字。";

    private final KnowledgeGraphEntityRepository entityRepo;
    private final KnowledgeGraphRelationRepository relationRepo;
    private final KnowledgeGraphBuildUnitRepository unitRepo;
    private final KnowledgeGraphBuildTaskRepository taskRepo;
    private final DatasetRepository datasetRepository;
    private final DatasetService datasetService;
    private final DatasetGroupService groupService;
    private final AgentDraftService agentDraftService;
    private final ObjectMapper mapper = new ObjectMapper();

    /** In-flight guard so a group cannot run two builds concurrently. */
    private final ConcurrentHashMap<String, Boolean> running = new ConcurrentHashMap<>();

    public KnowledgeGraphService(
            KnowledgeGraphEntityRepository entityRepo,
            KnowledgeGraphRelationRepository relationRepo,
            KnowledgeGraphBuildUnitRepository unitRepo,
            KnowledgeGraphBuildTaskRepository taskRepo,
            DatasetRepository datasetRepository,
            DatasetService datasetService,
            DatasetGroupService groupService,
            AgentDraftService agentDraftService) {
        this.entityRepo = entityRepo;
        this.relationRepo = relationRepo;
        this.unitRepo = unitRepo;
        this.taskRepo = taskRepo;
        this.datasetRepository = datasetRepository;
        this.datasetService = datasetService;
        this.groupService = groupService;
        this.agentDraftService = agentDraftService;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ExtractionEntity(String text, String label, Map<String, Object> attributes) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ExtractionRelation(String source, String target, String text, String label) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ExtractionResult(
            List<ExtractionEntity> entities, List<ExtractionRelation> relations) {}

    /**
     * Validates ownership + model availability, resets any prior graph, and starts an async build
     * over the selected units (knowledge document and/or specific datasets). Transactional so the
     * derived {@code deleteByGroupId} resets run in a transaction; the async worker is registered
     * as an afterCommit synchronization so it only starts once the unit/task rows are committed.
     */
    @Transactional
    public KnowledgeGraphBuildTaskEntity triggerBuild(
            String ownerId, String groupId, boolean includeDoc, List<String> datasetIds) {
        groupService.getGroup(ownerId, groupId);
        if (!agentDraftService.modelAvailable()) {
            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE, "AI not available — configure a model");
        }
        // The in-JVM lock is the authoritative concurrency guard: a prior live build holds it, and
        // a crashed/stale build (which may have left a RUNNING task/units in the DB) does not, so
        // this also unblocks groups stuck by an earlier failed run.
        if (running.putIfAbsent(groupId, Boolean.TRUE) != null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "graph build already running");
        }
        try {
            entityRepo.deleteByGroupId(groupId);
            relationRepo.deleteByGroupId(groupId);
            unitRepo.deleteByGroupId(groupId);
            taskRepo.deleteByGroupId(groupId);

            List<KnowledgeGraphBuildUnitEntity> units = new ArrayList<>();
            if (includeDoc) {
                String knowledge = datasetService.knowledgeText(groupId);
                if (knowledge != null && !knowledge.isBlank()) {
                    List<String> chunks = chunk(knowledge, CHUNK_CHARS);
                    for (int i = 0; i < chunks.size(); i++) {
                        KnowledgeGraphBuildUnitEntity u =
                                new KnowledgeGraphBuildUnitEntity(
                                        UUID.randomUUID().toString(),
                                        groupId,
                                        KnowledgeGraphBuildUnitEntity.TYPE_KNOWLEDGE_DOC,
                                        groupId + "#" + i,
                                        chunks.size() == 1
                                                ? "知识文档"
                                                : "知识文档 " + (i + 1) + "/" + chunks.size());
                        u.setPayload(chunks.get(i));
                        units.add(u);
                    }
                }
            }
            Set<String> wanted = datasetIds == null ? null : new HashSet<>(datasetIds);
            for (DatasetEntity d : groupService.listDatasets(ownerId, groupId)) {
                if (wanted != null && !wanted.contains(d.getId())) {
                    continue;
                }
                units.add(
                        new KnowledgeGraphBuildUnitEntity(
                                UUID.randomUUID().toString(),
                                groupId,
                                KnowledgeGraphBuildUnitEntity.TYPE_DATASET_SCHEMA,
                                d.getId(),
                                d.getName()));
            }
            if (units.isEmpty()) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST,
                        "select at least one document or table to build a graph from");
            }
            unitRepo.saveAll(units);
            KnowledgeGraphBuildTaskEntity task =
                    taskRepo.save(
                            new KnowledgeGraphBuildTaskEntity(
                                    UUID.randomUUID().toString(), groupId, ownerId));

            TransactionSynchronizationManager.registerSynchronization(
                    new TransactionSynchronization() {
                        @Override
                        public void afterCommit() {
                            Mono.fromCallable(
                                            () -> {
                                                executeBuild(groupId, ownerId);
                                                return null;
                                            })
                                    .subscribeOn(Schedulers.boundedElastic())
                                    .subscribe();
                        }
                    });
            return task;
        } catch (RuntimeException e) {
            running.remove(groupId);
            throw e;
        }
    }

    void executeBuild(String groupId, String ownerId) {
        try {
            List<KnowledgeGraphBuildUnitEntity> units =
                    unitRepo.findByGroupIdOrderByCreatedAtAsc(groupId);
            Flux.fromIterable(units)
                    .flatMap(
                            unit ->
                                    Mono.fromRunnable(() -> runUnit(ownerId, groupId, unit))
                                            .subscribeOn(Schedulers.boundedElastic()),
                            BUILD_CONCURRENCY)
                    .blockLast();
            boolean anyFailed =
                    unitRepo.findByGroupIdOrderByCreatedAtAsc(groupId).stream()
                            .anyMatch(
                                    u ->
                                            KnowledgeGraphBuildUnitEntity.STATUS_FAILED.equals(
                                                    u.getStatus()));
            Optional<KnowledgeGraphBuildTaskEntity> task =
                    taskRepo.findTopByGroupIdOrderByTriggeredAtDesc(groupId);
            if (task.isPresent()) {
                task.get()
                        .setStatus(
                                anyFailed
                                        ? KnowledgeGraphBuildTaskEntity.STATUS_PARTIAL_FAILED
                                        : KnowledgeGraphBuildTaskEntity.STATUS_COMPLETED);
                task.get().setFinishedAt(Instant.now());
                taskRepo.save(task.get());
            }
        } finally {
            running.remove(groupId);
        }
    }

    private void runUnit(String ownerId, String groupId, KnowledgeGraphBuildUnitEntity unit) {
        unit.setStatus(KnowledgeGraphBuildUnitEntity.STATUS_RUNNING);
        unitRepo.save(unit);
        try {
            extractUnit(ownerId, groupId, unit);
            unit.setStatus(KnowledgeGraphBuildUnitEntity.STATUS_SUCCEEDED);
        } catch (Exception e) {
            log.warn("KnowledgeGraphService: unit {} failed: {}", unit.getId(), e.getMessage());
            unit.setStatus(KnowledgeGraphBuildUnitEntity.STATUS_FAILED);
            unit.setErrorMsg(truncate(e.getMessage(), 500));
        }
        unit.setFinishedAt(Instant.now());
        unitRepo.save(unit);
    }

    private void extractUnit(String ownerId, String groupId, KnowledgeGraphBuildUnitEntity unit) {
        if (KnowledgeGraphBuildUnitEntity.TYPE_DATASET_SCHEMA.equals(unit.getUnitType())) {
            DatasetEntity dataset =
                    datasetRepository
                            .findById(unit.getUnitRefId())
                            .orElseThrow(
                                    () ->
                                            new IllegalStateException(
                                                    "dataset not found: " + unit.getUnitRefId()));
            List<List<String>> sampleRows = List.of();
            try {
                sampleRows = datasetService.preview(ownerId, dataset.getId(), 200).rows();
            } catch (Exception e) {
                log.warn(
                        "KnowledgeGraphService: sample rows unavailable for {}: {}",
                        dataset.getId(),
                        e.getMessage());
            }
            persistTriples(
                    ownerId,
                    groupId,
                    SchemaTripleExtractor.extract(
                            dataset, datasetService.readColumns(dataset), sampleRows));
            return;
        }
        String corpus = buildCorpus(unit);
        String prompt =
                SYSTEM_PROMPT
                        + "\n\n## 来源："
                        + unit.getUnitName()
                        + "\n"
                        + corpus
                        + "\n请仅用上述本体抽取三元组。";
        ExtractionResult result = parseWithRetry(prompt);
        persistExtraction(ownerId, groupId, result);
    }

    /** Upserts deterministic schema triples as entity nodes + relation edges (no LLM). */
    @Transactional
    public void persistTriples(
            String ownerId, String groupId, List<SchemaTripleExtractor.Triple> triples) {
        Map<String, String> keyToId = new HashMap<>();
        Set<String> seenRelations = new HashSet<>();
        for (KnowledgeGraphRelationEntity r : relationRepo.findByGroupId(groupId)) {
            seenRelations.add(
                    r.getSourceEntityId() + "|" + r.getTargetEntityId() + "|" + r.getLabel());
        }
        for (SchemaTripleExtractor.Triple t : triples) {
            String sId = upsertEntity(ownerId, groupId, keyToId, t.subject(), t.subjectType());
            String oId = upsertEntity(ownerId, groupId, keyToId, t.object(), t.objectType());
            if (!seenRelations.add(sId + "|" + oId + "|" + t.predicate())) {
                continue;
            }
            relationRepo.save(
                    new KnowledgeGraphRelationEntity(
                            UUID.randomUUID().toString(), groupId, sId, oId, t.predicate(), null));
        }
    }

    private String upsertEntity(
            String ownerId,
            String groupId,
            Map<String, String> keyToId,
            String text,
            String label) {
        String key = text.trim().toLowerCase();
        String cached = keyToId.get(key);
        if (cached != null) {
            return cached;
        }
        KnowledgeGraphEntityEntity entity = null;
        try {
            entity = entityRepo.findByGroupIdAndNormalizedKey(groupId, key).orElse(null);
        } catch (RuntimeException duplicate) {
            // Legacy/race duplicate rows: fall back to a group scan and reuse the first match.
            entity =
                    entityRepo.findByGroupId(groupId).stream()
                            .filter(e -> key.equals(e.getNormalizedKey()))
                            .findFirst()
                            .orElse(null);
        }
        if (entity == null) {
            entity =
                    entityRepo.save(
                            new KnowledgeGraphEntityEntity(
                                    UUID.randomUUID().toString(),
                                    groupId,
                                    ownerId,
                                    key,
                                    label,
                                    text.trim(),
                                    null));
        }
        keyToId.put(key, entity.getId());
        return entity.getId();
    }

    private ExtractionResult parseWithRetry(String prompt) {
        Exception last = null;
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                String raw = agentDraftService.chatBlocking(prompt);
                String json = agentDraftService.extractJsonObject(raw);
                return mapper.readValue(json, ExtractionResult.class);
            } catch (Exception e) {
                last = e;
            }
        }
        throw new IllegalStateException("model extraction failed: " + last.getMessage(), last);
    }

    private String buildCorpus(KnowledgeGraphBuildUnitEntity unit) {
        if (KnowledgeGraphBuildUnitEntity.TYPE_KNOWLEDGE_DOC.equals(unit.getUnitType())) {
            return "### 知识文档片段\n" + (unit.getPayload() == null ? "" : unit.getPayload());
        }
        DatasetEntity dataset =
                datasetRepository
                        .findById(unit.getUnitRefId())
                        .orElseThrow(
                                () ->
                                        new IllegalStateException(
                                                "dataset not found: " + unit.getUnitRefId()));
        StringBuilder sb = new StringBuilder("### Schema\n");
        for (ColumnSchema c : datasetService.readColumns(dataset)) {
            sb.append(dataset.getTableName())
                    .append('.')
                    .append(c.name())
                    .append('(')
                    .append(c.sqlType())
                    .append(')');
            if (c.description() != null && !c.description().isBlank()) {
                sb.append(':').append(c.description());
            }
            sb.append('\n');
            if (sb.length() > MAX_SCHEMA_CHARS) {
                sb.append("[TRUNCATED]\n");
                break;
            }
        }
        return sb.toString();
    }

    @Transactional
    public void persistExtraction(String ownerId, String groupId, ExtractionResult result) {
        Map<String, String> textToId = new HashMap<>();
        if (result.entities() != null) {
            for (ExtractionEntity e : result.entities()) {
                if (e == null || e.text() == null || e.text().isBlank()) {
                    continue;
                }
                String text = e.text().trim();
                String key = text.toLowerCase();
                KnowledgeGraphEntityEntity entity =
                        entityRepo
                                .findByGroupIdAndNormalizedKey(groupId, key)
                                .orElseGet(
                                        () ->
                                                entityRepo.save(
                                                        new KnowledgeGraphEntityEntity(
                                                                UUID.randomUUID().toString(),
                                                                groupId,
                                                                ownerId,
                                                                key,
                                                                e.label() == null
                                                                        ? "业务词"
                                                                        : e.label(),
                                                                text,
                                                                toJson(e.attributes()))));
                textToId.put(key, entity.getId());
            }
        }
        Set<String> seenRelations = new HashSet<>();
        if (result.relations() != null) {
            for (ExtractionRelation r : result.relations()) {
                if (r == null || r.source() == null || r.target() == null) {
                    continue;
                }
                String src = textToId.get(r.source().trim().toLowerCase());
                String tgt = textToId.get(r.target().trim().toLowerCase());
                if (src == null || tgt == null) {
                    continue;
                }
                String label = r.label() == null ? "关联" : r.label();
                if (!seenRelations.add(src + "|" + tgt + "|" + label)) {
                    continue;
                }
                relationRepo.save(
                        new KnowledgeGraphRelationEntity(
                                UUID.randomUUID().toString(), groupId, src, tgt, label, r.text()));
            }
        }
    }

    private String toJson(Map<String, Object> attributes) {
        if (attributes == null || attributes.isEmpty()) {
            return null;
        }
        try {
            return mapper.writeValueAsString(attributes);
        } catch (Exception e) {
            return null;
        }
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max) + "[TRUNCATED]";
    }

    /** Splits text into <=size chunks, breaking at the last newline in each window when possible. */
    private static List<String> chunk(String text, int size) {
        List<String> out = new ArrayList<>();
        String s = text.trim();
        int start = 0;
        while (start < s.length()) {
            int end = Math.min(s.length(), start + size);
            if (end < s.length()) {
                int nl = s.lastIndexOf('\n', end);
                if (nl > start + size / 2) {
                    end = nl + 1;
                }
            }
            out.add(s.substring(start, end).trim());
            start = end;
        }
        return out;
    }

    // ---- reads (ownership-checked) ----

    public List<KnowledgeGraphEntityEntity> entities(String ownerId, String groupId) {
        groupService.getGroup(ownerId, groupId);
        return entityRepo.findByGroupId(groupId);
    }

    public List<KnowledgeGraphRelationEntity> relations(String ownerId, String groupId) {
        groupService.getGroup(ownerId, groupId);
        return relationRepo.findByGroupId(groupId);
    }

    public List<KnowledgeGraphBuildUnitEntity> units(String ownerId, String groupId) {
        groupService.getGroup(ownerId, groupId);
        return unitRepo.findByGroupIdOrderByCreatedAtAsc(groupId);
    }

    public Optional<KnowledgeGraphBuildTaskEntity> latestTask(String ownerId, String groupId) {
        groupService.getGroup(ownerId, groupId);
        return taskRepo.findTopByGroupIdOrderByTriggeredAtDesc(groupId);
    }

    /**
     * Schema-linking / provenance lookup for the agent: given a business term or metric name, finds
     * matching graph entities across the tenant's KBs and returns their dependent fields (resolved
     * to table.column), 计算口径/粒度/范围/示例 facts, and cross-table JOIN hints. Empty when the
     * graph has nothing matching.
     */
    public String semanticContext(String ownerId, String term) {
        return semanticContext(ownerId, term, null);
    }

    /** Group-filtered variant; {@code onlyGroups} null/empty means all of the owner's KBs. */
    public String semanticContext(String ownerId, String term, java.util.List<String> onlyGroups) {
        if (term == null || term.isBlank()) {
            return "";
        }
        java.util.Set<String> want =
                onlyGroups == null || onlyGroups.isEmpty()
                        ? null
                        : new java.util.HashSet<>(onlyGroups);
        String q = term.trim().toLowerCase();
        Map<String, KnowledgeGraphEntityEntity> byId = new HashMap<>();
        List<KnowledgeGraphEntityEntity> matches = new ArrayList<>();
        for (KnowledgeGraphEntityEntity e : entityRepo.findByOwnerId(ownerId)) {
            if (want != null && !want.contains(e.getGroupId())) {
                continue;
            }
            byId.put(e.getId(), e);
            String t = e.getText() == null ? "" : e.getText().toLowerCase();
            if (!t.isEmpty() && (t.contains(q) || q.contains(t))) {
                matches.add(e);
            }
        }
        if (matches.isEmpty()) {
            return "";
        }
        // table lookup: field -> owning table via 包含字段
        Map<String, String> fieldToTable = new HashMap<>();
        Set<String> groupIds = new HashSet<>();
        for (KnowledgeGraphEntityEntity e : matches) {
            groupIds.add(e.getGroupId());
        }
        Map<String, List<KnowledgeGraphRelationEntity>> relByGroup = new HashMap<>();
        for (String gid : groupIds) {
            List<KnowledgeGraphRelationEntity> rs = relationRepo.findByGroupId(gid);
            relByGroup.put(gid, rs);
            for (KnowledgeGraphRelationEntity r : rs) {
                if ("包含字段".equals(r.getLabel())) {
                    KnowledgeGraphEntityEntity tbl = byId.get(r.getSourceEntityId());
                    KnowledgeGraphEntityEntity fld = byId.get(r.getTargetEntityId());
                    if (tbl == null) {
                        tbl = entityRepo.findById(r.getSourceEntityId()).orElse(null);
                        if (tbl != null) byId.put(tbl.getId(), tbl);
                    }
                    if (fld == null) {
                        fld = entityRepo.findById(r.getTargetEntityId()).orElse(null);
                        if (fld != null) byId.put(fld.getId(), fld);
                    }
                    if (tbl != null && fld != null) {
                        fieldToTable.put(fld.getId(), tbl.getText());
                    }
                }
            }
        }
        StringBuilder sb = new StringBuilder();
        for (KnowledgeGraphEntityEntity e : matches) {
            sb.append('[').append(e.getLabel()).append("] ").append(e.getText()).append('\n');
            for (KnowledgeGraphRelationEntity r :
                    relByGroup.getOrDefault(e.getGroupId(), List.of())) {
                boolean out = r.getSourceEntityId().equals(e.getId());
                boolean in = r.getTargetEntityId().equals(e.getId());
                if (!out && !in) {
                    continue;
                }
                String otherId = out ? r.getTargetEntityId() : r.getSourceEntityId();
                KnowledgeGraphEntityEntity other = byId.get(otherId);
                String otherName = other == null ? otherId : other.getText();
                String table = fieldToTable.get(otherId);
                sb.append("  - ").append(r.getLabel()).append(": ").append(otherName);
                if (table != null) {
                    sb.append("  (").append(table).append('.').append(otherName).append(')');
                }
                sb.append('\n');
            }
        }
        return sb.toString();
    }
}
