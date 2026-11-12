package io.agentscope.dataagent.dataset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.dataagent.web.DataAgentApp;
import io.agentscope.dataagent.web.persistence.jpa.DatasetGroupEntity;
import io.agentscope.dataagent.web.persistence.jpa.DatasetGroupRepository;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@DataJpaTest(properties = {"spring.sql.init.mode=never", "spring.jpa.show-sql=false"})
@ContextConfiguration(classes = DataAgentApp.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Tag("integration")
class MdlPublicationTransactionTest {
    @Autowired DatasetGroupRepository groups;
    @Autowired PlatformTransactionManager manager;
    @Autowired jakarta.persistence.EntityManager entities;
    @Autowired io.agentscope.dataagent.web.persistence.jpa.DatasetRepository datasets;
    @TempDir Path root;

    private DatasetGroupEntity group() {
        var group =
                new DatasetGroupEntity(
                        UUID.randomUUID().toString(),
                        "alice-" + UUID.randomUUID(),
                        "edited name",
                        "");
        return groups.saveAndFlush(group);
    }

    @Test
    void atomicPublicationPreservesConcurrentMetadataAndRejectsAnOldVersion() {
        var group = group();
        groups.requireModeling(group.getId());
        assertThat(groups.commitPublication(group.getId(), 0, 1, Instant.now(), 0)).isEqualTo(1);
        var actual = groups.findById(group.getId()).orElseThrow();
        assertThat(actual.getName()).isEqualTo("edited name");
        assertThat(actual.getModelingRevision()).isEqualTo(1);
        assertThat(actual.getPublishedModelingRevision()).isZero();
        assertThat(actual.getMdlVersion()).isEqualTo(1);
        assertThat(groups.commitPublication(group.getId(), 0, 1, Instant.now(), 0)).isZero();
    }

    @Test
    void recoveryVersionReadWaitsForAnUncommittedMetadataWriter() throws Exception {
        var group = group();
        var updated = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        var reading = new java.util.concurrent.CountDownLatch(1);
        var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var writer =
                    pool.submit(
                            () ->
                                    new TransactionTemplate(manager)
                                            .executeWithoutResult(
                                                    status -> {
                                                        groups.commitPublication(
                                                                group.getId(),
                                                                0,
                                                                1,
                                                                Instant.now(),
                                                                0);
                                                        updated.countDown();
                                                        try {
                                                            if (!release.await(
                                                                    5,
                                                                    java.util.concurrent.TimeUnit
                                                                            .SECONDS))
                                                                throw new IllegalStateException(
                                                                        "writer timed out");
                                                        } catch (InterruptedException e) {
                                                            Thread.currentThread().interrupt();
                                                            throw new IllegalStateException(e);
                                                        }
                                                    }));
            assertThat(updated.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            var reader =
                    pool.submit(
                            () ->
                                    new TransactionTemplate(manager)
                                            .execute(
                                                    status -> {
                                                        reading.countDown();
                                                        return groups.findForPublicationRecovery(
                                                                        group.getId())
                                                                .orElseThrow()
                                                                .getMdlVersion();
                                                    }));
            assertThat(reading.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            org.junit.jupiter.api.Assertions.assertThrows(
                    java.util.concurrent.TimeoutException.class,
                    () -> reader.get(150, java.util.concurrent.TimeUnit.MILLISECONDS));
            release.countDown();
            writer.get(5, java.util.concurrent.TimeUnit.SECONDS);
            assertThat(reader.get(5, java.util.concurrent.TimeUnit.SECONDS)).isEqualTo(1);
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    void failureBookkeepingDoesNotReplacePublishedOrModelingRevisions() {
        var group = group();
        groups.requireModeling(group.getId());
        groups.commitPublication(group.getId(), 0, 1, Instant.now(), 1);
        groups.recordPublicationFailure(group.getId(), "synthetic failure", Instant.now());
        var actual = groups.findById(group.getId()).orElseThrow();
        assertThat(actual.getMdlVersion()).isEqualTo(1);
        assertThat(actual.getModelingRevision()).isEqualTo(1);
        assertThat(actual.getPublishedModelingRevision()).isEqualTo(1);
        assertThat(actual.getMdlState()).isEqualTo("PUBLISHED");
        assertThat(actual.getName()).isEqualTo("edited name");
    }

    @Test
    void rolledBackDatabaseTransactionRestoresTheOriginalSnapshot() throws Exception {
        var group = group();
        Files.createDirectories(root.resolve("published"));
        Files.writeString(root.resolve("published/data"), "old");
        Files.writeString(root.resolve("mdl.json"), "{\"version\":0}");
        Files.createDirectories(root.resolve("published.next"));
        Files.writeString(root.resolve("published.next/data"), "new");
        Files.writeString(root.resolve("mdl.json.next"), "{\"version\":1}");
        var tx = new TransactionTemplate(manager);
        assertThatThrownBy(
                        () ->
                                new MdlPublicationStore(new ObjectMapper())
                                        .install(
                                                root,
                                                root.resolve("published.next"),
                                                root.resolve("mdl.json.next"),
                                                0,
                                                () ->
                                                        tx.executeWithoutResult(
                                                                status -> {
                                                                    groups.commitPublication(
                                                                            group.getId(),
                                                                            0,
                                                                            1,
                                                                            Instant.now(),
                                                                            0);
                                                                    throw new IllegalStateException(
                                                                            "rollback after SQL"
                                                                                    + " update");
                                                                }),
                                                () ->
                                                        groups.findById(group.getId())
                                                                .orElseThrow()
                                                                .getMdlVersion()))
                .isInstanceOf(DatasetException.class);
        assertThat(groups.findById(group.getId()).orElseThrow().getMdlVersion()).isZero();
        assertThat(Files.readString(root.resolve("published/data"))).isEqualTo("old");
        assertThat(root.resolve(MdlPublicationStore.JOURNAL)).doesNotExist();
    }

    @Test
    void wholeKnowledgeBaseDeletionRetainsItsIntentAndResumesAfterFailedDdl() throws Exception {
        var group = group();
        var props = new WrenProperties("wren", root.toString(), "dataagent", "mysql", 10);
        var operations = new KnowledgeBaseOperations(props);
        operations.recordImportedTable(group.getId(), "ds_orphan");
        Files.createDirectories(props.groupRoot(group.getId()));
        Files.writeString(props.groupRoot(group.getId()).resolve("synthetic"), "fixture");
        var tables = org.mockito.Mockito.mock(TableProvisioner.class);
        org.mockito.Mockito.doThrow(new IllegalStateException("synthetic offline"))
                .doNothing()
                .when(tables)
                .dropTableStrict("ds_orphan");
        var wren =
                org.mockito.Mockito.mock(
                        io.agentscope.dataagent.runtime.wren.WrenQueryGateway.class);
        var registry =
                org.mockito.Mockito.mock(
                        io.agentscope.dataagent.tools.data.InMemoryDataSourceRegistry.class);
        var first =
                new KnowledgeBaseDeletionService(
                        operations,
                        props,
                        groups,
                        datasets,
                        tables,
                        wren,
                        registry,
                        new ObjectMapper(),
                        manager);
        org.springframework.test.util.ReflectionTestUtils.setField(
                first, "entityManager", entities);
        assertThatThrownBy(() -> first.delete(group.getOwnerId(), group.getId()))
                .isInstanceOf(DatasetException.class);
        assertThat(groups.findById(group.getId()).orElseThrow().getMdlState())
                .isEqualTo("DELETING");
        assertThat(operations.journal(group.getId())).exists();
        var restarted =
                new KnowledgeBaseDeletionService(
                        new KnowledgeBaseOperations(props),
                        props,
                        groups,
                        datasets,
                        tables,
                        wren,
                        registry,
                        new ObjectMapper(),
                        manager);
        org.springframework.test.util.ReflectionTestUtils.setField(
                restarted, "entityManager", entities);
        restarted.retryPending();
        assertThat(groups.existsById(group.getId())).isFalse();
        var journal = new ObjectMapper().readTree(operations.journal(group.getId()).toFile());
        assertThat(journal.path("complete").asBoolean()).isTrue();
        assertThat(props.groupRoot(group.getId())).doesNotExist();
        org.mockito.Mockito.verify(tables, org.mockito.Mockito.times(2))
                .dropTableStrict("ds_orphan");
        first.stopRetries();
        restarted.stopRetries();
    }

    @Test
    void datasetDeletionRollbackPreservesMetadataAndPhysicalResource() {
        deletionTransaction(true);
    }

    @Test
    void datasetDeletionCommitCleansPhysicalResourceOnlyAfterMetadataCommit() {
        deletionTransaction(false);
    }

    private void deletionTransaction(boolean rollback) {
        var group = group();
        var entity = new io.agentscope.dataagent.web.persistence.jpa.DatasetEntity();
        entity.setId(UUID.randomUUID().toString());
        entity.setOwnerId(group.getOwnerId());
        entity.setGroupId(group.getId());
        entity.setName("orders");
        entity.setSchemaName("rehearsal");
        entity.setTableName("ds_" + entity.getId().replace("-", ""));
        entity.setColumnSchemaJson("[]");
        entity.setRowCount(0);
        entity.setOrigin("upload");
        datasets.saveAndFlush(entity);
        var tables = org.mockito.Mockito.mock(TableProvisioner.class);
        var registry =
                org.mockito.Mockito.mock(
                        io.agentscope.dataagent.tools.data.InMemoryDataSourceRegistry.class);
        var operations = org.mockito.Mockito.mock(KnowledgeBaseOperations.class);
        var cleanup =
                new DatasetDeletionCleanup(
                        datasets,
                        registry,
                        tables,
                        new WrenProperties("wren", root.toString(), "dataagent", "mysql", 10),
                        new ObjectMapper(),
                        operations,
                        manager);
        var service =
                new DatasetService(
                        datasets,
                        groups,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        registry,
                        tables,
                        java.util.List.of(),
                        new DatasetStoreProperties(),
                        new ObjectMapper(),
                        null,
                        null,
                        null);
        org.springframework.test.util.ReflectionTestUtils.setField(
                service, "deletionCleanup", cleanup);
        org.mockito.Mockito.doAnswer(
                        ignored -> {
                            assertThat(datasets.existsById(entity.getId())).isFalse();
                            return null;
                        })
                .when(tables)
                .dropTableStrict(entity.getTableName());
        new TransactionTemplate(manager)
                .executeWithoutResult(
                        status -> {
                            service.deleteEntity(entity);
                            org.mockito.Mockito.verifyNoInteractions(tables, registry);
                            cleanup.retryPending();
                            org.mockito.Mockito.verifyNoInteractions(tables, registry);
                            if (rollback) status.setRollbackOnly();
                        });
        assertThat(datasets.existsById(entity.getId())).isEqualTo(rollback);
        assertThat(root.resolve(".dataset-deletions/" + entity.getId() + ".json")).doesNotExist();
        if (rollback) org.mockito.Mockito.verifyNoInteractions(tables, registry, operations);
        else {
            org.mockito.Mockito.verify(tables).dropTableStrict(entity.getTableName());
            org.mockito.Mockito.verify(registry).remove(entity.getId());
            org.mockito.Mockito.verify(operations)
                    .forgetImportedTable(group.getId(), entity.getTableName());
        }
        cleanup.stop();
    }
}
