package io.agentscope.dataagent.tools.data;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** spec096: inference fixes and explicit chart specs. */
class ChartBuilderTest {

    private static List<List<String>> daily(int days) {
        List<List<String>> rows = new ArrayList<>();
        for (int d = 1; d <= days; d++)
            rows.add(List.of(String.format("2026-09-%02d", d), String.valueOf(1000 + d * 10)));
        return rows;
    }

    @Test
    void aDateSeriesIsALineEvenWhenTheWordingLooksLikeAComparison() {
        // "每天" matched the comparison hint and used to give a ranking bar.
        var chart = ChartBuilder.build(List.of("日期", "访问次数"), daily(18), "9月每天访问报表的人数");
        assertThat(chart.chartType()).isEqualTo("line");
    }

    @Test
    void numericIdColumnsAreDimensionsNotMetrics() {
        List<List<String>> rows = new ArrayList<>();
        for (int i = 0; i < 10; i++)
            rows.add(List.of(String.valueOf(800 + i), String.valueOf(700 - i * 30)));
        var chart = ChartBuilder.build(List.of("project_id", "visitors"), rows, "访问人数最多的项目 Top10");
        assertThat(chart.chartType()).isEqualTo("hbar");
        @SuppressWarnings("unchecked")
        var yAxis = (Map<String, Object>) chart.option().get("yAxis");
        assertThat(yAxis.get("data")).asList().contains("800", "809");
    }

    @Test
    void withoutAQuestionTheTitleNamesTheMetric() {
        var rows = List.of(List.of("市场经营部", "30"), List.of("平台运营中心", "20"), List.of("其他", "5"));
        var chart = ChartBuilder.build(List.of("部门", "访问次数", "访问人数"), withSecondMetric(rows), null);
        assertThat(chart.title()).doesNotContain("对比图");
        var single = ChartBuilder.build(List.of("部门", "访问次数"), longer(), null);
        assertThat(single.title()).isEqualTo("访问次数（按部门）");
    }

    @Test
    void explicitDualAxisPutsY2OnTheRightAxis() {
        List<List<String>> rows = new ArrayList<>();
        for (int d = 1; d <= 5; d++)
            rows.add(List.of("2026-10-0" + d, String.valueOf(100 * d), String.valueOf(10 * d)));
        var chart =
                ChartBuilder.build(
                        List.of("日期", "访问次数", "访问人数"),
                        rows,
                        "访问次数与人数",
                        new ChartBuilder.Spec(
                                "dual_axis", "日期", List.of("访问次数"), List.of("访问人数"), null, true));
        assertThat(chart.chartType()).isEqualTo("dual_axis");
        assertThat((List<?>) chart.option().get("yAxis")).hasSize(2);
        @SuppressWarnings("unchecked")
        var series = (List<Map<String, Object>>) chart.option().get("series");
        assertThat(series).extracting(s -> s.get("yAxisIndex")).containsExactly(0, 1);
        assertThat(series).allSatisfy(s -> assertThat(s.get("label")).isNotNull());
    }

    @Test
    void explicitStackedBarGroupsBySeriesColumn() {
        var rows =
                List.of(
                        List.of("9月", "报表", "30"),
                        List.of("9月", "仪表盘", "10"),
                        List.of("10月", "报表", "20"),
                        List.of("10月", "仪表盘", "15"));
        var chart =
                ChartBuilder.build(
                        List.of("月份", "类型", "次数"),
                        rows,
                        null,
                        new ChartBuilder.Spec(
                                "stacked_bar", "月份", List.of("次数"), null, "类型", null));
        @SuppressWarnings("unchecked")
        var series = (List<Map<String, Object>>) chart.option().get("series");
        assertThat(series).extracting(s -> s.get("name")).containsExactly("报表", "仪表盘");
        assertThat(series).allSatisfy(s -> assertThat(s.get("stack")).isEqualTo("total"));
        assertThat(series.get(0).get("data")).isEqualTo(List.of(30.0, 20.0));
    }

    @Test
    void explicitHeatmapUsesSeriesAsTheSecondDimension() {
        var rows =
                List.of(
                        List.of("周一", "9", "12"),
                        List.of("周一", "10", "30"),
                        List.of("周二", "9", "8"));
        var chart =
                ChartBuilder.build(
                        List.of("星期", "小时", "次数"),
                        rows,
                        null,
                        new ChartBuilder.Spec("heatmap", "星期", List.of("次数"), null, "小时", null));
        assertThat(chart.chartType()).isEqualTo("heatmap");
        assertThat(chart.option()).containsKey("visualMap");
    }

    @Test
    void invalidSpecsAreRefusedWithReadableMessages() {
        var rows = longer();
        assertThatThrownBy(
                        () ->
                                ChartBuilder.build(
                                        List.of("部门", "访问次数"),
                                        rows,
                                        null,
                                        new ChartBuilder.Spec(
                                                "bar", "单位", List.of("访问次数"), null, null, null)))
                .hasMessageContaining("可用列：部门、访问次数");
        assertThatThrownBy(
                        () ->
                                ChartBuilder.build(
                                        List.of("部门", "访问次数"),
                                        rows,
                                        null,
                                        new ChartBuilder.Spec(
                                                "radar", null, null, null, null, null)))
                .hasMessageContaining("不支持的图表类型");
        assertThat(
                        new DataAgentToolkit(null)
                                .renderChart(
                                        null,
                                        List.of("部门", "访问次数"),
                                        rows,
                                        null,
                                        null,
                                        "heatmap",
                                        "部门",
                                        null,
                                        null,
                                        null,
                                        null))
                .startsWith("error: 热力图需要用 series");
    }

    @Test
    void anEmptySpecKeepsTheInferredChart() {
        var inferred = ChartBuilder.build(List.of("日期", "访问次数"), daily(5), "趋势");
        var withEmpty =
                ChartBuilder.build(
                        List.of("日期", "访问次数"),
                        daily(5),
                        "趋势",
                        new ChartBuilder.Spec(null, null, List.of(), null, null, null));
        assertThat(withEmpty.option()).isEqualTo(inferred.option());
    }

    private static List<List<String>> longer() {
        List<List<String>> rows = new ArrayList<>();
        for (int i = 0; i < 6; i++) rows.add(List.of("部门" + i, String.valueOf(60 - i)));
        return rows;
    }

    private static List<List<String>> withSecondMetric(List<List<String>> rows) {
        List<List<String>> out = new ArrayList<>();
        for (var r : rows) out.add(List.of(r.get(0), r.get(1), "1"));
        return out;
    }
}
