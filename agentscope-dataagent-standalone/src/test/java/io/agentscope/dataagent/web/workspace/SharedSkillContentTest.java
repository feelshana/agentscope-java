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
package io.agentscope.dataagent.web.workspace;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Guards the shipped skill content under {@code classpath:shared/} — the single source of truth
 * that {@link SharedWorkspaceSeeder} materialises into every deployment's runtime {@code shared/}
 * directory.
 *
 * <p>Two failure classes are covered. An emptied {@code SKILL.md} registers no skill at all
 * (harness {@code WorkspaceSkillRepository} parses the frontmatter, so a zero-byte file silently
 * drops the skill from {@code available_skills}); and a stale body that still names retired tools
 * teaches the model to call tools that no longer exist. Both went unnoticed before because nothing
 * asserted on the bundled content.
 */
class SharedSkillContentTest {

    private static final String SKILLS = "shared/agents/data-agent/skills/";

    private static final String SQL_ANALYSIS = SKILLS + "sql-analysis/SKILL.md";
    private static final String PYTHON_ANALYSIS = SKILLS + "python-analysis/SKILL.md";
    private static final String CHART_RENDERING = SKILLS + "chart-rendering/SKILL.md";

    /** Tool names retired by the TC-style toolchain (ADR 0001); they must not be taught anymore. */
    private static final List<String> LEGACY_TOOL_NAMES =
            List.of("run_sql_preview", "describe_table", "list_data_sources", "read_knowledge");

    @Test
    void everyShippedSkillIsNonEmptyAndHasFrontmatter() {
        for (String path : List.of(SQL_ANALYSIS, PYTHON_ANALYSIS, CHART_RENDERING)) {
            String body = read(path);
            assertThat(body).as("%s must not be empty", path).isNotBlank();
            assertThat(body).as("%s must start with frontmatter", path).startsWith("---");
            assertThat(body).as("%s must declare a skill name", path).contains("name: ");
        }
    }

    @Test
    void sqlAnalysisCarriesCrossTableDedupRules() {
        assertThat(read(SQL_ANALYSIS))
                .contains("name: sql-analysis")
                .contains("SELECT DISTINCT")
                .contains("扇出")
                .contains("禁止把上一步查询结果作为字面量复制进")
                // the ban must also cover fetching a dimension attribute, not just filtering
                .contains("补维度属性")
                .contains("query_structured_data")
                .contains("prepare_data_context");
    }

    /**
     * The batch-prepare how-to (ADR 0006) lives only here: the system prompt just says "load
     * sql-analysis and follow its steps", so dropping these lines would lose the rule entirely.
     */
    @Test
    void sqlAnalysisCarriesBatchPrepareHowTo() {
        assertThat(read(SQL_ANALYSIS))
                .contains("1–3 张表")
                .contains("tables")
                .contains("一次调用批量取回")
                .contains("[DATA_SOURCES_OVERVIEW]");
    }

    /**
     * The skill must not advertise output the tool never produces. {@code
     * DataAgentToolkit#buildTableSection} emits name/original name/type/description only, so
     * claiming "维度值样例" invites the model to go probe for values that were promised but
     * missing (ADR 0008).
     */
    @Test
    void sqlAnalysisDoesNotClaimSampleValues() {
        assertThat(read(SQL_ANALYSIS))
                .doesNotContain("维度值样例")
                .doesNotContain("样例值")
                .contains("取值提示");
    }

    /**
     * The full anti-probe wording (example SQL included) lives only here — the tool description
     * carries the one-line gate, so this is the handbook layer of the ADR 0008 split.
     */
    @Test
    void sqlAnalysisKeepsAntiProbeHowTo() {
        assertThat(read(SQL_ANALYSIS))
                .contains("摸底")
                .contains("SELECT COUNT(*)")
                .contains("SELECT MIN(date)");
    }

    /**
     * Subagent orchestration is off by default (ADR 0009), so every delegation instruction must be
     * conditional on {@code agent_spawn} actually being in the tool set — otherwise the skill
     * teaches the model to call a tool that does not exist.
     */
    @Test
    void sqlAnalysisGatesDelegationOnToolAvailability() {
        assertThat(read(SQL_ANALYSIS))
                .contains("agent_spawn")
                .contains("dataagent.agent.subagents-enabled")
                .contains("不要尝试调用不存在的工具");
    }

    @Test
    void skillsDoNotReferenceRetiredToolNames() {
        for (String path : List.of(SQL_ANALYSIS, PYTHON_ANALYSIS, CHART_RENDERING)) {
            assertThat(read(path))
                    .as("%s must not name retired tools", path)
                    .doesNotContain(LEGACY_TOOL_NAMES.toArray(String[]::new));
        }
    }

    /**
     * Chart-labelling language and the sandbox CJK font live only here: the system prompt keeps
     * the generic "everything in Simplified Chinese" gate but no matplotlib detail.
     */
    @Test
    void pythonAnalysisKeepsCurrentToolNamesAndChineseLabelRule() {
        assertThat(read(PYTHON_ANALYSIS))
                .contains("query_structured_data")
                .contains("prepare_data_context")
                .contains("[DATA_SOURCES_OVERVIEW]")
                .contains("retrieve_evidence")
                .contains("run_python")
                .contains("图表标题/坐标轴/图例/注释使用英文")
                .contains("Noto Sans CJK SC");
    }

    @Test
    void chartRenderingPointsAtEvidenceRetrieval() {
        assertThat(read(CHART_RENDERING)).contains("retrieve_evidence").contains("render_chart");
    }

    private static String read(String classpathLocation) {
        try (InputStream in =
                SharedSkillContentTest.class
                        .getClassLoader()
                        .getResourceAsStream(classpathLocation)) {
            assertThat(in).as("classpath resource %s must exist", classpathLocation).isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new AssertionError("failed to read " + classpathLocation, e);
        }
    }
}
