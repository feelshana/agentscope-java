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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.dataagent.dataset.parser.SchemaGenerationService;
import io.agentscope.dataagent.tools.data.InMemoryDataSourceRegistry;
import io.agentscope.dataagent.web.persistence.jpa.DatasetEntity;
import io.agentscope.dataagent.web.persistence.jpa.DatasetGroupEntity;
import io.agentscope.dataagent.web.persistence.jpa.DatasetGroupRepository;
import io.agentscope.dataagent.web.persistence.jpa.DatasetKnowledgeRepository;
import io.agentscope.dataagent.web.persistence.jpa.DatasetRelationRepository;
import io.agentscope.dataagent.web.persistence.jpa.DatasetRepository;
import io.agentscope.dataagent.web.persistence.jpa.ExternalDataSourceEntity;
import io.agentscope.dataagent.web.persistence.jpa.ExternalDataSourceRepository;
import io.agentscope.dataagent.web.persistence.jpa.SemanticBusinessRuleRepository;
import io.agentscope.dataagent.web.persistence.jpa.SemanticViewRepository;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Locks the single-Wren-source and all-or-nothing external table association rules. */
class DatasetServiceSourceCombinationTest {

    private DatasetRepository repository;
    private ExternalDataSourceRepository externalSources;
    private DataSourceIntrospector introspector;
    private InMemoryDataSourceRegistry registry;
    private DatasetService service;
    private ExternalDataSourceEntity source;

    @BeforeEach
    void setUp() {
        repository = mock(DatasetRepository.class);
        DatasetGroupRepository groupRepository = mock(DatasetGroupRepository.class);
        externalSources = mock(ExternalDataSourceRepository.class);
        introspector = mock(DataSourceIntrospector.class);
        registry = new InMemoryDataSourceRegistry(List.of());
        DatasetStoreProperties storeProps = mock(DatasetStoreProperties.class);
        when(storeProps.url()).thenReturn("jdbc:mysql://localhost:3306/data_agent");
        when(storeProps.username()).thenReturn("dataagent");
        when(storeProps.password()).thenReturn("secret");

        DatasetGroupEntity group = new DatasetGroupEntity("group-1", "alice", "零售分析", null);
        source =
                new ExternalDataSourceEntity(
                        "source-1",
                        "alice",
                        "零售库",
                        "mysql",
                        "jdbc:mysql://localhost:3306/retail",
                        "dataagent",
                        "secret",
                        false);
        when(groupRepository.findById("group-1")).thenReturn(Optional.of(group));
        when(externalSources.findById("source-1")).thenReturn(Optional.of(source));
        when(repository.findByGroupId("group-1")).thenReturn(List.of());
        when(repository.save(any(DatasetEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        service =
                new DatasetService(
                        repository,
                        groupRepository,
                        mock(DatasetKnowledgeRepository.class),
                        mock(SemanticViewRepository.class),
                        mock(SemanticBusinessRuleRepository.class),
                        externalSources,
                        introspector,
                        mock(DatasetRelationRepository.class),
                        registry,
                        mock(TableProvisioner.class),
                        List.of(),
                        storeProps,
                        new ObjectMapper(),
                        mock(DatasetImportService.class),
                        new SchemaGenerationService(Optional.empty()),
                        mock(MdlWorkspaceReader.class));
        var access = mock(KnowledgeBaseAccessService.class);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "access", access);
        when(access.requireManage(
                        org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.anyString()))
                .thenAnswer(inv -> groupRepository.findById(inv.getArgument(1)).orElseThrow());
    }

    /** spec105: anyone may associate the built-in source, but only its demo schema. */
    @Test
    void builtinSourceIsAssociatedOnlyFromTheDemoSchema() {
        source.setOwnerId(DemoDataService.OWNER);
        when(introspector.validatedUrl(source)).thenReturn(source.getJdbcUrl());
        when(introspector.listColumns(source, DemoDataService.SCHEMA, "report_visits"))
                .thenReturn(List.of(new DataSourceIntrospector.ColumnInfo("id", "BIGINT", "")));

        assertThat(
                        service.associateTables(
                                "alice",
                                "group-1",
                                "source-1",
                                DemoDataService.SCHEMA,
                                List.of("report_visits"),
                                false))
                .hasSize(1);
        assertThatThrownBy(
                        () ->
                                service.associateTables(
                                        "alice",
                                        "group-1",
                                        "source-1",
                                        "mysql",
                                        List.of("user"),
                                        false))
                .isInstanceOf(DatasetException.class);
        verify(introspector, never()).listColumns(source, "mysql", "user");
    }

    @Test
    void anotherUsersSourceCannotBeAssociated() {
        source.setOwnerId("bob");
        assertThatThrownBy(
                        () ->
                                service.associateTables(
                                        "alice",
                                        "group-1",
                                        "source-1",
                                        "retail",
                                        List.of("orders"),
                                        false))
                .isInstanceOf(DatasetException.class);
        verify(introspector, never()).listColumns(any(), any(), any());
    }

    @Test
    void rejectsNonMysqlSourceBeforeWriting() {
        source.setKind("postgresql");

        assertThatThrownBy(
                        () ->
                                service.associateTables(
                                        "alice",
                                        "group-1",
                                        "source-1",
                                        "public",
                                        List.of("orders"),
                                        false))
                .isInstanceOf(DatasetException.class)
                .hasMessageContaining("仅支持")
                .hasMessageContaining("MySQL");

        verify(repository, never()).save(any());
        verify(introspector, never()).listColumns(any(), any(), any());
        assertThat(registry.list()).isEmpty();
    }

    @Test
    void rejectsUploadedAndExternalTablesCombinationBeforeWriting() {
        DatasetEntity uploaded = new DatasetEntity();
        uploaded.setOrigin("upload");
        when(repository.findByGroupId("group-1")).thenReturn(List.of(uploaded));

        assertThatThrownBy(
                        () ->
                                service.associateTables(
                                        "alice",
                                        "group-1",
                                        "source-1",
                                        "retail",
                                        List.of("orders"),
                                        false))
                .isInstanceOf(DatasetException.class)
                .hasMessageContaining("已包含上传数据集");

        verify(repository, never()).save(any());
        verify(introspector, never()).listColumns(any(), any(), any());
    }

    @Test
    void rejectsDifferentExternalSourcesBeforeWriting() {
        DatasetEntity existing = new DatasetEntity();
        existing.setOrigin("datasource");
        existing.setExternalDataSourceId("source-2");
        when(repository.findByGroupId("group-1")).thenReturn(List.of(existing));

        assertThatThrownBy(
                        () ->
                                service.associateTables(
                                        "alice",
                                        "group-1",
                                        "source-1",
                                        "retail",
                                        List.of("orders"),
                                        false))
                .isInstanceOf(DatasetException.class)
                .hasMessageContaining("同一个外部数据源");

        verify(repository, never()).save(any());
        verify(introspector, never()).listColumns(any(), any(), any());
    }

    @Test
    void rejectsDuplicateRequestBeforeIntrospectionOrWriting() {
        assertThatThrownBy(
                        () ->
                                service.associateTables(
                                        "alice",
                                        "group-1",
                                        "source-1",
                                        "retail",
                                        List.of("orders", "orders"),
                                        false))
                .isInstanceOf(DatasetException.class)
                .hasMessageContaining("重复关联");

        verify(repository, never()).save(any());
        verify(introspector, never()).listColumns(any(), any(), any());
        assertThat(registry.list()).isEmpty();
    }

    @Test
    void metadataFailureOnLaterTableLeavesNoPartialAssociation() {
        when(introspector.listColumns(source, "retail", "orders"))
                .thenReturn(
                        List.of(
                                new DataSourceIntrospector.ColumnInfo(
                                        "order_id", "BIGINT", "订单ID")));
        when(introspector.listColumns(source, "retail", "customers"))
                .thenThrow(new DatasetException("无法读取客户表"));

        assertThatThrownBy(
                        () ->
                                service.associateTables(
                                        "alice",
                                        "group-1",
                                        "source-1",
                                        "retail",
                                        List.of("orders", "customers"),
                                        false))
                .isInstanceOf(DatasetException.class)
                .hasMessageContaining("无法读取客户表");

        verify(repository, never()).save(any());
        assertThat(registry.list()).isEmpty();
    }

    @Test
    void associatesPreparedMysqlTablesAsOneBatch() {
        when(introspector.validatedUrl(source))
                .thenReturn(
                        new ExternalDataSourcePolicy("localhost:3306")
                                .normalize(source.getKind(), source.getJdbcUrl()));
        when(introspector.listColumns(any(), any(), any()))
                .thenReturn(List.of(new DataSourceIntrospector.ColumnInfo("id", "BIGINT", "主键")));

        List<DatasetEntity> created =
                service.associateTables(
                        "alice",
                        "group-1",
                        "source-1",
                        "retail",
                        List.of("orders", "customers"),
                        false);

        assertThat(created)
                .extracting(DatasetEntity::getName)
                .containsExactly("orders", "customers");
        assertThat(registry.list()).hasSize(2);
        verify(introspector, never()).countRows(any(), any(), any());
        assertThat(created)
                .allSatisfy(
                        d ->
                                assertThat(
                                                DatasetSampling.decode(d.getSampleSummaryJson())
                                                        .status())
                                        .isEqualTo("DISABLED"));
        verify(repository, org.mockito.Mockito.times(2)).save(any(DatasetEntity.class));
    }

    @Test
    void refreshChecksManagementAgainAfterWaitingForLock() {
        var dataset = new DatasetEntity();
        dataset.setId("dataset-1");
        dataset.setGroupId("group-1");
        dataset.setOrigin("datasource");
        when(repository.findById("dataset-1")).thenReturn(Optional.of(dataset));
        when(repository.lockById("dataset-1")).thenReturn(Optional.of(dataset));
        var access =
                (KnowledgeBaseAccessService)
                        org.springframework.test.util.ReflectionTestUtils.getField(
                                service, "access");
        when(access.requireManage("alice", "group-1"))
                .thenReturn(new DatasetGroupEntity("group-1", "alice", "test", null))
                .thenThrow(new DatasetException("权限已撤销", 403));
        assertThatThrownBy(() -> service.refreshSample("alice", "dataset-1"))
                .isInstanceOf(DatasetException.class)
                .hasMessageContaining("权限已撤销");
        verify(introspector, never()).listColumns(any(), any(), any());
    }

    @Test
    void refreshFailurePreservesSuccessfulSample() throws Exception {
        var dataset = new DatasetEntity();
        dataset.setId("dataset-1");
        dataset.setGroupId("group-1");
        dataset.setOrigin("datasource");
        dataset.setSchemaName("retail");
        dataset.setTableName("orders");
        dataset.setExternalDataSourceId("source-1");
        String previous = DatasetSampling.encode(DatasetSampling.empty("SUCCESS"));
        dataset.setSampleSummaryJson(previous);
        source.setSampling(true);
        when(repository.findById("dataset-1")).thenReturn(Optional.of(dataset));
        when(repository.lockById("dataset-1")).thenReturn(Optional.of(dataset));
        when(introspector.listColumns(any(), any(), any()))
                .thenReturn(List.of(new DataSourceIntrospector.ColumnInfo("id", "BIGINT", "")));
        when(introspector.open(source)).thenThrow(new java.sql.SQLException("timeout"));
        assertThatThrownBy(() -> service.refreshSample("alice", "dataset-1"))
                .isInstanceOf(DatasetException.class)
                .hasMessageContaining("保留上次");
        assertThat(dataset.getSampleSummaryJson()).isEqualTo(previous);
        verify(repository, never()).save(any());
    }
}
