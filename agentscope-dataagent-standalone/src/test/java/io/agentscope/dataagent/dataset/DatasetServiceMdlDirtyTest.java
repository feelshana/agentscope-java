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
import io.agentscope.dataagent.web.persistence.jpa.SemanticViewRepository;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Metadata edits preserve published querying; dataset/schema mutations retain the hard gate. */
class DatasetServiceMdlDirtyTest {

    @org.junit.jupiter.api.io.TempDir java.nio.file.Path mdlHome;

    @Test
    void queuedMetadataSaveMergesLatestOtherFieldInsteadOfWritingItsStaleCopy() throws Exception {
        registerGroup("g1", "PUBLISHED");
        var entity = dataset("d1", "g1");
        when(repository.findById("d1")).thenReturn(Optional.of(entity));
        var workspace =
                new MdlWorkspaceService(
                        new WrenProperties("wren", mdlHome.toString(), "dataagent", "mysql", 10),
                        mock(WrenCli.class));
        var entities = mock(jakarta.persistence.EntityManager.class);
        org.mockito.Mockito.doAnswer(
                        ignored -> {
                            entity.setColumnSchemaJson(
                                    mapper.writeValueAsString(
                                            List.of(
                                                    new ColumnSchema(
                                                            "c1", "c1", "varchar", true, "old"),
                                                    new ColumnSchema(
                                                            "c2",
                                                            "c2",
                                                            "bigint",
                                                            true,
                                                            "同事刚保存的说明"))));
                            return null;
                        })
                .when(entities)
                .refresh(entity, jakarta.persistence.LockModeType.PESSIMISTIC_WRITE);
        org.springframework.test.util.ReflectionTestUtils.setField(
                service,
                "metadataDraftSync",
                new DatasetMetadataDraftSync(
                        workspace, mock(KnowledgeBaseAccessService.class), entities));
        service.updateColumnDescriptions(
                "owner", "d1", List.of(new DatasetService.ColumnDescUpdate("c1", "我的新说明")));
        assertEquals("我的新说明", service.readColumns(entity).get(0).description());
        assertEquals("同事刚保存的说明", service.readColumns(entity).get(1).description());
        verify(entities).refresh(entity, jakarta.persistence.LockModeType.PESSIMISTIC_WRITE);
    }

    @Test
    void datasetPageSaveProducesActualDraftDiffWhilePublishedRevisionRemainsUsable()
            throws Exception {
        var group = registerGroup("g1", "PUBLISHED");
        group.setMdlVersion(14);
        var entity = dataset("d1", "g1");
        when(repository.findById("d1")).thenReturn(Optional.of(entity));
        var workspace =
                new MdlWorkspaceService(
                        new WrenProperties("wren", mdlHome.toString(), "dataagent", "mysql", 10),
                        mock(WrenCli.class));
        var access = mock(KnowledgeBaseAccessService.class);
        var sync = new DatasetMetadataDraftSync(workspace, access);
        org.springframework.test.util.ReflectionTestUtils.setField(
                service, "metadataDraftSync", sync);
        var root = workspace.workspaceRoot("g1");
        java.nio.file.Files.createDirectories(root.resolve(".platform"));
        java.nio.file.Files.createDirectories(root.resolve("models/orders"));
        java.nio.file.Files.writeString(
                root.resolve(".platform/datasets.json"), "{\"d1\":\"orders\"}");
        var model = root.resolve("models/orders/metadata.yml");
        java.nio.file.Files.writeString(
                model,
                "columns:\n"
                        + "  - name: c1\n"
                        + "    type: VARCHAR\n"
                        + "    properties:\n"
                        + "      description: old\n");
        service.updateColumnDescriptions(
                "owner", "d1", List.of(new DatasetService.ColumnDescUpdate("c1", "资料页新说明")));
        var yaml = new ObjectMapper(new com.fasterxml.jackson.dataformat.yaml.YAMLFactory());
        assertEquals(
                "资料页新说明",
                yaml.readTree(model.toFile()).at("/columns/0/properties/description").asText());
        service.updateDescription("owner", "d1", "资料页新表说明");
        assertEquals(
                "资料页新表说明", yaml.readTree(model.toFile()).at("/properties/description").asText());
        assertEquals(14, group.getMdlVersion());
        verify(groupRepository, never()).requireModeling(anyString());
        assertEquals("DIRTY", group.getMdlState());
    }

    private DatasetRepository repository;
    private DatasetGroupRepository groupRepository;
    private DatasetKnowledgeRepository knowledgeRepository;
    private TableProvisioner provisioner;
    private ExternalDataSourceRepository externalSources;
    private DataSourceIntrospector introspector;
    private DatasetService service;

    private final ObjectMapper mapper = new ObjectMapper();
    private final Map<String, DatasetGroupEntity> groupsById = new HashMap<>();

    @BeforeEach
    void setUp() {
        repository = mock(DatasetRepository.class);
        groupRepository = mock(DatasetGroupRepository.class);
        knowledgeRepository = mock(DatasetKnowledgeRepository.class);
        provisioner = mock(TableProvisioner.class);
        externalSources = mock(ExternalDataSourceRepository.class);
        introspector = mock(DataSourceIntrospector.class);
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
                        mock(SemanticViewRepository.class),
                        mock(SemanticBusinessRuleRepository.class),
                        externalSources,
                        introspector,
                        mock(DatasetRelationRepository.class),
                        new InMemoryDataSourceRegistry(List.of()),
                        provisioner,
                        List.of(),
                        storeProps,
                        mapper,
                        mock(DatasetImportService.class),
                        mock(SchemaGenerationService.class),
                        mock(MdlWorkspaceReader.class));
        var access = mock(KnowledgeBaseAccessService.class);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "access", access);
        when(access.requireManage(
                        org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.anyString()))
                .thenAnswer(inv -> groupRepository.findById(inv.getArgument(1)).orElseThrow());

        when(groupRepository.findById(anyString()))
                .thenAnswer(inv -> Optional.ofNullable(groupsById.get(inv.getArgument(0))));
        org.mockito.Mockito.doAnswer(
                        inv -> {
                            DatasetGroupEntity g = groupsById.get(inv.getArgument(0));
                            if (g != null) {
                                g.setMdlState("DIRTY");
                                g.setUpdatedAt(Instant.now());
                                org.springframework.test.util.ReflectionTestUtils.setField(
                                        g, "modelingRevision", g.getModelingRevision() + 1);
                            }
                            return null;
                        })
                .when(groupRepository)
                .requireModeling(anyString());
        org.mockito.Mockito.doAnswer(
                        inv -> {
                            DatasetGroupEntity g = groupsById.get(inv.getArgument(0));
                            if (g != null) g.setMdlState("DIRTY");
                            return null;
                        })
                .when(groupRepository)
                .markDraftChanged(anyString());
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
        verify(groupRepository).requireModeling(g.getId());
    }

    @Test
    void everyMutationRequiresANewPublicationEvenWhenAlreadyDirty() {
        registerGroup("none", "NONE");
        DatasetGroupEntity dirty = registerGroup("dirty", "DIRTY");

        service.markMdlDirty("none");
        service.markMdlDirty("dirty");

        assertEquals("DIRTY", groupsById.get("none").getMdlState());
        assertEquals("DIRTY", dirty.getMdlState());
        assertEquals(1, dirty.getModelingRevision());
        service.markMdlDirty("dirty");
        assertEquals(2, dirty.getModelingRevision());
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
        verify(groupRepository).requireModeling(g.getId());
    }

    @Test
    void updateColumnDescriptionsLeavesPublishedQueryAvailable() {
        DatasetGroupEntity g = registerGroup("g1", "PUBLISHED");
        DatasetEntity d = dataset("t1", "g1");
        when(repository.findById("t1")).thenReturn(Optional.of(d));

        service.updateColumnDescriptions(
                "owner", "t1", List.of(new DatasetService.ColumnDescUpdate("c1", "新描述")));

        assertTrue(
                d.getColumnSchemaJson().contains("新描述"),
                "the edited description must land on the dataset");
        assertEquals("DIRTY", g.getMdlState());
        verify(groupRepository).markDraftChanged(g.getId());
        verify(groupRepository, never()).requireModeling(anyString());
        org.junit.jupiter.api.Assertions.assertFalse(g.isModelingPending());
    }

    @Test
    void fieldLabelsRemainSeparateFromPhysicalNamesAndLegacyEditsKeepTheLabel() {
        DatasetGroupEntity g = registerGroup("g1", "PUBLISHED");
        g.setMdlVersion(3);
        DatasetEntity d = dataset("t1", "g1");
        when(repository.findById("t1")).thenReturn(Optional.of(d));
        service.updateColumnDescriptions(
                "owner", "t1", List.of(new DatasetService.ColumnDescUpdate("c1", "业务口径", "销售金额")));
        var c = service.readColumns(d).get(0);
        assertEquals("c1", c.name());
        assertEquals("销售金额", c.displayName());
        assertEquals("业务口径", c.description());
        assertTrue(c.userDescribed());
        assertEquals(3, g.getMdlVersion());
        service.updateColumnDescriptions(
                "owner", "t1", List.of(new DatasetService.ColumnDescUpdate("c1", "仅改说明")));
        assertEquals("销售金额", service.readColumns(d).get(0).displayName());
        service.updateColumnDescriptions(
                "owner", "t1", List.of(new DatasetService.ColumnDescUpdate("c1", "清空名称", "")));
        assertEquals("", service.readColumns(d).get(0).displayName());
        org.junit.jupiter.api.Assertions.assertThrows(
                DatasetException.class,
                () ->
                        service.updateColumnDescriptions(
                                "owner",
                                "t1",
                                List.of(
                                        new DatasetService.ColumnDescUpdate(
                                                "missing", "说明", "错误"))));
    }

    @Test
    void saveKnowledgeOnlyChangesDraft() {
        DatasetGroupEntity g = registerGroup("g1", "PUBLISHED");
        when(knowledgeRepository.findById("g1")).thenReturn(Optional.empty());

        service.saveKnowledge("g1", "关系文档内容");

        verify(knowledgeRepository).save(any(DatasetKnowledgeEntity.class));
        assertEquals("DIRTY", g.getMdlState());
        verify(groupRepository).markDraftChanged(g.getId());
        verify(groupRepository, never()).requireModeling(anyString());
    }

    @Test
    void sharedQueryUsesVersion14AfterDescriptionAndLabelEdits() {
        DatasetGroupEntity g = registerGroup("g1", "PUBLISHED");
        g.setMdlVersion(14);
        DatasetEntity d = dataset("t1", "g1");
        when(repository.findById("t1")).thenReturn(Optional.of(d));
        var groups = mock(DatasetGroupService.class);
        when(groups.queryGroups("colleague", List.of("g1"))).thenReturn(List.of(g));
        var query =
                new WrenQueryAccess(
                        groups,
                        new io.agentscope.dataagent.web.session.ConversationScopeRegistry());
        var context =
                io.agentscope.core.agent.RuntimeContext.builder()
                        .userId("colleague")
                        .sessionId("shared-query")
                        .put(DatasetScope.class, new DatasetScope("colleague", List.of("g1")))
                        .build();

        service.updateColumnDescriptions(
                "owner", "t1", List.of(new DatasetService.ColumnDescUpdate("c1", "新口径说明", "中文名")));
        service.updateDescription("owner", "t1", "新表说明");
        service.saveKnowledge("g1", "新文档");

        assertEquals(14, query.resolve(context, "g1").getMdlVersion());
        assertEquals("DIRTY", g.getMdlState());
        assertEquals(0, g.getModelingRevision());
        verify(provisioner, never()).dropTable(anyString());
        verify(groupRepository, never()).requireModeling(anyString());
    }

    @Test
    void metadataEditsNeverClearAnEarlierPhysicalChangeGate() {
        DatasetGroupEntity g = registerGroup("g1", "PUBLISHED");
        g.setMdlVersion(14);
        DatasetEntity d = dataset("t1", "g1");
        when(repository.findById("t1")).thenReturn(Optional.of(d));
        service.markMdlDirty("g1");
        service.updateColumnDescriptions(
                "owner", "t1", List.of(new DatasetService.ColumnDescUpdate("c1", "说明")));
        assertTrue(g.isModelingPending());
        assertEquals(1, g.getModelingRevision());
        assertEquals(14, g.getMdlVersion());
        verify(groupRepository, never())
                .coverModelingRevision(anyString(), org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    void editingDescriptionsDoesNotPublishANeverPublishedKnowledgeBase() {
        var g = registerGroup("g1", "NONE");
        var d = dataset("t1", "g1");
        when(repository.findById("t1")).thenReturn(Optional.of(d));
        service.updateColumnDescriptions(
                "owner", "t1", List.of(new DatasetService.ColumnDescUpdate("c1", "说明")));
        var groups = mock(DatasetGroupService.class);
        when(groups.queryGroups("owner", List.of("g1"))).thenReturn(List.of(g));
        var query =
                new WrenQueryAccess(
                        groups,
                        new io.agentscope.dataagent.web.session.ConversationScopeRegistry());
        var context =
                io.agentscope.core.agent.RuntimeContext.builder()
                        .userId("owner")
                        .sessionId("unpublished")
                        .put(DatasetScope.class, new DatasetScope("owner", List.of("g1")))
                        .build();
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> query.resolve(context, "g1"))
                .isInstanceOf(DatasetException.class)
                .hasMessageContaining("尚未发布");
        assertEquals(0, g.getMdlVersion());
    }

    private DatasetEntity sampledDataset(List<DataSourceIntrospector.ColumnInfo> columns)
            throws Exception {
        DatasetEntity d = dataset("t1", "g1");
        d.setOrigin("datasource");
        d.setSchemaName("retail");
        d.setExternalDataSourceId("source");
        when(repository.findById("t1")).thenReturn(Optional.of(d));
        when(repository.lockById("t1")).thenReturn(Optional.of(d));
        var source = new io.agentscope.dataagent.web.persistence.jpa.ExternalDataSourceEntity();
        source.setSampling(true);
        when(externalSources.findById("source")).thenReturn(Optional.of(source));
        when(introspector.listColumns(source, "retail", "ds_t1")).thenReturn(columns);
        var connection = mock(java.sql.Connection.class);
        var statement = mock(java.sql.Statement.class);
        var rows = mock(java.sql.ResultSet.class);
        when(introspector.open(source)).thenReturn(connection);
        when(connection.createStatement()).thenReturn(statement);
        when(statement.executeQuery(anyString())).thenReturn(rows);
        return d;
    }

    @Test
    void refreshingOnlySamplesKeepsPublishedQueriesAvailable() throws Exception {
        var g = registerGroup("g1", "PUBLISHED");
        g.setMdlVersion(14);
        var d =
                sampledDataset(
                        List.of(
                                new DataSourceIntrospector.ColumnInfo("c2", "BIGINT", "新注释"),
                                new DataSourceIntrospector.ColumnInfo("c1", "VARCHAR", "新注释")));
        service.refreshSample("owner", "t1");
        assertEquals("SUCCESS", DatasetSampling.decode(d.getSampleSummaryJson()).status());
        org.junit.jupiter.api.Assertions.assertFalse(g.isModelingPending());
        assertEquals(14, g.getMdlVersion());
        verify(groupRepository, never()).requireModeling(anyString());
    }

    @Test
    void samplingThatObservesRemovedOrRetypedColumnsKeepsTheGate() throws Exception {
        var g = registerGroup("g1", "PUBLISHED");
        var d =
                sampledDataset(
                        List.of(new DataSourceIntrospector.ColumnInfo("c1", "VARCHAR", null)));
        service.refreshSample("owner", "t1");
        assertTrue(g.isModelingPending());
        assertEquals(2, service.readColumns(d).size());
        var source = externalSources.findById("source").orElseThrow();
        when(introspector.listColumns(source, "retail", "ds_t1"))
                .thenReturn(
                        List.of(
                                new DataSourceIntrospector.ColumnInfo("c1", "BIGINT", null),
                                new DataSourceIntrospector.ColumnInfo("c2", "BIGINT", null)));
        service.refreshSample("owner", "t1");
        assertEquals(2, g.getModelingRevision());
    }
}
