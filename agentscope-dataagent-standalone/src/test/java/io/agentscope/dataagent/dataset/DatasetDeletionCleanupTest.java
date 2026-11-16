package io.agentscope.dataagent.dataset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.dataagent.tools.data.InMemoryDataSourceRegistry;
import io.agentscope.dataagent.web.persistence.jpa.DatasetEntity;
import io.agentscope.dataagent.web.persistence.jpa.DatasetRepository;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DatasetDeletionCleanupTest {
    @TempDir Path home;
    final DatasetRepository datasets = mock(DatasetRepository.class);
    final InMemoryDataSourceRegistry registry = mock(InMemoryDataSourceRegistry.class);
    final TableProvisioner tables = mock(TableProvisioner.class);
    final KnowledgeBaseOperations operations = mock(KnowledgeBaseOperations.class);

    DatasetDeletionCleanup cleanup() {
        return new DatasetDeletionCleanup(
                datasets,
                registry,
                tables,
                new WrenProperties("wren", home.toString(), "dataagent", "mysql", 10),
                new ObjectMapper(),
                operations,
                null);
    }

    DatasetEntity entity(boolean external) {
        var entity = new DatasetEntity();
        entity.setId("d1");
        entity.setGroupId("g1");
        entity.setTableName(external ? "customer_orders" : "ds_orders");
        entity.setOrigin(external ? "datasource" : "upload");
        return entity;
    }

    Path journal() {
        return home.resolve(".dataset-deletions/d1.json");
    }

    @Test
    void restartCancelsUncommittedDeletionWithoutTouchingTableOrRegistry() {
        cleanup().prepare(entity(false));
        when(datasets.findById("d1")).thenReturn(Optional.of(entity(false)));
        cleanup().retryPending();
        assertThat(journal()).doesNotExist();
        verifyNoInteractions(tables, registry, operations);
    }

    @Test
    void failedDropSurvivesRestartAndSuccessfulRetryRemovesIntent() {
        var first = cleanup();
        var intent = first.prepare(entity(false));
        doThrow(new IllegalStateException("offline"))
                .doNothing()
                .when(tables)
                .dropTableStrict("ds_orders");
        first.complete(intent);
        assertThat(journal()).exists();
        verifyNoInteractions(operations);
        cleanup().retryPending();
        assertThat(journal()).doesNotExist();
        verify(tables, times(2)).dropTableStrict("ds_orders");
        verify(operations).forgetImportedTable("g1", "ds_orders");
    }

    @Test
    void externalAssociationNeverDropsSourceTable() {
        var cleanup = cleanup();
        cleanup.complete(cleanup.prepare(entity(true)));
        verify(registry).remove("d1");
        verifyNoInteractions(tables, operations);
        assertThat(journal()).doesNotExist();
    }

    @Test
    void timerCannotConsumeInFlightIntentBeforeTransactionCompletes() {
        var cleanup = cleanup();
        var intent = cleanup.prepare(entity(false));
        cleanup.retryPending();
        assertThat(journal()).exists();
        verifyNoInteractions(datasets, tables, registry);
        cleanup.cancel(intent);
        assertThat(journal()).doesNotExist();
    }

    @Test
    void databaseOutageRetainsIntentAndCannotDropTable() {
        cleanup().prepare(entity(false));
        when(datasets.findById("d1")).thenThrow(new IllegalStateException("offline"));
        cleanup().retryPending();
        assertThat(journal()).exists();
        verifyNoInteractions(tables, registry, operations);
    }

    @Test
    void malformedJournalCannotAuthorizeTableDrop() throws Exception {
        Files.createDirectories(journal().getParent());
        Files.writeString(
                journal(),
                "{\"format\":1,\"datasetId\":\"d1\",\"groupId\":\"g1\",\"table\":\"users\",\"uploaded\":true}");
        cleanup().retryPending();
        assertThat(journal()).exists();
        verifyNoInteractions(datasets, tables, registry, operations);
    }
}
