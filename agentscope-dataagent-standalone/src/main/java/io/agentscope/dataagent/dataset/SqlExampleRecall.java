package io.agentscope.dataagent.dataset;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/** Adapts published examples and official CLI responses without implementing retrieval. */
public final class SqlExampleRecall {
    private SqlExampleRecall() {}

    public record Example(String question, String sql, String content) {}

    public static List<Example> published(List<String> markdown) {
        List<Example> result = new ArrayList<>();
        for (String text : markdown) {
            if (!text.startsWith("---\n")) continue;
            int end = text.indexOf("\n---", 4);
            if (end < 0) continue;
            try {
                Object document =
                        new Yaml(new SafeConstructor(new LoaderOptions()))
                                .load(text.substring(4, end));
                if (!(document instanceof java.util.Map<?, ?> fields)) continue;
                Object nl = fields.get("nl");
                Object sql = fields.get("sql");
                if (nl instanceof String q
                        && sql instanceof String s
                        && !q.isBlank()
                        && !s.isBlank()) result.add(new Example(q, s, text));
            } catch (RuntimeException ignored) {
                // Malformed examples are never presented as reusable queries.
            }
        }
        return result;
    }

    /** Parses the official response without changing its order or applying local ranking. */
    public static List<Example> results(String output) {
        if ("No results found.".equals(output.trim())) return List.of();
        try {
            JsonNode rows = new ObjectMapper().readTree(output);
            if (rows == null || !rows.isArray()) throw new IllegalArgumentException();
            List<Example> results = new ArrayList<>();
            for (JsonNode row : rows) {
                String nl = row.path(row.has("nl_query") ? "nl_query" : "nl").asText("");
                String sql = row.path(row.has("sql_query") ? "sql_query" : "sql").asText("");
                if (nl.isBlank() || sql.isBlank()) throw new IllegalArgumentException();
                results.add(new Example(nl, sql, "业务问题：" + nl + "\n```sql\n" + sql + "\n```"));
            }
            return results;
        } catch (Exception e) {
            throw new DatasetException("官方 Wren 召回结果格式错误", e);
        }
    }
}
