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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.tool.Tool;
import io.agentscope.dataagent.dataset.DatasetContextProvider;
import io.agentscope.dataagent.dataset.DatasetScope;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Verifies the remaining knowledge-retrieval and chart tools of {@link DataAgentToolkit}. */
class DataAgentToolkitTest {

    private static final RuntimeContext RC = null;
    private static final DataAgentToolkit TOOLKIT = new DataAgentToolkit(null, null, null);

    @Test
    void physicalQueryToolsAreRemoved() {
        List<String> toolNames =
                Arrays.stream(DataAgentToolkit.class.getDeclaredMethods())
                        .map(method -> method.getAnnotation(Tool.class))
                        .filter(java.util.Objects::nonNull)
                        .map(Tool::name)
                        .toList();

        assertThat(toolNames)
                .contains("retrieve_evidence", "render_chart")
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

    @Test
    void retrieveEvidenceReturnsCitedPassages() {
        DatasetContextProvider provider = mock(DatasetContextProvider.class);
        when(provider.evidenceFor(eq("bob"), any(), anyString(), eq(3)))
                .thenReturn("【知识库「KB」 › 考核口径】\n- 日活跃用户目标 1000 万");
        DataAgentToolkit tk = new DataAgentToolkit(provider, null, null);

        String out = tk.retrieveEvidence(new DatasetScope("bob"), RC, "考核目标");

        assertThat(out).startsWith("## 检索结果");
        assertThat(out).contains("【知识库「KB」 › 考核口径】");
        assertThat(out).contains("目标 1000 万");
    }

    @Test
    void retrieveEvidenceKeepsNotFoundWording() {
        DatasetContextProvider provider = mock(DatasetContextProvider.class);
        when(provider.evidenceFor(any(), any(), anyString(), org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(null);
        DataAgentToolkit tk = new DataAgentToolkit(provider, null, null);

        // The not-found wording stays byte-stable: the LLM already adapts to this failure shape
        assertThat(tk.retrieveEvidence(new DatasetScope("bob"), RC, "考核目标"))
                .isEqualTo("知识库中没有与 '考核目标' 相关的内容。");
    }
}
