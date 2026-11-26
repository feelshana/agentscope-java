package io.agentscope.dataagent.dataset;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * One table's caliber list (spec149): the usage scenario and the named calibers, each a fixed set
 * of conditions over the table's columns. It is the single source of truth for the generated
 * caliber View and Cube; the parser only checks structure, column existence is checked by the
 * compiler against the model.
 */
public record CaliberList(
        String model,
        String name,
        Scenario scenario,
        String unrestricted,
        List<Caliber> calibers,
        Map<String, String> documents) {

    public static final String DIRECTORY = "calibers";
    static final String DEFAULT_UNRESTRICTED = "不限";
    static final Pattern IDENTIFIER = Pattern.compile("[\\p{L}_][\\p{L}\\p{N}_]{0,63}");
    private static final Pattern ASCII_IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_]{0,63}");
    private static final Set<String> FORMATS = Set.of("yyyymm", "yyyymmdd", "date");
    private static final Set<String> GRAINS = Set.of("day", "month");
    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

    /**
     * spec149 P5: a measure's wordings, and whether it answers a question that names only the
     * table's audience ("会员数"). At most one measure of a list is the default.
     */
    public record Measure(
            String column,
            String name,
            String label,
            String description,
            List<String> synonyms,
            boolean isDefault,
            // spec149 P7-4: shown with every value; omitted when the document gives none.
            String unit,
            // spec149 P7-4: "month_to_date" when a day's row holds the month so far, so a month
            // is read from its last day; null when a row holds only its own period.
            String period) {
        public static final String MONTH_TO_DATE = "month_to_date";

        public Measure(
                String column,
                String name,
                String label,
                String description,
                List<String> synonyms,
                boolean isDefault) {
            this(column, name, label, description, synonyms, isDefault, null, null);
        }
    }

    /** spec149 P5: a table selection key (business or audience) and its wordings. */
    public record Key(String name, List<String> synonyms) {
        public List<String> all() {
            List<String> out = new ArrayList<>();
            out.add(name);
            out.addAll(synonyms);
            return out;
        }
    }

    /**
     * spec149 P5: a scope dimension (province, channel, payment) kept as a column of the generated
     * View and a dimension of the Cube. Summary rows ({@code total}) and detail rows coexist, so a
     * query must pick values or group by it; {@code defaultValue} is used when the user names
     * none. {@code values}, when listed, are the only values a query may filter on.
     */
    public record Scope(
            String column,
            String name,
            String label,
            String total,
            String defaultValue,
            List<String> values) {}

    public record Time(String column, String grain, String format) {}

    public record Scenario(
            String subject,
            String description,
            Time time,
            List<Measure> measures,
            Map<String, String> fixedScope,
            List<String> useFor,
            List<String> notFor,
            Key business,
            Key audience,
            List<Scope> scope) {
        public Measure defaultMeasure() {
            return measures.stream().filter(Measure::isDefault).findFirst().orElse(null);
        }
    }

    /** {@code where} maps a column to one value or several candidates (IN). */
    public record Caliber(
            String name,
            String group,
            Map<String, List<String>> where,
            List<String> synonyms,
            String note,
            String pending,
            // "sum" when a person confirmed that rows of several candidate values add up.
            String combine,
            Map<String, Object> source,
            Map<String, Object> conflict,
            // spec149 P7-4: a caliber's own unit, e.g. one key-value KPI per caliber.
            String unit) {
        public Caliber(
                String name,
                String group,
                Map<String, List<String>> where,
                List<String> synonyms,
                String note,
                String pending,
                String combine,
                Map<String, Object> source,
                Map<String, Object> conflict) {
            this(name, group, where, synonyms, note, pending, combine, source, conflict, null);
        }
    }

    /** The parsed list, or the problems that make it unusable (Chinese, shown to the assistant). */
    public record Parsed(CaliberList list, List<String> errors) {
        public boolean ok() {
            return list != null && errors.isEmpty();
        }
    }

    /** Base name of the generated View ({@code <name>_caliber}) and Cube ({@code <name>_metrics}). */
    public String viewName() {
        return name + "_caliber";
    }

    public String cubeName() {
        return name + "_metrics";
    }

    public static Parsed parse(String fileName, String yaml) {
        List<String> errors = new ArrayList<>();
        Map<String, Object> root;
        try {
            Object value = YAML.readValue(yaml == null ? "" : yaml, Object.class);
            root = map(value);
        } catch (IOException e) {
            return new Parsed(null, List.of(fileName + " 不是合法的 YAML：" + firstLine(e.getMessage())));
        }
        if (root == null) return new Parsed(null, List.of(fileName + " 必须是一个 YAML 对象"));
        unknown(root, ROOT_KEYS, "清单顶层", errors);

        String model = text(root.get("model"));
        if (model == null || !IDENTIFIER.matcher(model).matches()) errors.add("model 必须写成对应的模型名");
        String name = text(root.get("name"));
        if (name == null) name = model;
        if (name != null && !ASCII_IDENTIFIER.matcher(name).matches())
            errors.add("name 只能用字母、数字和下划线（用于生成 <name>_caliber 与 <name>_metrics）；模型名含中文时请另写 name");

        Map<String, Object> scenarioMap = map(root.get("scenario"));
        Scenario scenario = null;
        if (scenarioMap == null) {
            errors.add("缺少 scenario（表说明）");
        } else {
            scenario = scenario(scenarioMap, errors);
        }

        Map<String, Object> conventions = map(root.get("conventions"));
        if (conventions != null) unknown(conventions, CONVENTION_KEYS, "conventions", errors);
        String unrestricted =
                conventions == null || text(conventions.get("unrestricted")) == null
                        ? DEFAULT_UNRESTRICTED
                        : text(conventions.get("unrestricted"));

        List<Caliber> calibers = new ArrayList<>();
        Set<String> names = new LinkedHashSet<>();
        if (!(root.get("calibers") instanceof List<?> rows) || rows.isEmpty()) {
            errors.add("calibers 至少要有一个口径");
        } else {
            for (int i = 0; i < rows.size(); i++) {
                Caliber caliber = caliber(rows.get(i), i + 1, errors);
                if (caliber == null) continue;
                if (!names.add(caliber.name().toLowerCase(Locale.ROOT)))
                    errors.add("口径名重复：" + caliber.name());
                calibers.add(caliber);
            }
        }

        Map<String, String> documents = new LinkedHashMap<>();
        Map<String, Object> docs = map(root.get("documents"));
        if (docs != null) docs.forEach((k, v) -> documents.put(k, text(v)));

        CaliberList list =
                new CaliberList(
                        model, name, scenario, unrestricted, List.copyOf(calibers), documents);
        return new Parsed(errors.isEmpty() ? list : null, List.copyOf(errors));
    }

    private static Scenario scenario(Map<String, Object> m, List<String> errors) {
        unknown(m, SCENARIO_KEYS, "scenario", errors);
        String subject = text(m.get("subject"));
        if (subject == null) errors.add("scenario.subject（这张表统计什么）不能为空");
        Time time = null;
        Map<String, Object> t = map(m.get("time"));
        if (t != null) {
            unknown(t, TIME_KEYS, "scenario.time", errors);
            String column = text(t.get("column"));
            String grain = lower(text(t.get("grain")));
            String format = lower(text(t.get("format")));
            if (column == null || !IDENTIFIER.matcher(column).matches())
                errors.add("scenario.time.column 必须是时间列名");
            if (grain == null || !GRAINS.contains(grain))
                errors.add("scenario.time.grain 只能是 day 或 month");
            if (format == null || !FORMATS.contains(format))
                errors.add("scenario.time.format 只能是 yyyymm、yyyymmdd 或 date");
            time = new Time(column, grain, format);
        }
        List<Measure> measures = new ArrayList<>();
        if (!(m.get("measures") instanceof List<?> rows) || rows.isEmpty()) {
            errors.add("scenario.measures 至少要有一个度量");
        } else {
            Set<String> seen = new LinkedHashSet<>();
            for (Object row : rows) {
                Map<String, Object> r = map(row);
                String column = r == null ? null : text(r.get("column"));
                String name = r == null ? null : text(r.get("name"));
                if (column == null || !IDENTIFIER.matcher(column).matches()) {
                    errors.add("scenario.measures 的每项都要写 column（度量列名）");
                    continue;
                }
                if (name == null || !ASCII_IDENTIFIER.matcher(name).matches()) {
                    errors.add("度量 " + column + " 的 name 只能用字母、数字和下划线");
                    continue;
                }
                unknown(r, MEASURE_KEYS, "度量 " + column, errors);
                if (name.equalsIgnoreCase(column))
                    errors.add(
                            "度量 "
                                    + column
                                    + " 的 name 不能与列名相同（Wren 会报循环依赖），例如写成 "
                                    + column
                                    + "_total");
                if (!seen.add(name.toLowerCase(Locale.ROOT))) errors.add("度量名重复：" + name);
                measures.add(
                        new Measure(
                                column,
                                name,
                                text(r.get("label")),
                                text(r.get("description")),
                                texts(r.get("synonyms")),
                                Boolean.TRUE.equals(r.get("default"))
                                        || "true".equalsIgnoreCase(text(r.get("default"))),
                                text(r.get("unit")),
                                period(text(r.get("period")), column, errors)));
            }
        }
        Map<String, String> fixedScope = new LinkedHashMap<>();
        Map<String, Object> fixed = map(m.get("fixed_scope"));
        if (fixed != null)
            fixed.forEach(
                    (column, value) -> {
                        if (!IDENTIFIER.matcher(column).matches() || text(value) == null)
                            errors.add("scenario.fixed_scope 的每项写成 列名: 取值");
                        else fixedScope.put(column, text(value));
                    });
        if ((time == null || !"day".equals(time.grain()))
                && measures.stream().anyMatch(x -> Measure.MONTH_TO_DATE.equals(x.period())))
            errors.add("period: month_to_date 只用于按日统计的表（scenario.time.grain 为 day）");
        if (measures.stream().filter(Measure::isDefault).count() > 1)
            errors.add("scenario.measures 中只能有一个 default: true 的默认指标");
        List<Scope> scope = scope(m.get("scope"), fixedScope, measures, errors);
        return new Scenario(
                subject,
                text(m.get("description")),
                time,
                List.copyOf(measures),
                fixedScope,
                texts(m.get("use_for")),
                texts(m.get("not_for")),
                key(m.get("business"), "scenario.business", errors),
                key(m.get("audience"), "scenario.audience", errors),
                scope);
    }

    /** Names the generated View and Cube already use; scope dimensions must not take them. */
    static final Set<String> RESERVED_MEMBERS =
            Set.of(
                    "caliber",
                    "caliber_system",
                    "caliber_rule",
                    "caliber_name",
                    "caliber_group",
                    "caliber_condition",
                    "caliber_note",
                    "period",
                    "period_date");

    private static List<Scope> scope(
            Object value, Map<String, String> fixed, List<Measure> measures, List<String> errors) {
        if (value == null) return List.of();
        if (!(value instanceof List<?> rows)) {
            errors.add("scenario.scope 写成列表，每项包含 column、name、default");
            return List.of();
        }
        Set<String> names = new LinkedHashSet<>();
        Set<String> columns = new LinkedHashSet<>();
        measures.forEach(x -> names.add(x.name().toLowerCase(Locale.ROOT)));
        List<Scope> out = new ArrayList<>();
        for (Object row : rows) {
            Map<String, Object> r = map(row);
            String column = r == null ? null : text(r.get("column"));
            String name = r == null ? null : text(r.get("name"));
            if (column == null || !IDENTIFIER.matcher(column).matches()) {
                errors.add("scenario.scope 的每项都要写 column（范围列名）");
                continue;
            }
            if (name == null || !ASCII_IDENTIFIER.matcher(name).matches()) {
                errors.add("范围列 " + column + " 的 name 只能用字母、数字和下划线");
                continue;
            }
            unknown(r, SCOPE_KEYS, "范围列 " + column, errors);
            if (name.equalsIgnoreCase(column))
                errors.add("范围列 " + column + " 的 name 不能与列名相同（Wren 会报循环依赖），例如写成 province");
            if (RESERVED_MEMBERS.contains(name.toLowerCase(Locale.ROOT))
                    || !names.add(name.toLowerCase(Locale.ROOT)))
                errors.add("范围维度名 " + name + " 与度量或生成的列重名，请换一个名字");
            if (!columns.add(column.toLowerCase(Locale.ROOT))) errors.add("范围列重复：" + column);
            if (fixed.keySet().stream().anyMatch(column::equalsIgnoreCase))
                errors.add("列 " + column + " 不能同时写在 fixed_scope 和 scope 中");
            String defaultValue = text(r.get("default"));
            if (defaultValue == null)
                errors.add(
                        "范围列 "
                                + column
                                + " 要写 default（用户没指定时取的值）；文档没写时写汇总值，并在 scenario.description"
                                + " 末尾以“待业务确认：”注明");
            List<String> values = texts(r.get("values"));
            if (defaultValue != null && !values.isEmpty() && !values.contains(defaultValue))
                errors.add("范围列 " + column + " 的 default 不在 values 中");
            // spec149 P7-4: values listing only the summary would refuse every detail value
            // ("浙江") at query time; without listed detail values nothing is written.
            String total = text(r.get("total"));
            if (!values.isEmpty()
                    && values.stream().allMatch(v -> v.equals(total) || v.equals(defaultValue)))
                errors.add("范围列 " + column + " 的 values 只写了汇总值，问数时会拒绝所有明细取值；文档没有列出明细取值时不写 values");
            out.add(
                    new Scope(
                            column,
                            name,
                            text(r.get("label")),
                            text(r.get("total")),
                            defaultValue,
                            values));
        }
        return List.copyOf(out);
    }

    private static Key key(Object value, String field, List<String> errors) {
        if (value == null) return null;
        if (value instanceof Map<?, ?>) {
            Map<String, Object> m = map(value);
            unknown(m, KEY_KEYS, field, errors);
            String name = text(m.get("name"));
            if (name == null) {
                errors.add(field + " 写成 名称，或 {name: 名称, synonyms: [说法]}");
                return null;
            }
            return new Key(name, texts(m.get("synonyms")));
        }
        String name = text(value);
        return name == null ? null : new Key(name, List.of());
    }

    private static Caliber caliber(Object row, int index, List<String> errors) {
        Map<String, Object> r = map(row);
        String name = r == null ? null : text(r.get("name"));
        if (name == null) {
            errors.add("第 " + index + " 个口径缺少 name");
            return null;
        }
        if (name.contains("'") || name.contains(",") || name.length() > 80) {
            errors.add("口径名不能含单引号或英文逗号且不超过 80 字：" + name);
            return null;
        }
        unknown(r, CALIBER_KEYS, "口径「" + name + "」", errors);
        Map<String, List<String>> where = new LinkedHashMap<>();
        Map<String, Object> w = map(r.get("where"));
        if (w == null || w.isEmpty()) {
            errors.add("口径「" + name + "」缺少 where（取数条件）");
        } else {
            for (Map.Entry<String, Object> e : w.entrySet()) {
                List<String> values = texts(e.getValue());
                if (!IDENTIFIER.matcher(e.getKey()).matches() || values.isEmpty())
                    errors.add("口径「" + name + "」的条件写成 列名: 取值 或 列名: [取值1, 取值2]");
                else where.put(e.getKey(), values);
            }
        }
        return new Caliber(
                name,
                text(r.get("group")),
                where,
                texts(r.get("synonyms")),
                text(r.get("note")),
                text(r.get("pending")),
                text(r.get("combine")),
                map(r.get("source")),
                map(r.get("conflict")),
                text(r.get("unit")));
    }

    private static String period(String value, String column, List<String> errors) {
        if (value == null) return null;
        if (Measure.MONTH_TO_DATE.equals(value)) return value;
        errors.add("度量 " + column + " 的 period 只能写 month_to_date（月累计：每月最后一天的值为当月值），其余不写");
        return null;
    }

    // ------------------------------------------------------------------ field names

    // 2026-11-25: unknown fields used to be skipped silently, so a "format" on a scope or a
    // "pending" on a measure looked written but never reached the Cube or the query assistant.
    private static final List<String> ROOT_KEYS =
            List.of("model", "name", "scenario", "conventions", "calibers", "documents");
    private static final List<String> SCENARIO_KEYS =
            List.of(
                    "subject",
                    "description",
                    "business",
                    "audience",
                    "time",
                    "measures",
                    "scope",
                    "fixed_scope",
                    "use_for",
                    "not_for");
    private static final List<String> TIME_KEYS = List.of("column", "grain", "format");
    private static final List<String> MEASURE_KEYS =
            List.of(
                    "column",
                    "name",
                    "label",
                    "description",
                    "synonyms",
                    "default",
                    "unit",
                    "period");
    private static final List<String> SCOPE_KEYS =
            List.of("column", "name", "label", "total", "default", "values");
    private static final List<String> KEY_KEYS = List.of("name", "synonyms");
    private static final List<String> CONVENTION_KEYS = List.of("unrestricted");
    private static final List<String> CALIBER_KEYS =
            List.of(
                    "name",
                    "group",
                    "where",
                    "synonyms",
                    "note",
                    "pending",
                    "combine",
                    "source",
                    "conflict",
                    "unit");

    /** Reports fields the list format does not have, so nothing written is dropped unseen. */
    private static void unknown(
            Map<String, Object> m, List<String> allowed, String where, List<String> errors) {
        List<String> extra = m.keySet().stream().filter(k -> !allowed.contains(k)).toList();
        if (extra.isEmpty()) return;
        errors.add(
                where
                        + " 不支持字段 "
                        + String.join("、", extra)
                        + "（可写："
                        + String.join("、", allowed)
                        + "）。待确认事项写在口径的 pending，表级的写在 scenario.description 末尾、以“待业务确认：”开头；"
                        + "范围取值写法等说明写进 scenario.description，指标说明写进度量的 description，口径说明写进 note。");
    }

    // ------------------------------------------------------------------ YAML helpers

    @SuppressWarnings("unchecked")
    static Map<String, Object> map(Object value) {
        if (!(value instanceof Map<?, ?> m)) return null;
        Map<String, Object> out = new LinkedHashMap<>();
        ((Map<Object, Object>) m).forEach((k, v) -> out.put(String.valueOf(k), v));
        return out;
    }

    static String text(Object value) {
        if (value == null) return null;
        String s = String.valueOf(value).strip();
        return s.isEmpty() ? null : s;
    }

    static List<String> texts(Object value) {
        List<String> out = new ArrayList<>();
        if (value instanceof List<?> list) {
            for (Object item : list) if (text(item) != null) out.add(text(item));
        } else if (text(value) != null) {
            out.add(text(value));
        }
        return List.copyOf(out);
    }

    private static String lower(String value) {
        return value == null ? null : value.toLowerCase(Locale.ROOT);
    }

    private static String firstLine(String message) {
        if (message == null) return "";
        int nl = message.indexOf('\n');
        return nl < 0 ? message : message.substring(0, nl);
    }
}
