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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.agentscope.dataagent.web.ai.AgentDraftService;
import io.agentscope.dataagent.web.persistence.jpa.DatasetRepository;
import io.agentscope.dataagent.web.persistence.jpa.KnowledgeGraphBuildTaskEntity;
import io.agentscope.dataagent.web.persistence.jpa.KnowledgeGraphBuildTaskRepository;
import io.agentscope.dataagent.web.persistence.jpa.KnowledgeGraphBuildUnitEntity;
import io.agentscope.dataagent.web.persistence.jpa.KnowledgeGraphBuildUnitRepository;
import io.agentscope.dataagent.web.persistence.jpa.KnowledgeGraphEntityEntity;
import io.agentscope.dataagent.web.persistence.jpa.KnowledgeGraphEntityRepository;
import io.agentscope.dataagent.web.persistence.jpa.KnowledgeGraphRelationEntity;
import io.agentscope.dataagent.web.persistence.jpa.KnowledgeGraphRelationRepository;
import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.server.ResponseStatusException;

/**
 * Locks the LLM GraphRAG persistence contract: entity dedupe by normalized key, relation
 * source/target resolution (unknown endpoints skipped), per-unit failure isolation, and the
 * trigger guards (503 without a model, 409 when a build is already running).
 */
class KnowledgeGraphServiceTest {

    private KnowledgeGraphEntityRepository entityRepo;
    private KnowledgeGraphRelationRepository relationRepo;
    private KnowledgeGraphBuildUnitRepository unitRepo;
    private KnowledgeGraphBuildTaskRepository taskRepo;
    private DatasetRepository datasetRepository;
    private DatasetService datasetService;
    private DatasetGroupService groupService;
    private AgentDraftService agentDraftService;
    private KnowledgeGraphService service;

    /** In-memory stand-in for the entity table so dedupe lookups behave like the repository. */
    private final Map<String, KnowledgeGraphEntityEntity> entityTable = new HashMap<>();

    @BeforeEach
    void setUp() {
        entityRepo = mock(KnowledgeGraphEntityRepository.class);
        relationRepo = mock(KnowledgeGraphRelationRepository.class);
        unitRepo = mock(KnowledgeGraphBuildUnitRepository.class);
        taskRepo = mock(KnowledgeGraphBuildTaskRepository.class);
        datasetRepository = mock(DatasetRepository.class);
        datasetService = mock(DatasetService.class);
        groupService = mock(DatasetGroupService.class);
        agentDraftService = mock(AgentDraftService.class);
        service =
                new KnowledgeGraphService(
                        entityRepo,
                        relationRepo,
                        unitRepo,
                        taskRepo,
                        datasetRepository,
                        datasetService,
                        groupService,
                        agentDraftService);

        when(entityRepo.findByGroupIdAndNormalizedKey(anyString(), anyString()))
                .thenAnswer(
                        inv ->
                                Optional.ofNullable(
                                        entityTable.get(
                                                inv.getArgument(0) + "|" + inv.getArgument(1))));
        when(entityRepo.save(any(KnowledgeGraphEntityEntity.class)))
                .thenAnswer(
                        inv -> {
                            KnowledgeGraphEntityEntity e = inv.getArgument(0);
                            entityTable.put(e.getGroupId() + "|" + e.getNormalizedKey(), e);
                            return e;
                        });
    }

    private static KnowledgeGraphService.ExtractionResult result(
            List<KnowledgeGraphService.ExtractionEntity> entities,
            List<KnowledgeGraphService.ExtractionRelation> relations) {
        return new KnowledgeGraphService.ExtractionResult(entities, relations);
    }

    @Test
    void persistExtractionDedupesEntitiesAndResolvesRelations() {
        KnowledgeGraphService.ExtractionResult first =
                result(
                        List.of(
                                new KnowledgeGraphService.ExtractionEntity("在订用户", "业务词", Map.of()),
                                new KnowledgeGraphService.ExtractionEntity("省份", "字段", Map.of())),
                        List.of(
                                new KnowledgeGraphService.ExtractionRelation(
                                        "在订用户", "省份", "按省份统计", "包含字段")));
        service.persistExtraction("owner", "g1", first);
        // Same entity text again from a second document must merge, not duplicate.
        service.persistExtraction(
                "owner",
                "g1",
                result(
                        List.of(
                                new KnowledgeGraphService.ExtractionEntity(
                                        "在订用户", "业务词", Map.of())),
                        List.of()));

        assertEquals(2, entityTable.size(), "duplicate entity text must not create a second node");
        ArgumentCaptor<KnowledgeGraphRelationEntity> rel =
                ArgumentCaptor.forClass(KnowledgeGraphRelationEntity.class);
        verify(relationRepo).save(rel.capture());
        assertEquals("包含字段", rel.getValue().getLabel());
        assertTrue(rel.getValue().getSourceEntityId() != null);
        assertTrue(rel.getValue().getTargetEntityId() != null);
    }

    @Test
    void persistExtractionSkipsRelationsWithUnknownEndpoints() {
        service.persistExtraction(
                "owner",
                "g1",
                result(
                        List.of(
                                new KnowledgeGraphService.ExtractionEntity(
                                        "在订用户", "业务词", Map.of())),
                        List.of(
                                new KnowledgeGraphService.ExtractionRelation(
                                        "在订用户", "不存在的实体", "x", "关联"))));
        verify(relationRepo, never()).save(any(KnowledgeGraphRelationEntity.class));
    }

    @Test
    void executeBuildMarksUnitFailedWithoutAbortingOthers() {
        KnowledgeGraphBuildUnitEntity unit =
                new KnowledgeGraphBuildUnitEntity(
                        "u1", "g1", KnowledgeGraphBuildUnitEntity.TYPE_KNOWLEDGE_DOC, "g1", "知识文档");
        when(unitRepo.findByGroupIdOrderByCreatedAtAsc("g1")).thenReturn(List.of(unit));
        when(datasetService.knowledgeText("g1")).thenReturn("some doc");
        when(agentDraftService.chatBlocking(anyString()))
                .thenThrow(new IllegalStateException("model returned non-JSON"));
        KnowledgeGraphBuildTaskEntity task = new KnowledgeGraphBuildTaskEntity("t1", "g1", "owner");
        when(taskRepo.findTopByGroupIdOrderByTriggeredAtDesc("g1")).thenReturn(Optional.of(task));

        service.executeBuild("g1", "owner");

        assertEquals(KnowledgeGraphBuildUnitEntity.STATUS_FAILED, unit.getStatus());
        assertEquals(KnowledgeGraphBuildTaskEntity.STATUS_PARTIAL_FAILED, task.getStatus());
    }

    @Test
    void triggerBuildRejectsWhenModelMissing() {
        when(groupService.getGroup("owner", "g1")).thenReturn(null);
        when(agentDraftService.modelAvailable()).thenReturn(false);
        ResponseStatusException e =
                assertThrows(
                        ResponseStatusException.class,
                        () -> service.triggerBuild("owner", "g1", true, null));
        assertEquals(503, e.getStatusCode().value());
    }

    @Test
    @SuppressWarnings("unchecked")
    void triggerBuildRejectsWhenInJvmLockHeld() throws Exception {
        when(groupService.getGroup("owner", "g1")).thenReturn(null);
        when(agentDraftService.modelAvailable()).thenReturn(true);
        Field f = KnowledgeGraphService.class.getDeclaredField("running");
        f.setAccessible(true);
        ((ConcurrentHashMap<String, Boolean>) f.get(service)).put("g1", Boolean.TRUE);
        ResponseStatusException e =
                assertThrows(
                        ResponseStatusException.class,
                        () -> service.triggerBuild("owner", "g1", true, null));
        assertEquals(409, e.getStatusCode().value());
    }

    @Test
    void triggerBuildRejectsEmptySelection() {
        when(groupService.getGroup("owner", "g1")).thenReturn(null);
        when(agentDraftService.modelAvailable()).thenReturn(true);
        when(datasetService.knowledgeText("g1")).thenReturn("");
        when(groupService.listDatasets("owner", "g1")).thenReturn(List.of());
        ResponseStatusException e =
                assertThrows(
                        ResponseStatusException.class,
                        () -> service.triggerBuild("owner", "g1", true, null));
        assertEquals(400, e.getStatusCode().value());
    }
}
