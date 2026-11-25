package io.agentscope.dataagent.dataset;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * spec149 P4: checks a {@code query_cube} call on a Cube generated from a caliber list before it
 * runs. Calibers overlap (a parent includes its children), so a query must filter one or more
 * calibers by name or group by caliber, and every name must be a caliber of the list. A refusal
 * returns the reason and the nearest calibers to the model; arguments are never rewritten and
 * nothing is routed. Cubes not generated from a list are not checked here.
 */
public final class CaliberQueryGate {
    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());
    private static final Set<String> SELECTING = Set.of("in", "eq");

    private CaliberQueryGate() {}

    /** Returns the refusal message, or null when the call may run. */
    public static String check(Path published, Map<String, Object> args) {
        String cube = CaliberList.text(args == null ? null : args.get("cube"));
        if (cube == null || !cube.matches("[A-Za-z0-9_]{1,128}")) return null;
        Map<String, Object> meta =
                read(published.resolve("cubes").resolve(cube).resolve("metadata.yml"));
        Map<String, Object> props = meta == null ? null : CaliberList.map(meta.get("properties"));
        String listPath =
                props == null ? null : CaliberList.text(props.get(CaliberCompiler.GENERATED_BY));
        if (listPath == null) return null;
        String dimension = CaliberList.text(props.get("non_additive_dimension"));
        if (dimension == null) dimension = "caliber";
        CaliberList list = list(published, listPath);
        if (list == null) return null;
        String time = time(cube, list, args.get("time_dimension"));
        if (time != null) return time;

        boolean grouped = CaliberList.texts(args.get("dimensions")).contains(dimension);
        List<String> named = new ArrayList<>();
        for (String filter : CaliberList.texts(args.get("filters"))) {
            String[] parts = filter.split(":", 3);
            if (parts.length < 2 || !parts[0].equals(dimension)) continue;
            if (!SELECTING.contains(parts[1]) || parts.length < 3)
                return "指标 "
                        + cube
                        + " 的口径之间有包含关系，不能用 "
                        + parts[1]
                        + " 组合多个口径；请用 "
                        + dimension
                        + ":in:口径名 筛选，或在 dimensions 中加入 "
                        + dimension
                        + " 分别列出。";
            for (String value : parts[2].split(",")) if (!value.isBlank()) named.add(value.strip());
        }
        if (named.isEmpty() && !grouped)
            return "指标 "
                    + cube
                    + " 的口径之间有包含关系（例如父口径包含子口径），人数不能跨口径相加。请在 filters 中用 "
                    + dimension
                    + ":in:口径名 指定口径，或在 dimensions 中加入 "
                    + dimension
                    + " 按口径分别列出。可用口径："
                    + String.join("、", names(list))
                    + "。";
        String scope = scope(cube, list, args);
        if (scope != null) return scope;
        List<String> unknown = new ArrayList<>();
        List<String> hints = new ArrayList<>();
        for (String value : named) {
            if (names(list).contains(value)) continue;
            unknown.add(value);
            CaliberList.Caliber owner =
                    list.calibers().stream()
                            .filter(c -> c.synonyms().contains(value))
                            .findFirst()
                            .orElse(null);
            if (owner != null) hints.add("「" + value + "」是口径「" + owner.name() + "」的说法");
            else {
                List<String> near = nearest(list, value);
                hints.add(
                        "「"
                                + value
                                + "」"
                                + (near.isEmpty() ? "没有相近的口径" : "可能对应：" + String.join("、", near)));
            }
        }
        if (unknown.isEmpty()) return null;
        return "指标 "
                + cube
                + " 中没有口径 "
                + String.join("、", unknown)
                + "。"
                + String.join("；", hints)
                + "。筛选必须使用口径名原文；用户的说法可能对应多个口径或对不上任何口径时，先向用户确认是哪个口径，不要自行选择。";
    }

    /**
     * spec149 P5: summary and detail rows of a scope dimension coexist, so each one is filtered to
     * values or grouped; listed values are the only ones allowed.
     */
    private static String scope(String cube, CaliberList list, Map<String, Object> args) {
        if (list.scenario().scope().isEmpty()) return null;
        List<String> dimensions = CaliberList.texts(args.get("dimensions"));
        List<String> filters = CaliberList.texts(args.get("filters"));
        List<String> missing = new ArrayList<>();
        for (CaliberList.Scope d : list.scenario().scope()) {
            boolean grouped = dimensions.contains(d.name());
            List<String> values = new ArrayList<>();
            boolean filtered = false;
            for (String filter : filters) {
                String[] parts = filter.split(":", 3);
                if (parts.length < 2 || !parts[0].equals(d.name())) continue;
                if (!SELECTING.contains(parts[1]) || parts.length < 3)
                    return "指标 "
                            + cube
                            + " 的"
                            + CaliberCompiler.label(d)
                            + "有汇总值和明细值，不能用 "
                            + parts[1]
                            + " 组合；请用 "
                            + d.name()
                            + ":eq:取值 筛选，或在 dimensions 中加入 "
                            + d.name()
                            + " 分别列出。";
                filtered = true;
                for (String v : parts[2].split(",")) if (!v.isBlank()) values.add(v.strip());
            }
            if (!filtered && !grouped) {
                missing.add(d.name() + ":eq:" + d.defaultValue());
                continue;
            }
            if (d.values().isEmpty()) continue;
            for (String v : values)
                if (!d.values().contains(v)) {
                    List<String> near = nearestValues(d.values(), v);
                    return "指标 "
                            + cube
                            + " 的"
                            + CaliberCompiler.label(d)
                            + "没有取值「"
                            + v
                            + "」。"
                            + (near.isEmpty()
                                    ? "可选取值：" + String.join("、", d.values())
                                    : "可能对应：" + String.join("、", near))
                            + "。请用取值原文；对应不上时先向用户确认。";
                }
        }
        if (missing.isEmpty()) return null;
        return "指标 "
                + cube
                + " 的范围维度有汇总值和明细值，每个都要指定取值或分组，否则会把汇总行和明细行相加。"
                + "用户没说的范围请用默认值，在 filters 中加入："
                + String.join("、", missing)
                + "。";
    }

    /**
     * spec149 P7: each row is a finished statistic of one day or one month, so the time dimension
     * is required and grouped at the table's own grain; a coarser grain would add the rows of a
     * week or month (daily active users summed into a month counts a user once per day).
     */
    private static String time(String cube, CaliberList list, Object value) {
        CaliberList.Time t = list.scenario().time();
        if (t == null) return null;
        String grain = t.grain();
        String unit = "month".equals(grain) ? "月" : "日";
        String example =
                "month".equals(grain)
                        ? "period:month:2026-10-01,2026-11-01"
                        : "period:day:2026-10-01,2026-10-02";
        String spec = value instanceof String s ? s.strip() : null;
        if (spec == null || spec.isEmpty())
            return "指标 "
                    + cube
                    + " 的每行是某一"
                    + unit
                    + "预先算好的统计结果，必须写 time_dimension 并按 "
                    + grain
                    + " 粒度分组，例如 "
                    + example
                    + "（结束日期不包含）；不写时间会把所有"
                    + unit
                    + "的值相加。";
        String[] parts = spec.split(":", 3);
        String granularity =
                parts.length > 1 ? parts[1].strip().toLowerCase(java.util.Locale.ROOT) : "";
        if (!parts[0].strip().equals("period") || !granularity.equals(grain))
            return "指标 "
                    + cube
                    + " 的每行是某一"
                    + unit
                    + "预先算好的统计结果，time_dimension 只能写 period:"
                    + grain
                    + ":开始,结束（例如 "
                    + example
                    + "）；按更粗的粒度会把多"
                    + unit
                    + "的值相加，结果错误。问一段时间就按"
                    + unit
                    + "逐个列出；问月活等跨周期的数，读文档定义的那一"
                    + unit
                    + "（如月末）的值，文档没定义时先问用户，不要相加或平均。";
        return null;
    }

    /**
     * spec149 P7: whether a Cube was generated from a caliber list. Its rows are finished
     * statistics of different calibers, periods or scopes, so they are never totalled.
     */
    public static boolean generatedCube(Path published, Object cubeArgument) {
        String cube = CaliberList.text(cubeArgument);
        if (published == null || cube == null || !cube.matches("[A-Za-z0-9_]{1,128}")) return false;
        Map<String, Object> meta =
                read(published.resolve("cubes").resolve(cube).resolve("metadata.yml"));
        Map<String, Object> props = meta == null ? null : CaliberList.map(meta.get("properties"));
        return props != null && CaliberList.text(props.get(CaliberCompiler.GENERATED_BY)) != null;
    }

    /**
     * 2026-11-25 (user decision): results of different calibers are never added, not even when
     * the user asks for a total (the model added 篮球通 and 足球通 by itself). A result with several
     * rows carries this note; one row needs none.
     */
    public static String noSumNote(String payload) {
        if (payload == null) return "";
        try {
            com.fasterxml.jackson.databind.JsonNode rows =
                    new ObjectMapper().readTree(payload.strip()).path("rows");
            if (!rows.isArray() || rows.size() < 2) return "";
        } catch (IOException | RuntimeException e) {
            return "";
        }
        return "\n\n**口径结果说明（平台）：** 以上各行是不同口径、不同时间或不同范围预先算好的统计结果，"
                + "口径之间可能重叠或包含，任何情况下都不能相加。用户要求合计、加起来、一共时，分别列出各行数值，"
                + "说明这些人数不能相加（同一用户可能同时属于多个口径），不要给出合计，也不要用 run_python 相加。";
    }

    /**
     * spec149 P7: a generated measure is null when one result meets several rows (some dimension
     * was left open) or the row has no value. Such a result is refused instead of shown, since the
     * model would otherwise report it as missing data. Returns the refusal, or null.
     */
    public static String checkResult(Path published, Map<String, Object> args, String payload) {
        String cube = CaliberList.text(args == null ? null : args.get("cube"));
        if (cube == null || !cube.matches("[A-Za-z0-9_]{1,128}") || payload == null) return null;
        Map<String, Object> meta =
                read(published.resolve("cubes").resolve(cube).resolve("metadata.yml"));
        Map<String, Object> props = meta == null ? null : CaliberList.map(meta.get("properties"));
        if (props == null || CaliberList.text(props.get(CaliberCompiler.GENERATED_BY)) == null)
            return null;
        List<String> measures = new ArrayList<>();
        for (String m : CaliberList.texts(args.get("measures")))
            for (String part : m.split(",")) if (!part.isBlank()) measures.add(part.strip());
        if (measures.isEmpty()) return null;
        try {
            com.fasterxml.jackson.databind.JsonNode root =
                    new ObjectMapper().readTree(payload.strip());
            com.fasterxml.jackson.databind.JsonNode rows = root.path("rows");
            if (!rows.isArray()) return null;
            List<String> columns = new ArrayList<>();
            for (com.fasterxml.jackson.databind.JsonNode c : root.path("columns"))
                columns.add(c.isTextual() ? c.asText() : c.path("name").asText());
            int empty = 0;
            for (com.fasterxml.jackson.databind.JsonNode row : rows)
                for (String m : measures) {
                    com.fasterxml.jackson.databind.JsonNode v =
                            row.isArray() ? row.path(columns.indexOf(m)) : row.path(m);
                    if (columns.contains(m) || row.isObject())
                        if (v.isMissingNode() || v.isNull()) empty++;
                }
            if (empty == 0) return null;
            return "指标 "
                    + cube
                    + " 有 "
                    + empty
                    + " 个结果为空：每个结果只能对应一行统计数据，为空说明对应了多行（有维度没定下来：口径、每个范围维度都要筛选单值或分组，时间按表的粒度分组），或该行本身没有值。请检查后重新查询，不要把空值当作"
                    + " 0，也不要改用相加的方式。";
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    private static List<String> nearestValues(List<String> values, String value) {
        record Scored(String value, int score) {}
        return values.stream()
                .map(v -> new Scored(v, common(v, value) + prefix(v, value)))
                .filter(s -> common(s.value(), value) >= Math.min(2, value.length()))
                .sorted(Comparator.comparingInt(Scored::score).reversed())
                .limit(5)
                .map(Scored::value)
                .toList();
    }

    /**
     * spec149 P5: a table with a published caliber list is read through its Cube; a raw SQL query
     * on it would rebuild the six-level conditions by guessing. Returns the refusal, or null.
     */
    public static String checkSql(Path published, String sql) {
        if (sql == null || published == null) return null;
        // The source table and the generated View both carry overlapping calibers and summary
        // rows; only the Cube's query gate keeps them apart.
        Map<String, String> cubes = new java.util.LinkedHashMap<>();
        for (Map.Entry<String, String> entry : CaliberCompiler.lists(published).entrySet()) {
            CaliberList.Parsed parsed = CaliberList.parse(entry.getKey(), entry.getValue());
            if (!parsed.ok()) continue;
            cubes.put(parsed.list().model(), parsed.list().cubeName());
            cubes.put(parsed.list().viewName(), parsed.list().cubeName());
        }
        if (cubes.isEmpty()) return null;
        String code = stripLiterals(sql);
        for (Map.Entry<String, String> e : cubes.entrySet()) {
            java.util.regex.Pattern table =
                    java.util.regex.Pattern.compile(
                            "(?i)(?<![\\p{L}\\p{N}_])"
                                    + java.util.regex.Pattern.quote(e.getKey())
                                    + "(?![\\p{L}\\p{N}_])");
            if (table.matcher(code).find())
                return "表 "
                        + e.getKey()
                        + " 属于已发布的口径清单，请用指标 "
                        + e.getValue()
                        + " 的 query_cube 按口径名取数，不要对原表写 SQL 拼取数条件；清单里没有用户要的口径或范围时，告诉用户并给出最接近的口径。";
        }
        return null;
    }

    /** SQL with string literals and comments blanked, so only identifiers are matched. */
    static String stripLiterals(String sql) {
        StringBuilder out = new StringBuilder(sql.length());
        int i = 0;
        while (i < sql.length()) {
            char c = sql.charAt(i);
            if (c == '\'') {
                int j = i + 1;
                while (j < sql.length()) {
                    if (sql.charAt(j) == '\'' && j + 1 < sql.length() && sql.charAt(j + 1) == '\'')
                        j += 2;
                    else if (sql.charAt(j) == '\'') break;
                    else j++;
                }
                out.append(' ');
                i = j + 1;
            } else if (c == '-' && i + 1 < sql.length() && sql.charAt(i + 1) == '-') {
                int j = sql.indexOf('\n', i);
                i = j < 0 ? sql.length() : j;
            } else if (c == '/' && i + 1 < sql.length() && sql.charAt(i + 1) == '*') {
                int j = sql.indexOf("*/", i + 2);
                i = j < 0 ? sql.length() : j + 2;
            } else if (c == '"' || c == '`') {
                out.append(' ');
                i++;
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }

    private static List<String> names(CaliberList list) {
        return list.calibers().stream().map(CaliberList.Caliber::name).toList();
    }

    /**
     * Calibers sharing a run of at least two characters with the user's wording, closest first.
     * A shared beginning weighs extra: in "足球会员" the subject is "足球", while "会员" is a
     * generic suffix many calibers share.
     */
    static List<String> nearest(CaliberList list, String value) {
        record Scored(String name, int score) {}
        List<Scored> scored = new ArrayList<>();
        for (CaliberList.Caliber c : list.calibers()) {
            int overlap = common(c.name(), value);
            int score = overlap + prefix(c.name(), value);
            for (String s : c.synonyms()) {
                overlap = Math.max(overlap, common(s, value));
                score = Math.max(score, common(s, value) + prefix(s, value));
            }
            if (overlap >= 2) scored.add(new Scored(c.name(), score));
        }
        Set<String> out = new LinkedHashSet<>();
        scored.stream()
                .sorted(Comparator.comparingInt(Scored::score).reversed())
                .limit(5)
                .forEach(s -> out.add(s.name()));
        return List.copyOf(out);
    }

    private static int prefix(String a, String b) {
        int n = 0;
        while (n < a.length() && n < b.length() && a.charAt(n) == b.charAt(n)) n++;
        return n;
    }

    /** Length of the longest common substring. */
    private static int common(String a, String b) {
        int best = 0;
        int[] prev = new int[b.length() + 1];
        for (int i = 1; i <= a.length(); i++) {
            int[] cur = new int[b.length() + 1];
            for (int j = 1; j <= b.length(); j++)
                if (a.charAt(i - 1) == b.charAt(j - 1)) {
                    cur[j] = prev[j - 1] + 1;
                    best = Math.max(best, cur[j]);
                }
            prev = cur;
        }
        return best;
    }

    private static CaliberList list(Path published, String listPath) {
        if (!listPath.matches("calibers/[a-z0-9_-]{1,64}\\.yml")) return null;
        Path file = published.resolve(listPath);
        try {
            if (!Files.isRegularFile(file)) return null;
            return CaliberList.parse(listPath, Files.readString(file, StandardCharsets.UTF_8))
                    .list();
        } catch (IOException e) {
            return null;
        }
    }

    private static Map<String, Object> read(Path file) {
        try {
            return Files.isRegularFile(file)
                    ? CaliberList.map(YAML.readValue(file.toFile(), Object.class))
                    : null;
        } catch (IOException e) {
            return null;
        }
    }
}
