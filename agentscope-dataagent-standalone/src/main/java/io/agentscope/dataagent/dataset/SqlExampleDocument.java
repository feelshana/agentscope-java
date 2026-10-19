package io.agentscope.dataagent.dataset;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/** Parses the official Markdown envelope without normalizing SQL or unrelated body text. */
final class SqlExampleDocument {
    private static final Pattern START = Pattern.compile("\\A\\uFEFF?---[ \\t]*\\r?\\n");
    private static final Pattern END = Pattern.compile("(?m)^---[ \\t]*(?:\\r?\\n|\\z)");
    private static final Pattern FENCE = Pattern.compile("(?im)^(`{3,}|~{3,})sql[ \\t]*\\r?$");
    private final Map<String, Object> fields;
    private final String body;
    private final String newline;

    private SqlExampleDocument(Map<String, Object> fields, String body, String newline) {
        this.fields = fields;
        this.body = body;
        this.newline = newline;
    }

    static SqlExampleDocument parse(String text) {
        if (text.length() > 1_000_000) throw invalid();
        var start = START.matcher(text);
        if (!start.find()) throw invalid();
        var end = END.matcher(text);
        if (!end.find(start.end())) throw invalid();
        try {
            LoaderOptions options = new LoaderOptions();
            options.setAllowDuplicateKeys(false);
            options.setMaxAliasesForCollections(20);
            options.setCodePointLimit(1_000_000);
            Object loaded =
                    new Yaml(new SafeConstructor(options))
                            .load(text.substring(start.end(), end.start()));
            if (!(loaded instanceof Map<?, ?> map)) throw invalid();
            Map<String, Object> fields = new LinkedHashMap<>();
            for (var entry : map.entrySet()) {
                if (!(entry.getKey() instanceof String key)) throw invalid();
                fields.put(key, entry.getValue());
            }
            if (!(fields.get("nl") instanceof String q)
                    || q.isBlank()
                    || !(fields.get("sql") instanceof String s)
                    || s.isBlank()) throw invalid();
            return new SqlExampleDocument(
                    fields,
                    text.substring(end.end()),
                    text.substring(0, start.end()).contains("\r\n") ? "\r\n" : "\n");
        } catch (RuntimeException e) {
            if (e instanceof DatasetException d) throw d;
            throw invalid();
        }
    }

    String question() {
        return (String) fields.get("nl");
    }

    String sql() {
        return (String) fields.get("sql");
    }

    String update(String question, String sql) {
        Map<String, Object> updated = new LinkedHashMap<>(fields);
        updated.put("nl", question);
        updated.put("sql", sql);
        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        options.setSplitLines(false);
        options.setWidth(Integer.MAX_VALUE);
        String header = new Yaml(options).dump(updated).replace("\n", newline);
        String result = "---" + newline + header + "---" + newline + updateBody(sql);
        var check = parse(result);
        if (!question.equals(check.question()) || !sql.equals(check.sql())) throw invalid();
        return result;
    }

    private String updateBody(String sql) {
        var opening = FENCE.matcher(body);
        if (!opening.find()) return body;
        String fence = opening.group(1);
        var closing =
                Pattern.compile("(?m)^" + Pattern.quote(fence) + fence.charAt(0) + "*[ \\t]*\\r?$")
                        .matcher(body);
        if (!closing.find(opening.end())) throw invalid();
        int longest = 2;
        var runs =
                Pattern.compile(Pattern.quote(String.valueOf(fence.charAt(0))) + "+").matcher(sql);
        while (runs.find()) longest = Math.max(longest, runs.group().length());
        String safeFence =
                String.valueOf(fence.charAt(0)).repeat(Math.max(fence.length(), longest + 1));
        return body.substring(0, opening.start())
                + safeFence
                + "sql"
                + newline
                + sql
                + (sql.endsWith("\n") ? "" : newline)
                + safeFence
                + body.substring(closing.end());
    }

    private static DatasetException invalid() {
        return new DatasetException("SQL问答对文件格式异常，请核对原始Markdown/YAML；未覆盖文件", 400);
    }
}
