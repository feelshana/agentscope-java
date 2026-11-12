package io.agentscope.dataagent.dataset;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Comparator;
import java.util.function.IntSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Recoverable local publication installation; callers serialize by group and workspace. */
final class MdlPublicationStore {
    static final String JOURNAL = ".publication.json";
    private static final String MARKER = "target/.publication-version";
    private static final Logger log = LoggerFactory.getLogger(MdlPublicationStore.class);
    private final ObjectMapper mapper;

    record Intent(
            int format,
            int previousVersion,
            int nextVersion,
            boolean directory,
            boolean manifest) {}

    MdlPublicationStore(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    static boolean pending(Path root) {
        return Files.exists(root.resolve(JOURNAL), LinkOption.NOFOLLOW_LINKS);
    }

    void install(
            Path root,
            Path prepared,
            Path manifest,
            int previousVersion,
            Runnable commitMetadata,
            IntSupplier committedVersion) {
        if (!prepared.equals(root.resolve("published.next"))
                || !manifest.equals(root.resolve("mdl.json.next")))
            throw new DatasetException("发布临时路径不属于当前知识库", 500);
        if (pending(root)) recover(root, committedVersion.getAsInt());
        Intent intent =
                new Intent(
                        1,
                        previousVersion,
                        Math.addExact(previousVersion, 1),
                        Files.exists(root.resolve("published")),
                        Files.exists(root.resolve("mdl.json")));
        try {
            removeTree(root.resolve("published.previous"));
            Files.deleteIfExists(root.resolve("mdl.json.previous"));
            Files.createDirectories(prepared.resolve("target"));
            Files.writeString(prepared.resolve(MARKER), Integer.toString(intent.nextVersion()));
            writeIntent(root, intent);
            if (intent.directory())
                move(root.resolve("published"), root.resolve("published.previous"));
            if (intent.manifest())
                move(root.resolve("mdl.json"), root.resolve("mdl.json.previous"));
            move(prepared, root.resolve("published"));
            move(manifest, root.resolve("mdl.json"));
            commitMetadata.run();
        } catch (IOException | RuntimeException failure) {
            if (pending(root)) {
                try {
                    int version = committedVersion.getAsInt();
                    recover(root, version);
                    // A connection can fail after the database actually committed.
                    if (version == intent.nextVersion()) return;
                } catch (RuntimeException recoveryFailure) {
                    failure.addSuppressed(recoveryFailure);
                }
            }
            if (failure instanceof DatasetException problem) throw problem;
            throw new DatasetException("发布快照替换失败：" + failure.getMessage(), failure);
        }
        finish(root, intent);
    }

    void recover(Path root, int committedVersion) {
        if (!pending(root)) return;
        try {
            Intent intent = mapper.readValue(root.resolve(JOURNAL).toFile(), Intent.class);
            if (intent.format() != 1
                    || intent.previousVersion() < 0
                    || intent.nextVersion() != intent.previousVersion() + 1)
                throw new IOException("invalid publication intent");
            if (committedVersion == intent.nextVersion()) {
                verifyInstalled(root, intent.nextVersion());
                finish(root, intent);
            } else if (committedVersion == intent.previousVersion() || committedVersion == -1) {
                restoreDirectory(root, intent);
                restoreManifest(root, intent);
                removeTree(root.resolve("published.next"));
                Files.deleteIfExists(root.resolve("mdl.json.next"));
                Files.delete(root.resolve(JOURNAL));
            } else {
                throw new IOException("publication intent and committed version disagree");
            }
        } catch (IOException failure) {
            throw new DatasetException("发布恢复未完成，禁止使用不一致版本：" + failure.getMessage(), 503, failure);
        }
    }

    private void verifyInstalled(Path root, int version) throws IOException {
        if (!Files.isDirectory(root.resolve("published"), LinkOption.NOFOLLOW_LINKS)
                || marker(root.resolve("published")) != version
                || !Files.isRegularFile(root.resolve("mdl.json"), LinkOption.NOFOLLOW_LINKS)
                || mapper.readTree(root.resolve("mdl.json").toFile()).path("version").asInt(-1)
                        != version)
            throw new IOException("committed publication files are incomplete");
    }

    private void restoreDirectory(Path root, Intent intent) throws IOException {
        Path current = root.resolve("published");
        Path backup = root.resolve("published.previous");
        if (Files.exists(backup, LinkOption.NOFOLLOW_LINKS)) {
            removeTree(current);
            move(backup, current);
        } else if (!intent.directory()) {
            removeTree(current);
        } else if (!Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS)
                || marker(current) == intent.nextVersion()) {
            throw new IOException("previous publication directory is unavailable");
        }
    }

    private void restoreManifest(Path root, Intent intent) throws IOException {
        Path current = root.resolve("mdl.json");
        Path backup = root.resolve("mdl.json.previous");
        if (Files.exists(backup, LinkOption.NOFOLLOW_LINKS)) {
            Files.deleteIfExists(current);
            move(backup, current);
        } else if (!intent.manifest()) {
            Files.deleteIfExists(current);
        } else if (!Files.isRegularFile(current, LinkOption.NOFOLLOW_LINKS)
                || mapper.readTree(current.toFile()).path("version").asInt(-1)
                        == intent.nextVersion()) {
            throw new IOException("previous publication manifest is unavailable");
        }
    }

    private static int marker(Path directory) throws IOException {
        Path marker = directory.resolve(MARKER);
        if (!Files.isRegularFile(marker, LinkOption.NOFOLLOW_LINKS)) return -1;
        try {
            return Integer.parseInt(Files.readString(marker).strip());
        } catch (NumberFormatException failure) {
            throw new IOException("invalid publication marker", failure);
        }
    }

    private void finish(Path root, Intent intent) {
        try {
            verifyInstalled(root, intent.nextVersion());
            // Once metadata committed, cleanup must never roll the publication back.
            Files.deleteIfExists(root.resolve(JOURNAL));
        } catch (IOException failure) {
            throw new DatasetException("发布已提交，收尾恢复待重试", 503, failure);
        }
        try {
            removeTree(root.resolve("published.previous"));
            Files.deleteIfExists(root.resolve("mdl.json.previous"));
            removeTree(root.resolve("published.next"));
            Files.deleteIfExists(root.resolve("mdl.json.next"));
        } catch (IOException cleanup) {
            log.warn("Committed publication backup cleanup pending: {}", root.getFileName());
        }
    }

    private void writeIntent(Path root, Intent intent) throws IOException {
        Files.createDirectories(root);
        Path temporary = root.resolve(JOURNAL + ".tmp");
        mapper.writeValue(temporary.toFile(), intent);
        try (var file = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
            file.force(true);
        }
        move(temporary, root.resolve(JOURNAL));
    }

    private static void move(Path source, Path target) throws IOException {
        try {
            Files.move(
                    source,
                    target,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException unsupported) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void removeTree(Path root) throws IOException {
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return;
        try (var paths = Files.walk(root)) {
            for (var path : paths.sorted(Comparator.reverseOrder()).toList())
                Files.deleteIfExists(path);
        }
    }
}
