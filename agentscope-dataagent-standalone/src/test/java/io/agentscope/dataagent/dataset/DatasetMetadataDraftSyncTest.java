package io.agentscope.dataagent.dataset;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.agentscope.dataagent.web.persistence.jpa.DatasetEntity;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

class DatasetMetadataDraftSyncTest {
    @TempDir Path home;
    Path root, file;
    MdlWorkspaceService workspace;
    KnowledgeBaseAccessService access;
    DatasetMetadataDraftSync sync;
    DatasetEntity dataset;
    final ObjectMapper yaml = new ObjectMapper(new YAMLFactory());
    final List<DatasetService.ColumnDescUpdate> edit =
            List.of(new DatasetService.ColumnDescUpdate("amount", "新说明", "新名称"));

    @BeforeEach
    void setup() throws Exception {
        workspace =
                new MdlWorkspaceService(
                        new WrenProperties("wren", home.toString(), "dataagent", "mysql", 10),
                        mock(WrenCli.class));
        access = mock(KnowledgeBaseAccessService.class);
        sync = new DatasetMetadataDraftSync(workspace, access);
        dataset = new DatasetEntity();
        dataset.setId("d1");
        dataset.setGroupId("g1");
        root = workspace.workspaceRoot("g1");
        file = root.resolve("models/orders/metadata.yml");
        Files.createDirectories(file.getParent());
        Files.createDirectories(root.resolve(".platform"));
        Files.writeString(root.resolve(".platform/datasets.json"), "{\"d1\":\"orders\"}");
        Files.writeString(
                file,
                """
                table: ds_orders
                columns:
                  - name: amount
                    type: DECIMAL
                    properties:
                      description: old
                      displayName: oldName
                      semanticType: MEASURE
                  - name: ratio
                    type: DOUBLE
                    is_calculated: true
                    expression: SUM(amount) / COUNT(*)
                properties:
                  description: old table
                  tableRole: FACT
                """);
    }

    @Test
    void explicitEditsPreserveOtherDraftChangesAndPublishedBytes() throws Exception {
        Path published = home.resolve("g1/published/models/orders/metadata.yml");
        Files.createDirectories(published.getParent());
        Files.copy(file, published);
        byte[] snapshot = Files.readAllBytes(published);
        assertEquals("saved", sync.update("manager", dataset, "new table", edit, () -> "saved"));
        var model = yaml.readTree(file.toFile());
        assertEquals("新说明", model.at("/columns/0/properties/description").asText());
        assertEquals("新名称", model.at("/columns/0/properties/displayName").asText());
        assertEquals("MEASURE", model.at("/columns/0/properties/semanticType").asText());
        assertEquals("SUM(amount) / COUNT(*)", model.at("/columns/1/expression").asText());
        assertEquals("FACT", model.at("/properties/tableRole").asText());
        assertArrayEquals(snapshot, Files.readAllBytes(published));
        assertFalse(java.util.Arrays.equals(snapshot, Files.readAllBytes(file)));
        assertEquals("manager", DraftChangeLog.read(root).get(0).userId());
    }

    @Test
    void emptyDescriptionAndOmittedDisplayNameDoNotRenameOrChangeFormula() throws Exception {
        sync.update(
                "manager",
                dataset,
                "",
                List.of(new DatasetService.ColumnDescUpdate("amount", "")),
                () -> true);
        var model = yaml.readTree(file.toFile());
        assertEquals("", model.at("/columns/0/properties/description").asText());
        assertEquals("oldName", model.at("/columns/0/properties/displayName").asText());
        assertEquals("", model.at("/properties/description").asText());
    }

    @Test
    void absentModelOnlySavesMetadataAndCorruptModelDoesNotSave() throws Exception {
        Files.delete(root.resolve(".platform/datasets.json"));
        assertTrue(sync.update("manager", dataset, null, edit, () -> true));
        assertFalse(Files.exists(root.resolve(".platform/draft-changes.jsonl")));
        Files.writeString(root.resolve(".platform/datasets.json"), "{\"d1\":\"orders\"}");
        Files.writeString(file, "columns: [");
        var called = new AtomicBoolean();
        assertThrows(
                DatasetException.class,
                () ->
                        sync.update(
                                "manager",
                                dataset,
                                null,
                                edit,
                                () -> {
                                    called.set(true);
                                    return true;
                                }));
        assertFalse(called.get());
    }

    @Test
    void invalidMappingOrModelShapeCannotSilentlySaveOnlyTheDataset() throws Exception {
        var called = new AtomicBoolean();
        Files.writeString(root.resolve(".platform/datasets.json"), "{\"d1\":\"../escape\"}");
        assertThrows(
                DatasetException.class,
                () ->
                        sync.update(
                                "manager",
                                dataset,
                                null,
                                edit,
                                () -> {
                                    called.set(true);
                                    return true;
                                }));
        Files.writeString(root.resolve(".platform/datasets.json"), "{\"d1\":\"orders\"}");
        Files.writeString(file, "columns: invalid");
        assertThrows(
                DatasetException.class,
                () ->
                        sync.update(
                                "manager",
                                dataset,
                                null,
                                edit,
                                () -> {
                                    called.set(true);
                                    return true;
                                }));
        Files.delete(file);
        Files.createDirectory(file);
        assertThrows(
                DatasetException.class,
                () ->
                        sync.update(
                                "manager",
                                dataset,
                                null,
                                edit,
                                () -> {
                                    called.set(true);
                                    return true;
                                }));
        assertFalse(called.get());
    }

    @Test
    void saveFailureRestoresOriginalFileAndChangeLog() throws Exception {
        byte[] before = Files.readAllBytes(file);
        assertThrows(
                IllegalStateException.class,
                () ->
                        sync.update(
                                "manager",
                                dataset,
                                null,
                                edit,
                                () -> {
                                    throw new IllegalStateException();
                                }));
        assertArrayEquals(before, Files.readAllBytes(file));
        assertTrue(DraftChangeLog.read(root).isEmpty());
    }

    @Test
    void transactionRollbackRestoresFilesBeforeUnlockAndCommitKeepsDraft() throws Exception {
        var tx =
                new TransactionTemplate(
                        new AbstractPlatformTransactionManager() {
                            protected Object doGetTransaction() {
                                return new Object();
                            }

                            protected void doBegin(Object t, TransactionDefinition d) {}

                            protected void doCommit(DefaultTransactionStatus s) {}

                            protected void doRollback(DefaultTransactionStatus s) {}
                        });
        byte[] before = Files.readAllBytes(file);
        tx.execute(
                status -> {
                    sync.update("manager", dataset, null, edit, () -> true);
                    status.setRollbackOnly();
                    return null;
                });
        assertArrayEquals(before, Files.readAllBytes(file));
        assertTrue(DraftChangeLog.read(root).isEmpty());
        tx.execute(status -> sync.update("manager", dataset, null, edit, () -> true));
        assertEquals(
                "新说明",
                yaml.readTree(file.toFile()).at("/columns/0/properties/description").asText());
        assertTrue(workspace.withWorkspaceLock("g1", () -> true));
    }

    @Test
    void queuedWriteRechecksManagePermissionAfterLock() throws Exception {
        byte[] before = Files.readAllBytes(file);
        var entered = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        try {
            var holder =
                    pool.submit(
                            () ->
                                    workspace.withWorkspaceLock(
                                            "g1",
                                            () -> {
                                                entered.countDown();
                                                try {
                                                    release.await();
                                                } catch (InterruptedException e) {
                                                    throw new RuntimeException(e);
                                                }
                                                return true;
                                            }));
            assertTrue(entered.await(3, TimeUnit.SECONDS));
            var pending =
                    pool.submit(() -> sync.update("query-only", dataset, null, edit, () -> true));
            when(access.requireManage("query-only", "g1"))
                    .thenThrow(new DatasetException("需要管理权限", 403));
            release.countDown();
            holder.get(3, TimeUnit.SECONDS);
            assertThrows(
                    java.util.concurrent.ExecutionException.class,
                    () -> pending.get(3, TimeUnit.SECONDS));
            assertArrayEquals(before, Files.readAllBytes(file));
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }
}
