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
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.dataformat.yaml.YAMLGenerator;
import io.agentscope.dataagent.dataset.parser.ColumnSchema;
import io.agentscope.dataagent.web.ai.AgentDraftService;
import io.agentscope.dataagent.web.persistence.jpa.DatasetEntity;
import io.agentscope.dataagent.web.persistence.jpa.DatasetRelationEntity;
import io.agentscope.dataagent.web.persistence.jpa.DatasetRelationRepository;
import io.agentscope.dataagent.web.persistence.jpa.DatasetRepository;
import io.agentscope.dataagent.web.persistence.jpa.SemanticCubeEntity;
import io.agentscope.dataagent.web.persistence.jpa.SemanticCubeRepository;
import io.agentscope.dataagent.web.persistence.jpa.SemanticTermEntity;
import io.agentscope.dataagent.web.persistence.jpa.SemanticTermRepository;
import io.agentscope.dataagent.web.persistence.jpa.SemanticViewEntity;
import io.agentscope.dataagent.web.persistence.jpa.SemanticViewRepository;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
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
 * Semantic-modeling suggestion layer for a knowledge base (specs/010 M1): deterministic rule
 * edges (delegated to {@link RelationInferenceService#reinferGroup}), LLM-proposed relation
 * candidates and cube proposals, plus joinType resolution by uniqueness probing
 * ({@code COUNT(DISTINCT)} vs row count — never guessed). Human review state lives on
 * {@link DatasetRelationEntity#getStatus()}: cursored records (CONFIRMED/REJECTED) and
 * manual/llm origins survive re-inference.
 *
 * <p>specs/019 §7: a confirmed edge is also persisted straight into the workspace's {@code
 * relationships.yml} (the official project file the wren engine joins on); the DB row remains
 * review bookkeeping only. {@link #rejectRelation} never touches the file.
 *
 * <p>All methods are blocking (JDBC probes + model calls); callers must run them on a
 * bounded-elastic scheduler.
 */
@Service
public class MdlSuggestionService {

    private static final Logger log = LoggerFactory.getLogger(MdlSuggestionService.class);

    /**
     * MySQL/Doris functions proven unsupported by the wren view engine's DuckDB planner (probe
     * evidence, specs/018 / ADR 0032): function name → rewrite hint. View statements are expanded
     * into CTEs and planned by DuckDB, where these functions only fail at query time — hence the
     * create/update gate. Ordered so the error message lists hints deterministically.
     */
    private static final Map<String, String> WREN_UNSUPPORTED_VIEW_FUNCTIONS =
            buildViewFunctionBlocklist();

    private static Map<String, String> buildViewFunctionBlocklist() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("DATE_SUB", "日期减法改用 col - INTERVAL n DAY");
        m.put("DATE_ADD", "日期加法改用 col + INTERVAL n DAY");
        m.put("ADDDATE", "日期加法改用 col + INTERVAL n DAY");
        m.put("SUBDATE", "日期减法改用 col - INTERVAL n DAY");
        m.put("YEAR", "取年份改用 DATE_FORMAT(col, '%Y')");
        m.put("CURDATE", "改用 CURRENT_DATE");
        m.put("CURTIME", "改用 CURRENT_TIME");
        m.put("STR_TO_DATE", "字符串解析改用 CAST/TRY_CAST");
        m.put("GROUP_CONCAT", "聚合拼接改用 STRING_AGG(col, '分隔符')");
        m.put("DATEDIFF", "日期差改用 DATE_DIFF('day', a, b)");
        m.put("TIMESTAMPDIFF", "时间差改用 DATE_DIFF(unit, a, b)");
        m.put("IF", "条件判断改用 CASE WHEN ... THEN ... ELSE ... END");
        m.put("LAST_DAY", "月末改用 DATE_TRUNC('month', col) + INTERVAL 1 MONTH - INTERVAL 1 DAY");
        m.put("UNIX_TIMESTAMP", "时间戳秒数改用 EPOCH(col)");
        return m;
    }

    private final DatasetRepository datasetRepository;
    private final DatasetService datasetService;
    private final DatasetRelationRepository relationRepository;
    private final RelationInferenceService relationInference;
    private final SemanticCubeRepository cubeRepository;
    private final SemanticTermRepository termRepository;
    private final SemanticViewRepository viewRepository;
    private final AgentDraftService agentDraftService;
    private final MdlWorkspaceService workspace;
    private final MdlWorkspaceReader workspaceReader;
    private final ObjectMapper mapper;

    /** Official entry shape: bare scalars, no document start marker (mirrors MdlPublishService). */
    private final ObjectMapper yaml =
            new ObjectMapper(
                    new YAMLFactory()
                            .configure(YAMLGenerator.Feature.MINIMIZE_QUOTES, true)
                            .configure(YAMLGenerator.Feature.WRITE_DOC_START_MARKER, false));

    /** One equality term of a relationship condition: {@code <model>.<column> = <model>.<column>}. */
    private static final Pattern CONDITION_TERM_KEY =
            Pattern.compile("([\\w]+)\\.([\\w]+)\\s*=\\s*([\\w]+)\\.([\\w]+)");

    public MdlSuggestionService(
            DatasetRepository datasetRepository,
            DatasetService datasetService,
            DatasetRelationRepository relationRepository,
            RelationInferenceService relationInference,
            SemanticCubeRepository cubeRepository,
            SemanticTermRepository termRepository,
            SemanticViewRepository viewRepository,
            AgentDraftService agentDraftService,
            MdlWorkspaceService workspace,
            MdlWorkspaceReader workspaceReader,
            ObjectMapper mapper) {
        this.datasetRepository = datasetRepository;
        this.datasetService = datasetService;
        this.relationRepository = relationRepository;
        this.relationInference = relationInference;
        this.cubeRepository = cubeRepository;
        this.termRepository = termRepository;
        this.viewRepository = viewRepository;
        this.agentDraftService = agentDraftService;
        this.workspace = workspace;
        this.workspaceReader = workspaceReader;
        this.mapper = mapper;
    }

    /** A manual relation submission from the modeling UI (specs/013: composite keys supported). */
    public record ManualRelationPayload(
            String sourceDatasetId,
            String sourceColumn,
            String targetDatasetId,
            String targetColumn,
            String joinType,
            List<String> sourceColumns,
            List<String> targetColumns) {

        /** Legacy single-column constructor — existing UI callers keep working unchanged. */
        public ManualRelationPayload(
                String sourceDatasetId,
                String sourceColumn,
                String targetDatasetId,
                String targetColumn,
                String joinType) {
            this(
                    sourceDatasetId,
                    sourceColumn,
                    targetDatasetId,
                    targetColumn,
                    joinType,
                    null,
                    null);
        }

        /** Normalized source column pair (single-column payload collapses to a one-element list). */
        public List<String> sourceColumnList() {
            return normalize(sourceColumns, sourceColumn);
        }

        /** Normalized target column pair (single-column payload collapses to a one-element list). */
        public List<String> targetColumnList() {
            return normalize(targetColumns, targetColumn);
        }

        private static List<String> normalize(List<String> columns, String single) {
            if (columns != null && !columns.isEmpty()) {
                return List.copyOf(columns);
            }
            return single == null || single.isBlank() ? List.of() : List.of(single);
        }
    }

    /** A cube submission from the modeling UI; the JSON bodies are stored verbatim. */
    public record CubePayload(
            String name,
            String baseDatasetId,
            String description,
            List<Map<String, Object>> measures,
            List<Map<String, Object>> dimensions,
            List<Map<String, Object>> timeDimensions) {}

    /** A named-SQL-view submission (specs/011 M2); {@code sqlText} must be one SELECT/WITH. */
    public record ViewPayload(
            String name, String baseDatasetId, String sqlText, String description) {}

    /** An LLM cube proposal (not persisted until the user adopts it). */
    public record CubeSuggestion(
            String datasetId,
            String datasetName,
            String name,
            List<Map<String, Object>> measures,
            List<Map<String, Object>> dimensions,
            List<Map<String, Object>> timeDimensions,
            String reason) {}

    // ------------------------------------------------------------------ relations

    /** Read-only: the group's edges as currently persisted (no recomputation). */
    public List<DatasetRelationEntity> listRelations(String groupId) {
        return relationRepository.findByGroupId(groupId);
    }

    /**
     * Full relation refresh: rule edges (selective rebuild), LLM candidates merged as PENDING
     * llm-origin edges, then joinType probing on every edge still missing one. Returns the
     * group's edges as persisted.
     */
    @Transactional
    public List<DatasetRelationEntity> refreshRelations(String groupId) {
        relationInference.reinferGroup(groupId);
        suggestRelationsLlm(groupId);
        probeJoinTypes(groupId);
        return relationRepository.findByGroupId(groupId);
    }

    /** LLM layer: propose relation candidates; merge as PENDING llm edges, skipping known pairs. */
    private void suggestRelationsLlm(String groupId) {
        List<DatasetEntity> datasets = datasetRepository.findByGroupId(groupId);
        if (datasets.size() < 2 || !agentDraftService.modelAvailable()) {
            return;
        }
        String reply;
        try {
            reply = agentDraftService.chatBlockingModeling(buildRelationPrompt(datasets));
        } catch (RuntimeException e) {
            log.warn(
                    "MdlSuggestionService: LLM relation suggestions unavailable for {}: {}",
                    groupId,
                    e.getMessage());
            return;
        }
        List<LlmRelation> candidates = parseRelationSuggestions(reply, datasets);
        Set<String> existing = new HashSet<>();
        for (DatasetRelationEntity r : relationRepository.findByGroupId(groupId)) {
            existing.add(
                    pairKey(
                            r.getSourceDatasetId(),
                            r.getSourceColumn(),
                            r.getTargetDatasetId(),
                            r.getTargetColumn()));
        }
        List<DatasetRelationEntity> fresh = new ArrayList<>();
        for (LlmRelation c : candidates) {
            if (!existing.add(
                    pairKey(
                            c.sourceDatasetId(),
                            c.sourceColumn(),
                            c.targetDatasetId(),
                            c.targetColumn()))) {
                continue;
            }
            DatasetRelationEntity e =
                    new DatasetRelationEntity(
                            UUID.randomUUID().toString(),
                            groupId,
                            c.sourceDatasetId(),
                            c.sourceColumn(),
                            c.targetDatasetId(),
                            c.targetColumn(),
                            "LLM",
                            c.reason(),
                            0.8,
                            "llm");
            e.setStatus("PENDING");
            fresh.add(e);
        }
        if (!fresh.isEmpty()) {
            relationRepository.saveAll(fresh);
            log.info(
                    "MdlSuggestionService: added {} LLM relation candidate(s) for group {}",
                    fresh.size(),
                    groupId);
        }
    }

    /** Uniqueness probe: fills joinType on edges that do not have one yet. */
    private void probeJoinTypes(String groupId) {
        Map<String, DatasetEntity> byId = new HashMap<>();
        for (DatasetEntity d : datasetRepository.findByGroupId(groupId)) {
            byId.put(d.getId(), d);
        }
        for (DatasetRelationEntity e : relationRepository.findByGroupId(groupId)) {
            if (e.getJoinType() != null
                    || e.getSourceColumn() == null
                    || e.getTargetColumn() == null) {
                continue;
            }
            DatasetEntity src = byId.get(e.getSourceDatasetId());
            DatasetEntity tgt = byId.get(e.getTargetDatasetId());
            if (src == null || tgt == null) {
                continue;
            }
            try {
                String joinType = probeJoinType(src, e.getSourceColumn(), tgt, e.getTargetColumn());
                if (joinType != null) {
                    e.setJoinType(joinType);
                    relationRepository.save(e);
                }
            } catch (RuntimeException ex) {
                // e.g. document-derived edges whose column spelling is not a physical column
                log.warn(
                        "MdlSuggestionService: joinType probe skipped for edge {}: {}",
                        e.getId(),
                        ex.getMessage());
            }
        }
    }

    private String probeJoinType(
            DatasetEntity src, String srcColumn, DatasetEntity tgt, String tgtColumn) {
        long[] s = datasetService.columnProfile(src.getOwnerId(), src.getId(), srcColumn);
        if (s[0] == 0) {
            return null;
        }
        boolean srcUnique = s[1] == s[0];
        long[] t = datasetService.columnProfile(tgt.getOwnerId(), tgt.getId(), tgtColumn);
        if (t[0] == 0) {
            return null;
        }
        boolean tgtUnique = t[1] == t[0];
        if (!srcUnique && tgtUnique) {
            return "MANY_TO_ONE";
        }
        if (srcUnique && !tgtUnique) {
            return "ONE_TO_MANY";
        }
        if (srcUnique && tgtUnique) {
            return "ONE_TO_ONE";
        }
        return null;
    }

    /** Human confirms an edge, optionally adjusting direction (swap) and joinType. */
    @Transactional
    public DatasetRelationEntity confirmRelation(
            String groupId, String relationId, String joinType, boolean swap) {
        return confirmRelation(groupId, relationId, joinType, swap, null, null);
    }

    /**
     * Human confirms an edge and may replace its aligned columns in the final direction.
     *
     * <p>The overload is used by the graphical HITL card. Column replacement is atomic with the
     * direction/cardinality decision, so a rejected or invalid adjustment never leaves a partially
     * modified relation.
     */
    @Transactional
    public DatasetRelationEntity confirmRelation(
            String groupId,
            String relationId,
            String joinType,
            boolean swap,
            List<String> sourceColumns,
            List<String> targetColumns) {
        DatasetRelationEntity e = getRelation(groupId, relationId);
        if (swap) {
            List<String> oldSrc = e.sourceColumnList();
            List<String> oldTgt = e.targetColumnList();
            String si = e.getSourceDatasetId();
            e.setSourceDatasetId(e.getTargetDatasetId());
            e.setTargetDatasetId(si);
            e.setSourceColumnList(oldTgt);
            e.setTargetColumnList(oldSrc);
            e.setJoinType(reverseJoinType(e.getJoinType()));
        }
        if (sourceColumns != null || targetColumns != null) {
            if (sourceColumns == null
                    || targetColumns == null
                    || sourceColumns.isEmpty()
                    || sourceColumns.size() != targetColumns.size()) {
                throw new DatasetException(
                        "Adjusted relation columns must be non-empty paired lists", 400);
            }
            for (String c : sourceColumns) {
                resolveColumnOrThrow(e.getSourceDatasetId(), c);
            }
            for (String c : targetColumns) {
                resolveColumnOrThrow(e.getTargetDatasetId(), c);
            }
            e.setSourceColumnList(sourceColumns);
            e.setTargetColumnList(targetColumns);
        }
        if (joinType != null && !joinType.isBlank()) {
            e.setJoinType(joinType);
        }
        e.setStatus("CONFIRMED");
        persistConfirmedRelation(groupId, e);
        return relationRepository.save(e);
    }

    /** Human rejects an edge; the verdict survives re-inference so it does not come back. */
    @Transactional
    public DatasetRelationEntity rejectRelation(String groupId, String relationId) {
        DatasetRelationEntity e = getRelation(groupId, relationId);
        e.setStatus("REJECTED");
        return relationRepository.save(e);
    }

    /**
     * Adds a hand-written relation; an existing edge on the same column pair is upgraded
     * (specs/013: when both sides hit the same dataset pair, the column lists merge so a
     * composite key grows incrementally instead of colliding).
     */
    @Transactional
    public DatasetRelationEntity addManualRelation(String groupId, ManualRelationPayload p) {
        requireDatasetInGroup(groupId, p.sourceDatasetId());
        requireDatasetInGroup(groupId, p.targetDatasetId());
        if (p.sourceDatasetId().equals(p.targetDatasetId())) {
            throw new DatasetException("A relation needs two different datasets", 400);
        }
        List<String> srcCols = p.sourceColumnList();
        List<String> tgtCols = p.targetColumnList();
        if (srcCols.isEmpty() || tgtCols.isEmpty()) {
            throw new DatasetException("A relation needs at least one column pair", 400);
        }
        if (srcCols.size() != tgtCols.size()) {
            throw new DatasetException(
                    "Composite key columns must pair up on both sides ("
                            + srcCols.size()
                            + " vs "
                            + tgtCols.size()
                            + ")",
                    400);
        }
        for (String c : srcCols) {
            resolveColumnOrThrow(p.sourceDatasetId(), c);
        }
        for (String c : tgtCols) {
            resolveColumnOrThrow(p.targetDatasetId(), c);
        }
        String key =
                pairKey(p.sourceDatasetId(), srcCols.get(0), p.targetDatasetId(), tgtCols.get(0));
        for (DatasetRelationEntity e : relationRepository.findByGroupId(groupId)) {
            if (key.equals(
                    pairKey(
                            e.getSourceDatasetId(),
                            e.getSourceColumn(),
                            e.getTargetDatasetId(),
                            e.getTargetColumn()))) {
                // Same dataset pair already has an edge: merge column lists and upgrade it.
                LinkedHashSet<String> mergedSrc = new LinkedHashSet<>(e.sourceColumnList());
                LinkedHashSet<String> mergedTgt = new LinkedHashSet<>(e.targetColumnList());
                mergedSrc.addAll(srcCols);
                mergedTgt.addAll(tgtCols);
                e.setSourceColumnList(new ArrayList<>(mergedSrc));
                e.setTargetColumnList(new ArrayList<>(mergedTgt));
                e.setOrigin("manual");
                e.setStatus("CONFIRMED");
                e.setConfidence(1.0);
                if (p.joinType() != null && !p.joinType().isBlank()) {
                    e.setJoinType(p.joinType());
                }
                persistConfirmedRelation(groupId, e);
                return relationRepository.save(e);
            }
        }
        DatasetRelationEntity e =
                new DatasetRelationEntity(
                        UUID.randomUUID().toString(),
                        groupId,
                        p.sourceDatasetId(),
                        srcCols.get(0),
                        p.targetDatasetId(),
                        tgtCols.get(0),
                        "MANUAL",
                        null,
                        1.0,
                        "manual");
        e.setSourceColumnList(srcCols);
        e.setTargetColumnList(tgtCols);
        e.setStatus("CONFIRMED");
        if (p.joinType() != null && !p.joinType().isBlank()) {
            e.setJoinType(p.joinType());
        }
        persistConfirmedRelation(groupId, e);
        return relationRepository.save(e);
    }

    // ------------------------------------------------------- relationships.yml write side

    /**
     * specs/019 §7: a confirmed relation is persisted straight into the workspace's {@code
     * relationships.yml} — the official project file is what the wren engine joins on, while the
     * DB row stays review bookkeeping. Runs before the entity save so a workspace failure aborts
     * the whole confirmation; a re-confirm is an idempotent upsert on the file.
     */
    private void persistConfirmedRelation(String groupId, DatasetRelationEntity edge) {
        workspace.withWorkspaceLock(
                groupId,
                () -> {
                    workspace.ensureWorkspace(groupId);
                    String srcModel = resolveModelName(groupId, edge.getSourceDatasetId());
                    String tgtModel = resolveModelName(groupId, edge.getTargetDatasetId());
                    String condition =
                            conditionText(
                                    srcModel,
                                    edge.sourceColumnList(),
                                    tgtModel,
                                    edge.targetColumnList());
                    upsertRelationship(groupId, srcModel, tgtModel, edge.getJoinType(), condition);
                    return null;
                });
    }

    /**
     * The engine-facing model name a dataset is published under: {@link MdlWorkspaceReader}
     * resolves it from the seeded mapping and the metadata.yml {@code name}. Errors when the
     * dataset has no seeded model yet — a relationship must point at an existing model.
     */
    private String resolveModelName(String groupId, String datasetId) {
        requireDatasetInGroup(groupId, datasetId);
        for (MdlWorkspaceReader.WorkspaceModel m : workspaceReader.read(groupId).models()) {
            if (datasetId.equals(m.datasetId())) {
                return m.name();
            }
        }
        throw new DatasetException("数据集尚未播种模型文件（工作区 models/ 中没有对应映射），请先在「语义建模」页完成一次发布后再确认关系", 400);
    }

    private static String conditionText(
            String srcModel, List<String> srcCols, String tgtModel, List<String> tgtCols) {
        List<String> terms = new ArrayList<>();
        for (int i = 0; i < srcCols.size(); i++) {
            terms.add(srcModel + "." + srcCols.get(i) + " = " + tgtModel + "." + tgtCols.get(i));
        }
        return String.join(" AND ", terms);
    }

    /** Renders one official relationships.yml entry ({@code name/models/join_type/condition}). */
    private String renderRelationshipEntry(
            String name, String srcModel, String tgtModel, String joinType, String condition) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("name", name);
        entry.put("models", List.of(srcModel, tgtModel));
        if (joinType != null && !joinType.isBlank()) {
            entry.put("join_type", joinType);
        }
        entry.put("condition", condition);
        String block;
        try {
            block = yaml.writeValueAsString(entry);
        } catch (IOException e) {
            throw new DatasetException("渲染关系条目失败：" + e.getMessage(), e);
        }
        List<String> lines = new ArrayList<>();
        String[] raw = block.split("\n");
        for (int i = 0; i < raw.length; i++) {
            if (raw[i].isBlank()) {
                continue;
            }
            lines.add((i == 0 ? "  - " : "    ") + raw[i]);
        }
        return String.join("\n", lines) + "\n";
    }

    private void upsertRelationship(
            String groupId, String srcModel, String tgtModel, String joinType, String condition) {
        Path file = workspace.workspaceRoot(groupId).resolve("relationships.yml");
        String existing = null;
        if (Files.isRegularFile(file)) {
            try {
                existing = Files.readString(file, StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new DatasetException("读取 relationships.yml 失败：" + e.getMessage(), e);
            }
        }
        JsonNode entries = existingRelationships(existing);
        String base =
                MdlPublishService.sanitizeIdentifier(srcModel, 60)
                        + "_"
                        + MdlPublishService.sanitizeIdentifier(tgtModel, 60);
        String rendered =
                renderRelationshipEntry(
                        uniqueEntryName(entries, base), srcModel, tgtModel, joinType, condition);
        String updated =
                mergeRelationships(existing, entries, rendered, srcModel, tgtModel, condition);
        try {
            Files.writeString(file, updated, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new DatasetException("写入 relationships.yml 失败：" + e.getMessage(), e);
        }
    }

    /** The relationships array of the raw file, or null when absent/empty/irregular. */
    private JsonNode existingRelationships(String existing) {
        if (existing == null || existing.isBlank()) {
            return null;
        }
        try {
            JsonNode root = yaml.readTree(existing);
            if (root == null || !root.isObject()) {
                return null;
            }
            JsonNode arr = root.get("relationships");
            return arr != null && arr.isArray() ? arr : null;
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * Merge-on-confirm text surgery over relationships.yml: replaces the entry covering the same
     * model pair and overlapping condition terms (direction-insensitive, so a swapped
     * re-confirm rewrites the
     * existing block instead of duplicating it) and appends otherwise. A missing or irregular
     * file is rewritten wholesale with the minimal official shape. Agent-written entries whose key
     * does not match are left byte-for-byte untouched.
     */
    private String mergeRelationships(
            String existing,
            JsonNode entries,
            String rendered,
            String srcModel,
            String tgtModel,
            String condition) {
        if (existing == null || existing.isBlank() || !(entries instanceof JsonNode)) {
            return "relationships:\n" + rendered;
        }
        Set<String> wantTerms = conditionTerms(condition);
        int hit = -1;
        for (int i = 0; i < entries.size(); i++) {
            if (coversSameRelation(entries.get(i), srcModel, tgtModel, wantTerms)) {
                hit = i;
                break;
            }
        }
        if (hit >= 0) {
            return replaceEntryBlock(existing, hit, rendered);
        }
        return existing.stripTrailing() + "\n" + rendered;
    }

    /**
     * Whether the entry covers the same (unordered) model pair with a compatible condition: the
     * equality-term sets must nest one way or the other, so a composite key growing from one
     * column pair to two still replaces the original entry instead of duplicating it. Safe because
     * the DB side keeps at most one edge per unordered dataset pair.
     */
    private static boolean coversSameRelation(
            JsonNode rel, String srcModel, String tgtModel, Set<String> wantTerms) {
        JsonNode models = rel.get("models");
        if (models == null || !models.isArray() || models.size() != 2) {
            return false;
        }
        if (!Set.of(models.get(0).asText(""), models.get(1).asText(""))
                .equals(Set.of(srcModel, tgtModel))) {
            return false;
        }
        JsonNode cond = rel.get("condition");
        if (cond == null) {
            return wantTerms.isEmpty();
        }
        Set<String> have = conditionTerms(cond.asText(""));
        return have.containsAll(wantTerms) || wantTerms.containsAll(have);
    }

    /** Direction-insensitive canonical keys of a join condition's equality terms. */
    private static Set<String> conditionTerms(String condition) {
        Set<String> out = new HashSet<>();
        for (String term : condition.split("(?i)\\s+AND\\s+")) {
            Matcher m = CONDITION_TERM_KEY.matcher(term.strip());
            if (!m.matches()) {
                out.add(term.strip().toLowerCase(Locale.ROOT));
                continue;
            }
            String a = m.group(1) + "." + m.group(2);
            String b = m.group(3) + "." + m.group(4);
            out.add(a.compareTo(b) <= 0 ? a + "|" + b : b + "|" + a);
        }
        return out;
    }

    /**
     * Replaces the {@code index}-th list entry's text block in the raw file: entry starts are the
     * {@code <indent>- } lines in file order and a block runs to the next start or EOF. The first
     * {@code - } line fixes the top-level indent; deeper dashes are nested list items, not
     * entries.
     */
    private static String replaceEntryBlock(String existing, int index, String rendered) {
        Pattern entryStart = Pattern.compile("^(\\s*)-\\s");
        String[] lines = existing.stripTrailing().split("\n", -1);
        int topIndent = -1;
        for (String line : lines) {
            Matcher m = entryStart.matcher(line);
            if (m.find()) {
                topIndent = m.group(1).length();
                break;
            }
        }
        int seen = -1;
        int begin = -1;
        int end = lines.length;
        for (int i = 0; i < lines.length; i++) {
            Matcher m = entryStart.matcher(lines[i]);
            if (m.find() && m.group(1).length() == topIndent) {
                seen++;
                if (seen == index) {
                    begin = i;
                } else if (seen == index + 1) {
                    end = i;
                    break;
                }
            }
        }
        if (begin < 0) {
            return existing.stripTrailing() + "\n" + rendered;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < begin; i++) {
            sb.append(lines[i]).append('\n');
        }
        sb.append(rendered);
        for (int i = end; i < lines.length; i++) {
            sb.append(lines[i]).append('\n');
        }
        return sb.toString();
    }

    /** A stable collision-free entry name derived from both model names. */
    private static String uniqueEntryName(JsonNode entries, String base) {
        Set<String> used = new HashSet<>();
        if (entries != null && entries.isArray()) {
            for (JsonNode e : entries) {
                JsonNode n = e.get("name");
                if (n != null && n.isTextual() && !n.asText().isBlank()) {
                    used.add(n.asText());
                }
            }
        }
        if (!used.contains(base)) {
            return base;
        }
        for (int i = 2; i < 100; i++) {
            if (!used.contains(base + "_" + i)) {
                return base + "_" + i;
            }
        }
        return base + "_" + UUID.randomUUID().toString().substring(0, 8);
    }

    private DatasetRelationEntity getRelation(String groupId, String relationId) {
        return relationRepository
                .findById(relationId)
                .filter(r -> groupId.equals(r.getGroupId()))
                .orElseThrow(() -> new DatasetException("Relation not found: " + relationId, 404));
    }

    private static String reverseJoinType(String joinType) {
        if ("MANY_TO_ONE".equals(joinType)) {
            return "ONE_TO_MANY";
        }
        if ("ONE_TO_MANY".equals(joinType)) {
            return "MANY_TO_ONE";
        }
        return joinType;
    }

    // ------------------------------------------------------------------ cubes

    /**
     * LLM cube proposals following the aggregation-shape decision tree: numeric columns become
     * measures, category columns dimensions, date columns time dimensions; row-detail-only tables
     * are not proposed. Nothing is persisted until the user adopts a suggestion.
     */
    public List<CubeSuggestion> suggestCubes(String groupId) {
        List<DatasetEntity> datasets = datasetRepository.findByGroupId(groupId);
        if (datasets.isEmpty() || !agentDraftService.modelAvailable()) {
            return List.of();
        }
        String reply;
        try {
            reply = agentDraftService.chatBlockingModeling(buildCubePrompt(datasets));
        } catch (RuntimeException e) {
            log.warn(
                    "MdlSuggestionService: LLM cube suggestions unavailable for {}: {}",
                    groupId,
                    e.getMessage());
            return List.of();
        }
        return parseCubeSuggestions(reply, datasets);
    }

    public List<SemanticCubeEntity> listCubes(String groupId) {
        return cubeRepository.findByGroupId(groupId);
    }

    @Transactional
    public SemanticCubeEntity createCube(String groupId, CubePayload p) {
        String name = requireCubeName(p.name());
        requireDatasetInGroup(groupId, p.baseDatasetId());
        if (cubeRepository.findByGroupIdAndName(groupId, name) != null) {
            throw new DatasetException("Cube name already exists in this KB: " + name, 409);
        }
        SemanticCubeEntity e =
                new SemanticCubeEntity(
                        UUID.randomUUID().toString(),
                        groupId,
                        name,
                        p.baseDatasetId(),
                        p.description());
        e.setMeasuresJson(writeJson(p.measures()));
        e.setDimensionsJson(writeJson(p.dimensions()));
        e.setTimeDimensionsJson(writeJson(p.timeDimensions()));
        return cubeRepository.save(e);
    }

    @Transactional
    public SemanticCubeEntity updateCube(String groupId, String cubeId, CubePayload p) {
        SemanticCubeEntity e = getCube(groupId, cubeId);
        if (p.name() != null && !p.name().isBlank()) {
            String name = requireCubeName(p.name());
            if (!name.equals(e.getName())) {
                SemanticCubeEntity clash = cubeRepository.findByGroupIdAndName(groupId, name);
                if (clash != null && !clash.getId().equals(cubeId)) {
                    throw new DatasetException("Cube name already exists in this KB: " + name, 409);
                }
                e.setName(name);
            }
        }
        if (p.baseDatasetId() != null && !p.baseDatasetId().isBlank()) {
            requireDatasetInGroup(groupId, p.baseDatasetId());
            e.setBaseDatasetId(p.baseDatasetId());
        }
        if (p.description() != null) {
            e.setDescription(p.description());
        }
        if (p.measures() != null) {
            e.setMeasuresJson(writeJson(p.measures()));
        }
        if (p.dimensions() != null) {
            e.setDimensionsJson(writeJson(p.dimensions()));
        }
        if (p.timeDimensions() != null) {
            e.setTimeDimensionsJson(writeJson(p.timeDimensions()));
        }
        e.setUpdatedAt(java.time.Instant.now());
        // An edit makes the published cube stale: back to DRAFT until the KB is republished.
        e.setStatus("DRAFT");
        return cubeRepository.save(e);
    }

    @Transactional
    public void deleteCube(String groupId, String cubeId) {
        cubeRepository.delete(getCube(groupId, cubeId));
    }

    private SemanticCubeEntity getCube(String groupId, String cubeId) {
        return cubeRepository
                .findById(cubeId)
                .filter(c -> groupId.equals(c.getGroupId()))
                .orElseThrow(() -> new DatasetException("Cube not found: " + cubeId, 404));
    }

    private static String requireCubeName(String name) {
        String n = name == null ? "" : name.trim();
        if (n.isEmpty() || n.length() > 100) {
            throw new DatasetException("Cube name must be 1-100 chars", 400);
        }
        return n;
    }

    private void requireDatasetInGroup(String groupId, String datasetId) {
        if (datasetId == null || datasetId.isBlank()) {
            throw new DatasetException("Dataset id is required", 400);
        }
        datasetRepository
                .findById(datasetId)
                .filter(d -> groupId.equals(d.getGroupId()))
                .orElseThrow(
                        () ->
                                new DatasetException(
                                        "Dataset not found in this KB: " + datasetId, 404));
    }

    private void resolveColumnOrThrow(String datasetId, String column) {
        DatasetEntity d = datasetRepository.findById(datasetId).orElse(null);
        if (d == null || resolveColumn(d, column) == null) {
            throw new DatasetException(
                    "Unknown column " + column + " on dataset " + datasetId, 400);
        }
    }

    private String writeJson(List<Map<String, Object>> value) {
        try {
            return mapper.writeValueAsString(value == null ? List.of() : value);
        } catch (Exception e) {
            throw new DatasetException("Failed to serialize cube JSON: " + e.getMessage(), e);
        }
    }

    // ------------------------------------------------------------------ semantic terms

    /**
     * Group-scoped term dictionary (specs/026): terms bind to one knowledge base. A blank group
     * renders nothing rather than leaking other tenants' entries.
     */
    public List<SemanticTermEntity> listTerms(String groupId) {
        return groupId == null || groupId.isBlank()
                ? List.of()
                : termRepository.findByGroupIdOrderByCreatedAtDesc(groupId);
    }

    /**
     * Creates a business term bound to {@code groupId} (specs/026). Duplicates are rejected with
     * 409 so a conversational create can never silently overwrite a curated entry; the same noun
     * may exist in different knowledge bases with different definitions.
     */
    @Transactional
    public SemanticTermEntity createTerm(
            String groupId, String term, String explanation, String synonyms) {
        if (groupId == null || groupId.isBlank()) {
            throw new DatasetException("Term requires a knowledge base (group_id)", 400);
        }
        String t = requireBounded(term, 30, "Term");
        String exp = requireBounded(explanation, 100, "Explanation");
        if (termRepository.findByGroupIdAndTerm(groupId, t).isPresent()) {
            throw new DatasetException("Term already exists: " + t, 409);
        }
        return termRepository.save(
                new SemanticTermEntity(
                        UUID.randomUUID().toString(), groupId, t, exp, blankToNull(synonyms)));
    }

    @Transactional
    public void deleteTerm(String groupId, String termId) {
        termRepository.delete(getTerm(groupId, termId));
    }

    private SemanticTermEntity getTerm(String groupId, String termId) {
        return termRepository
                .findById(termId)
                .filter(e -> groupId != null && groupId.equals(e.getGroupId()))
                .orElseThrow(() -> new DatasetException("Term not found: " + termId, 404));
    }

    private static String requireBounded(String value, int max, String label) {
        String v = value == null ? "" : value.trim();
        if (v.isEmpty() || v.length() > max) {
            throw new DatasetException(label + " must be 1-" + max + " chars", 400);
        }
        return v;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    // ------------------------------------------------------------------ semantic views

    /** Read-only: the group's named SQL views as persisted (specs/011 M2). */
    public List<SemanticViewEntity> listViews(String groupId) {
        return viewRepository.findByGroupId(groupId);
    }

    /**
     * Creates a named SQL view. The statement must be a single SELECT/WITH (defense in depth —
     * the wren engine expands it into every generated query, so anything else must never land);
     * duplicates are rejected with 409 like cubes.
     */
    @Transactional
    public SemanticViewEntity createView(String groupId, ViewPayload p) {
        String name = requireViewName(p.name());
        String sql = requireViewSql(p.sqlText());
        if (p.baseDatasetId() != null && !p.baseDatasetId().isBlank()) {
            requireDatasetInGroup(groupId, p.baseDatasetId());
        }
        if (viewRepository.findByGroupIdAndName(groupId, name) != null) {
            throw new DatasetException("View name already exists in this KB: " + name, 409);
        }
        return viewRepository.save(
                new SemanticViewEntity(
                        UUID.randomUUID().toString(),
                        groupId,
                        name,
                        blankToNull(p.baseDatasetId()),
                        sql,
                        blankToNull(p.description())));
    }

    @Transactional
    public SemanticViewEntity updateView(String groupId, String viewId, ViewPayload p) {
        SemanticViewEntity e = getView(groupId, viewId);
        if (p.name() != null && !p.name().isBlank()) {
            String name = requireViewName(p.name());
            if (!name.equals(e.getName())) {
                SemanticViewEntity clash = viewRepository.findByGroupIdAndName(groupId, name);
                if (clash != null && !clash.getId().equals(viewId)) {
                    throw new DatasetException("View name already exists in this KB: " + name, 409);
                }
                e.setName(name);
            }
        }
        if (p.baseDatasetId() != null) {
            e.setBaseDatasetId(blankToNull(p.baseDatasetId()));
            if (e.getBaseDatasetId() != null) {
                requireDatasetInGroup(groupId, e.getBaseDatasetId());
            }
        }
        if (p.sqlText() != null && !p.sqlText().isBlank()) {
            e.setSqlText(requireViewSql(p.sqlText()));
        }
        if (p.description() != null) {
            e.setDescription(blankToNull(p.description()));
        }
        e.setUpdatedAt(java.time.Instant.now());
        // Same staleness rule as cubes: an edited view must be republished to take effect.
        e.setStatus("DRAFT");
        return viewRepository.save(e);
    }

    @Transactional
    public void deleteView(String groupId, String viewId) {
        viewRepository.delete(getView(groupId, viewId));
    }

    private SemanticViewEntity getView(String groupId, String viewId) {
        return viewRepository
                .findById(viewId)
                .filter(v -> groupId.equals(v.getGroupId()))
                .orElseThrow(() -> new DatasetException("View not found: " + viewId, 404));
    }

    private static String requireViewName(String name) {
        String n = name == null ? "" : name.trim();
        if (n.isEmpty() || n.length() > 100) {
            throw new DatasetException("View name must be 1-100 chars", 400);
        }
        return n;
    }

    /**
     * Accepts exactly one SELECT/WITH statement and returns it normalised: SQL line and block
     * comments blanked, at most one trailing semicolon kept off, no inner semicolons —
     * INSERT/UPDATE/DELETE/DDL never pass. Also rejects MySQL/Doris-only functions the wren view
     * engine (DuckDB planner) cannot bind (specs/018): dialect errors otherwise only surface at
     * query time, after publishing.
     *
     * <p>Shared entry point: the modeling toolkit's {@code create_view} tool calls this
     * directly (specs/035) so the tool surface and the REST surface enforce the identical gate.
     * Every rejection throws {@link DatasetException} with status 400.
     */
    public static String requireViewSql(String sql) {
        String s = sql == null ? "" : stripComments(sql).trim();
        if (s.isEmpty()) {
            throw new DatasetException("View SQL is required", 400);
        }
        if (s.length() > 4000) {
            throw new DatasetException("View SQL must be 1-4000 chars", 400);
        }
        String upper = s.toUpperCase();
        if (!upper.startsWith("SELECT") && !upper.startsWith("WITH")) {
            throw new DatasetException("View SQL must start with SELECT or WITH", 400);
        }
        String noTrailing = s.endsWith(";") ? s.substring(0, s.length() - 1).trim() : s;
        if (noTrailing.contains(";")) {
            throw new DatasetException("View SQL must be a single statement", 400);
        }
        String dialectError = viewDialectError(noTrailing);
        if (dialectError != null) {
            throw new DatasetException(dialectError, 400);
        }
        return noTrailing;
    }

    /** Blanks SQL line and block comments on a copy of the statement. */
    private static String stripComments(String sql) {
        return sql.replaceAll("(?m)--.*$", " ").replaceAll("(?s)/\\*.*?\\*/", " ");
    }

    /**
     * Collects every blacklisted MySQL/Doris function call in the statement; {@code null} means
     * clean. Word boundary plus call parenthesis matching keeps plain column names such as
     * {@code year} or {@code my_date_sub} safe from false positives.
     */
    private static String viewDialectError(String sql) {
        List<String> hits = new ArrayList<>();
        for (Map.Entry<String, String> fn : WREN_UNSUPPORTED_VIEW_FUNCTIONS.entrySet()) {
            if (Pattern.compile("(?i)\\b" + Pattern.quote(fn.getKey()) + "\\s*\\(")
                    .matcher(sql)
                    .find()) {
                hits.add("「" + fn.getKey() + "」" + fn.getValue());
            }
        }
        if (hits.isEmpty()) {
            return null;
        }
        return "视图 SQL 使用了 wren 视图引擎（DuckDB）不支持的 MySQL/Doris 函数，请改写后重试：" + String.join("；", hits);
    }

    // ------------------------------------------------------------------ LLM parsing

    private record LlmRelation(
            String sourceDatasetId,
            String sourceColumn,
            String targetDatasetId,
            String targetColumn,
            String reason) {}

    private String buildRelationPrompt(List<DatasetEntity> datasets) {
        StringBuilder sb = new StringBuilder();
        sb.append("你是数据建模助手。以下是同一知识库中的 ")
                .append(datasets.size())
                .append(" 张表及其字段（物理列名、原始表头、类型、业务描述）。\n")
                .append("请判断表与表之间真实存在的关联关系（外键关系，或业务上可用于 JOIN 的列对应）。")
                .append("只输出有把握的候选，没有把握就不要输出。\n\n");
        for (DatasetEntity d : datasets) {
            sb.append("## 表：").append(d.getName()).append('\n');
            appendColumns(sb, d);
            sb.append('\n');
        }
        sb.append("仅输出一个 JSON 对象，不要输出其他文字。格式：\n")
                .append(
                        "{\"relations\":[{\"source_table\":\"表名\",\"source_column\":\"物理列名\","
                                + "\"target_table\":\"表名\",\"target_column\":\"物理列名\","
                                + "\"reason\":\"中文依据\"}]}\n")
                .append("source_column/target_column 必须使用上面列出的物理列名（行首的名称）。");
        return sb.toString();
    }

    private String buildCubePrompt(List<DatasetEntity> datasets) {
        StringBuilder sb = new StringBuilder();
        sb.append("你是数据建模助手。以下是同一知识库中的表与字段。请提议适合做聚合分析的「语义 cube」（指标视图）：\n")
                .append("- 数值列（金额/数量/次数等）作为指标（measures），聚合函数选 SUM/AVG/COUNT/MAX/MIN，结合列描述判断\n")
                .append("- 类别/文本列（省份/状态/类型等）作为维度（dimensions）\n")
                .append("- 日期/时间列作为时间维度（time_dimensions，granularity 如 DAY/MONTH/YEAR）\n")
                .append("- 明显只做行级明细查询的表不要提议 cube\n")
                .append("- 同一表达式的指标不要重复提议\n")
                .append("- 每张合适的表最多一个 cube，列必须来自上面列出的物理列名\n\n");
        for (DatasetEntity d : datasets) {
            sb.append("## 表：").append(d.getName()).append('\n');
            appendColumns(sb, d);
            sb.append('\n');
        }
        sb.append("仅输出一个 JSON 对象，不要输出其他文字。格式：\n")
                .append(
                        "{\"cubes\":[{\"dataset\":\"表名\",\"name\":\"cube 名\","
                            + "\"measures\":[{\"name\":\"指标名\",\"column\":\"物理列名\",\"agg\":\"SUM\","
                            + "\"description\":\"口径\"}],"
                            + "\"dimensions\":[{\"name\":\"维度名\",\"column\":\"物理列名\"}],"
                            + "\"time_dimensions\":[{\"name\":\"时间维度名\",\"column\":\"物理列名\","
                            + "\"granularity\":\"MONTH\"}],\"reason\":\"中文依据\"}]}");
        return sb.toString();
    }

    private void appendColumns(StringBuilder sb, DatasetEntity d) {
        String schema = d.getColumnSchemaJson();
        if (schema == null || schema.isBlank()) {
            return;
        }
        try {
            List<ColumnSchema> cols =
                    mapper.readValue(schema, new TypeReference<List<ColumnSchema>>() {});
            for (ColumnSchema c : cols) {
                sb.append("- ").append(c.name());
                if (c.originalName() != null && !c.originalName().equals(c.name())) {
                    sb.append("（原表头：").append(c.originalName()).append("）");
                }
                if (c.sqlType() != null && !c.sqlType().isBlank()) {
                    sb.append("（类型：").append(c.sqlType()).append("）");
                }
                if (c.description() != null && !c.description().isBlank()) {
                    sb.append("：").append(c.description());
                }
                sb.append('\n');
            }
        } catch (Exception e) {
            log.warn("MdlSuggestionService: unreadable column schema on dataset {}", d.getId());
        }
    }

    private List<LlmRelation> parseRelationSuggestions(String reply, List<DatasetEntity> datasets) {
        Map<String, DatasetEntity> byName = nameIndex(datasets);
        List<LlmRelation> out = new ArrayList<>();
        try {
            JsonNode root = mapper.readTree(agentDraftService.extractJsonObject(reply));
            JsonNode arr = root.path("relations");
            if (!arr.isArray()) {
                return out;
            }
            for (JsonNode n : arr) {
                DatasetEntity a = byName.get(lower(n.path("source_table").asText("")));
                DatasetEntity b = byName.get(lower(n.path("target_table").asText("")));
                if (a == null || b == null || a.getId().equals(b.getId())) {
                    continue;
                }
                String ac = resolveColumn(a, n.path("source_column").asText(""));
                String bc = resolveColumn(b, n.path("target_column").asText(""));
                if (ac == null || bc == null) {
                    continue;
                }
                out.add(new LlmRelation(a.getId(), ac, b.getId(), bc, n.path("reason").asText("")));
            }
        } catch (Exception e) {
            log.warn(
                    "MdlSuggestionService: could not parse LLM relation output: {}",
                    e.getMessage());
        }
        return out;
    }

    private List<CubeSuggestion> parseCubeSuggestions(String reply, List<DatasetEntity> datasets) {
        Map<String, DatasetEntity> byName = nameIndex(datasets);
        List<CubeSuggestion> out = new ArrayList<>();
        try {
            JsonNode root = mapper.readTree(agentDraftService.extractJsonObject(reply));
            JsonNode arr = root.path("cubes");
            if (!arr.isArray()) {
                return out;
            }
            for (JsonNode n : arr) {
                DatasetEntity d = byName.get(lower(n.path("dataset").asText("")));
                if (d == null) {
                    continue;
                }
                String name = n.path("name").asText("").trim();
                if (name.isEmpty()) {
                    continue;
                }
                List<Map<String, Object>> measures = sanitizeItems(n.path("measures"), d);
                List<Map<String, Object>> dims = sanitizeItems(n.path("dimensions"), d);
                List<Map<String, Object>> times = sanitizeItems(n.path("time_dimensions"), d);
                if (measures.isEmpty() && dims.isEmpty() && times.isEmpty()) {
                    continue;
                }
                out.add(
                        new CubeSuggestion(
                                d.getId(),
                                d.getName(),
                                name,
                                measures,
                                dims,
                                times,
                                n.path("reason").asText("")));
            }
        } catch (Exception e) {
            log.warn("MdlSuggestionService: could not parse LLM cube output: {}", e.getMessage());
        }
        return out;
    }

    /** Keeps only items whose {@code column} resolves to a physical column of the dataset. */
    private List<Map<String, Object>> sanitizeItems(JsonNode arr, DatasetEntity d) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (arr == null || !arr.isArray()) {
            return out;
        }
        for (JsonNode item : arr) {
            String col = resolveColumn(d, item.path("column").asText(""));
            if (col == null) {
                continue;
            }
            Map<String, Object> m =
                    mapper.convertValue(item, new TypeReference<Map<String, Object>>() {});
            m.put("column", col);
            out.add(new LinkedHashMap<>(m));
        }
        return out;
    }

    private String resolveColumn(DatasetEntity d, String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String want = lower(raw);
        List<ColumnSchema> cols = datasetService.readColumns(d);
        for (ColumnSchema c : cols) {
            if (c.name().equals(want)) {
                return c.name();
            }
        }
        for (ColumnSchema c : cols) {
            if (c.originalName() != null && lower(c.originalName()).equals(want)) {
                return c.name();
            }
        }
        return null;
    }

    private static Map<String, DatasetEntity> nameIndex(List<DatasetEntity> datasets) {
        Map<String, DatasetEntity> byName = new HashMap<>();
        for (DatasetEntity d : datasets) {
            byName.put(lower(d.getName()), d);
            byName.put(lower(d.getTableName()), d);
            byName.put(lower(d.getId()), d);
        }
        return byName;
    }

    private static String lower(String s) {
        return s == null ? "" : s.trim().toLowerCase();
    }

    /** Order-independent key of a column pair, so the same edge is never stored twice. */
    private static String pairKey(String aId, String aCol, String bId, String bCol) {
        String a = aId + "|" + aCol;
        String b = bId + "|" + bCol;
        return a.compareTo(b) <= 0 ? a + "~" + b : b + "~" + a;
    }
}
