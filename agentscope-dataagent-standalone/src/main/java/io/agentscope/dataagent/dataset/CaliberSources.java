package io.agentscope.dataagent.dataset;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Row-level provenance of caliber lists (spec149): which document, conversation or person last
 * set each caliber, and conflicts a later change was not allowed to overwrite. Kept beside the
 * draft under {@code .platform/}, never published; callers hold the workspace lock.
 */
public final class CaliberSources {
    static final String FILE = ".platform/caliber-sources.json";
    public static final String DOCUMENT = "document";
    public static final String CHAT = "chat";
    public static final String EDIT = "edit";
    // A person's own choices: a document draft must not silently replace them.
    private static final Set<String> HUMAN = Set.of(CHAT, EDIT);
    private static final ObjectMapper JSON = new ObjectMapper();

    private CaliberSources() {}

    public record RowSource(String kind, String document, String user, String at, String hash) {}

    /** A document's version of a caliber that was kept back because a person had changed it. */
    public record Conflict(
            String list,
            String caliber,
            String keptKind,
            String document,
            Map<String, Object> proposed,
            String at) {}

    public record State(
            Map<String, Map<String, RowSource>> rows,
            List<Conflict> conflicts,
            // list path -> document name -> content hash at the time the list was drafted
            Map<String, Map<String, String>> documents) {
        static State empty() {
            return new State(new TreeMap<>(), new ArrayList<>(), new TreeMap<>());
        }
    }

    public static State read(Path root) {
        Path file = root.resolve(FILE);
        if (!Files.isRegularFile(file)) return State.empty();
        try {
            State s = JSON.readValue(file.toFile(), State.class);
            return new State(
                    s.rows() == null ? new TreeMap<>() : new TreeMap<>(s.rows()),
                    s.conflicts() == null ? new ArrayList<>() : new ArrayList<>(s.conflicts()),
                    s.documents() == null ? new TreeMap<>() : new TreeMap<>(s.documents()));
        } catch (IOException e) {
            throw new DatasetException("读取口径来源记录失败：" + e.getMessage(), e);
        }
    }

    static void write(Path root, State state) {
        Path file = root.resolve(FILE);
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(
                    file,
                    JSON.writerWithDefaultPrettyPrinter().writeValueAsString(state),
                    StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new DatasetException("保存口径来源记录失败：" + e.getMessage(), e);
        }
    }

    /**
     * Records who set each changed row. A document draft that would change or remove a row a
     * person set (chat or edit) is refused: the document's version is kept as a conflict for the
     * person to choose, and the returned message tells the assistant to leave those rows as they
     * are. Returns null when the change may be written.
     *
     * @param before the list's YAML before the change, or null when it is new
     * @param after the new YAML (already compiled), or null when the list is deleted
     * @param documents current hashes of the documents declaring the list's model
     */
    public static String apply(
            Path root,
            String listPath,
            String before,
            String after,
            String kind,
            String user,
            Map<String, String> documents) {
        Map<String, CaliberList.Caliber> old = rows(listPath, before);
        Map<String, CaliberList.Caliber> now = rows(listPath, after);
        State state = read(root);
        Map<String, RowSource> sources =
                new LinkedHashMap<>(state.rows().getOrDefault(listPath, Map.of()));
        String at = Instant.now().toString();
        String single = documents.size() == 1 ? documents.keySet().iterator().next() : null;
        List<Conflict> conflicts = new ArrayList<>();
        Map<String, RowSource> next = new LinkedHashMap<>();
        for (Map.Entry<String, CaliberList.Caliber> e : now.entrySet()) {
            CaliberList.Caliber row = e.getValue();
            String hash = hash(row);
            RowSource previous = sources.get(e.getKey());
            if (previous != null && hash.equals(previous.hash())) {
                next.put(e.getKey(), previous);
                continue;
            }
            String document = document(row, single);
            if (DOCUMENT.equals(kind)
                    && previous != null
                    && old.containsKey(e.getKey())
                    && HUMAN.contains(previous.kind())) {
                conflicts.add(
                        new Conflict(
                                listPath, e.getKey(), previous.kind(), document, plain(row), at));
                continue;
            }
            next.put(e.getKey(), new RowSource(kind, document, user, at, hash));
        }
        for (String removed : old.keySet()) {
            RowSource previous = sources.get(removed);
            if (!now.containsKey(removed)
                    && DOCUMENT.equals(kind)
                    && previous != null
                    && HUMAN.contains(previous.kind()))
                conflicts.add(new Conflict(listPath, removed, previous.kind(), single, null, at));
        }
        if (!conflicts.isEmpty()) {
            List<Conflict> kept = new ArrayList<>(state.conflicts());
            kept.removeIf(c -> conflicts.stream().anyMatch(n -> same(c, n)));
            kept.addAll(conflicts);
            write(root, new State(state.rows(), kept, state.documents()));
            return "未写入：以下口径此前由用户修改过，文档起草不能覆盖："
                    + String.join("、", conflicts.stream().map(Conflict::caliber).toList())
                    + "。请保持这些口径原样（不改、不删），其余口径照常写入；文档中的版本已记录为冲突，由用户在口径页选择保留哪一个。";
        }
        Map<String, Map<String, RowSource>> rows = new TreeMap<>(state.rows());
        Map<String, Map<String, String>> docs = new TreeMap<>(state.documents());
        if (after == null) {
            rows.remove(listPath);
            docs.remove(listPath);
        } else {
            rows.put(listPath, next);
            if (DOCUMENT.equals(kind)) docs.put(listPath, new TreeMap<>(documents));
        }
        List<Conflict> kept = new ArrayList<>(state.conflicts());
        // A row the change settles is no longer in conflict.
        kept.removeIf(
                c ->
                        c.list().equals(listPath)
                                && next.containsKey(c.caliber())
                                && !DOCUMENT.equals(kind));
        if (after == null) kept.removeIf(c -> c.list().equals(listPath));
        write(root, new State(rows, kept, docs));
        return null;
    }

    private static boolean same(Conflict a, Conflict b) {
        return a.list().equals(b.list()) && a.caliber().equals(b.caliber());
    }

    private static String document(CaliberList.Caliber row, String single) {
        Object declared = row.source() == null ? null : row.source().get("document");
        String name = CaliberList.text(declared);
        return name != null ? name : single;
    }

    private static Map<String, CaliberList.Caliber> rows(String listPath, String yaml) {
        Map<String, CaliberList.Caliber> out = new LinkedHashMap<>();
        if (yaml == null) return out;
        CaliberList.Parsed parsed = CaliberList.parse(listPath, yaml);
        if (parsed.list() != null)
            for (CaliberList.Caliber c : parsed.list().calibers()) out.put(c.name(), c);
        return out;
    }

    /** What a caliber means, without its bookkeeping, for comparing versions. */
    static Map<String, Object> plain(CaliberList.Caliber c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", c.name());
        m.put("group", c.group());
        m.put("where", new TreeMap<>(c.where()));
        m.put("synonyms", c.synonyms());
        m.put("note", c.note());
        m.put("pending", c.pending());
        m.put("combine", c.combine());
        // spec149 P7-4: only present when written, so earlier rows keep their reviewed hash.
        if (c.unit() != null) m.put("unit", c.unit());
        return m;
    }

    static String hash(CaliberList.Caliber c) {
        try {
            return sha(JSON.writeValueAsString(plain(c)));
        } catch (IOException e) {
            throw new DatasetException("计算口径摘要失败", e);
        }
    }

    public static String sha(String text) {
        try {
            byte[] d =
                    MessageDigest.getInstance("SHA-256")
                            .digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(d, 0, 8);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
