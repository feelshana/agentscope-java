/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.dataagent.tools.data;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Deterministic ECharts option builder (Java port of opne-aureka's chart-builder heuristics).
 * The LLM only hands over the query result (columns + rows) and the question; the chart type and
 * the full ECharts option are decided here from question semantics + data shape, so every chart
 * has a clean, consistently ordered axis and a type that matches the data — live and in history.
 */
public final class ChartBuilder {

    public static final int MAX_SERIES_ITEMS = 20;

    private static final Pattern DATE_PATTERN =
            Pattern.compile("^\\d{4}[-/]\\d{1,2}([-/]\\d{1,2})?");
    private static final Pattern HINT_TREND =
            Pattern.compile(
                    "趋势|走势|变化|增长|逐(月|日|年|周|季)|按(月|日|年|周|季)|时间|同比|环比|trend|growth|monthly|daily",
                    Pattern.CASE_INSENSITIVE);
    private static final Pattern HINT_RANK =
            Pattern.compile(
                    "排行|排名|top\\s*\\d*|前\\s*\\d+|最(多|少|高|低|大|小)|领先|榜|ranking|rank|highest|lowest",
                    Pattern.CASE_INSENSITIVE);
    private static final Pattern HINT_CORR =
            Pattern.compile(
                    "相关|关系|关联|散点|correlation|relationship|versus|\\bvs\\b",
                    Pattern.CASE_INSENSITIVE);
    private static final Pattern HINT_PROP =
            Pattern.compile(
                    "占比|比例|构成|分布|份额|比重|proportion|share|distribution|breakdown",
                    Pattern.CASE_INSENSITIVE);
    private static final Pattern HINT_COMPARE =
            Pattern.compile(
                    "对比|比较|差异|各(个)?|每(个)?|compare|comparison|difference", Pattern.CASE_INSENSITIVE);
    private static final Pattern PCT_COLUMN =
            Pattern.compile("pct|percent|ratio|rate|占比|比例|百分比|%", Pattern.CASE_INSENSITIVE);

    public record BuiltChart(String chartType, String title, Map<String, Object> option) {}

    private ChartBuilder() {}

    /**
     * Overlays a dashed red horizontal reference line (ECharts {@code markLine}) on every line/bar
     * series of a built option, mirroring TC's KPI target line on trend charts (e.g. 日均2000万).
     */
    @SuppressWarnings("unchecked")
    public static void applyMarkLine(Map<String, Object> option, double value, String label) {
        if (option == null) {
            return;
        }
        Object seriesObj = option.get("series");
        List<Map<String, Object>> seriesList = new ArrayList<>();
        if (seriesObj instanceof List<?> l) {
            for (Object o : l) {
                if (o instanceof Map<?, ?> m) {
                    seriesList.add((Map<String, Object>) m);
                }
            }
        } else if (seriesObj instanceof Map<?, ?> m) {
            seriesList.add((Map<String, Object>) m);
        }
        Map<String, Object> markLine = new LinkedHashMap<>();
        markLine.put("silent", true);
        markLine.put("symbol", "none");
        markLine.put("lineStyle", Map.of("type", "dashed", "color", "#e5484d", "width", 1.5));
        markLine.put(
                "label",
                Map.of(
                        "show",
                        true,
                        "position",
                        "insideEndTop",
                        "formatter",
                        label == null || label.isBlank() ? "目标" : label,
                        "color",
                        "#e5484d",
                        "fontSize",
                        10));
        markLine.put("data", List.of(Map.of("yAxis", value)));
        for (Map<String, Object> s : seriesList) {
            Object type = s.get("type");
            if ("line".equals(type) || "bar".equals(type)) {
                s.put("markLine", markLine);
            }
        }
    }

    public static BuiltChart build(List<String> columns, List<List<String>> rows, String question) {
        if (columns == null || rows == null || columns.isEmpty() || rows.isEmpty()) {
            return null;
        }
        BuiltChart kpi = tryBuildKpiChart(columns, rows, question);
        if (kpi != null) {
            return kpi;
        }
        List<Integer> numericCols = new ArrayList<>();
        List<Integer> categoricalCols = new ArrayList<>();
        for (int i = 0; i < columns.size(); i++) {
            if (isNumericColumn(rows, i)) {
                numericCols.add(i);
            } else {
                categoricalCols.add(i);
            }
        }
        if (numericCols.isEmpty()) {
            return null;
        }
        if (rows.size() == 1 && numericCols.size() == 1 && categoricalCols.isEmpty()) {
            return null;
        }
        Integer dateColIdx = null;
        for (int idx : categoricalCols) {
            if (isDateColumn(rows, idx)) {
                dateColIdx = idx;
                break;
            }
        }
        String chartType =
                decideChartType(dateColIdx, categoricalCols, numericCols, rows.size(), question);
        switch (chartType) {
            case "pie":
                {
                    int pieNumIdx = numericCols.get(0);
                    for (int idx : numericCols) {
                        if (!PCT_COLUMN.matcher(columns.get(idx)).find()) {
                            pieNumIdx = idx;
                            break;
                        }
                    }
                    return buildPie(
                            columns,
                            rows,
                            categoricalCols.isEmpty() ? 0 : categoricalCols.get(0),
                            pieNumIdx,
                            question);
                }
            case "scatter":
                {
                    int xIdx =
                            numericCols.size() > 1
                                    ? numericCols.get(1)
                                    : (dateColIdx != null
                                            ? dateColIdx
                                            : (!categoricalCols.isEmpty()
                                                    ? categoricalCols.get(0)
                                                    : numericCols.get(0)));
                    return buildScatter(columns, rows, xIdx, numericCols.get(0), question);
                }
            case "hbar":
                return buildHBar(
                        columns,
                        rows,
                        categoricalCols.isEmpty() ? 0 : categoricalCols.get(0),
                        numericCols.get(0),
                        question);
            default:
                return buildCategoryChart(
                        chartType,
                        columns,
                        rows,
                        categoricalCols,
                        numericCols,
                        dateColIdx,
                        question);
        }
    }

    // ------------------------------------------------------------------ heuristics

    private static boolean isNumericColumn(List<List<String>> rows, int colIdx) {
        List<String> sample = new ArrayList<>();
        for (List<String> r : rows.subList(0, Math.min(50, rows.size()))) {
            String v = colIdx < r.size() ? r.get(colIdx) : null;
            if (v != null && !v.isEmpty()) {
                sample.add(v);
            }
        }
        if (sample.isEmpty()) {
            return false;
        }
        for (String v : sample) {
            if (toNum(v) == null) {
                return false;
            }
        }
        return true;
    }

    private static boolean isDateColumn(List<List<String>> rows, int colIdx) {
        List<String> sample = new ArrayList<>();
        for (List<String> r : rows.subList(0, Math.min(20, rows.size()))) {
            String v = colIdx < r.size() ? r.get(colIdx) : null;
            if (v != null && !v.isEmpty()) {
                sample.add(v);
            }
        }
        if (sample.isEmpty()) {
            return false;
        }
        long matches = sample.stream().filter(v -> DATE_PATTERN.matcher(v).find()).count();
        return (double) matches / sample.size() >= 0.7;
    }

    private static Double toNum(String v) {
        if (v == null || v.isEmpty()) {
            return null;
        }
        try {
            double n = Double.parseDouble(v.trim());
            if (!Double.isFinite(n)) {
                return null;
            }
            if (n == Math.rint(n) || Math.abs(n) < 1) {
                return n;
            }
            return Math.round(n * 100) / 100.0;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String detectHint(String question) {
        if (question == null || question.isBlank()) {
            return "none";
        }
        if (HINT_CORR.matcher(question).find()) return "correlation";
        if (HINT_TREND.matcher(question).find()) return "trend";
        if (HINT_RANK.matcher(question).find()) return "ranking";
        if (HINT_PROP.matcher(question).find()) return "proportion";
        if (HINT_COMPARE.matcher(question).find()) return "comparison";
        return "none";
    }

    private static String decideChartType(
            Integer dateColIdx,
            List<Integer> categoricalCols,
            List<Integer> numericCols,
            int rowCount,
            String question) {
        String hint = detectHint(question);
        boolean hasTime = dateColIdx != null;
        boolean singleMetric = numericCols.size() == 1 && !categoricalCols.isEmpty();
        boolean multiMetric = numericCols.size() >= 2;

        if ("correlation".equals(hint) && multiMetric) return "scatter";
        if ("trend".equals(hint) && (hasTime || rowCount >= 3)) return "line";
        if ("ranking".equals(hint) && singleMetric && rowCount >= 3) return "hbar";
        if ("proportion".equals(hint)
                && !categoricalCols.isEmpty()
                && rowCount >= 2
                && rowCount <= 12) {
            return "pie";
        }
        if ("comparison".equals(hint) && (singleMetric || multiMetric)) {
            return rowCount > 8 ? "hbar" : "bar";
        }
        if (hasTime) return "line";
        if (multiMetric && categoricalCols.isEmpty()) return "scatter";
        if (singleMetric) {
            if (rowCount > 8) return "hbar";
            if (rowCount >= 2 && rowCount <= 5) return "pie";
            return "bar";
        }
        return "bar";
    }

    private static String makeTitle(String question, String chartType, String dimCol) {
        Map<String, String> label = new LinkedHashMap<>();
        label.put("bar", "对比图");
        label.put("line", "趋势图");
        label.put("pie", "占比图");
        label.put("scatter", "散点图");
        label.put("hbar", "排行图");
        if (question != null && !question.isBlank() && question.length() <= 30) {
            return question.replaceAll("[?？。.]$", "");
        }
        return dimCol + " " + label.getOrDefault(chartType, "可视化");
    }

    private static Map<String, Object> titleBlock(String text) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("text", text);
        m.put("left", "center");
        m.put("top", 10);
        return m;
    }

    private static Map<String, Object> gridBlock(
            boolean rotated, boolean legend, boolean horizontal) {
        int bottom;
        if (legend) {
            bottom = rotated ? 72 : 48;
        } else {
            bottom = rotated ? 56 : (horizontal ? 28 : 28);
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("top", 58);
        m.put("left", horizontal ? 16 : 12);
        m.put("right", 24);
        m.put("bottom", bottom);
        m.put("containLabel", true);
        return m;
    }

    private static Map<String, Object> categoryLabel(List<String> categories, boolean horizontal) {
        Map<String, Object> m = new LinkedHashMap<>();
        if (horizontal) {
            m.put("rotate", 0);
            m.put("hideOverlap", true);
            m.put("width", 104);
            m.put("overflow", "truncate");
            m.put("ellipsis", "…");
            return m;
        }
        int longest = 0;
        for (String c : categories) {
            longest = Math.max(longest, c.length());
        }
        boolean dense = categories.size() > 8;
        m.put("rotate", dense ? 35 : (longest > 6 ? 20 : 0));
        m.put("hideOverlap", true);
        m.put("width", 84);
        m.put("overflow", "truncate");
        m.put("ellipsis", "…");
        return m;
    }

    private static Map<String, Object> valueLabel(int count, String position) {
        Map<String, Object> m = new LinkedHashMap<>();
        if (count > 12) {
            m.put("show", false);
            return m;
        }
        m.put("show", true);
        m.put("position", position);
        m.put("fontSize", 10);
        return m;
    }

    private static List<List<String>> sortByValueDesc(List<List<String>> rows, int numIdx) {
        List<List<String>> copy = new ArrayList<>(rows);
        copy.sort(
                (a, b) -> {
                    Double va = toNum(numIdx < a.size() ? a.get(numIdx) : null);
                    Double vb = toNum(numIdx < b.size() ? b.get(numIdx) : null);
                    double da = va == null ? Double.NEGATIVE_INFINITY : va;
                    double db = vb == null ? Double.NEGATIVE_INFINITY : vb;
                    return Double.compare(db, da);
                });
        return copy;
    }

    private static List<List<String>> sortByDimAsc(List<List<String>> rows, int dimIdx) {
        List<List<String>> copy = new ArrayList<>(rows);
        copy.sort(
                Comparator.comparing(
                        r -> String.valueOf(dimIdx < r.size() ? r.get(dimIdx) : ""),
                        Comparator.naturalOrder()));
        return copy;
    }

    // ------------------------------------------------------------------ builders

    private static BuiltChart tryBuildKpiChart(
            List<String> columns, List<List<String>> rows, String question) {
        if (rows.size() == 1) {
            List<Map<String, Object>> pairs = new ArrayList<>();
            for (int idx = 0; idx < columns.size(); idx++) {
                Double n = toNum(rows.get(0).get(idx));
                if (n != null) {
                    Map<String, Object> p = new LinkedHashMap<>();
                    p.put("name", columns.get(idx));
                    p.put("value", n);
                    pairs.add(p);
                }
            }
            return pairs.size() >= 2 ? buildKpiHBar(pairs, question) : null;
        }
        if (columns.size() == 2 && !isNumericColumn(rows, 1)) {
            List<Map<String, Object>> pairs = new ArrayList<>();
            for (List<String> r : rows) {
                Double n = toNum(r.size() > 1 ? r.get(1) : null);
                String name = String.valueOf(r.get(0)).trim();
                if (n != null && !name.isEmpty()) {
                    Map<String, Object> p = new LinkedHashMap<>();
                    p.put("name", name);
                    p.put("value", n);
                    pairs.add(p);
                }
            }
            return pairs.size() >= 2 ? buildKpiHBar(pairs, question) : null;
        }
        return null;
    }

    private static BuiltChart buildKpiHBar(List<Map<String, Object>> pairs, String question) {
        if ("proportion".equals(detectHint(question)) && pairs.size() >= 3 && pairs.size() <= 8) {
            return buildKpiPie(pairs, question);
        }
        List<Map<String, Object>> top = new ArrayList<>(pairs);
        top.sort((a, b) -> Double.compare((Double) b.get("value"), (Double) a.get("value")));
        top = top.subList(0, Math.min(MAX_SERIES_ITEMS, top.size()));
        List<Map<String, Object>> ordered = new ArrayList<>(top);
        java.util.Collections.reverse(ordered);

        List<String> names = new ArrayList<>();
        List<Double> values = new ArrayList<>();
        for (Map<String, Object> p : ordered) {
            names.add((String) p.get("name"));
            values.add((Double) p.get("value"));
        }
        String title = makeTitle(question, "hbar", "指标");

        Map<String, Object> yAxis = new LinkedHashMap<>();
        yAxis.put("type", "category");
        yAxis.put("data", names);
        yAxis.put("axisLabel", categoryLabel(names, true));

        Map<String, Object> series = new LinkedHashMap<>();
        series.put("name", "数值");
        series.put("type", "bar");
        series.put("data", values);
        series.put("barMaxWidth", 22);
        series.put("itemStyle", Map.of("borderRadius", List.of(0, 4, 4, 0)));
        series.put("label", valueLabel(names.size(), "right"));

        Map<String, Object> option = new LinkedHashMap<>();
        option.put("title", titleBlock(title));
        option.put("tooltip", Map.of("trigger", "axis", "axisPointer", Map.of("type", "shadow")));
        option.put("grid", gridBlock(false, false, true));
        option.put("xAxis", Map.of("type", "value"));
        option.put("yAxis", yAxis);
        option.put("series", List.of(series));
        return new BuiltChart("hbar", title, option);
    }

    private static BuiltChart buildKpiPie(List<Map<String, Object>> pairs, String question) {
        List<Map<String, Object>> data = new ArrayList<>(pairs);
        data.sort((a, b) -> Double.compare((Double) b.get("value"), (Double) a.get("value")));
        data.removeIf(p -> (Double) p.get("value") <= 0);
        String title = makeTitle(question, "pie", "指标");

        Map<String, Object> series = new LinkedHashMap<>();
        series.put("name", "数值");
        series.put("type", "pie");
        series.put("radius", List.of("42%", "68%"));
        series.put("center", List.of("50%", "54%"));
        series.put("avoidLabelOverlap", true);
        series.put("minAngle", 3);
        series.put("itemStyle", Map.of("borderRadius", 6));
        series.put("label", Map.of("show", true, "formatter", "{b}\n{d}%", "fontSize", 11));
        series.put("data", data);

        Map<String, Object> option = new LinkedHashMap<>();
        option.put("title", titleBlock(title));
        option.put("tooltip", Map.of("trigger", "item", "formatter", "{b}: {c} ({d}%)"));
        option.put("legend", Map.of("type", "scroll", "bottom", 8, "itemGap", 16));
        option.put("series", List.of(series));
        return new BuiltChart("pie", title, option);
    }

    private static BuiltChart buildCategoryChart(
            String chartType,
            List<String> columns,
            List<List<String>> rows,
            List<Integer> categoricalCols,
            List<Integer> numericCols,
            Integer dateColIdx,
            String question) {
        int dimIdx =
                dateColIdx != null
                        ? dateColIdx
                        : (!categoricalCols.isEmpty() ? categoricalCols.get(0) : 0);
        String dimName = columns.get(dimIdx);
        int primaryNumIdx = numericCols.get(0);
        for (int idx : numericCols) {
            if (idx != dimIdx) {
                primaryNumIdx = idx;
                break;
            }
        }
        if (primaryNumIdx == dimIdx && numericCols.size() > 1) {
            primaryNumIdx = numericCols.get(1);
        }
        List<List<String>> orderedRows =
                "line".equals(chartType)
                        ? sortByDimAsc(rows, dimIdx)
                        : sortByValueDesc(rows, primaryNumIdx);
        List<List<String>> limited =
                orderedRows.subList(0, Math.min(MAX_SERIES_ITEMS, orderedRows.size()));

        List<String> categories = new ArrayList<>();
        for (List<String> r : limited) {
            categories.add(String.valueOf(dimIdx < r.size() ? r.get(dimIdx) : ""));
        }
        List<Integer> seriesCols = new ArrayList<>();
        for (int idx : numericCols) {
            if (idx != dimIdx) {
                seriesCols.add(idx);
            }
            if (seriesCols.size() == 4) break;
        }
        if (seriesCols.isEmpty()) {
            seriesCols.add(numericCols.get(0));
        }

        List<Map<String, Object>> series = new ArrayList<>();
        for (int numIdx : seriesCols) {
            Map<String, Object> s = new LinkedHashMap<>();
            s.put("name", columns.get(numIdx));
            s.put("type", chartType);
            if ("line".equals(chartType)) {
                s.put("smooth", true);
            }
            List<Double> data = new ArrayList<>();
            for (List<String> r : limited) {
                data.add(toNum(numIdx < r.size() ? r.get(numIdx) : null));
            }
            s.put("data", data);
            if ("bar".equals(chartType)) {
                s.put("barMaxWidth", 38);
                s.put("itemStyle", Map.of("borderRadius", List.of(4, 4, 0, 0)));
                s.put(
                        "label",
                        seriesCols.size() == 1
                                ? valueLabel(categories.size(), "top")
                                : Map.of("show", false));
            }
            if ("line".equals(chartType)) {
                s.put("showSymbol", categories.size() <= 24);
                s.put("symbolSize", 6);
                s.put("lineStyle", Map.of("width", 2));
                if (seriesCols.size() == 1) {
                    s.put("areaStyle", Map.of("opacity", 0.12));
                }
            }
            series.add(s);
        }

        String title = makeTitle(question, chartType, dimName);
        Map<String, Object> label = categoryLabel(categories, false);
        boolean hasLegend = seriesCols.size() > 1;

        Map<String, Object> xAxis = new LinkedHashMap<>();
        xAxis.put("type", "category");
        xAxis.put("data", categories);
        xAxis.put("boundaryGap", "bar".equals(chartType));
        xAxis.put("axisLabel", label);

        Map<String, Object> yAxis = new LinkedHashMap<>();
        yAxis.put("type", "value");
        if (seriesCols.size() == 1) {
            yAxis.put("name", columns.get(seriesCols.get(0)));
        }

        Map<String, Object> option = new LinkedHashMap<>();
        option.put("title", titleBlock(title));
        option.put(
                "tooltip",
                Map.of(
                        "trigger",
                        "axis",
                        "axisPointer",
                        Map.of("type", "bar".equals(chartType) ? "shadow" : "line")));
        if (hasLegend) {
            option.put("legend", Map.of("bottom", 8, "type", "scroll", "itemGap", 16));
        }
        option.put(
                "grid", gridBlock(((Number) label.get("rotate")).intValue() > 0, hasLegend, false));
        option.put("xAxis", xAxis);
        option.put("yAxis", yAxis);
        option.put("series", series);
        return new BuiltChart(chartType, title, option);
    }

    private static BuiltChart buildHBar(
            List<String> columns,
            List<List<String>> rows,
            int catIdx,
            int numIdx,
            String question) {
        List<List<String>> top = sortByValueDesc(rows, numIdx);
        top = top.subList(0, Math.min(MAX_SERIES_ITEMS, top.size()));
        List<List<String>> ordered = new ArrayList<>(top);
        java.util.Collections.reverse(ordered);

        List<String> categories = new ArrayList<>();
        List<Double> data = new ArrayList<>();
        for (List<String> r : ordered) {
            categories.add(String.valueOf(catIdx < r.size() ? r.get(catIdx) : ""));
            data.add(toNum(numIdx < r.size() ? r.get(numIdx) : null));
        }
        String title = makeTitle(question, "hbar", columns.get(catIdx));

        Map<String, Object> yAxis = new LinkedHashMap<>();
        yAxis.put("type", "category");
        yAxis.put("data", categories);
        yAxis.put("axisLabel", categoryLabel(categories, true));

        Map<String, Object> series = new LinkedHashMap<>();
        series.put("name", columns.get(numIdx));
        series.put("type", "bar");
        series.put("data", data);
        series.put("barMaxWidth", 22);
        series.put("itemStyle", Map.of("borderRadius", List.of(0, 4, 4, 0)));
        series.put("label", valueLabel(categories.size(), "right"));

        Map<String, Object> option = new LinkedHashMap<>();
        option.put("title", titleBlock(title));
        option.put("tooltip", Map.of("trigger", "axis", "axisPointer", Map.of("type", "shadow")));
        option.put("grid", gridBlock(false, false, true));
        option.put("xAxis", Map.of("type", "value", "name", columns.get(numIdx)));
        option.put("yAxis", yAxis);
        option.put("series", List.of(series));
        return new BuiltChart("hbar", title, option);
    }

    private static BuiltChart buildPie(
            List<String> columns,
            List<List<String>> rows,
            int catIdx,
            int numIdx,
            String question) {
        List<Map<String, Object>> data = new ArrayList<>();
        for (List<String> r : sortByValueDesc(rows, numIdx)) {
            Double v = toNum(numIdx < r.size() ? r.get(numIdx) : null);
            if (v != null && v > 0) {
                Map<String, Object> d = new LinkedHashMap<>();
                d.put("name", String.valueOf(catIdx < r.size() ? r.get(catIdx) : ""));
                d.put("value", v);
                data.add(d);
            }
            if (data.size() == MAX_SERIES_ITEMS) break;
        }
        String title = makeTitle(question, "pie", columns.get(catIdx));
        boolean dense = data.size() > 7;

        Map<String, Object> legend = new LinkedHashMap<>();
        legend.put("type", "scroll");
        if (dense) {
            legend.put("orient", "vertical");
            legend.put("right", 8);
            legend.put("top", "middle");
            legend.put("itemGap", 10);
        } else {
            legend.put("bottom", 8);
            legend.put("itemGap", 16);
        }

        Map<String, Object> series = new LinkedHashMap<>();
        series.put("name", columns.get(numIdx));
        series.put("type", "pie");
        series.put("radius", List.of("42%", "68%"));
        series.put("center", dense ? List.of("38%", "56%") : List.of("50%", "54%"));
        series.put("avoidLabelOverlap", true);
        series.put("minAngle", 3);
        series.put("itemStyle", Map.of("borderRadius", 6));
        series.put(
                "label",
                dense
                        ? Map.of("show", false)
                        : Map.of("show", true, "formatter", "{b}\n{d}%", "fontSize", 11));
        series.put(
                "labelLine", dense ? Map.of("show", false) : Map.of("length", 10, "length2", 12));
        series.put("data", data);

        Map<String, Object> option = new LinkedHashMap<>();
        option.put("title", titleBlock(title));
        option.put("tooltip", Map.of("trigger", "item", "formatter", "{b}: {c} ({d}%)"));
        option.put("legend", legend);
        option.put("series", List.of(series));
        return new BuiltChart("pie", title, option);
    }

    private static BuiltChart buildScatter(
            List<String> columns, List<List<String>> rows, int xIdx, int yIdx, String question) {
        List<List<Double>> data = new ArrayList<>();
        for (List<String> r : rows.subList(0, Math.min(200, rows.size()))) {
            Double x = toNum(xIdx < r.size() ? r.get(xIdx) : null);
            Double y = toNum(yIdx < r.size() ? r.get(yIdx) : null);
            if (x != null && y != null) {
                data.add(List.of(x, y));
            }
        }
        String title =
                makeTitle(question, "scatter", columns.get(xIdx) + " vs " + columns.get(yIdx));

        Map<String, Object> xAxis = new LinkedHashMap<>();
        xAxis.put("type", "value");
        xAxis.put("name", columns.get(xIdx));
        xAxis.put("nameLocation", "middle");
        xAxis.put("nameGap", 26);
        xAxis.put("scale", true);

        Map<String, Object> yAxis = new LinkedHashMap<>();
        yAxis.put("type", "value");
        yAxis.put("name", columns.get(yIdx));
        yAxis.put("scale", true);

        Map<String, Object> series = new LinkedHashMap<>();
        series.put("name", columns.get(xIdx) + " / " + columns.get(yIdx));
        series.put("type", "scatter");
        series.put("symbolSize", 10);
        series.put("itemStyle", Map.of("opacity", 0.75));
        series.put("data", data);

        Map<String, Object> grid = gridBlock(false, false, false);
        grid.put("bottom", 42);

        Map<String, Object> option = new LinkedHashMap<>();
        option.put("title", titleBlock(title));
        option.put("tooltip", Map.of("trigger", "item"));
        option.put("grid", grid);
        option.put("xAxis", xAxis);
        option.put("yAxis", yAxis);
        option.put("series", List.of(series));
        return new BuiltChart("scatter", title, option);
    }
}
