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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.dataagent.dataset.parser.ColumnSchema;
import io.agentscope.dataagent.dataset.parser.SchemaGenerationService;
import io.agentscope.dataagent.tools.data.InMemoryDataSourceRegistry;
import io.agentscope.dataagent.web.persistence.jpa.DatasetEntity;
import io.agentscope.dataagent.web.persistence.jpa.DatasetGroupEntity;
import io.agentscope.dataagent.web.persistence.jpa.DatasetGroupRepository;
import io.agentscope.dataagent.web.persistence.jpa.DatasetKnowledgeEntity;
import io.agentscope.dataagent.web.persistence.jpa.DatasetKnowledgeRepository;
import io.agentscope.dataagent.web.persistence.jpa.DatasetRelationRepository;
import io.agentscope.dataagent.web.persistence.jpa.DatasetRepository;
import io.agentscope.dataagent.web.persistence.jpa.ExternalDataSourceRepository;
import io.agentscope.dataagent.web.persistence.jpa.SemanticBusinessRuleRepository;
import io.agentscope.dataagent.web.persistence.jpa.SemanticTermRepository;
import io.agentscope.dataagent.web.persistence.jpa.SemanticViewRepository;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Locks the dirty-marking contract of specs/010 M2.2: dataset mutations (upload, delete, column
 * description edits, knowledge upload) flag a PUBLISHED knowledge base as DIRTY - acceptance #6,
 * where the old MDL snapshot keeps serving until an explicit republish. NONE/DIRTY groups, orphan
 * datasets and blank ids must stay no-ops so nothing is written on every mutation.
 */
class DatasetServiceMdlDirtyTest {

    private DatasetRepository repository;
    private DatasetGroupRepository groupRepository;
    private DatasetKnowledgeRepository knowledgeRepository;
    private TableProvisioner provisioner;
    private DatasetService service;

    private final ObjectMapper mapper = new ObjectMapper();
    private final Map<String, DatasetGroupEntity> groupsById = new HashMap<>();

    @BeforeEach
    void setUp() {
        repository = mock(DatasetRepository.class);
        groupRepository = mock(DatasetGroupRepository.class);
        knowledgeRepository = mock(DatasetKnowledgeRepository.class);
        provisioner = mock(TableProvisioner.class);
        DatasetStoreProperties storeProps = mock(DatasetStoreProperties.class);
        // toDataSource feeds these into a Map.copyOf-backed record: nulls are rejected.
        when(storeProps.url()).thenReturn("jdbc:mysql://localhost:3306/data_agent");
        when(storeProps.username()).thenReturn("dataagent");
        when(storeProps.password()).thenReturn("secret");
        service =
                new DatasetService(
                        repository,
                        groupRepository,
                        knowledgeRepository,
                        mock(SemanticTermRepository.class),
                        mock(SemanticViewRepository.class),
                        mock(SemanticBusinessRuleRepository.class),
                        mock(ExternalDataSourceRepository.class),
                        mock(DataSourceIntrospector.class),
                        mock(RelationInferenceService.class),
                        mock(DatasetRelationRepository.class),
                        new InMemoryDataSourceRegistry(List.of()),
                        provisioner,
                        List.of(),
                        storeProps,
                        mapper,
                        mock(DatasetImportService.class),
                        mock(SchemaGenerationService.class),
                        mock(MdlWorkspaceReader.class));
        when(groupRepository.findById(anyString()))
                .thenAnswer(inv -> Optional.ofNullable(groupsById.get(inv.getArgument(0))));
        when(groupRepository.save(any(DatasetGroupEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        when(knowledgeRepository.save(any(DatasetKnowledgeEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));
    }

    // ------------------------------------------------------------------ fixtures

    private DatasetGroupEntity registerGroup(String groupId, String mdlState) {
        DatasetGroupEntity g = new DatasetGroupEntity(groupId, "owner", "KB " + groupId, null);
        g.setMdlState(mdlState);
        g.setCreatedAt(Instant.parse("2026-09-01T00:00:00Z"));
        g.setUpdatedAt(Instant.parse("2026-09-01T00:00:00Z"));
        groupsById.put(groupId, g);
        return g;
    }

    private DatasetEntity dataset(String id, String groupId) {
        DatasetEntity d = new DatasetEntity();
        d.setId(id);
        d.setGroupId(groupId);
        d.setOwnerId("owner");
        d.setName("表 " + id);
        d.setTableName("ds_" + id);
        try {
            d.setColumnSchemaJson(
                    mapper.writeValueAsString(
                            List.of(
                                    new ColumnSchema("c1", "c1", "varchar", true, "旧描述"),
                                    new ColumnSchema("c2", "c2", "bigint", true, null))));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        return d;
    }

    // ------------------------------------------------------------------ markMdlDirty semantics

    @Test
    void markMdlDirtyFlipsPublishedGroupToDirty() {
        DatasetGroupEntity g = registerGroup("g1", "PUBLISHED");
        g.setMdlVersion(3);

        service.markMdlDirty("g1");

        assertEquals("DIRTY", g.getMdlState());
        assertEquals("KB g1", g.getName());
        assertTrue(g.getUpdatedAt().isAfter(g.getCreatedAt()));
        assertEquals(3, g.getMdlVersion(), "the version only moves on publish");
        verify(groupRepository).save(g);
    }

    @Test
    void markMdlDirtyLeavesNoneAndDirtyGroupsAlone() {
        registerGroup("none", "NONE");
        DatasetGroupEntity dirty = registerGroup("dirty", "DIRTY");

        service.markMdlDirty("none");
        service.markMdlDirty("dirty");

        assertEquals("NONE", groupsById.get("none").getMdlState());
        assertEquals("DIRTY", dirty.getMdlState());
        assertEquals(
                Instant.parse("2026-09-01T00:00:00Z"),
                dirty.getUpdatedAt(),
                "an already-dirty group must not be rewritten");
        verify(groupRepository, never()).save(any(DatasetGroupEntity.class));
    }

    @Test
    void markMdlDirtyNoOpsOnBlankOrOrphanIds() {
        DatasetGroupEntity published = registerGroup("g1", "PUBLISHED");

        service.markMdlDirty(null);
        service.markMdlDirty("  ");
        verify(groupRepository, never()).findById(any());

        service.markMdlDirty("ghost"); // group missing from the repository

        assertEquals("PUBLISHED", published.getMdlState());
        verify(groupRepository, never()).save(any(DatasetGroupEntity.class));
    }

    // ------------------------------------------------------------------ trigger points

    @Test
    void deleteEntityMarksGroupDirty() {
        DatasetGroupEntity g = registerGroup("g1", "PUBLISHED");
        DatasetEntity d = dataset("t1", "g1");

        service.deleteEntity(d);

        verify(provisioner).dropTable("ds_t1");
        verify(repository).delete(d);
        assertEquals("DIRTY", g.getMdlState());
        verify(groupRepository).save(g);
    }

    @Test
    void updateColumnDescriptionsMarksGroupDirty() {
        DatasetGroupEntity g = registerGroup("g1", "PUBLISHED");
        DatasetEntity d = dataset("t1", "g1");
        when(repository.findById("t1")).thenReturn(Optional.of(d));

        service.updateColumnDescriptions(
                "owner", "t1", List.of(new DatasetService.ColumnDescUpdate("c1", "新描述")));

        assertTrue(
                d.getColumnSchemaJson().contains("新描述"),
                "the edited description must land on the dataset");
        assertEquals("DIRTY", g.getMdlState());
        verify(groupRepository).save(g);
    }

    @Test
    void saveKnowledgeMarksGroupDirty() {
        DatasetGroupEntity g = registerGroup("g1", "PUBLISHED");
        when(knowledgeRepository.findById("g1")).thenReturn(Optional.empty());

        service.saveKnowledge("g1", "关系文档内容");

        verify(knowledgeRepository).save(any(DatasetKnowledgeEntity.class));
        assertEquals("DIRTY", g.getMdlState());
        verify(groupRepository).save(g);
    }
}
