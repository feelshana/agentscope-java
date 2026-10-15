/*
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * https://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.dataagent.dataset;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.dataagent.dataset.parser.SchemaGenerationService;
import io.agentscope.dataagent.tools.data.InMemoryDataSourceRegistry;
import io.agentscope.dataagent.web.persistence.jpa.DatasetGroupEntity;
import io.agentscope.dataagent.web.persistence.jpa.DatasetGroupRepository;
import io.agentscope.dataagent.web.persistence.jpa.DatasetKnowledgeRepository;
import io.agentscope.dataagent.web.persistence.jpa.DatasetRelationRepository;
import io.agentscope.dataagent.web.persistence.jpa.DatasetRepository;
import io.agentscope.dataagent.web.persistence.jpa.ExternalDataSourceRepository;
import io.agentscope.dataagent.web.persistence.jpa.SemanticBusinessRuleRepository;
import io.agentscope.dataagent.web.persistence.jpa.SemanticTermRepository;
import io.agentscope.dataagent.web.persistence.jpa.SemanticViewRepository;
import java.io.ByteArrayInputStream;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class DatasetImportRollbackTest {
    private DatasetRepository repository;
    private DatasetImportService importer;
    private DatasetService service;

    @BeforeEach
    void setup() {
        repository = mock(DatasetRepository.class);
        importer = mock(DatasetImportService.class);
        var props = mock(DatasetStoreProperties.class);
        when(props.url()).thenReturn("jdbc:mysql://localhost/data");
        when(props.username()).thenReturn("u");
        when(props.password()).thenReturn("p");
        service =
                new DatasetService(
                        repository,
                        mock(DatasetGroupRepository.class),
                        mock(DatasetKnowledgeRepository.class),
                        mock(SemanticTermRepository.class),
                        mock(SemanticViewRepository.class),
                        mock(SemanticBusinessRuleRepository.class),
                        mock(ExternalDataSourceRepository.class),
                        mock(DataSourceIntrospector.class),
                        mock(DatasetRelationRepository.class),
                        new InMemoryDataSourceRegistry(List.of()),
                        mock(TableProvisioner.class),
                        List.of(),
                        props,
                        new ObjectMapper(),
                        importer,
                        mock(SchemaGenerationService.class),
                        mock(MdlWorkspaceReader.class));
        var access = mock(KnowledgeBaseAccessService.class);
        when(access.requireManage("u", "g"))
                .thenReturn(new DatasetGroupEntity("g", "u", "KB", null));
        ReflectionTestUtils.setField(service, "access", access);
        when(importer.importFileForGroup(any(), any(), any(), any(), any(), any()))
                .thenReturn(
                        new DatasetImportService.ImportResult(List.of(), 1, "sample", "ds_fresh"));
    }

    private void ingest() {
        service.ingest(
                "u", "g", "sample", null, new ByteArrayInputStream(new byte[0]), "sample.csv");
    }

    @AfterEach
    void clearTransaction() {
        if (TransactionSynchronizationManager.isSynchronizationActive())
            TransactionSynchronizationManager.clearSynchronization();
    }

    @Test
    void metadataFailureRemovesOnlyNewTable() {
        when(repository.save(any())).thenThrow(new IllegalStateException("metadata failed"));
        assertThatThrownBy(this::ingest).hasMessage("metadata failed");
        verify(importer).cleanupFailedImport("g", "ds_fresh");
    }

    @Test
    void transactionRollbackCleansPhysicalTable() {
        TransactionSynchronizationManager.initSynchronization();
        ingest();
        verify(importer, never()).cleanupFailedImport(any(), any());
        TransactionSynchronizationManager.getSynchronizations()
                .forEach(
                        sync ->
                                sync.afterCompletion(
                                        TransactionSynchronization.STATUS_ROLLED_BACK));
        verify(importer).cleanupFailedImport("g", "ds_fresh");
    }

    @Test
    void transactionCommitPreservesPhysicalTable() {
        TransactionSynchronizationManager.initSynchronization();
        ingest();
        TransactionSynchronizationManager.getSynchronizations()
                .forEach(sync -> sync.afterCompletion(TransactionSynchronization.STATUS_COMMITTED));
        verify(importer, never()).cleanupFailedImport(any(), any());
    }

    @Test
    void cleanupFailureRetainsActionableMessage() {
        when(repository.save(any())).thenThrow(new IllegalStateException("metadata failed"));
        doThrow(new DatasetException("drop failed"))
                .when(importer)
                .cleanupFailedImport("g", "ds_fresh");
        assertThatThrownBy(this::ingest).hasMessageContaining("临时表待清理：ds_fresh");
    }
}
