package io.agentscope.dataagent.dataset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MdlPublicationStoreTest {
    @TempDir Path root;
    private final ObjectMapper json = new ObjectMapper();
    private final MdlPublicationStore store = new MdlPublicationStore(json);

    @Test
    void metadataFailureRestoresBothOldArtifacts() throws Exception {
        prepare(true);
        assertThatThrownBy(
                        () ->
                                install(
                                        () -> {
                                            throw new IllegalStateException("db refused");
                                        },
                                        () -> 0))
                .isInstanceOf(DatasetException.class);
        assertOld();
        assertThat(root.resolve(MdlPublicationStore.JOURNAL)).doesNotExist();
    }

    @Test
    void partialFileInstallationRestoresBothOldArtifacts() throws Exception {
        prepare(true);
        Files.delete(root.resolve("mdl.json.next"));
        assertThatThrownBy(
                        () ->
                                install(
                                        () -> {
                                            throw new AssertionError("metadata must not execute");
                                        },
                                        () -> 0))
                .isInstanceOf(DatasetException.class);
        assertOld();
        assertThat(root.resolve(MdlPublicationStore.JOURNAL)).doesNotExist();
    }

    @Test
    void failedFirstPublicationLeavesNoPublishedArtifacts() throws Exception {
        prepare(false);
        assertThatThrownBy(
                        () ->
                                install(
                                        () -> {
                                            throw new IllegalStateException("db refused");
                                        },
                                        () -> 0))
                .isInstanceOf(DatasetException.class);
        assertThat(root.resolve("published")).doesNotExist();
        assertThat(root.resolve("mdl.json")).doesNotExist();
    }

    @Test
    void uncertainCommitIsResolvedUsingTheCommittedDatabaseVersion() throws Exception {
        prepare(true);
        var version = new AtomicInteger();
        install(
                () -> {
                    version.set(1);
                    throw new IllegalStateException("connection lost after commit");
                },
                version::get);
        assertNew();
        assertThat(root.resolve(MdlPublicationStore.JOURNAL)).doesNotExist();
        assertThat(root.resolve("published.previous")).doesNotExist();
    }

    @Test
    void corruptedIntentFailsClosedAndIsNotDeleted() throws Exception {
        prepare(true);
        Files.writeString(root.resolve(MdlPublicationStore.JOURNAL), "not json");
        assertThatThrownBy(() -> store.recover(root, 0)).isInstanceOf(DatasetException.class);
        assertOld();
        assertThat(root.resolve(MdlPublicationStore.JOURNAL)).exists();
    }

    @Test
    void processDeathBeforeMetadataCommitRollsBackAfterRestart() throws Exception {
        crash(false);
        new MdlPublicationStore(json).recover(root, databaseVersion());
        assertOld();
        assertThat(root.resolve(MdlPublicationStore.JOURNAL)).doesNotExist();
    }

    @Test
    void processDeathAfterMetadataCommitFinishesAfterRestart() throws Exception {
        crash(true);
        new MdlPublicationStore(json).recover(root, databaseVersion());
        assertNew();
        assertThat(root.resolve(MdlPublicationStore.JOURNAL)).doesNotExist();
        store.recover(root, 1); // Recovery is idempotent.
        assertNew();
    }

    @Test
    void lostRollbackBackupBlocksRecoveryInsteadOfGuessing() throws Exception {
        crash(false);
        try (var files = Files.walk(root.resolve("published.previous"))) {
            for (var path : files.sorted(java.util.Comparator.reverseOrder()).toList())
                Files.delete(path);
        }
        assertThatThrownBy(() -> store.recover(root, 0)).isInstanceOf(DatasetException.class);
        assertThat(root.resolve(MdlPublicationStore.JOURNAL)).exists();
    }

    private void crash(boolean commit) throws Exception {
        prepare(true);
        try (var connection = DriverManager.getConnection(url(), "sa", "");
                var sql = connection.createStatement()) {
            sql.execute("CREATE TABLE publication_version(v INT)");
            sql.execute("INSERT INTO publication_version VALUES(0)");
        }
        var process =
                new ProcessBuilder(
                                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                                "-cp",
                                System.getProperty("java.class.path"),
                                CrashWorker.class.getName(),
                                root.toString(),
                                Boolean.toString(commit))
                        .redirectErrorStream(true)
                        .redirectOutput(root.resolve("worker.log").toFile())
                        .start();
        try {
            assertThat(process.waitFor(20, TimeUnit.SECONDS)).isTrue();
            assertThat(process.exitValue())
                    .as(Files.readString(root.resolve("worker.log")))
                    .isEqualTo(23);
        } finally {
            if (process.isAlive()) process.destroyForcibly().waitFor();
        }
        assertThat(root.resolve(MdlPublicationStore.JOURNAL)).exists();
    }

    public static class CrashWorker {
        public static void main(String[] args) throws Exception {
            Path root = Path.of(args[0]);
            new MdlPublicationStore(new ObjectMapper())
                    .install(
                            root,
                            root.resolve("published.next"),
                            root.resolve("mdl.json.next"),
                            0,
                            () -> {
                                if (Boolean.parseBoolean(args[1])) {
                                    try (var c =
                                                    DriverManager.getConnection(
                                                            "jdbc:h2:file:"
                                                                    + root.resolve("db")
                                                                    + ";WRITE_DELAY=0",
                                                            "sa",
                                                            "");
                                            var sql = c.createStatement()) {
                                        sql.executeUpdate("UPDATE publication_version SET v=1");
                                    } catch (Exception failure) {
                                        throw new RuntimeException(failure);
                                    }
                                }
                                Runtime.getRuntime().halt(23);
                            },
                            () -> 0);
        }
    }

    private String url() {
        return "jdbc:h2:file:" + root.resolve("db") + ";WRITE_DELAY=0";
    }

    private int databaseVersion() throws Exception {
        try (var c = DriverManager.getConnection(url(), "sa", "");
                var sql = c.createStatement();
                var rows = sql.executeQuery("SELECT v FROM publication_version")) {
            rows.next();
            return rows.getInt(1);
        }
    }

    private void install(Runnable commit, java.util.function.IntSupplier version) {
        store.install(
                root,
                root.resolve("published.next"),
                root.resolve("mdl.json.next"),
                0,
                commit,
                version);
    }

    private void prepare(boolean old) throws Exception {
        if (old) {
            Files.createDirectories(root.resolve("published"));
            Files.writeString(root.resolve("published/data"), "old");
            Files.writeString(root.resolve("mdl.json"), "{\"version\":0}");
        }
        Files.createDirectories(root.resolve("published.next"));
        Files.writeString(root.resolve("published.next/data"), "new");
        Files.writeString(root.resolve("mdl.json.next"), "{\"version\":1}");
    }

    private void assertOld() throws Exception {
        assertThat(Files.readString(root.resolve("published/data"))).isEqualTo("old");
        assertThat(json.readTree(root.resolve("mdl.json").toFile()).path("version").asInt())
                .isZero();
    }

    private void assertNew() throws Exception {
        assertThat(Files.readString(root.resolve("published/data"))).isEqualTo("new");
        assertThat(json.readTree(root.resolve("mdl.json").toFile()).path("version").asInt())
                .isEqualTo(1);
    }
}
