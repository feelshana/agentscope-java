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
package io.agentscope.dataagent.web.config;

import static org.assertj.core.api.Assertions.assertThat;

import io.agentscope.core.tool.Tool;
import io.agentscope.dataagent.tools.data.DataAgentToolkit;
import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;

/**
 * Guards the prompt layering of ADR 0007. Two always-on layers are asserted here:
 *
 * <ul>
 *   <li>the built-in system prompt ({@code DataAgentConfig#DEFAULT_AGENT_SYS_PROMPT}, the default
 *       of {@code dataagent.agent.sys-prompt} / {@code DATAAGENT_AGENT_SYS_PROMPT}) keeps the
 *       persona, the behaviour gates that must hold before any skill is loaded, the workflow
 *       skeleton and a pointer at the {@code sql-analysis} skill — and restates none of the
 *       skill-level how-to;
 *   <li>the {@code query_structured_data} tool description carries the hard SQL gates
 *       (JOIN/CTE-first, dimension-table de-duplication) plus the anti-probe gate. Tool
 *       descriptions travel with the tool schema on every turn, so they reach the model even when
 *       the skill body was never loaded.
 * </ul>
 *
 * <p>The detailed how-to lives in the skills and is guarded by {@code SharedSkillContentTest}.
 *
 * <p>ADR 0008 adds two more gates after a real session ({@code logs/LLM.log}) showed the model
 * never loading the skill: a ban on probe-only queries (tool description, layer A) and an
 * answer-count consistency duty (system prompt, layer B). Both are asserted here precisely because
 * the skill-level wording is unreachable when the skill is not loaded.
 */
class DataAgentConfigTest {

    /**
     * The IN-literal ban must name the "fetch a dimension attribute" case too. A real session
     * ({@code logs/LLM.log}, 18:23) copied nine accounts into {@code WHERE account IN (...)} just
     * to look up department names — which the previous wording, scoped to "cross-table filtering",
     * let through.
     */
    @Test
    void queryToolDescriptionCarriesCrossTableGates() {
        assertThat(toolDescription("query_structured_data"))
                .contains("禁止把上一步查询结果作为字面量复制进 IN")
                .contains("补维度属性")
                .contains("JOIN")
                .contains("CTE")
                .contains("一个关联键对应多行")
                .contains("SELECT DISTINCT")
                .contains("扇出")
                .contains("sql-analysis");
    }

    @Test
    void queryToolDescriptionKeepsPrepareFirstContract() {
        assertThat(toolDescription("query_structured_data"))
                .contains("[DATA_SOURCES_OVERVIEW]")
                .contains("prepare_data_context")
                .contains("SELECT / WITH");
    }

    /**
     * The prompt must keep the gates that only work while always-on: a "do not chart unless asked"
     * rule is useless inside the charting skill (which is loaded only after the model already
     * decided to chart), and the output-language rule applies whether or not any skill is loaded.
     */
    @Test
    void defaultSysPromptKeepsAlwaysOnBehaviourGates() {
        assertThat(DataAgentConfig.DEFAULT_AGENT_SYS_PROMPT)
                .contains("不编造数据")
                .contains("不主动生成图表")
                .contains("不主动生成 PDF/Excel/PPT")
                .contains("所有输出必须使用简体中文")
                .contains("[DATA_SOURCES_OVERVIEW]")
                .contains("[KNOWLEDGE_BASE_OVERVIEW]");
    }

    /** The prompt points at the skill for SQL work and carries the tool-chain skeleton. */
    @Test
    void defaultSysPromptPointsAtSqlAnalysisSkill() {
        assertThat(DataAgentConfig.DEFAULT_AGENT_SYS_PROMPT)
                .contains("sql-analysis")
                .contains("prepare_data_context")
                .contains("query_structured_data")
                .contains("retrieve_evidence")
                .contains("render_chart");
    }

    /**
     * Anti-duplication guard: how-to detail belongs to exactly one layer. Everything asserted
     * absent here is carried — in more detail — by {@code sql-analysis} (batch prepare of the
     * directly related tables), the {@code query_structured_data} tool description (JOIN/CTE
     * de-duplication) and {@code python-analysis} (matplotlib labelling and CJK fonts), all of
     * which {@code SharedSkillContentTest} pins down.
     */
    @Test
    void defaultSysPromptDoesNotRestateSkillLevelDetail() {
        assertThat(DataAgentConfig.DEFAULT_AGENT_SYS_PROMPT)
                .doesNotContain("1–3 张表")
                .doesNotContain("tables 参数")
                .doesNotContain("JOIN/CTE")
                .doesNotContain("SELECT DISTINCT")
                .doesNotContain("plt.title")
                .doesNotContain("matplotlib");
    }

    /**
     * Anti-probe gate (ADR 0008). The observed session spent a whole turn on three probe queries
     * whose results never reached the final SQL, while {@code sql-analysis} step 3 — which forbids
     * exactly that — was never loaded. Only the function-name anchors live here; the full example
     * wording ({@code SELECT MIN(date)}) stays in the skill so the two layers do not drift.
     */
    @Test
    void queryToolDescriptionForbidsProbeQueries() {
        assertThat(toolDescription("query_structured_data"))
                .contains("探查查询")
                .contains("COUNT(*)")
                .contains("MIN")
                .contains("MAX")
                .contains("结论本身需要的聚合不在此列")
                // the cross-table gates must survive next to the new one
                .contains("禁止把上一步查询结果作为字面量复制进 IN")
                .contains("SELECT DISTINCT")
                // the gate names functions, it does not restate the skill's example SQL
                .doesNotContain("SELECT MIN(");
    }

    /**
     * The prepare tool must advertise what it actually returns. {@code buildTableSection} emits
     * name/original name/type/description only — never sample values — and the AI-written
     * descriptions usually carry the enum hints, which is what makes the anti-probe gate viable.
     */
    @Test
    void prepareToolDescriptionStatesValueHints() {
        assertThat(toolDescription("prepare_data_context"))
                .contains("取值提示")
                .contains("直接写 WHERE")
                .doesNotContain("样例值")
                .doesNotContain("维度值样例");
    }

    /**
     * Answer-count consistency (ADR 0008): the observed answer claimed "共 8 位" above a table
     * listing 9 accounts. The duty spans every tool (SQL, Python, retrieval) and belongs with the
     * "do not fabricate data" principle, so it lives in the always-on prompt, not in a skill.
     */
    @Test
    void defaultSysPromptRequiresCountConsistency() {
        assertThat(DataAgentConfig.DEFAULT_AGENT_SYS_PROMPT)
                .contains("与自己列出的明细行数一致")
                .contains("以明细为准重算")
                // the pre-existing answer rules must all survive
                .contains("默认用 markdown 表格呈现结构化数据")
                .contains("不主动生成图表")
                .contains("不主动生成 PDF/Excel/PPT")
                .contains("所有输出必须使用简体中文");
    }

    /** Reads the {@code @Tool#description} the framework exposes to the model for a tool name. */
    private static String toolDescription(String toolName) {
        for (Method m : DataAgentToolkit.class.getDeclaredMethods()) {
            Tool tool = m.getAnnotation(Tool.class);
            if (tool != null && toolName.equals(tool.name())) {
                return tool.description();
            }
        }
        throw new AssertionError("no @Tool named '" + toolName + "' on DataAgentToolkit");
    }
}
