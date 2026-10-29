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

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import io.agentscope.dataagent.web.persistence.jpa.ChartOptionEntity;
import io.agentscope.dataagent.web.persistence.jpa.ChartOptionRepository;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Agent-facing presentation toolkit for chart rendering. Structured data queries and knowledge
 * retrieval are exposed exclusively by official Wren MCP tools; this class deliberately has no
 * physical metadata, SQL, or platform retrieval entry point.
 *
 * <p>Registered onto the main {@code data-agent} at startup; user-custom agents may opt in by
 * listing the presentation tool in their workspace {@code tools.json}.
 */
public final class DataAgentToolkit {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ChartOptionRepository chartOptions;

    public DataAgentToolkit(ChartOptionRepository chartOptions) {
        this.chartOptions = chartOptions;
    }

    @Tool(
            name = "render_chart",
            description =
                    """
                    渲染图表（默认的出图方式）：传入查询结果的列和数据，服务端生成可交互的 ECharts 图表。\
                    不传 chart_type 时按数据形态自动推断：有日期列画折线，排行画横向条形图，少量占比画饼图。\
                    需要时可显式指定 chart_type（line/bar/hbar/pie/scatter/stacked_bar/dual_axis/heatmap）\
                    以及 x（维度列）、y（指标列）、y2（双轴图右轴指标）、series（堆叠分组列或热力图第二维度）、\
                    labels（显示数据标签）。列名必须与查询结果一致。不要自己构造图表配置。\
                    返回 JSON payload {chart:"echarts", chartType, title, chartId} 供 UI 渲染。\
                    """)
    public String renderChart(
            RuntimeContext context,
            @ToolParam(name = "question", description = "本图表回答的用户问题（中文）", required = false)
                    String question,
            @ToolParam(name = "columns", description = "查询结果的列名，按顺序排列") List<String> columns,
            @ToolParam(name = "rows", description = "结果数据行；每行是一个列表，单元格值为字符串")
                    List<List<String>> rows,
            @ToolParam(
                            name = "mark_line_value",
                            description =
                                    "可选的 KPI 目标/参考值，以红色虚线水平线呈现" + "（如知识文档中的 20000000）。无目标时省略。",
                            required = false)
                    String markLineValue,
            @ToolParam(
                            name = "mark_line_label",
                            description = "参考线的标签，如 日均目标2000万",
                            required = false)
                    String markLineLabel,
            @ToolParam(
                            name = "chart_type",
                            description =
                                    "可选图表类型：line/bar/hbar/pie/scatter/stacked_bar/dual_axis/heatmap；省略时自动推断",
                            required = false)
                    String chartType,
            @ToolParam(name = "x", description = "可选：作为横轴或分类的列名", required = false) String x,
            @ToolParam(name = "y", description = "可选：指标列名列表", required = false) List<String> y,
            @ToolParam(name = "y2", description = "可选：双轴图右轴的指标列名列表", required = false)
                    List<String> y2,
            @ToolParam(name = "series", description = "可选：堆叠柱状图的分组列，或热力图的第二个维度列", required = false)
                    String series,
            @ToolParam(name = "labels", description = "可选：true 时显示数据标签", required = false)
                    Boolean labels) {
        ChartBuilder.BuiltChart chart;
        try {
            chart =
                    ChartBuilder.build(
                            columns,
                            rows,
                            question,
                            new ChartBuilder.Spec(chartType, x, y, y2, series, labels));
        } catch (IllegalArgumentException e) {
            return "error: " + e.getMessage();
        }
        return render(context, chart, markLineValue, markLineLabel);
    }

    /** Inferred chart without an explicit spec; kept for existing callers. */
    public String renderChart(
            RuntimeContext context,
            @ToolParam(name = "question", description = "本图表回答的用户问题（中文）", required = false)
                    String question,
            @ToolParam(name = "columns", description = "查询结果的列名，按顺序排列") List<String> columns,
            @ToolParam(name = "rows", description = "结果数据行；每行是一个列表，单元格值为字符串")
                    List<List<String>> rows,
            @ToolParam(
                            name = "mark_line_value",
                            description =
                                    "可选的 KPI 目标/参考值，以红色虚线水平线呈现" + "（如知识文档中的 20000000）。无目标时省略。",
                            required = false)
                    String markLineValue,
            @ToolParam(
                            name = "mark_line_label",
                            description = "参考线的标签，如 日均目标2000万",
                            required = false)
                    String markLineLabel) {
        return render(
                context, ChartBuilder.build(columns, rows, question), markLineValue, markLineLabel);
    }

    private String render(
            RuntimeContext context,
            ChartBuilder.BuiltChart chart,
            String markLineValue,
            String markLineLabel) {
        if (context == null || context.getUserId() == null || context.getUserId().isBlank()) {
            return "error: 缺少图表所属用户，无法生成图表";
        }
        if (chart == null) {
            return "error: 数据不适合绘制图表（需要至少1列数值且多于1行）";
        }
        if (markLineValue != null && !markLineValue.isBlank()) {
            try {
                double v = Double.parseDouble(markLineValue.trim().replace(",", ""));
                ChartBuilder.applyMarkLine(chart.option(), v, markLineLabel);
            } catch (NumberFormatException e) {
                // ignore unparsable target; chart still renders without the line
            }
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("chart", "echarts");
        payload.put("chartType", chart.chartType());
        payload.put("title", chart.title());
        if (chartOptions != null) {
            try {
                String chartId = java.util.UUID.randomUUID().toString();
                chartOptions.save(
                        new ChartOptionEntity(
                                chartId,
                                context.getUserId(),
                                MAPPER.writeValueAsString(chart.option())));
                payload.put("chartId", chartId);
            } catch (JsonProcessingException e) {
                return "error: 序列化图表配置失败";
            }
        } else {
            payload.put("option", chart.option());
        }
        try {
            return MAPPER.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            return "error: 序列化图表数据失败";
        }
    }
}
