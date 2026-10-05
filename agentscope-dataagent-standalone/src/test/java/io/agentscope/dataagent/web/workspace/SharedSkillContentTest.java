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

    /** Retired tool names must never be taught by the shipped Wren-only skills. */
    private static final List<String> LEGACY_TOOL_NAMES =
            List.of(
                    "run_sql_preview",
                    "describe_table",
                    "list_data_sources",
                    "read_knowledge",
                    "prepare_data_context",
                    "query_structured_data");

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
                .contains("wren_run_sql")
                .contains("wren_describe_model");
    }

    /** Logical-model discovery is batched through the Wren metadata tool. */
    @Test
    void sqlAnalysisCarriesModelDiscoveryHowTo() {
        assertThat(read(SQL_ANALYSIS))
                .contains("1–5 个逻辑模型")
                .contains("model_names")
                .contains("wren_describe_model")
                .contains("[DATA_SOURCES_OVERVIEW]");
    }

    @Test
    void sqlAnalysisKeepsCubeViewProjectionSqlRoutingOrder() {
        assertThat(read(SQL_ANALYSIS))
                .contains("按官方决策树选工具")
                .contains("已发布 Cube 成员覆盖时优先用 `wren_query_cube`")
                .contains("已发布 View 能直接覆盖问题时优先用 `wren_run_sql` 按视图名直接查询")
                .contains("展开 many 侧关联字段组")
                .contains("单一逻辑模型的投影列查询")
                .contains("relationship condition 自动 JOIN")
                .contains("语义资产都无法表达时才用 `wren_run_sql`")
                .contains("显式 JOIN 是最后兜底")
                // specs/025: official alignment — prefer wording, no mandatory gate
                .doesNotContain("不得从基础模型重建同等语义")
                .doesNotContain("禁止改写为手工聚合 SQL")
                .doesNotContain("View 必须直接出现在 `FROM` 中");
    }

    @Test
    void sqlAnalysisRejectsPhysicalFallback() {
        assertThat(read(SQL_ANALYSIS))
                .contains("不尝试物理 SQL 回退")
                .contains("绝不猜测物理表名、datasetId 或 sourceId")
                .doesNotContain("prepare_data_context")
                .doesNotContain("query_structured_data");
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

    /** SQL and Python analysis share the same Wren-only structured-query channel. */
    @Test
    void skillsUseWrenOnlyChannel() {
        for (String path : List.of(SQL_ANALYSIS, PYTHON_ANALYSIS)) {
            assertThat(read(path))
                    .contains("wren_run_sql")
                    .contains("wren_query_cube")
                    .contains("wren_describe_model")
                    .doesNotContain("prepare_data_context")
                    .doesNotContain("query_structured_data");
        }
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
                .contains("wren_describe_model")
                .contains("wren_run_sql")
                .contains("wren_query_cube")
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

    /**
     * The file-level data handoff (specs/016): the explore template must read the CSV referenced
     * by the wren query tools, and the anti-pattern list must ban copying rows into code literals
     * (the old "硬编码为 DataFrame" template was the institutionalised root cause).
     */
    @Test
    void pythonAnalysisReadsQueryDataFilesInsteadOfCopyingRows() {
        assertThat(read(PYTHON_ANALYSIS))
                .contains("pd.read_csv('data/")
                .contains("数据文件")
                .contains("禁止把查询结果行抄写成代码字面量")
                .contains("把查询结果数据抄写成 Python 字面量")
                .doesNotContain("硬编码为 DataFrame");
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
