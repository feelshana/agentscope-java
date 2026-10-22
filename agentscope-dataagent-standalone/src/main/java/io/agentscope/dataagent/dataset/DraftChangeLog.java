package io.agentscope.dataagent.dataset;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Who changed which draft file and why (ADR0111). Lives under the workspace's {@code .platform/},
 * which is never published or exposed to the agent. The diff itself always comes from comparing
 * the draft with the published snapshot; this log only annotates it. Callers hold the workspace
 * lock.
 */
public final class DraftChangeLog {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String FILE = ".platform/draft-changes.jsonl";

    private DraftChangeLog() {}

    /** One write: workspace-relative path, {@link ModelingTurnPurpose} source, user, time. */
    public record Entry(String path, String source, String userId, String at) {}

    public static void record(Path root, String path, String source, String userId) {
        try {
            Path file = root.resolve(FILE);
            Files.createDirectories(file.getParent());
            Files.writeString(
                    file,
                    JSON.writeValueAsString(
                                    new Entry(
                                            normalize(path),
                                            source == null ? ModelingTurnPurpose.CHAT : source,
                                            userId,
                                            Instant.now().toString()))
                            + "\n",
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new DatasetException("记录草稿修改失败：" + e.getMessage(), e);
        }
    }

    public static List<Entry> read(Path root) {
        Path file = root.resolve(FILE);
        if (!Files.isRegularFile(file)) return List.of();
        List<Entry> out = new ArrayList<>();
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                if (line.isBlank()) continue;
                try {
                    out.add(JSON.readValue(line, Entry.class));
                } catch (IOException ignored) {
                    // A torn line from a crash loses one annotation, never the diff itself.
                }
            }
        } catch (IOException e) {
            throw new DatasetException("读取草稿修改记录失败：" + e.getMessage(), e);
        }
        return out;
    }

    /** Drops the annotations of a reverted file. */
    public static void forget(Path root, String path) {
        String target = normalize(path);
        List<Entry> kept = read(root).stream().filter(e -> !target.equals(e.path())).toList();
        rewrite(root, kept);
    }

    /** Called after a publish or a full discard: the draft equals the snapshot again. */
    public static void clear(Path root) {
        try {
            Files.deleteIfExists(root.resolve(FILE));
        } catch (IOException e) {
            throw new DatasetException("清理草稿修改记录失败：" + e.getMessage(), e);
        }
    }

    private static void rewrite(Path root, List<Entry> entries) {
        clear(root);
        if (entries.isEmpty()) return;
        try {
            Path file = root.resolve(FILE);
            Files.createDirectories(file.getParent());
            StringBuilder text = new StringBuilder();
            for (Entry entry : entries) text.append(JSON.writeValueAsString(entry)).append('\n');
            Files.writeString(file, text.toString(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new DatasetException("更新草稿修改记录失败：" + e.getMessage(), e);
        }
    }

    private static String normalize(String path) {
        String value = path.replace('\\', '/');
        while (value.startsWith("/")) value = value.substring(1);
        if (value.startsWith("project/")) value = value.substring("project/".length());
        return value;
    }
}
