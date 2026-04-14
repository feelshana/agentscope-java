/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.examples.chatbi.tool;

import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

/**
 * Tool for data interpretation (数据解读), a sub-type of the {@code da} intent.
 *
 * <p>When the user says "请解读当前数据" or "帮我分析这份数据", the frontend passes the
 * chart/table data as a {@code param} JSON string in the request. This tool formats
 * that raw data into a structured context for the LLM to produce analytical insights.
 *
 * <p>Unlike {@code DataQueryTool} which fetches new data from SuperSonic, this tool
 * operates on data that is already present in the frontend and passed via the request.
 */
public class DataInterpretTool {

    private static final Logger log = LoggerFactory.getLogger(DataInterpretTool.class);

    /** Per-session chart data injected at session creation (from request.param). */
    private final String chartParam;

    public DataInterpretTool(String chartParam) {
        this.chartParam = chartParam;
    }

    /**
     * Interpret chart or table data that is currently displayed on the frontend.
     * Wraps the raw data and user's analysis focus into a structured prompt context.
     *
     * @param analysisAngle specific angle or question the user wants analyzed
     * @return formatted data context for LLM interpretation
     */
    @Tool(
            name = "interpret_chart_data",
            description =
                    "Interpret and analyze chart/table data that the user is currently viewing. Use"
                        + " this tool when the user says '请解读当前数据', '帮我分析这份数据', '这个数据说明了什么', or"
                        + " similar requests to analyze displayed data. The tool returns the raw"
                        + " data in a structured format for you to analyze.")
    public Mono<String> interpretChartData(
            @ToolParam(
                            name = "analysis_angle",
                            description =
                                    "The specific aspect or angle the user wants analyzed. "
                                            + "E.g., '趋势分析', '异常点', '同比变化', '整体表现'. "
                                            + "If the user did not specify, use '整体数据解读'.")
                    String analysisAngle) {
        log.info("[interpret_chart_data] analysisAngle={}", analysisAngle);
        if (chartParam == null || chartParam.isBlank()) {
            return Mono.just("未获取到当前图表数据，请确认前端已正确传递 param 参数。");
        }
        String context =
                "【当前图表/数据内容】\n"
                        + chartParam
                        + "\n\n"
                        + "【分析角度】\n"
                        + analysisAngle
                        + "\n\n"
                        + "请根据以上数据内容，从指定角度进行深度分析和解读，"
                        + "识别趋势、异常、关键指标变化，并给出业务洞察和建议。";
        return Mono.just(context);
    }
}
