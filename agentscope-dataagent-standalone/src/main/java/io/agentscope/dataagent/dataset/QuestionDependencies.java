package io.agentscope.dataagent.dataset;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * What a confirmed question's SQL depends on (spec112, ADR0130): the models and assets it reads,
 * the assets built on them, the relationships touching them and the rule entries naming them.
 * A confirmation stores this snapshot; when the draft no longer matches, the question needs
 * review and each difference becomes a reason the user can read.
 *
 * <p>Matching is by name, so it errs on the side of flagging: a rule that merely mentions a table
 * the SQL reads counts as a dependency.
 */
final class QuestionDependencies {
    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int MAX_RULE_CHARS = 160;
    static final String MODEL = "模型";
    static final String DERIVED = "加工表";
    static final String VIEW = "查询模板";

    /** A model, ref_sql model or view of a project, with the text its definition hashes over. */
    record Node(String name, String kind, String sql, String table, String definition) {}

    /** One relationship entry, by the models it joins. */
    record Relation(String name, List<String> models, String definition) {}

    /** A project read once, so every question of a listing is compared against the same state. */
    record Project(Map<String, Node> nodes, List<Relation> relations, List<String> rules) {}

    private QuestionDependencies() {}

    /** Reads the parts of a project that dependencies are taken from; missing parts are empty. */
    static Project read(Path root) {
        Map<String, Node> nodes = new LinkedHashMap<>();
        List<Relation> relations = new ArrayList<>();
        List<String> rules = new ArrayList<>();
        if (root == null || !Files.isDirectory(root)) return new Project(nodes, relations, rules);
        try {
            for (Path dir : children(root.resolve("models"))) {
                Path metadata = dir.resolve("metadata.yml");
                if (!Files.isRegularFile(metadata, LinkOption.NOFOLLOW_LINKS)) continue;
                String text = Files.readString(metadata, StandardCharsets.UTF_8);
                JsonNode node = YAML.readTree(text);
                if (node == null) continue;
                String name = node.path("name").asText(dir.getFileName().toString());
                Path refFile = dir.resolve("ref_sql.sql");
                String refSql =
                        Files.isRegularFile(refFile, LinkOption.NOFOLLOW_LINKS)
                                ? Files.readString(refFile, StandardCharsets.UTF_8)
                                : node.path("ref_sql").asText("");
                String table = node.path("table_reference").path("table").asText("");
                boolean derived = table.isBlank() && !refSql.isBlank();
                nodes.put(
                        key(name),
                        new Node(
                                name,
                                derived ? DERIVED : MODEL,
                                derived ? refSql : "",
                                table,
                                text + "\n" + refSql));
            }
            for (Path dir : children(root.resolve("views"))) {
                Path metadata = dir.resolve("metadata.yml");
                if (!Files.isRegularFile(metadata, LinkOption.NOFOLLOW_LINKS)) continue;
                String text = Files.readString(metadata, StandardCharsets.UTF_8);
                JsonNode node = YAML.readTree(text);
                if (node == null) continue;
                String name = node.path("name").asText(dir.getFileName().toString());
                nodes.put(
                        key(name),
                        new Node(name, VIEW, node.path("statement").asText(""), "", text));
            }
            Path relationsFile = root.resolve("relationships.yml");
            if (Files.isRegularFile(relationsFile, LinkOption.NOFOLLOW_LINKS)) {
                JsonNode parsed =
                        YAML.readTree(Files.readString(relationsFile, StandardCharsets.UTF_8));
                for (JsonNode entry :
                        parsed == null ? JSON.createArrayNode() : parsed.path("relationships")) {
                    List<String> models = new ArrayList<>();
                    entry.path("models").forEach(m -> models.add(m.asText()));
                    relations.add(
                            new Relation(
                                    entry.path("name").asText(String.join("-", models)),
                                    models,
                                    JSON.writeValueAsString(entry)));
                }
            }
            for (Path file : files(root.resolve("knowledge/rules"))) {
                for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                    String entry = line.strip();
                    if (entry.startsWith("- ") || entry.startsWith("* ")) rules.add(entry);
                }
            }
        } catch (IOException e) {
            throw new DatasetException("读取问题依赖失败：" + e.getMessage(), e);
        }
        return new Project(nodes, relations, rules);
    }

    /**
     * The dependency snapshot of one SQL: keys name what is depended on, values carry a kind and
     * digest (or the rule text) so a later comparison can say what changed.
     */
    static Map<String, String> snapshot(Project project, String sql) {
        Set<String> read = reads(project, sql);
        // An asset the SQL reads depends in turn on the models its own SQL reads.
        Set<String> direct = new LinkedHashSet<>(read);
        for (String name : direct) {
            Node node = project.nodes().get(name);
            if (node != null && !node.sql().isBlank()) read.addAll(reads(project, node.sql()));
        }
        Map<String, String> out = new java.util.TreeMap<>();
        for (String name : read) {
            Node node = project.nodes().get(name);
            out.put("node:" + node.name(), node.kind() + "|" + digest(node.definition()));
        }
        // Assets built on what the SQL reads: a new one usually comes with a rule to prefer it.
        for (Node node : project.nodes().values()) {
            if (node.sql().isBlank() || read.contains(key(node.name()))) continue;
            Set<String> base = reads(project, node.sql());
            base.retainAll(read);
            if (!base.isEmpty())
                out.put("asset:" + node.name(), node.kind() + "|" + digest(node.definition()));
        }
        for (Relation relation : project.relations()) {
            if (relation.models().stream().anyMatch(m -> read.contains(key(m))))
                out.put("relation:" + relation.name(), digest(relation.definition()));
        }
        for (String rule : project.rules()) {
            String lower = rule.toLowerCase(Locale.ROOT);
            boolean mentions =
                    read.stream()
                            .map(n -> project.nodes().get(n).name().toLowerCase(Locale.ROOT))
                            .anyMatch(lower::contains);
            if (mentions) out.put("rule:" + digest(rule), clip(rule));
        }
        return out;
    }

    /** Readable differences between a confirmation's snapshot and the current one, in order. */
    static List<String> changes(Map<String, String> before, Map<String, String> after) {
        List<String> out = new ArrayList<>();
        Set<String> keys = new java.util.TreeSet<>(before.keySet());
        keys.addAll(after.keySet());
        for (String key : keys) {
            String old = before.get(key);
            String now = after.get(key);
            if (old != null && old.equals(now)) continue;
            int colon = key.indexOf(':');
            String type = key.substring(0, colon);
            String name = key.substring(colon + 1);
            switch (type) {
                case "node" -> {
                    if (now == null) out.add(kind(old) + "「" + name + "」已删除");
                    else if (old != null) out.add(kind(now) + "「" + name + "」的定义已修改");
                }
                case "asset" -> {
                    if (old == null) out.add("新建了" + kind(now) + "「" + name + "」，它基于本题查询的表");
                    else if (now == null) out.add(kind(old) + "「" + name + "」已删除");
                    else out.add(kind(now) + "「" + name + "」的定义已修改");
                }
                case "relation" ->
                        out.add(
                                "关系「"
                                        + name
                                        + "」"
                                        + (old == null ? "已新增" : now == null ? "已删除" : "已修改"));
                case "rule" ->
                        out.add((old == null ? "新增规则：" : "删除规则：") + (old == null ? now : old));
                default -> {}
            }
        }
        return out;
    }

    /** Model and asset names the SQL reads, by logical name or by a model's physical table. */
    private static Set<String> reads(Project project, String sql) {
        Set<String> tokens = PublishedAssetWindows.names(sql);
        Set<String> out = new LinkedHashSet<>();
        for (Map.Entry<String, Node> entry : project.nodes().entrySet()) {
            Node node = entry.getValue();
            if (tokens.contains(entry.getKey())
                    || (!node.table().isBlank() && tokens.contains(key(node.table()))))
                out.add(entry.getKey());
        }
        return out;
    }

    private static String kind(String value) {
        return value == null ? MODEL : value.substring(0, value.indexOf('|'));
    }

    private static List<Path> children(Path dir) throws IOException {
        if (!Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) return List.of();
        try (Stream<Path> list = Files.list(dir)) {
            return list.filter(p -> Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS))
                    .sorted()
                    .toList();
        }
    }

    private static List<Path> files(Path dir) throws IOException {
        if (!Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) return List.of();
        try (Stream<Path> list = Files.list(dir)) {
            return list.filter(p -> Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS))
                    .filter(p -> p.getFileName().toString().endsWith(".md"))
                    .sorted()
                    .toList();
        }
    }

    private static String key(String name) {
        return name.toLowerCase(Locale.ROOT);
    }

    private static String clip(String rule) {
        String text = rule.replaceFirst("^[-*]\\s+", "");
        return text.length() > MAX_RULE_CHARS ? text.substring(0, MAX_RULE_CHARS) + "…" : text;
    }

    private static String digest(String value) {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(value.getBytes(StandardCharsets.UTF_8)))
                    .substring(0, 16);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
