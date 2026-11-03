package io.agentscope.dataagent.dataset;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Time-window rules for published Views and ref_sql models (ADR0123). Their SQL hides the fact
 * tables from the window gate, so dependencies are read from the published manifest and each
 * asset inherits the tightest window of what it reads. Time columns are platform data confirmed
 * at review and never enter the MDL; window sizes stay live in the database.
 */
@Component
public class PublishedAssetWindows {
    /** Written beside the snapshot at publish: asset name to its confirmed time columns. */
    public static final String FILE = "asset-windows.json";

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern NOT_CODE =
            Pattern.compile("(?s)'(?:''|[^'])*'|/\\*.*?\\*/|--[^\\r\\n]*");
    private static final Pattern NAME = Pattern.compile("[\\p{L}\\p{N}_]+");

    private final WrenProperties props;

    public PublishedAssetWindows(WrenProperties props) {
        this.props = props;
    }

    /** One limited fact table: its gate name, physical name and window. */
    public record Fact(String table, String physicalTable, int maxWindowDays) {}

    /** Derived rules, plus assets over limited tables that have no confirmed time column. */
    public record Derived(List<SqlWindowGate.Rule> rules, List<String> unconfigured) {
        static final Derived NONE = new Derived(List.of(), List.of());
    }

    public Derived derive(String groupId, List<Fact> facts) {
        if (facts.isEmpty()) return Derived.NONE;
        Path published = props.groupRoot(groupId).resolve("published");
        Path manifest = published.resolve("target/mdl.json");
        if (!Files.isRegularFile(manifest)) return Derived.NONE;
        try {
            return derive(
                    JSON.readTree(manifest.toFile()), facts, timeColumns(published.resolve(FILE)));
        } catch (IOException e) {
            throw new DatasetException("无法读取已发布资产的时间配置，查询未执行，请重新发布", e);
        }
    }

    static Derived derive(JsonNode manifest, List<Fact> facts, Map<String, List<String>> columns) {
        Map<String, Fact> byName = new HashMap<>();
        for (Fact fact : facts) {
            byName.put(lower(fact.table()), fact);
            if (fact.physicalTable() != null) byName.put(lower(fact.physicalTable()), fact);
        }
        Map<String, String> assets = new LinkedHashMap<>();
        Map<String, Set<Fact>> reads = new HashMap<>();
        for (JsonNode model : manifest.path("models")) {
            String name = model.path("name").asText();
            String sql = model.path("refSql").asText("");
            if (!sql.isBlank()) {
                assets.put(name, sql);
                continue;
            }
            Fact fact = byName.get(lower(name));
            if (fact == null)
                fact = byName.get(lower(model.path("tableReference").path("table").asText("")));
            if (fact != null)
                reads.computeIfAbsent(lower(name), k -> new LinkedHashSet<>()).add(fact);
        }
        for (JsonNode view : manifest.path("views"))
            assets.put(view.path("name").asText(), view.path("statement").asText(""));

        Map<String, Set<String>> uses = new HashMap<>();
        for (var asset : assets.entrySet()) {
            Set<String> names = names(asset.getValue());
            uses.put(lower(asset.getKey()), names);
            Set<Fact> direct =
                    reads.computeIfAbsent(lower(asset.getKey()), k -> new LinkedHashSet<>());
            for (String used : names) if (byName.containsKey(used)) direct.add(byName.get(used));
        }
        // Assets may read one another (a View over a ref_sql model), so spread until stable.
        for (boolean changed = true; changed; ) {
            changed = false;
            for (var entry : uses.entrySet()) {
                Set<Fact> target = reads.get(entry.getKey());
                for (String used : entry.getValue()) {
                    Set<Fact> inherited = reads.get(used);
                    if (inherited != null && target != inherited)
                        changed |= target.addAll(inherited);
                }
            }
        }

        List<SqlWindowGate.Rule> rules = new ArrayList<>();
        List<String> unconfigured = new ArrayList<>();
        for (String asset : assets.keySet()) {
            Set<Fact> read = reads.get(lower(asset));
            if (read.isEmpty()) continue;
            List<String> time = columns.getOrDefault(asset, List.of());
            if (time.isEmpty()) {
                unconfigured.add(asset);
                continue;
            }
            int days = read.stream().mapToInt(Fact::maxWindowDays).min().orElseThrow();
            rules.add(new SqlWindowGate.Rule(asset, time, days));
        }
        return new Derived(List.copyOf(rules), List.copyOf(unconfigured));
    }

    /** Identifiers a statement mentions; a same-named column only ever tightens the gate. */
    static Set<String> names(String sql) {
        Set<String> names = new HashSet<>();
        Matcher matcher = NAME.matcher(NOT_CODE.matcher(sql == null ? "" : sql).replaceAll(" "));
        while (matcher.find()) names.add(lower(matcher.group()));
        return names;
    }

    private static Map<String, List<String>> timeColumns(Path file) throws IOException {
        if (!Files.isRegularFile(file)) return Map.of();
        return JSON.readValue(file.toFile(), new TypeReference<>() {});
    }

    private static String lower(String value) {
        return value.toLowerCase(Locale.ROOT);
    }
}
