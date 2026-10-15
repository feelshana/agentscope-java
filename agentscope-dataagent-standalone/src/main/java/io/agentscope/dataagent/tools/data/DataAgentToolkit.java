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
                    渲染图表。传入查询结果的列和数据，图表类型由服务端根据数据形态自动推断。\
                    返回 JSON payload {chart:"echarts", chartType, title, option} 供 UI 渲染。\
                    不要自己构造图表配置，只传数据即可。\
                    """)
    public String renderChart(
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
        ChartBuilder.BuiltChart chart = ChartBuilder.build(columns, rows, question);
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
                        new ChartOptionEntity(chartId, MAPPER.writeValueAsString(chart.option())));
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
