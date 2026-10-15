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

import static org.assertj.core.api.Assertions.assertThat;

import io.agentscope.core.tool.Tool;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Verifies the remaining chart presentation tool of {@link DataAgentToolkit}. */
class DataAgentToolkitTest {

    private static final DataAgentToolkit TOOLKIT = new DataAgentToolkit(null);

    @Test
    void physicalQueryToolsAreRemoved() {
        List<String> toolNames =
                Arrays.stream(DataAgentToolkit.class.getDeclaredMethods())
                        .map(method -> method.getAnnotation(Tool.class))
                        .filter(java.util.Objects::nonNull)
                        .map(Tool::name)
                        .toList();

        assertThat(toolNames)
                .containsExactly("render_chart")
                .doesNotContain("prepare_data_context", "query_structured_data");
    }

    @Test
    void renderChartBuildsDeterministicOption() {
        String out =
                TOOLKIT.renderChart(
                        "分析下云南省 30 天走势",
                        List.of("dt", "cnt"),
                        List.of(
                                List.of("2026-09-01", "10"),
                                List.of("2026-09-02", "12"),
                                List.of("2026-09-03", "11")),
                        null,
                        null);

        assertThat(out).contains("\"chart\":\"echarts\"");
        assertThat(out).contains("\"chartType\":\"line\"");
        assertThat(out).contains("\"series\"");
    }

    @Test
    void renderChartAppliesTargetMarkLine() {
        String out =
                TOOLKIT.renderChart(
                        "活跃用户趋势",
                        List.of("dt", "cnt"),
                        List.of(List.of("2026-09-01", "10"), List.of("2026-09-02", "12")),
                        "20",
                        "日均目标");
        assertThat(out).contains("markLine");
        assertThat(out).contains("日均目标");
    }

    @Test
    void renderChartRejectsUnchartableData() {
        assertThat(TOOLKIT.renderChart("q", List.of("a"), List.of(List.of("x")), null, null))
                .startsWith("error:");
    }
}
