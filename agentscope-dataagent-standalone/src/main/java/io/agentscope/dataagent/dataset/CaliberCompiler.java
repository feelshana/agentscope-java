package io.agentscope.dataagent.dataset;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.dataformat.yaml.YAMLGenerator;
import io.agentscope.dataagent.dataset.MdlTypes.MdlIssue;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * Turns caliber lists into the View and Cube the official engine reads (spec149). Generated files
 * carry {@code properties.generated_by} naming their list; they are rewritten from the list and
 * never edited by hand, so a list change is the only way to change a caliber. Pure file I/O on a
 * workspace root: callers hold the workspace lock.
 */
public final class CaliberCompiler {
    public static final String GENERATED_BY = "generated_by";
    static final Set<String> VIEW_COLUMNS =
            Set.of(
                    "caliber_name",
                    "caliber_group",
                    "caliber_condition",
                    "caliber_note",
                    "period_date");
    private static final ObjectMapper READER = new ObjectMapper(new YAMLFactory());
    private static final ObjectMapper WRITER =
            new ObjectMapper(
                    YAMLFactory.builder()
                            .enable(YAMLGenerator.Feature.MINIMIZE_QUOTES)
                            .enable(YAMLGenerator.Feature.LITERAL_BLOCK_STYLE)
                            .disable(YAMLGenerator.Feature.WRITE_DOC_START_MARKER)
                            .build());

    private CaliberCompiler() {}

    /** Model columns by lower-cased name, with their declared type (may be empty). */
    public record ModelColumns(String model, Map<String, String> types) {}

    /** The two generated files, or why the list cannot be compiled against its model. */
    public record Compiled(
            CaliberList list,
            String viewPath,
            String view,
            String cubePath,
            String cube,
            List<String> errors) {
        public boolean ok() {
            return errors.isEmpty();
        }
    }

    public static Compiled compile(CaliberList list, String listPath, ModelColumns columns) {
        List<String> errors = new ArrayList<>();
        if (columns == null) {
            errors.add("模型 " + list.model() + " 不存在，请先完成该表的基础建模");
            return new Compiled(list, null, null, null, null, errors);
        }
        CaliberList.Scenario s = list.scenario();
        requireColumn(columns, s.time() == null ? null : s.time().column(), "时间列", errors);
        for (CaliberList.Measure m : s.measures()) {
            requireColumn(columns, m.column(), "度量列", errors);
            if (VIEW_COLUMNS.contains(m.name().toLowerCase(Locale.ROOT)))
                errors.add("度量名 " + m.name() + " 与生成视图的列名冲突，请换一个名字");
        }
        s.fixedScope().keySet().forEach(c -> requireColumn(columns, c, "固定范围列", errors));
        for (CaliberList.Scope d : s.scope()) {
            requireColumn(columns, d.column(), "范围列", errors);
            for (CaliberList.Caliber c : list.calibers())
                if (c.where().keySet().stream().anyMatch(d.column()::equalsIgnoreCase))
                    errors.add("口径「" + c.name() + "」的条件列 " + d.column() + " 已是范围维度，口径中不要再写");
        }
        for (CaliberList.Caliber c : list.calibers()) {
            for (Map.Entry<String, List<String>> w : c.where().entrySet()) {
                requireColumn(columns, w.getKey(), "口径「" + c.name() + "」的条件列", errors);
                // Pre-aggregated rows of several candidates add up; that must be a decision.
                if (w.getValue().size() > 1 && !"sum".equalsIgnoreCase(c.combine()))
                    errors.add(
                            "口径「"
                                    + c.name()
                                    + "」的 "
                                    + w.getKey()
                                    + " 写了多个取值，查询时会把这些行的数值相加；业务确认可以相加时写 combine: sum，否则拆成多个口径");
                if (w.getValue().size() > 1 && w.getValue().contains(list.unrestricted()))
                    errors.add(
                            "口径「"
                                    + c.name()
                                    + "」的 "
                                    + w.getKey()
                                    + " 既写了多个取值又写了"
                                    + list.unrestricted());
            }
        }
        if (!errors.isEmpty())
            return new Compiled(list, null, null, null, null, List.copyOf(errors));

        String viewPath = "views/" + list.viewName() + "/metadata.yml";
        String cubePath = "cubes/" + list.cubeName() + "/metadata.yml";
        return new Compiled(
                list,
                viewPath,
                write(view(list, listPath)),
                cubePath,
                write(cube(list, listPath, columns)),
                List.of());
    }

    // ------------------------------------------------------------------ rendering

    private static Map<String, Object> view(CaliberList list, String listPath) {
        List<String> parts = new ArrayList<>();
        for (CaliberList.Caliber c : list.calibers()) parts.add(select(list, c));
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("displayName", list.scenario().subject() + "口径");
        props.put(
                "description",
                "由口径清单 "
                        + listPath
                        + " 生成，每个口径每个时间一行：caliber_name 为口径名，caliber_group 为所属体系，"
                        + "caliber_condition 为取数条件，caliber_note 为说明与待确认事项。不同口径之间可能互相包含，人数不能相加。");
        props.put(GENERATED_BY, listPath);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("name", list.viewName());
        out.put("statement", String.join("\nUNION ALL\n", parts) + "\n");
        out.put("properties", props);
        return out;
    }

    /** One caliber's SELECT of the generated View, exactly as it is written into the View. */
    public static String segment(CaliberList list, CaliberList.Caliber c) {
        return select(list, c);
    }

    private static String select(CaliberList list, CaliberList.Caliber c) {
        CaliberList.Scenario s = list.scenario();
        StringBuilder sql = new StringBuilder("SELECT ");
        sql.append(literal(c.name())).append(" AS caliber_name, ");
        sql.append(literal(c.group() == null ? "" : c.group())).append(" AS caliber_group, ");
        sql.append(literal(condition(list, c))).append(" AS caliber_condition, ");
        sql.append(literal(note(c))).append(" AS caliber_note");
        if (s.time() != null)
            sql.append(", ").append(timeExpression(s.time())).append(" AS period_date");
        for (CaliberList.Scope d : s.scope())
            sql.append(", ").append(quote(d.column())).append(" AS ").append(quote(d.column()));
        for (CaliberList.Measure m : s.measures())
            sql.append(", ").append(quote(m.column())).append(" AS ").append(quote(m.column()));
        sql.append("\nFROM ").append(quote(list.model()));
        List<String> where = new ArrayList<>();
        Map<String, List<String>> merged = new LinkedHashMap<>();
        s.fixedScope().forEach((k, v) -> merged.put(k, List.of(v)));
        merged.putAll(c.where());
        for (Map.Entry<String, List<String>> e : merged.entrySet()) {
            List<String> values = e.getValue();
            if (values.size() == 1 && values.get(0).equals(list.unrestricted())) continue;
            where.add(
                    values.size() == 1
                            ? quote(e.getKey()) + " = " + literal(values.get(0))
                            : quote(e.getKey())
                                    + " IN ("
                                    + String.join(
                                            ", ",
                                            values.stream().map(CaliberCompiler::literal).toList())
                                    + ")");
        }
        if (!where.isEmpty()) sql.append("\nWHERE ").append(String.join("\n  AND ", where));
        return sql.toString();
    }

    /** Readable condition text for reviewers and the model, e.g. "vip_type_3=体育赛事；vip_type_2 不限". */
    static String condition(CaliberList list, CaliberList.Caliber c) {
        List<String> parts = new ArrayList<>();
        for (Map.Entry<String, List<String>> e : c.where().entrySet()) {
            List<String> v = e.getValue();
            parts.add(
                    v.size() == 1 && v.get(0).equals(list.unrestricted())
                            ? e.getKey() + " " + list.unrestricted()
                            : e.getKey() + "=" + String.join("或", v));
        }
        return String.join("；", parts);
    }

    private static String note(CaliberList.Caliber c) {
        List<String> parts = new ArrayList<>();
        if (c.note() != null) parts.add(c.note());
        if ("sum".equalsIgnoreCase(c.combine()))
            for (Map.Entry<String, List<String>> w : c.where().entrySet())
                if (w.getValue().size() > 1)
                    parts.add(
                            "数值为 " + w.getKey() + " 取 " + String.join("、", w.getValue()) + " 各行相加");
        if (c.pending() != null) parts.add("待业务确认：" + c.pending());
        return String.join("；", parts);
    }

    private static String timeExpression(CaliberList.Time t) {
        String column = quote(t.column());
        String text = "CAST(" + column + " AS VARCHAR)";
        return switch (t.format()) {
            case "yyyymm" ->
                    "CAST(CONCAT(SUBSTR("
                            + text
                            + ", 1, 4), '-', SUBSTR("
                            + text
                            + ", 5, 2), '-01') AS DATE)";
            case "yyyymmdd" ->
                    "CAST(CONCAT(SUBSTR("
                            + text
                            + ", 1, 4), '-', SUBSTR("
                            + text
                            + ", 5, 2), '-', SUBSTR("
                            + text
                            + ", 7, 2)) AS DATE)";
            default -> "CAST(" + column + " AS DATE)";
        };
    }

    private static Map<String, Object> cube(
            CaliberList list, String listPath, ModelColumns columns) {
        CaliberList.Scenario s = list.scenario();
        List<Map<String, Object>> measures = new ArrayList<>();
        for (CaliberList.Measure m : s.measures()) {
            String type = columns.types().getOrDefault(m.column().toLowerCase(Locale.ROOT), "");
            String label = m.label() == null ? m.column() : m.label();
            measures.add(
                    member(
                            m.name(),
                            "SUM(" + quote(m.column()) + ")",
                            type.toUpperCase(Locale.ROOT).contains("INT") ? "BIGINT" : "DOUBLE",
                            label
                                    + (m.description() == null ? "" : "：" + m.description())
                                    + (m.synonyms().isEmpty()
                                            ? ""
                                            : "。常见说法：" + String.join("、", m.synonyms()))
                                    + (m.isDefault() ? "。默认指标：用户只说统计对象、没说具体指标时用它" : "")
                                    + "。必须按 caliber 筛选单个口径或按 caliber 分组，不能跨口径相加。"));
        }
        List<Map<String, Object>> dimensions = new ArrayList<>();
        dimensions.add(
                member(
                        "caliber",
                        "caliber_name",
                        "VARCHAR",
                        "口径名，取值："
                                + String.join(
                                        "、",
                                        list.calibers().stream()
                                                .map(CaliberList.Caliber::name)
                                                .toList())
                                + "。"
                                + synonyms(list)));
        dimensions.add(
                member(
                        "caliber_system",
                        "caliber_group",
                        "VARCHAR",
                        "口径所属体系，只用于展示和筛选，不能按它把不同口径相加。"));
        dimensions.add(member("caliber_rule", "caliber_condition", "VARCHAR", "该口径的取数条件说明。"));
        for (CaliberList.Scope d : s.scope()) dimensions.add(scopeMember(d));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("name", list.cubeName());
        out.put("base_object", list.viewName());
        out.put("measures", measures);
        out.put("dimensions", dimensions);
        if (s.time() != null) {
            out.put(
                    "time_dimensions",
                    List.of(
                            member(
                                    "period",
                                    "period_date",
                                    "DATE",
                                    ("month".equals(s.time().grain())
                                                    ? "账期月份（当月 1 日），按 month 粒度使用"
                                                    : "统计日期，按 day 粒度使用")
                                            + "；来源列 "
                                            + s.time().column()
                                            + "。时间范围左闭右开，结束日期不包含"
                                            + ("month".equals(s.time().grain())
                                                    ? "：查 2026 年 10 月写"
                                                            + " period:month:2026-10-01,2026-11-01。"
                                                    : "：查 2026-10-01 一天写"
                                                          + " period:day:2026-10-01,2026-10-02。"))));
            out.put("hierarchies", Map.of("time", List.of("period")));
        }
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("description", cubeDescription(list));
        props.put("non_additive_dimension", "caliber");
        props.put(GENERATED_BY, listPath);
        out.put("properties", props);
        return out;
    }

    private static Map<String, Object> scopeMember(CaliberList.Scope d) {
        return member(
                d.name(),
                d.column(),
                "VARCHAR",
                label(d)
                        + "（来源列 "
                        + d.column()
                        + "）"
                        + (d.total() == null ? "" : "；" + d.total() + " 为汇总行，与明细行同时存在，不能相加")
                        + "。查询时必须在 filters 中指定（如 "
                        + d.name()
                        + ":eq:"
                        + d.defaultValue()
                        + "）或按它分组；用户没说时取 "
                        + d.defaultValue()
                        + "。"
                        + (d.values().isEmpty()
                                ? ""
                                : "可选取值：" + String.join("、", d.values()) + "。"));
    }

    static String label(CaliberList.Scope d) {
        return d.label() == null ? d.name() : d.label();
    }

    /** "province:eq:全国" for every scope dimension, the filters a query takes by default. */
    public static List<String> defaultFilters(CaliberList list) {
        return list.scenario().scope().stream()
                .map(d -> d.name() + ":eq:" + d.defaultValue())
                .toList();
    }

    private static String cubeDescription(CaliberList list) {
        CaliberList.Scenario s = list.scenario();
        StringBuilder d = new StringBuilder(s.subject()).append("。");
        if (s.business() != null || s.audience() != null)
            d.append("业务：")
                    .append(s.business() == null ? "未写" : String.join("、", s.business().all()))
                    .append("；统计对象：")
                    .append(s.audience() == null ? "未写" : String.join("、", s.audience().all()))
                    .append("。");
        if (s.description() != null) d.append(s.description()).append("。");
        if (!s.useFor().isEmpty()) d.append("适用：").append(String.join("、", s.useFor())).append("。");
        if (!s.notFor().isEmpty())
            d.append("不适用：").append(String.join("、", s.notFor())).append("。");
        if (!s.fixedScope().isEmpty()) {
            List<String> scope = new ArrayList<>();
            s.fixedScope().forEach((k, v) -> scope.add(k + "=" + v));
            d.append("已固定范围：").append(String.join("；", scope)).append("。");
        }
        if (!s.scope().isEmpty())
            d.append("范围维度（每个都要指定取值或分组，用户没说时用默认值）：")
                    .append(
                            String.join(
                                    "；",
                                    s.scope().stream()
                                            .map(
                                                    x ->
                                                            label(x)
                                                                    + " "
                                                                    + x.name()
                                                                    + " 默认 "
                                                                    + x.defaultValue())
                                            .toList()))
                    .append("。");
        CaliberList.Measure primary = s.defaultMeasure();
        if (primary != null)
            d.append("默认指标：")
                    .append(primary.name())
                    .append("（")
                    .append(primary.label() == null ? primary.column() : primary.label())
                    .append("）。");
        d.append("每个口径每个")
                .append(s.time() == null ? "统计单元" : "month".equals(s.time().grain()) ? "月" : "日")
                .append("一行；查询时必须按 caliber 筛选单个口径或按 caliber 分组，不同口径之间可能互相包含，不能相加。");
        return d.toString();
    }

    private static String synonyms(CaliberList list) {
        List<String> parts = new ArrayList<>();
        for (CaliberList.Caliber c : list.calibers())
            if (!c.synonyms().isEmpty())
                parts.add(String.join("、", c.synonyms()) + " 指 " + c.name());
        return parts.isEmpty() ? "" : "常见说法：" + String.join("；", parts) + "。";
    }

    private static Map<String, Object> member(
            String name, String expression, String type, String description) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", name);
        m.put("expression", expression);
        m.put("type", type);
        m.put("properties", Map.of("description", description));
        return m;
    }

    // ------------------------------------------------------------------ workspace sync

    /** Lists under {@code calibers/}, keyed by workspace-relative path, sorted for stable output. */
    public static Map<String, String> lists(Path root) {
        Map<String, String> out = new TreeMap<>();
        Path dir = root.resolve(CaliberList.DIRECTORY);
        if (!Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) return out;
        try (Stream<Path> files = Files.list(dir)) {
            for (Path f : files.filter(p -> p.getFileName().toString().endsWith(".yml")).toList())
                out.put(
                        CaliberList.DIRECTORY + "/" + f.getFileName(),
                        Files.readString(f, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new DatasetException("读取口径清单失败：" + e.getMessage(), e);
        }
        return out;
    }

    /** Columns of {@code models/<model>/metadata.yml}, or null when the model does not exist. */
    public static ModelColumns modelColumns(Path root, String model) {
        Path file = root.resolve("models").resolve(model).resolve("metadata.yml");
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) return null;
        try {
            Map<String, Object> parsed =
                    CaliberList.map(READER.readValue(file.toFile(), Object.class));
            Map<String, String> types = new LinkedHashMap<>();
            if (parsed != null && parsed.get("columns") instanceof List<?> cols)
                for (Object col : cols) {
                    Map<String, Object> c = CaliberList.map(col);
                    String name = c == null ? null : CaliberList.text(c.get("name"));
                    if (name != null) {
                        String type = CaliberList.text(c.get("type"));
                        types.put(name.toLowerCase(Locale.ROOT), type == null ? "" : type);
                    }
                }
            return new ModelColumns(model, types);
        } catch (IOException e) {
            return null;
        }
    }

    /** Parses and compiles one list against the workspace's models. */
    public static Compiled compile(Path root, String listPath, String yaml) {
        CaliberList.Parsed parsed = CaliberList.parse(listPath, yaml);
        if (!parsed.ok()) return new Compiled(null, null, null, null, null, parsed.errors());
        return compile(parsed.list(), listPath, modelColumns(root, parsed.list().model()));
    }

    /**
     * Regenerates every list's View and Cube and removes generated files whose list is gone.
     * Lists that do not compile keep their previous generated files; {@link #check} reports them.
     */
    public static List<MdlIssue> sync(Path root) {
        List<MdlIssue> issues = new ArrayList<>();
        Map<String, String> expected = new LinkedHashMap<>();
        for (Map.Entry<String, String> list : lists(root).entrySet()) {
            Compiled compiled = compile(root, list.getKey(), list.getValue());
            if (!compiled.ok()) {
                compiled.errors()
                        .forEach(
                                e ->
                                        issues.add(
                                                new MdlIssue(
                                                        "error",
                                                        list.getKey() + "：" + e,
                                                        "local")));
                generatedFrom(root, list.getKey()).forEach(p -> expected.put(p, null));
                continue;
            }
            expected.put(compiled.viewPath(), compiled.view());
            expected.put(compiled.cubePath(), compiled.cube());
        }
        try {
            for (String path : generated(root).keySet())
                if (!expected.containsKey(path)) deleteGenerated(root, path);
            for (Map.Entry<String, String> e : expected.entrySet()) {
                if (e.getValue() == null) continue;
                Path file = root.resolve(e.getKey());
                String existing =
                        Files.isRegularFile(file)
                                ? Files.readString(file, StandardCharsets.UTF_8)
                                : null;
                if (existing != null && !isGenerated(existing)) {
                    issues.add(
                            new MdlIssue(
                                    "error",
                                    e.getKey() + " 已存在且不是由口径清单生成的文件，请给口径清单换一个 name",
                                    "local"));
                    continue;
                }
                if (e.getValue().equals(existing)) continue;
                Files.createDirectories(file.getParent());
                Files.writeString(file, e.getValue(), StandardCharsets.UTF_8);
            }
        } catch (IOException ex) {
            throw new DatasetException("生成口径视图与指标失败：" + ex.getMessage(), ex);
        }
        writeRules(root);
        return issues;
    }

    /** spec149 P4: the generated selection rules, read through the official instructions. */
    public static final String RULES_FILE = "knowledge/rules/calibers.md";

    /**
     * spec149 P5: the selection index over every compiling list. A table is chosen by business x
     * audience, then a caliber by name; each row names the Cube, its default measure and default
     * scope. Returns null when there is no list.
     */
    static String rules(Path root) {
        List<CaliberList> lists = compiledLists(root);
        if (lists.isEmpty()) return null;
        StringBuilder out =
                new StringBuilder("# 口径指标\n\n")
                        .append("<!-- 由 calibers/ 下的口径清单生成，请修改口径清单而不是本文件。 -->\n\n")
                        .append("## Canonical tables\n\n")
                        .append("先按“业务 × 统计对象”确定用哪个指标（Cube），再按口径名取数：\n\n")
                        .append("| 业务 | 统计对象 | 指标 | 默认指标 | 时间 | 范围（用户没说时） | 口径 |\n")
                        .append("|---|---|---|---|---|---|---|\n");
        List<String> notes = new ArrayList<>();
        Set<String> models = new java.util.LinkedHashSet<>();
        for (CaliberList list : lists) {
            CaliberList.Scenario s = list.scenario();
            models.add(list.model());
            CaliberList.Measure primary = s.defaultMeasure();
            List<String> scope = new ArrayList<>();
            s.fixedScope().forEach((k, v) -> scope.add(k + "=" + v + "（固定）"));
            s.scope().forEach(x -> scope.add(x.name() + ":eq:" + x.defaultValue()));
            out.append("| ")
                    .append(cell(s.business() == null ? "未写" : keyText(s.business())))
                    .append(" | ")
                    .append(cell(s.audience() == null ? s.subject() : keyText(s.audience())))
                    .append(" | ")
                    .append(list.cubeName())
                    .append(" | ")
                    .append(
                            primary == null
                                    ? "无，先问"
                                    : primary.name()
                                            + "（"
                                            + cell(
                                                    primary.label() == null
                                                            ? primary.column()
                                                            : primary.label())
                                            + "）")
                    .append(" | ")
                    .append(s.time() == null ? "—" : "month".equals(s.time().grain()) ? "月" : "日")
                    .append(" | ")
                    .append(scope.isEmpty() ? "—" : cell(String.join("；", scope)))
                    .append(" | ")
                    .append(
                            cell(
                                    String.join(
                                            "、",
                                            list.calibers().stream()
                                                    .map(CaliberList.Caliber::name)
                                                    .toList())))
                    .append(" |\n");
            StringBuilder note =
                    new StringBuilder("- ").append(list.cubeName()).append("：").append(s.subject());
            if (!s.useFor().isEmpty()) note.append("；适用于").append(String.join("、", s.useFor()));
            if (!s.notFor().isEmpty()) note.append("；不适用于").append(String.join("、", s.notFor()));
            notes.add(note.append("。").toString());
        }
        out.append("\n").append(String.join("\n", notes)).append("\n");
        out.append(
                        "- 先定业务和统计对象：用户没说业务或统计对象、且对应多个指标时（例如只说“用户数”），先问是哪一个；问到上表没有的业务或统计对象时，说明当前没有这类数据，不用其他表凑数。\n")
                .append("- 再定指标：用户只说统计对象、没说具体指标时用“默认指标”；默认指标为“无，先问”时先问。\n")
                .append(
                        "- 再定口径：按口径名筛选（filters: caliber:in:口径名）或按 caliber"
                            + " 分组，不同口径之间可能互相包含，不能相加；用户的说法可能对应多个口径或对不上任何口径名时，先向用户确认是哪个口径，不要自行选择。\n")
                .append("- 范围：上表“范围”列出的每个维度都要写进 filters（用户没说时用所列默认值），或按它分组；汇总值与明细值同时存在，不能相加。\n")
                .append("- 以上指标覆盖的问题只用 query_cube 取数，不要自己拼取数条件，也不要对这些指标的原表（模型 ")
                .append(String.join("、", models))
                .append("）用 run_sql；其他问题照常用 run_sql。\n");
        return out.toString();
    }

    private static String keyText(CaliberList.Key key) {
        return key.synonyms().isEmpty()
                ? key.name()
                : key.name() + "（" + String.join("、", key.synonyms()) + "）";
    }

    private static String cell(String text) {
        return text.replace("|", "／").replace("\n", " ");
    }

    /** Lists that compile against their model, in path order. */
    public static List<CaliberList> compiledLists(Path root) {
        List<CaliberList> out = new ArrayList<>();
        for (Map.Entry<String, String> entry : lists(root).entrySet()) {
            Compiled compiled = compile(root, entry.getKey(), entry.getValue());
            if (compiled.ok()) out.add(compiled.list());
        }
        return out;
    }

    /**
     * spec149 P5: tables are chosen by business x audience, so two lists answering the same pair
     * at the same grain, a pair a list leaves out while several lists exist, or a selection word
     * that is also another table's caliber name would make the choice a guess.
     */
    public static List<MdlIssue> indexWarnings(Path root) {
        List<MdlIssue> issues = new ArrayList<>();
        List<CaliberList> lists = compiledLists(root);
        if (lists.size() < 2) return issues;
        for (CaliberList list : lists)
            if (list.scenario().business() == null || list.scenario().audience() == null)
                issues.add(
                        new MdlIssue(
                                "warning",
                                "口径清单 "
                                        + list.name()
                                        + " 没写 scenario.business 或 scenario.audience；多张表时问数靠这两项选表",
                                "calibers"));
        for (int i = 0; i < lists.size(); i++)
            for (int j = i + 1; j < lists.size(); j++) {
                CaliberList a = lists.get(i);
                CaliberList b = lists.get(j);
                if (overlaps(a.scenario().business(), b.scenario().business())
                        && overlaps(a.scenario().audience(), b.scenario().audience())
                        && java.util.Objects.equals(grain(a), grain(b)))
                    issues.add(
                            new MdlIssue(
                                    "warning",
                                    "口径清单 "
                                            + a.name()
                                            + " 与 "
                                            + b.name()
                                            + " 的业务和统计对象相同（时间粒度也相同），问数无法区分；请在其中一张的 scenario"
                                            + " 中写明区别或改用不同说法",
                                    "calibers"));
            }
        for (CaliberList a : lists)
            for (CaliberList b : lists) {
                if (a == b) continue;
                Set<String> words = new java.util.LinkedHashSet<>();
                if (a.scenario().business() != null) words.addAll(a.scenario().business().all());
                if (a.scenario().audience() != null) words.addAll(a.scenario().audience().all());
                for (CaliberList.Caliber c : b.calibers())
                    for (String w : words)
                        if (c.name().equals(w) || c.synonyms().contains(w))
                            issues.add(
                                    new MdlIssue(
                                            "warning",
                                            "口径清单 "
                                                    + a.name()
                                                    + " 的选表说法「"
                                                    + w
                                                    + "」也是 "
                                                    + b.name()
                                                    + " 的口径「"
                                                    + c.name()
                                                    + "」，问数可能选错表",
                                            "calibers"));
            }
        return issues;
    }

    private static boolean overlaps(CaliberList.Key a, CaliberList.Key b) {
        if (a == null || b == null) return false;
        for (String x : a.all()) for (String y : b.all()) if (x.equalsIgnoreCase(y)) return true;
        return false;
    }

    private static String grain(CaliberList list) {
        return list.scenario().time() == null ? null : list.scenario().time().grain();
    }

    /** Writes or removes {@link #RULES_FILE} so it matches the lists. */
    public static void writeRules(Path root) {
        Path file = root.resolve(RULES_FILE);
        String text = rules(root);
        try {
            if (text == null) {
                Files.deleteIfExists(file);
                return;
            }
            if (Files.isRegularFile(file)
                    && text.equals(Files.readString(file, StandardCharsets.UTF_8))) return;
            Files.createDirectories(file.getParent());
            Files.writeString(file, text, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new DatasetException("生成口径选表规则失败：" + e.getMessage(), e);
        }
    }

    /** Publish gate: every list compiles and its generated files are exactly what it produces. */
    public static List<MdlIssue> check(Path root) {
        List<MdlIssue> issues = new ArrayList<>();
        Map<String, String> generated = generated(root);
        Map<String, String> owners = new LinkedHashMap<>();
        for (Map.Entry<String, String> list : lists(root).entrySet()) {
            Compiled compiled = compile(root, list.getKey(), list.getValue());
            if (!compiled.ok()) {
                compiled.errors()
                        .forEach(
                                e ->
                                        issues.add(
                                                new MdlIssue(
                                                        "error",
                                                        list.getKey() + "：" + e,
                                                        "local")));
                continue;
            }
            for (String[] file :
                    List.of(
                            new String[] {compiled.viewPath(), compiled.view()},
                            new String[] {compiled.cubePath(), compiled.cube()})) {
                owners.put(file[0], list.getKey());
                if (!file[1].equals(generated.get(file[0])))
                    issues.add(
                            new MdlIssue(
                                    "error",
                                    file[0] + " 与口径清单 " + list.getKey() + " 不一致，请修改口径清单而不是生成文件",
                                    "local"));
            }
        }
        for (String path : generated.keySet())
            if (!owners.containsKey(path) && generatedOwnerMissing(root, generated.get(path)))
                issues.add(new MdlIssue("error", path + " 由已删除的口径清单生成，请重新生成", "local"));
        String rules = rules(root);
        Path rulesFile = root.resolve(RULES_FILE);
        try {
            String current =
                    Files.isRegularFile(rulesFile)
                            ? Files.readString(rulesFile, StandardCharsets.UTF_8)
                            : null;
            if (!java.util.Objects.equals(rules, current))
                issues.add(new MdlIssue("error", RULES_FILE + " 与口径清单不一致，请修改口径清单而不是该文件", "local"));
        } catch (IOException e) {
            issues.add(new MdlIssue("error", "读取 " + RULES_FILE + " 失败", "local"));
        }
        return issues;
    }

    /**
     * spec149: a caliber's conditions live only in its list. A business-rule line that names a
     * caliber and spells out a condition on one of the list's columns is probably a duplicate
     * left behind (or a caliber written into rules instead of the list); it is reported as a
     * warning, never rewritten by the platform.
     */
    public static List<MdlIssue> ruleWarnings(Path root) {
        List<MdlIssue> issues = new ArrayList<>(indexWarnings(root));
        Map<String, String> rules = new TreeMap<>();
        Path dir = root.resolve("knowledge/rules");
        if (Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) {
            try (Stream<Path> files = Files.list(dir)) {
                for (Path f :
                        files.filter(p -> p.toString().endsWith(".md"))
                                .filter(p -> !p.getFileName().toString().equals("calibers.md"))
                                .sorted()
                                .toList())
                    rules.put(
                            "knowledge/rules/" + f.getFileName(),
                            Files.readString(f, StandardCharsets.UTF_8));
            } catch (IOException e) {
                return issues;
            }
        }
        for (Map.Entry<String, String> list : lists(root).entrySet()) {
            CaliberList.Parsed parsed = CaliberList.parse(list.getKey(), list.getValue());
            if (parsed.list() == null) continue;
            java.util.Set<String> columns = new java.util.LinkedHashSet<>();
            parsed.list().calibers().forEach(c -> columns.addAll(c.where().keySet()));
            if (columns.isEmpty()) continue;
            java.util.regex.Pattern condition =
                    java.util.regex.Pattern.compile(
                            "(?i)\\b("
                                    + String.join(
                                            "|",
                                            columns.stream()
                                                    .map(java.util.regex.Pattern::quote)
                                                    .toList())
                                    + ")\\s*(=|IN\\s*\\()");
            for (Map.Entry<String, String> rule : rules.entrySet())
                for (String line : rule.getValue().split("\\R")) {
                    if (!condition.matcher(line).find()) continue;
                    String named =
                            parsed.list().calibers().stream()
                                    .map(CaliberList.Caliber::name)
                                    .filter(line::contains)
                                    .findFirst()
                                    .orElse(null);
                    String excerpt =
                            line.strip().length() > 80
                                    ? line.strip().substring(0, 80) + "…"
                                    : line.strip();
                    issues.add(
                            new MdlIssue(
                                    "warning",
                                    rule.getKey()
                                            + " 中疑似口径漏进规则"
                                            + (named == null ? "" : "（与口径「" + named + "」重复）")
                                            + "：“"
                                            + excerpt
                                            + "”；取数条件应只写在口径清单 "
                                            + list.getKey(),
                                    "calibers"));
                }
        }
        return issues;
    }

    /** Whether a workspace path is a file generated from a caliber list (never hand-edited). */
    public static boolean isGeneratedPath(Path root, String relative) {
        String lower = relative.replace('\\', '/').toLowerCase(Locale.ROOT);
        if (!lower.matches("(views|cubes)/[^/]+(/.*)?")) return false;
        String dir =
                relative.replace('\\', '/').split("/")[0]
                        + "/"
                        + relative.replace('\\', '/').split("/")[1];
        Path meta = root.resolve(dir).resolve("metadata.yml");
        try {
            return Files.isRegularFile(meta)
                    && isGenerated(Files.readString(meta, StandardCharsets.UTF_8));
        } catch (IOException e) {
            return false;
        }
    }

    static boolean isGenerated(String content) {
        try {
            Map<String, Object> parsed = CaliberList.map(READER.readValue(content, Object.class));
            Map<String, Object> props =
                    parsed == null ? null : CaliberList.map(parsed.get("properties"));
            return props != null && CaliberList.text(props.get(GENERATED_BY)) != null;
        } catch (IOException e) {
            return false;
        }
    }

    /** Generated metadata files by workspace-relative path. */
    static Map<String, String> generated(Path root) {
        Map<String, String> out = new TreeMap<>();
        for (String kind : List.of("views", "cubes")) {
            Path dir = root.resolve(kind);
            if (!Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) continue;
            try (Stream<Path> dirs = Files.list(dir)) {
                for (Path d : dirs.filter(Files::isDirectory).toList()) {
                    Path meta = d.resolve("metadata.yml");
                    if (!Files.isRegularFile(meta)) continue;
                    String content = Files.readString(meta, StandardCharsets.UTF_8);
                    if (isGenerated(content))
                        out.put(kind + "/" + d.getFileName() + "/metadata.yml", content);
                }
            } catch (IOException e) {
                throw new DatasetException("读取生成文件失败：" + e.getMessage(), e);
            }
        }
        return out;
    }

    private static List<String> generatedFrom(Path root, String listPath) {
        List<String> out = new ArrayList<>();
        generated(root)
                .forEach(
                        (path, content) -> {
                            if (listPath.equals(owner(content))) out.add(path);
                        });
        return out;
    }

    private static boolean generatedOwnerMissing(Path root, String content) {
        String owner = owner(content);
        return owner == null || !Files.isRegularFile(root.resolve(owner));
    }

    private static String owner(String content) {
        try {
            Map<String, Object> parsed = CaliberList.map(READER.readValue(content, Object.class));
            Map<String, Object> props =
                    parsed == null ? null : CaliberList.map(parsed.get("properties"));
            return props == null ? null : CaliberList.text(props.get(GENERATED_BY));
        } catch (IOException e) {
            return null;
        }
    }

    private static void deleteGenerated(Path root, String metadataPath) throws IOException {
        Path dir = root.resolve(metadataPath).getParent();
        try (Stream<Path> files = Files.list(dir)) {
            for (Path f : files.toList()) Files.deleteIfExists(f);
        }
        Files.deleteIfExists(dir);
    }

    // ------------------------------------------------------------------ helpers

    private static void requireColumn(
            ModelColumns columns, String column, String role, List<String> errors) {
        if (column != null && !columns.types().containsKey(column.toLowerCase(Locale.ROOT)))
            errors.add(role + " " + column + " 不在模型 " + columns.model() + " 中");
    }

    static String literal(String value) {
        return "'" + value.replace("'", "''") + "'";
    }

    /**
     * Identifiers are written bare: Wren 0.18 turns double-quoted names inside a view statement
     * into string literals for MySQL ({@code "a" = 'x'} became {@code 'a' = 'x'}, always false).
     * {@link CaliberList#IDENTIFIER} already limits names to letters, digits and underscores.
     */
    static String quote(String identifier) {
        if (!CaliberList.IDENTIFIER.matcher(identifier).matches())
            throw new DatasetException("口径清单中的名称不是合法标识符：" + identifier, 400);
        return identifier;
    }

    private static String write(Map<String, Object> value) {
        try {
            return WRITER.writeValueAsString(value);
        } catch (IOException e) {
            throw new DatasetException("生成口径文件失败：" + e.getMessage(), e);
        }
    }
}
