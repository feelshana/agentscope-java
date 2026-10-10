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
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.agentscope.dataagent.dataset.parser.ColumnSchema;
import io.agentscope.dataagent.web.ai.AgentDraftService;
import io.agentscope.dataagent.web.persistence.jpa.DatasetEntity;
import io.agentscope.dataagent.web.persistence.jpa.DatasetRepository;
import io.agentscope.dataagent.web.persistence.jpa.DocEnhanceProposalEntity;
import io.agentscope.dataagent.web.persistence.jpa.DocEnhanceProposalRepository;
import io.agentscope.dataagent.web.persistence.jpa.DocEnhanceTaskEntity;
import io.agentscope.dataagent.web.persistence.jpa.DocEnhanceTaskRepository;
import io.agentscope.dataagent.web.persistence.jpa.SemanticBusinessRuleEntity;
import io.agentscope.dataagent.web.persistence.jpa.SemanticBusinessRuleRepository;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import reactor.core.scheduler.Schedulers;

/**
 * Turns an uploaded knowledge document into reviewable semantic differences. Model output is never
 * applied directly: every high-impact change is persisted as a proposal and must pass the existing
 * modeling write path and MDL validation when adopted.
 */
@Service
public class DocEnhanceService {

    private static final Logger log = LoggerFactory.getLogger(DocEnhanceService.class);
    private static final Set<String> TYPES =
            Set.of("TERM", "BUSINESS_RULE", "RELATIONSHIP", "VIEW", "MANUAL_FIX");
    private static final Set<String> CLASSIFICATIONS = Set.of("NEW", "PARTIAL", "CONFLICT");
    private static final Set<String> CONFIDENCES = Set.of("HIGH", "MEDIUM", "LOW");

    private final DocEnhanceTaskRepository taskRepository;
    private final DocEnhanceProposalRepository proposalRepository;
    private final SemanticBusinessRuleRepository ruleRepository;
    private final DatasetRepository datasetRepository;
    private final DatasetService datasetService;
    private final DatasetGroupService groupService;
    private final MdlSuggestionService modelingService;
    private final MdlWorkspaceService workspace;
    private final MdlPublishService mdlPublishService;
    private final AgentDraftService agentDraftService;
    private final ObjectMapper mapper;
    private final TransactionTemplate transactionTemplate;

    @org.springframework.beans.factory.annotation.Autowired
    private KnowledgeBaseOperations operations;

    public DocEnhanceService(
            DocEnhanceTaskRepository taskRepository,
            DocEnhanceProposalRepository proposalRepository,
            SemanticBusinessRuleRepository ruleRepository,
            DatasetRepository datasetRepository,
            DatasetService datasetService,
            DatasetGroupService groupService,
            MdlSuggestionService modelingService,
            MdlWorkspaceService workspace,
            MdlPublishService mdlPublishService,
            AgentDraftService agentDraftService,
            ObjectMapper mapper,
            PlatformTransactionManager transactionManager) {
        this.taskRepository = taskRepository;
        this.proposalRepository = proposalRepository;
        this.ruleRepository = ruleRepository;
        this.datasetRepository = datasetRepository;
        this.datasetService = datasetService;
        this.groupService = groupService;
        this.modelingService = modelingService;
        this.workspace = workspace;
        this.mdlPublishService = mdlPublishService;
        this.agentDraftService = agentDraftService;
        this.mapper = mapper;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    /** Starts a manual analysis against the group's currently uploaded knowledge document. */
    public DocEnhanceTaskEntity triggerAnalyze(String ownerId, String groupId) {
        groupService.getGroup(ownerId, groupId);
        return start(ownerId, groupId, datasetService.knowledgeText(groupId), "MANUAL", "手动重新分析");
    }

    /** Starts upload-triggered analysis; callers deliberately ignore failures to protect upload. */
    public DocEnhanceTaskEntity triggerAnalyzeQuietly(
            String ownerId, String groupId, String sourceText, String sourceLabel) {
        try {
            return start(ownerId, groupId, sourceText, "UPLOAD", sourceLabel);
        } catch (RuntimeException e) {
            log.info(
                    "Document semantic analysis skipped for group {}: {}", groupId, e.getMessage());
            return null;
        }
    }

    private DocEnhanceTaskEntity start(
            String ownerId,
            String groupId,
            String sourceText,
            String sourceType,
            String sourceLabel) {
        groupService.getGroup(ownerId, groupId);
        if (taskRepository.existsByOwnerIdAndGroupIdAndStatus(
                ownerId, groupId, DocEnhanceTaskEntity.STATUS_RUNNING)) {
            throw new DatasetException("文档语义分析正在运行，请稍后再试", 409);
        }
        DocEnhanceTaskEntity task =
                taskRepository.save(
                        new DocEnhanceTaskEntity(
                                UUID.randomUUID().toString(),
                                ownerId,
                                groupId,
                                sourceType,
                                trim(sourceLabel, 255)));
        Schedulers.boundedElastic().schedule(() -> analyzeTask(task.getId(), sourceText));
        return task;
    }

    /** Latest task visible to this tenant, or empty when the group has never been analyzed. */
    public Optional<DocEnhanceTaskEntity> latestTask(String ownerId, String groupId) {
        groupService.getGroup(ownerId, groupId);
        return taskRepository.findFirstByOwnerIdAndGroupIdOrderByCreatedAtDesc(ownerId, groupId);
    }

    /** Proposals belonging to the latest tenant-visible task, including decided audit rows. */
    public List<DocEnhanceProposalEntity> latestProposals(String ownerId, String groupId) {
        return latestTask(ownerId, groupId)
                .map(task -> proposalRepository.findByTaskIdOrderByCreatedAtAsc(task.getId()))
                .orElseGet(List::of);
    }

    /** Synchronous worker entry kept package-visible for deterministic service tests. */
    void analyzeTask(String taskId, String sourceText) {
        DocEnhanceTaskEntity task = taskRepository.findById(taskId).orElse(null);
        if (task == null) return;
        if (operations == null) {
            analyzeTaskActive(taskId, sourceText);
        } else {
            operations.run(
                    task.getGroupId(),
                    () -> {
                        analyzeTaskActive(taskId, sourceText);
                        return null;
                    });
        }
    }

    private void analyzeTaskActive(String taskId, String sourceText) {
        DocEnhanceTaskEntity task = taskRepository.findById(taskId).orElse(null);
        if (task == null) {
            return;
        }
        try {
            if (sourceText == null || sourceText.isBlank()) {
                throw new DatasetException("知识文档为空，无法执行语义增强", 400);
            }
            if (!agentDraftService.modelAvailable()) {
                throw new DatasetException("未配置可用模型，无法执行语义增强", 503);
            }
            List<DatasetEntity> datasets = datasetRepository.findByGroupId(task.getGroupId());
            String prompt = buildPrompt(task, sourceText, datasets);
            String reply = agentDraftService.chatBlockingModeling(prompt);
            List<DocEnhanceProposalEntity> proposals = parseProposals(task, reply, datasets);
            transactionTemplate.executeWithoutResult(
                    ignored -> {
                        if (!proposals.isEmpty()) {
                            proposalRepository.saveAll(proposals);
                        }
                        task.setStatus(DocEnhanceTaskEntity.STATUS_READY);
                        task.setErrorMessage(null);
                        task.setFinishedAt(Instant.now());
                        taskRepository.save(task);
                    });
        } catch (RuntimeException e) {
            task.setStatus(DocEnhanceTaskEntity.STATUS_FAILED);
            task.setErrorMessage(trim(rootMessage(e), 1000));
            task.setFinishedAt(Instant.now());
            taskRepository.save(task);
            log.warn("Document semantic analysis failed for task {}: {}", taskId, e.getMessage());
        }
    }

    /** Applies one proposal through the existing strongly validated modeling write paths. */
    public DocEnhanceProposalEntity adopt(String ownerId, String groupId, String proposalId) {
        groupService.getGroup(ownerId, groupId);
        try {
            return transactionTemplate.execute(
                    ignored -> adoptInTransaction(ownerId, groupId, proposalId));
        } catch (DatasetException e) {
            recordDecisionError(ownerId, groupId, proposalId, e.getMessage());
            throw e;
        } catch (RuntimeException e) {
            String detail = rootMessage(e);
            recordDecisionError(ownerId, groupId, proposalId, detail);
            throw new DatasetException("提案 payload 无效：" + detail, 400);
        }
    }

    private DocEnhanceProposalEntity adoptInTransaction(
            String ownerId, String groupId, String proposalId) {
        DocEnhanceProposalEntity proposal = getProposal(ownerId, groupId, proposalId);
        requirePending(proposal);
        if ("CONFLICT".equals(proposal.getClassification())
                || "MANUAL_FIX".equals(proposal.getProposalType())) {
            throw new DatasetException("冲突或人工修复提案不能直接采纳，请先在编辑器中处理", 409);
        }
        JsonNode payload;
        try {
            payload = mapper.readTree(proposal.getPayloadJson());
        } catch (Exception e) {
            throw new DatasetException("提案 payload 无效：" + rootMessage(e), 400);
        }
        boolean mdlChanged = applyProposal(ownerId, groupId, proposal, payload);
        if (mdlChanged) {
            groupService.markMdlDirty(ownerId, groupId);
            MdlPublishService.MdlValidation validation = mdlPublishService.validate(groupId);
            if (!validation.ok()) {
                String detail =
                        validation.issues().isEmpty()
                                ? "MDL 严格校验未通过"
                                : validation.issues().get(0).message();
                throw new DatasetException("采纳后 MDL 校验失败：" + detail, 409);
            }
        }
        proposal.setStatus(DocEnhanceProposalEntity.STATUS_ADOPTED);
        proposal.setDecisionError(null);
        proposal.setDecidedAt(Instant.now());
        return proposalRepository.save(proposal);
    }

    private void recordDecisionError(
            String ownerId, String groupId, String proposalId, String message) {
        proposalRepository
                .findById(proposalId)
                .filter(p -> ownerId.equals(p.getOwnerId()) && groupId.equals(p.getGroupId()))
                .filter(p -> DocEnhanceProposalEntity.STATUS_PENDING.equals(p.getStatus()))
                .ifPresent(
                        proposal -> {
                            proposal.setDecisionError(trim(message, 1000));
                            proposalRepository.save(proposal);
                        });
    }

    @Transactional
    public DocEnhanceProposalEntity ignore(String ownerId, String groupId, String proposalId) {
        groupService.getGroup(ownerId, groupId);
        DocEnhanceProposalEntity proposal = getProposal(ownerId, groupId, proposalId);
        requirePending(proposal);
        proposal.setStatus(DocEnhanceProposalEntity.STATUS_IGNORED);
        proposal.setDecisionError(null);
        proposal.setDecidedAt(Instant.now());
        return proposalRepository.save(proposal);
    }

    private boolean applyProposal(
            String ownerId, String groupId, DocEnhanceProposalEntity proposal, JsonNode payload) {
        return switch (proposal.getProposalType()) {
            case "TERM" -> {
                modelingService.createTerm(
                        groupId,
                        text(payload, "term"),
                        text(payload, "explanation"),
                        nullableText(payload, "synonyms"));
                yield false;
            }
            case "BUSINESS_RULE" -> {
                createBusinessRule(
                        ownerId,
                        groupId,
                        text(payload, "name"),
                        text(payload, "content"),
                        taskRepository
                                .findById(proposal.getTaskId())
                                .map(DocEnhanceTaskEntity::getSourceLabel)
                                .orElse(null));
                yield false;
            }
            case "RELATIONSHIP" -> {
                List<String> sourceColumns = stringList(payload.path("sourceColumns"));
                List<String> targetColumns = stringList(payload.path("targetColumns"));
                modelingService.addManualRelation(
                        groupId,
                        new MdlSuggestionService.ManualRelationPayload(
                                text(payload, "sourceDatasetId"),
                                sourceColumns.isEmpty() ? null : sourceColumns.get(0),
                                text(payload, "targetDatasetId"),
                                targetColumns.isEmpty() ? null : targetColumns.get(0),
                                nullableText(payload, "joinType"),
                                sourceColumns,
                                targetColumns));
                yield true;
            }
            case "CUBE" -> throw new DatasetException("Cube 已停用，请使用逻辑模型或明细视图", 410);
            case "VIEW" -> {
                modelingService.createView(
                        groupId,
                        new MdlSuggestionService.ViewPayload(
                                text(payload, "name"),
                                nullableText(payload, "baseDatasetId"),
                                text(payload, "sqlText"),
                                nullableText(payload, "description")));
                yield true;
            }
            default -> throw new DatasetException("不支持采纳该提案类型", 409);
        };
    }

    /** Creates a group-scoped rule; also used by the modeling conversation toolkit. */
    @Transactional
    public SemanticBusinessRuleEntity createBusinessRule(
            String ownerId, String groupId, String name, String content, String sourceLabel) {
        groupService.getGroup(ownerId, groupId);
        String n = required(name, 100, "规则名称");
        String body = required(content, 2000, "规则内容");
        if (ruleRepository.findByOwnerIdAndGroupIdAndName(ownerId, groupId, n).isPresent()) {
            throw new DatasetException("当前知识库已存在同名业务规则：" + n, 409);
        }
        // specs/019 §7: the workspace file is what the query channel reads; the DB row stays the
        // review ledger. File first, so a workspace failure aborts the whole adoption.
        persistRuleFile(groupId, n, body);
        return ruleRepository.save(
                new SemanticBusinessRuleEntity(
                        UUID.randomUUID().toString(),
                        ownerId,
                        groupId,
                        n,
                        body,
                        trim(sourceLabel, 255)));
    }

    public List<SemanticBusinessRuleEntity> listBusinessRules(String ownerId, String groupId) {
        groupService.getGroup(ownerId, groupId);
        return ruleRepository.findByOwnerIdAndGroupIdOrderByCreatedAtAsc(ownerId, groupId);
    }

    /**
     * specs/019 §7: a business rule takes effect for the query channel only as {@code
     * knowledge/rules/*.md} inside the workspace; the DB row stays the review ledger. The file
     * name derives from the rule name and never overwrites an existing rule file.
     */
    private void persistRuleFile(String groupId, String name, String content) {
        workspace.withWorkspaceLock(
                groupId,
                () -> {
                    workspace.ensureWorkspace(groupId);
                    Path rules =
                            workspace.workspaceRoot(groupId).resolve("knowledge").resolve("rules");
                    String base = MdlPublishService.sanitizeIdentifier(name, 60);
                    Path file = rules.resolve(base + ".md");
                    for (int i = 2; Files.exists(file) && i < 100; i++) {
                        file = rules.resolve(base + "_" + i + ".md");
                    }
                    try {
                        Files.createDirectories(rules);
                        Files.writeString(
                                file,
                                "# " + name + "\n\n" + content.strip() + "\n",
                                StandardCharsets.UTF_8);
                    } catch (IOException e) {
                        throw new DatasetException("写入知识库规则文件失败：" + e.getMessage(), e);
                    }
                    return null;
                });
    }

    private DocEnhanceProposalEntity getProposal(
            String ownerId, String groupId, String proposalId) {
        return proposalRepository
                .findById(proposalId)
                .filter(p -> ownerId.equals(p.getOwnerId()) && groupId.equals(p.getGroupId()))
                .orElseThrow(() -> new DatasetException("语义增强提案不存在", 404));
    }

    private static void requirePending(DocEnhanceProposalEntity proposal) {
        if (!DocEnhanceProposalEntity.STATUS_PENDING.equals(proposal.getStatus())) {
            throw new DatasetException("该提案已经处理，不能重复操作", 409);
        }
    }

    private List<DocEnhanceProposalEntity> parseProposals(
            DocEnhanceTaskEntity task, String reply, List<DatasetEntity> datasets) {
        try {
            JsonNode root = mapper.readTree(agentDraftService.extractJsonObject(reply));
            JsonNode items = root.path("proposals");
            if (!items.isArray()) {
                throw new DatasetException("模型输出缺少 proposals 数组", 502);
            }
            Map<String, DatasetEntity> datasetIndex = datasetIndex(datasets);
            Set<String> fingerprints = new HashSet<>();
            List<DocEnhanceProposalEntity> out = new ArrayList<>();
            for (JsonNode item : items) {
                String classification = upper(item.path("classification").asText("NEW"));
                if ("COVERED".equals(classification)) {
                    continue;
                }
                String type = upper(item.path("type").asText(""));
                if (!TYPES.contains(type) || !CLASSIFICATIONS.contains(classification)) {
                    continue;
                }
                ObjectNode payload = normalizePayload(type, item.path("payload"), datasetIndex);
                if (payload == null) {
                    type = "MANUAL_FIX";
                    payload = mapper.createObjectNode();
                    payload.put("reason", "提案引用的表、列或 payload 无法安全解析");
                }
                String title = required(item.path("title").asText(""), 200, "提案标题");
                String payloadJson = trim(mapper.writeValueAsString(payload), 12000);
                String fingerprint = fingerprint(type + "|" + lower(title) + "|" + payloadJson);
                if (!fingerprints.add(fingerprint)
                        || proposalRepository.existsByOwnerIdAndGroupIdAndFingerprintAndStatus(
                                task.getOwnerId(),
                                task.getGroupId(),
                                fingerprint,
                                DocEnhanceProposalEntity.STATUS_PENDING)
                        || proposalRepository.existsByOwnerIdAndGroupIdAndFingerprintAndStatus(
                                task.getOwnerId(),
                                task.getGroupId(),
                                fingerprint,
                                DocEnhanceProposalEntity.STATUS_ADOPTED)) {
                    continue;
                }
                DocEnhanceProposalEntity entity = new DocEnhanceProposalEntity();
                entity.setId(UUID.randomUUID().toString());
                entity.setTaskId(task.getId());
                entity.setOwnerId(task.getOwnerId());
                entity.setGroupId(task.getGroupId());
                entity.setProposalType(type);
                entity.setClassification(classification);
                entity.setTitle(title);
                entity.setSummary(trim(item.path("summary").asText(""), 1000));
                entity.setPayloadJson(payloadJson);
                entity.setSourceQuote(trim(item.path("source_quote").asText(""), 500));
                String confidence = upper(item.path("confidence").asText("MEDIUM"));
                entity.setConfidence(CONFIDENCES.contains(confidence) ? confidence : "MEDIUM");
                entity.setFingerprint(fingerprint);
                out.add(entity);
            }
            return out;
        } catch (DatasetException e) {
            throw e;
        } catch (Exception e) {
            throw new DatasetException("无法解析模型生成的语义提案：" + rootMessage(e), 502);
        }
    }

    private ObjectNode normalizePayload(
            String type, JsonNode raw, Map<String, DatasetEntity> datasetIndex) {
        if (!raw.isObject()) {
            return null;
        }
        ObjectNode p = ((ObjectNode) raw).deepCopy();
        try {
            switch (type) {
                case "TERM" -> {
                    required(text(p, "term"), 30, "术语");
                    required(text(p, "explanation"), 100, "术语解释");
                }
                case "BUSINESS_RULE" -> {
                    required(text(p, "name"), 100, "规则名称");
                    required(text(p, "content"), 2000, "规则内容");
                }
                case "RELATIONSHIP" -> {
                    DatasetEntity source = resolveDataset(datasetIndex, text(p, "sourceDatasetId"));
                    DatasetEntity target = resolveDataset(datasetIndex, text(p, "targetDatasetId"));
                    if (source == null || target == null || source.getId().equals(target.getId())) {
                        return null;
                    }
                    List<String> sourceColumns =
                            resolveColumns(source, stringList(p.path("sourceColumns")));
                    List<String> targetColumns =
                            resolveColumns(target, stringList(p.path("targetColumns")));
                    if (sourceColumns.isEmpty() || sourceColumns.size() != targetColumns.size()) {
                        return null;
                    }
                    p.put("sourceDatasetId", source.getId());
                    p.put("targetDatasetId", target.getId());
                    p.set("sourceColumns", mapper.valueToTree(sourceColumns));
                    p.set("targetColumns", mapper.valueToTree(targetColumns));
                }
                case "VIEW" -> {
                    String sql = text(p, "sqlText").trim();
                    String upperSql = upper(sql);
                    if ((!upperSql.startsWith("SELECT") && !upperSql.startsWith("WITH"))
                            || sql.substring(0, Math.max(0, sql.length() - 1)).contains(";")) {
                        return null;
                    }
                    String baseRef = nullableText(p, "baseDatasetId");
                    if (baseRef != null) {
                        DatasetEntity base = resolveDataset(datasetIndex, baseRef);
                        if (base == null) {
                            return null;
                        }
                        p.put("baseDatasetId", base.getId());
                    }
                }
                case "MANUAL_FIX" -> {
                    // Display-only proposal; its payload is kept for the human editor.
                }
                default -> {
                    return null;
                }
            }
            return p;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private String buildPrompt(
            DocEnhanceTaskEntity task, String sourceText, List<DatasetEntity> datasets) {
        StringBuilder sb = new StringBuilder();
        sb.append("你是数据语义差异分析器。把业务文档中的原子声明与当前语义状态比较，只输出严格 JSON。\n")
                .append("分类：NEW=没有等价资产，PARTIAL=已有资产只覆盖部分，CONFLICT=与已确认资产冲突，")
                .append("COVERED=已完整覆盖。COVERED 不需要用户处理。\n")
                .append("路由：业务词/别名/枚举含义→TERM；默认过滤、软删、权威表、币种、财务周期、NULL/哨兵→BUSINESS_RULE；")
                .append(
                        "关系/复合键/基数→RELATIONSHIP；指标公式、单位、时间归属→BUSINESS_RULE；必需过滤和复杂关联→明细"
                                + " VIEW（保留实体身份与原始时间，不预聚合）；")
                .append("计算列或无法安全落点→MANUAL_FIX。不要把已有列描述或枚举样例重复生成。\n")
                .append("所有表引用优先使用下方 dataset_id，所有列使用物理列名。View SQL 只能是一条 SELECT/WITH。\n\n")
                .append("# 当前数据表\n");
        for (DatasetEntity dataset : datasets) {
            sb.append("## ")
                    .append(dataset.getName())
                    .append("（dataset_id=")
                    .append(dataset.getId())
                    .append("，physical_table=")
                    .append(dataset.getTableName())
                    .append("）\n");
            for (ColumnSchema column : datasetService.readColumns(dataset)) {
                sb.append("- ").append(column.name()).append(" | ").append(column.sqlType());
                if (column.description() != null && !column.description().isBlank()) {
                    sb.append(" | ").append(column.description());
                }
                sb.append('\n');
            }
        }
        sb.append("\n# 当前关系\n");
        modelingService
                .listRelations(task.getGroupId())
                .forEach(
                        r ->
                                sb.append("- ")
                                        .append(r.getSourceDatasetId())
                                        .append('.')
                                        .append(r.getSourceColumn())
                                        .append(" -> ")
                                        .append(r.getTargetDatasetId())
                                        .append('.')
                                        .append(r.getTargetColumn())
                                        .append(" [")
                                        .append(r.getStatus())
                                        .append("]\n"));
        sb.append("\n# 当前 View\n");
        modelingService
                .listViews(task.getGroupId())
                .forEach(
                        v ->
                                sb.append("- ")
                                        .append(v.getName())
                                        .append(": ")
                                        .append(v.getDescription())
                                        .append(" SQL=")
                                        .append(v.getSqlText())
                                        .append('\n'));
        sb.append("\n# 当前术语\n");
        modelingService
                .listTerms(task.getGroupId())
                .forEach(
                        t ->
                                sb.append("- ")
                                        .append(t.getTerm())
                                        .append(": ")
                                        .append(t.getExplanation())
                                        .append('\n'));
        sb.append("\n# 当前业务规则\n");
        ruleRepository
                .findByOwnerIdAndGroupIdOrderByCreatedAtAsc(task.getOwnerId(), task.getGroupId())
                .forEach(
                        r ->
                                sb.append("- ")
                                        .append(r.getName())
                                        .append(": ")
                                        .append(r.getContent())
                                        .append('\n'));
        sb.append("\n# 待分析文档\n")
                .append(trim(sourceText, 20000))
                .append("\n\n")
                .append(
                        "仅输出：{\"proposals\":[{\"type\":\"TERM|BUSINESS_RULE|RELATIONSHIP|VIEW|MANUAL_FIX\",\"classification\":\"NEW|PARTIAL|CONFLICT|COVERED\",\"title\":\"短标题\",\"summary\":\"自包含说明\",\"payload\":{},\"source_quote\":\"原文\",\"confidence\":\"HIGH|MEDIUM|LOW\"}]}\n")
                .append(
                        "TERM payload={term,explanation,synonyms}; BUSINESS_RULE={name,content};"
                            + " RELATIONSHIP={sourceDatasetId,sourceColumns,targetDatasetId,targetColumns,joinType};"
                            + " VIEW={name,baseDatasetId,sqlText,description}。");
        return sb.toString();
    }

    private Map<String, DatasetEntity> datasetIndex(List<DatasetEntity> datasets) {
        Map<String, DatasetEntity> out = new HashMap<>();
        for (DatasetEntity dataset : datasets) {
            out.put(lower(dataset.getId()), dataset);
            out.put(lower(dataset.getName()), dataset);
            out.put(lower(dataset.getTableName()), dataset);
        }
        return out;
    }

    private static DatasetEntity resolveDataset(
            Map<String, DatasetEntity> index, String reference) {
        return index.get(lower(reference));
    }

    private List<String> resolveColumns(DatasetEntity dataset, List<String> references) {
        Map<String, String> index = new HashMap<>();
        for (ColumnSchema column : datasetService.readColumns(dataset)) {
            index.put(lower(column.name()), column.name());
            if (column.originalName() != null) {
                index.put(lower(column.originalName()), column.name());
            }
        }
        List<String> out = new ArrayList<>();
        for (String reference : references) {
            String resolved = index.get(lower(reference));
            if (resolved == null) {
                return List.of();
            }
            out.add(resolved);
        }
        return out;
    }

    private static String text(JsonNode node, String field) {
        return node.path(field).asText("").trim();
    }

    private static String nullableText(JsonNode node, String field) {
        String value = text(node, field);
        return value.isEmpty() ? null : value;
    }

    private static List<String> stringList(JsonNode node) {
        List<String> out = new ArrayList<>();
        if (node != null && node.isArray()) {
            node.forEach(
                    value -> {
                        String text = value.asText("").trim();
                        if (!text.isEmpty()) {
                            out.add(text);
                        }
                    });
        }
        return out;
    }

    private List<Map<String, Object>> objectList(JsonNode node) {
        if (node == null || !node.isArray()) {
            return List.of();
        }
        return mapper.convertValue(node, new TypeReference<List<Map<String, Object>>>() {});
    }

    private static String required(String value, int max, String label) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isEmpty() || normalized.length() > max) {
            throw new DatasetException(label + "长度必须为 1-" + max + " 个字符", 400);
        }
        return normalized;
    }

    private static String upper(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }

    private static String lower(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private static String trim(String value, int max) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        return normalized.length() <= max ? normalized : normalized.substring(0, max);
    }

    private static String fingerprint(String value) {
        try {
            byte[] digest =
                    MessageDigest.getInstance("SHA-256")
                            .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder();
            for (byte b : digest) {
                out.append(String.format("%02x", b));
            }
            return out.toString();
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static String rootMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        String message = current.getMessage();
        return message == null || message.isBlank() ? current.getClass().getSimpleName() : message;
    }
}
