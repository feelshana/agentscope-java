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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.dataagent.web.ai.AgentDraftService;
import io.agentscope.dataagent.web.persistence.jpa.DatasetGroupEntity;
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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

/** Locks proposal parsing, tenant isolation and the human-review adoption state machine. */
class DocEnhanceServiceTest {

    private final Map<String, DocEnhanceTaskEntity> tasks = new HashMap<>();
    private final Map<String, DocEnhanceProposalEntity> proposals = new HashMap<>();
    private final List<DocEnhanceProposalEntity> savedProposals = new ArrayList<>();

    private DocEnhanceTaskRepository taskRepository;
    private DocEnhanceProposalRepository proposalRepository;
    private SemanticBusinessRuleRepository ruleRepository;
    private DatasetGroupService groupService;
    private MdlSuggestionService modelingService;
    private MdlWorkspaceService workspace;
    private MdlPublishService mdlPublishService;
    private AgentDraftService agentDraftService;
    private DocEnhanceService service;

    @TempDir Path tempDir;

    @BeforeEach
    void setUp() {
        taskRepository = mock(DocEnhanceTaskRepository.class);
        proposalRepository = mock(DocEnhanceProposalRepository.class);
        ruleRepository = mock(SemanticBusinessRuleRepository.class);
        groupService = mock(DatasetGroupService.class);
        modelingService = mock(MdlSuggestionService.class);
        workspace = mock(MdlWorkspaceService.class);
        mdlPublishService = mock(MdlPublishService.class);
        agentDraftService = mock(AgentDraftService.class);
        DatasetRepository datasetRepository = mock(DatasetRepository.class);
        DatasetService datasetService = mock(DatasetService.class);
        PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
        when(transactionManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());

        service =
                new DocEnhanceService(
                        taskRepository,
                        proposalRepository,
                        ruleRepository,
                        datasetRepository,
                        datasetService,
                        groupService,
                        modelingService,
                        workspace,
                        mdlPublishService,
                        agentDraftService,
                        new ObjectMapper(),
                        transactionManager);

        DatasetGroupEntity group = new DatasetGroupEntity("g1", "alice", "测试知识库", null);
        when(groupService.getGroup("alice", "g1")).thenReturn(group);
        when(groupService.getGroup("bob", "g1"))
                .thenThrow(new DatasetException("Knowledge base not found: g1", 404));
        when(taskRepository.findById(anyString()))
                .thenAnswer(inv -> Optional.ofNullable(tasks.get(inv.getArgument(0))));
        when(taskRepository.save(any(DocEnhanceTaskEntity.class)))
                .thenAnswer(
                        inv -> {
                            DocEnhanceTaskEntity entity = inv.getArgument(0);
                            tasks.put(entity.getId(), entity);
                            return entity;
                        });
        when(proposalRepository.findById(anyString()))
                .thenAnswer(inv -> Optional.ofNullable(proposals.get(inv.getArgument(0))));
        when(proposalRepository.save(any(DocEnhanceProposalEntity.class)))
                .thenAnswer(
                        inv -> {
                            DocEnhanceProposalEntity entity = inv.getArgument(0);
                            proposals.put(entity.getId(), entity);
                            return entity;
                        });
        when(proposalRepository.saveAll(any()))
                .thenAnswer(
                        inv -> {
                            Iterable<DocEnhanceProposalEntity> entities = inv.getArgument(0);
                            entities.forEach(
                                    entity -> {
                                        proposals.put(entity.getId(), entity);
                                        savedProposals.add(entity);
                                    });
                            return savedProposals;
                        });
        when(proposalRepository.existsByOwnerIdAndGroupIdAndFingerprintAndStatus(
                        anyString(), anyString(), anyString(), anyString()))
                .thenAnswer(
                        inv ->
                                proposals.values().stream()
                                        .anyMatch(
                                                proposal ->
                                                        inv.getArgument(0)
                                                                        .equals(
                                                                                proposal
                                                                                        .getOwnerId())
                                                                && inv.getArgument(1)
                                                                        .equals(
                                                                                proposal
                                                                                        .getGroupId())
                                                                && inv.getArgument(2)
                                                                        .equals(
                                                                                proposal
                                                                                        .getFingerprint())
                                                                && inv.getArgument(3)
                                                                        .equals(
                                                                                proposal
                                                                                        .getStatus())));
        when(workspace.workspaceRoot(anyString())).thenReturn(tempDir);
        when(workspace.withWorkspaceLock(anyString(), any()))
                .thenAnswer(inv -> ((Supplier<?>) inv.getArgument(1)).get());
        when(datasetRepository.findByGroupId("g1")).thenReturn(List.of());
        when(modelingService.listRelations("g1")).thenReturn(List.of());
        when(modelingService.listCubes("g1")).thenReturn(List.of());
        when(modelingService.listViews("g1")).thenReturn(List.of());
        when(modelingService.listTerms("g1")).thenReturn(List.of());
        when(ruleRepository.findByOwnerIdAndGroupIdOrderByCreatedAtAsc("alice", "g1"))
                .thenReturn(List.of());
        when(agentDraftService.modelAvailable()).thenReturn(true);
        when(agentDraftService.extractJsonObject(anyString()))
                .thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void analysisKeepsActionableDifferencesAndDropsCovered() {
        DocEnhanceTaskEntity task = task("t1");
        String reply =
                """
                {"proposals":[
                  {"type":"TERM","classification":"NEW","title":"VIP",\
                   "summary":"累计消费达到一万元的客户",\
                   "payload":{"term":"VIP","explanation":"累计消费达到一万元的客户","synonyms":"贵宾"},\
                   "source_quote":"VIP 指累计消费达到一万元的客户","confidence":"HIGH"},
                  {"type":"TERM","classification":"COVERED","title":"老术语",\
                   "payload":{"term":"老术语","explanation":"已经存在"}},
                  {"type":"RELATIONSHIP","classification":"NEW","title":"无法解析的关系",\
                   "summary":"文档有关系但表名错误",\
                   "payload":{"sourceDatasetId":"missing","sourceColumns":["id"],\
                   "targetDatasetId":"also_missing","targetColumns":["id"]},"confidence":"LOW"}
                ]}
                """;
        when(agentDraftService.chatBlockingModeling(anyString())).thenReturn(reply);

        service.analyzeTask(task.getId(), "VIP 指累计消费达到一万元的客户");

        assertThat(task.getStatus()).isEqualTo(DocEnhanceTaskEntity.STATUS_READY);
        assertThat(savedProposals).hasSize(2);
        assertThat(savedProposals.get(0).getProposalType()).isEqualTo("TERM");
        assertThat(savedProposals.get(1).getProposalType()).isEqualTo("MANUAL_FIX");
        assertThat(savedProposals).allMatch(p -> p.getFingerprint().length() == 64);
    }

    @Test
    void duplicateItemsAndRepeatedAnalysisDoNotCreateDuplicateProposals() {
        DocEnhanceTaskEntity first = task("t-dup-1");
        String item =
                "{\"type\":\"TERM\",\"classification\":\"NEW\",\"title\":\"VIP\","
                        + "\"payload\":{\"term\":\"VIP\",\"explanation\":\"高价值客户\"}}";
        when(agentDraftService.chatBlockingModeling(anyString()))
                .thenReturn("{\"proposals\":[" + item + "," + item + "]}");

        service.analyzeTask(first.getId(), "VIP 说明");
        DocEnhanceTaskEntity second = task("t-dup-2");
        service.analyzeTask(second.getId(), "VIP 说明");

        assertThat(savedProposals).hasSize(1);
        assertThat(first.getStatus()).isEqualTo(DocEnhanceTaskEntity.STATUS_READY);
        assertThat(second.getStatus()).isEqualTo(DocEnhanceTaskEntity.STATUS_READY);
    }

    @Test
    void modelFailureMarksTaskFailedWithoutProposals() {
        DocEnhanceTaskEntity task = task("t2");
        when(agentDraftService.chatBlockingModeling(anyString()))
                .thenThrow(new IllegalStateException("provider unavailable"));

        service.analyzeTask(task.getId(), "业务说明");

        assertThat(task.getStatus()).isEqualTo(DocEnhanceTaskEntity.STATUS_FAILED);
        assertThat(task.getErrorMessage()).contains("provider unavailable");
        assertThat(savedProposals).isEmpty();
    }

    @Test
    void termAdoptionUsesExistingWritePathAndClosesProposal() {
        DocEnhanceProposalEntity proposal =
                proposal(
                        "p1",
                        "TERM",
                        "NEW",
                        "{\"term\":\"VIP\",\"explanation\":\"累计消费达到一万元的客户\",\"synonyms\":\"贵宾\"}");

        DocEnhanceProposalEntity result = service.adopt("alice", "g1", proposal.getId());

        verify(modelingService).createTerm("g1", "VIP", "累计消费达到一万元的客户", "贵宾");
        assertThat(result.getStatus()).isEqualTo(DocEnhanceProposalEntity.STATUS_ADOPTED);
        assertThat(result.getDecidedAt()).isNotNull();
    }

    @Test
    void mdlAdoptionMarksDirtyValidatesAndPersistsValidationError() {
        DocEnhanceProposalEntity proposal =
                proposal(
                        "p-mdl",
                        "RELATIONSHIP",
                        "NEW",
                        "{\"sourceDatasetId\":\"ds_order\",\"sourceColumns\":[\"customer_id\"],"
                            + "\"targetDatasetId\":\"ds_customer\",\"targetColumns\":[\"customer_id\"],"
                            + "\"joinType\":\"MANY_TO_ONE\"}");
        MdlPublishService.MdlIssue issue =
                new MdlPublishService.MdlIssue("error", "关系列不存在", "local");
        when(mdlPublishService.validate("g1"))
                .thenReturn(
                        new MdlPublishService.MdlValidation(
                                false, List.of(issue), "failed", List.of()));

        assertThatThrownBy(() -> service.adopt("alice", "g1", proposal.getId()))
                .isInstanceOf(DatasetException.class)
                .hasMessageContaining("关系列不存在");

        verify(modelingService).addManualRelation(anyString(), any());
        verify(groupService).markMdlDirty("alice", "g1");
        assertThat(proposal.getStatus()).isEqualTo(DocEnhanceProposalEntity.STATUS_PENDING);
        assertThat(proposal.getDecisionError()).contains("关系列不存在");
    }

    @Test
    void businessRuleAdoptionPersistsTenantAndGroup() throws IOException {
        DocEnhanceProposalEntity proposal =
                proposal(
                        "p2",
                        "BUSINESS_RULE",
                        "NEW",
                        "{\"name\":\"有效订单\",\"content\":\"默认排除 deleted_at 非空的订单\"}");
        when(ruleRepository.findByOwnerIdAndGroupIdAndName("alice", "g1", "有效订单"))
                .thenReturn(Optional.empty());
        when(ruleRepository.save(any(SemanticBusinessRuleEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        service.adopt("alice", "g1", proposal.getId());

        ArgumentCaptor<SemanticBusinessRuleEntity> captor =
                ArgumentCaptor.forClass(SemanticBusinessRuleEntity.class);
        verify(ruleRepository).save(captor.capture());
        assertThat(captor.getValue().getOwnerId()).isEqualTo("alice");
        assertThat(captor.getValue().getGroupId()).isEqualTo("g1");
        // specs/019 §7: the workspace file is the query-channel read side, not just the DB row.
        Path ruleFile = tempDir.resolve("knowledge").resolve("rules").resolve("有效订单.md");
        assertThat(ruleFile).exists();
        assertThat(Files.readString(ruleFile, StandardCharsets.UTF_8))
                .contains("# 有效订单")
                .contains("默认排除 deleted_at 非空的订单");
    }

    @Test
    void conflictCannotBeAdoptedAndForeignTenantCannotProbe() {
        DocEnhanceProposalEntity conflict = proposal("p3", "TERM", "CONFLICT", "{}");

        assertThatThrownBy(() -> service.adopt("alice", "g1", conflict.getId()))
                .isInstanceOf(DatasetException.class)
                .hasMessageContaining("不能直接采纳");
        assertThatThrownBy(() -> service.adopt("bob", "g1", conflict.getId()))
                .isInstanceOf(DatasetException.class)
                .hasMessageContaining("not found");
    }

    private DocEnhanceTaskEntity task(String id) {
        DocEnhanceTaskEntity task =
                new DocEnhanceTaskEntity(id, "alice", "g1", "UPLOAD", "业务口径.md");
        tasks.put(id, task);
        return task;
    }

    private DocEnhanceProposalEntity proposal(
            String id, String type, String classification, String payload) {
        DocEnhanceProposalEntity proposal = new DocEnhanceProposalEntity();
        proposal.setId(id);
        proposal.setTaskId("t1");
        proposal.setOwnerId("alice");
        proposal.setGroupId("g1");
        proposal.setProposalType(type);
        proposal.setClassification(classification);
        proposal.setTitle("测试提案");
        proposal.setPayloadJson(payload);
        proposal.setFingerprint(id);
        proposals.put(id, proposal);
        return proposal;
    }
}
