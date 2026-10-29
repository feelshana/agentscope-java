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
import io.agentscope.dataagent.runtime.session.ModelingTurnRules;
import io.agentscope.dataagent.tools.data.DataAgentToolkit;
import io.agentscope.dataagent.tools.data.WrenMcpToolkit;
import io.agentscope.harness.agent.gateway.channel.ChannelConfig;
import io.agentscope.harness.agent.gateway.channel.DmScope;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
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
 *   <li>the system prompt and Wren tool descriptions keep structured queries on the published
 *       semantic model, while the detailed SQL construction rules stay in {@code sql-analysis}.
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

    @Test
    void builtInWrenAgentsCannotInheritUnscopedWorkspaceMcpServers() {
        var source = new io.agentscope.harness.agent.tools.ToolsConfig();
        source.setDeny(List.of("execute_shell_command"));
        source.setMcpServers(
                java.util.Map.of(
                        "legacy-wren",
                        org.mockito.Mockito.mock(
                                io.agentscope.harness.agent.tools.McpServerConfig.class)));
        for (String agent : List.of("data-agent", "modeling-agent")) {
            var actual = DataAgentConfig.scopedWrenToolsConfig(agent, source);
            assertThat(actual.getMcpServers()).isEmpty();
            assertThat(actual.getDeny()).containsExactly("execute_shell_command");
            assertThat(DataAgentConfig.scopedWrenToolsConfig(agent, null).getMcpServers())
                    .isEmpty();
        }
        assertThat(source.getMcpServers()).containsKey("legacy-wren");
        assertThat(DataAgentConfig.scopedWrenToolsConfig("custom-agent", source)).isSameAs(source);
    }

    @Test
    void modelTransportDefaultsToHttp11AndAllowsExplicitProtocolOverride() throws Exception {
        for (var version : io.agentscope.core.model.transport.HttpVersion.values()) {
            var config = new DataAgentConfig();
            org.springframework.test.util.ReflectionTestUtils.setField(
                    config, "openaiApiKey", "test");
            org.springframework.test.util.ReflectionTestUtils.setField(
                    config, "openaiModelName", "test");
            if (version == io.agentscope.core.model.transport.HttpVersion.HTTP_2)
                org.springframework.test.util.ReflectionTestUtils.setField(
                        config, "openaiHttpVersion", version);
            var model = config.openaiModel();
            var client =
                    org.springframework.test.util.ReflectionTestUtils.getField(model, "client");
            var transport =
                    (io.agentscope.core.model.transport.HttpTransport)
                            org.springframework.test.util.ReflectionTestUtils.getField(
                                    client, "transport");
            try {
                var options =
                        (io.agentscope.core.model.transport.HttpTransportConfig)
                                org.springframework.test.util.ReflectionTestUtils.getField(
                                        transport, "config");
                assertThat(options.getHttpVersion()).isEqualTo(version);
            } finally {
                transport.close();
                io.agentscope.core.model.transport.HttpTransportFactory.unregister(transport);
            }
        }
    }

    @Test
    void chatUiAlwaysUsesConversationScopedSessions() {
        ChannelConfig configured =
                ChannelConfig.builder("chatui")
                        .defaultAgentId("data-agent")
                        .dmScope(DmScope.MAIN)
                        .build();

        ChannelConfig effective = DataAgentConfig.conversationScopedChatUiConfig(configured);

        assertThat(effective.dmScope()).isEqualTo(DmScope.PER_ACCOUNT_CHANNEL_PEER);
        assertThat(effective.defaultAgentId()).isEqualTo("data-agent");
    }

    @Test
    void conversationScopedChatUiConfigIsKeptAsIs() {
        ChannelConfig configured =
                ChannelConfig.builder("chatui").dmScope(DmScope.PER_ACCOUNT_CHANNEL_PEER).build();

        assertThat(DataAgentConfig.conversationScopedChatUiConfig(configured)).isSameAs(configured);
    }

    @Test
    void wrenToolDescriptionsRequirePublishedLogicalModels() {
        assertThat(toolDescription(WrenMcpToolkit.class, "wren_call_tool"))
                .contains("官方 MCP", "run_sql", "LIMIT/OFFSET");
    }

    /**
     * The prompt must keep the gates that only work while always-on: a "do not chart unless asked"
     * rule is useless inside the charting skill (which is loaded only after the model already
     * decided to chart), and the output-language rule applies whether or not any skill is loaded.
     */
    @Test
    void modelingPromptMatchesTheOfficialCliContractAndSourceTiers() {
        assertThat(DataAgentConfig.MODELING_SCRIPT)
                .contains(
                        "不调用 memory store，不写 knowledge/sql/",
                        "不为此自拟样例问题",
                        "knowledge/questions/<ID>.yml",
                        "页面发布后可被问数召回",
                        "[\"memory\",\"index\",\"--no-seed\"]",
                        "不再通过 wren_cli skills list/get 重复获取同一技能",
                        "不调用 cube 命令",
                        "不得声明 MANY_TO_ONE 或 ONE_TO_ONE",
                        "表来源与探查边界")
                .doesNotContain(
                        "禁止对同一路径连续发起多个 edit_file", "\"--tags\",\"source:enrich\"", "不要把问答保存留为");
        // 2026-10-22: rules must cite a real source, and date conditions come from the platform's
        // per-column formats rather than a prompt-taught LIKE technique.
        assertThat(DataAgentConfig.MODELING_SCRIPT)
                .contains(
                        "所注依据必须能在该来源中找到",
                        "必须用 request_user_confirmation 提问",
                        "description_source: user",
                        "不要重新加入",
                        // spec095: business terms share the page-maintained Naming conventions.
                        "knowledge/rules/naming.md",
                        "已有条目不改写、不删除",
                        // ADR0116: data facts go to descriptions, caveats are retired.
                        "写入相关表或列的描述",
                        "knowledge/caveats/ 已停用")
                .doesNotContain("LIKE", "其余推断写入 knowledge/caveats/");
        assertThat(DataAgentConfig.QUERY_EFFICIENCY_RULES).doesNotContain("LIKE");
        // Modeling replies are for business users: short, Chinese only, no files or commands.
        assertThat(DataAgentConfig.MODELING_SCRIPT)
                .contains("不要输出任何英文句子", "不列文件路径、YAML 键名、命令", "不提及平台内部机制", "不在回复正文里提问")
                .doesNotContain("最终说明实际修改、检查结果");
        // Reply length and turn-specific rules follow the page-declared purpose.
        assertThat(ModelingTurnRules.forPurpose("auto"))
                .contains("3–6 行", "可以声明 MANY_TO_ONE", "非数据库约束")
                .doesNotContain("试问 SQL");
        // 2026-10-26: skip the official preflight the context already answers, in auto and
        // document turns alike; documents are read, never searched for.
        assertThat(ModelingTurnRules.forPurpose("auto"))
                .contains("不执行 context show、memory status", "raw/docs/ 下的业务文档");
        assertThat(ModelingTurnRules.forPurpose("document"))
                .contains("不执行 context show、memory status", "只读有变化的文档");
        assertThat(ModelingTurnRules.forPurpose("confirm"))
                .contains("写入 knowledge/rules/（指标定义、默认过滤、术语）或对应模型的列描述", "1–2 行");
        assertThat(ModelingTurnRules.forPurpose("question"))
                .contains("knowledge/questions/<ID>.yml", "不要执行 query", "dry-plan", "不擅自新增业务条件")
                .doesNotContain("3–6 行");
        assertThat(ModelingTurnRules.forPurpose(null)).contains("用户对话", "试问 SQL");
        assertThat(DataAgentConfig.MODELING_SCRIPT)
                .contains(
                        "整体替换可直接用 write_file",
                        "由平台自动先执行 context build",
                        "不读取、不写入 cubes/ 与 views/",
                        "由用户在发布前统一审阅",
                        "不要在对话中用文字列出方案等待回复",
                        "request_user_confirmation",
                        "不要向用户确认");
        assertThat(DataAgentConfig.QUERY_EFFICIENCY_RULES)
                .contains(
                        "不为年份向用户提问",
                        "不为年份或日期再向用户确认",
                        "规则未定义且存在实质歧义时才询问",
                        "不得对多行结果心算或手工汇总",
                        "limit 不得小于预期行数",
                        "GROUP BY 只放该主体",
                        "执行了查询就不能声称未重新统计");
    }

    @Test
    void defaultSysPromptKeepsAlwaysOnBehaviourGates() {
        assertThat(DataAgentConfig.DEFAULT_AGENT_SYS_PROMPT)
                .contains("不编造结果", "不主动导出文件", "简体中文", "已授权已发布工程");
    }

    /** The prompt points at the skill and carries the Wren-only tool-chain skeleton. */
    @Test
    void defaultSysPromptPointsAtWrenOnlyWorkflow() {
        assertThat(DataAgentConfig.DEFAULT_AGENT_SYS_PROMPT)
                .contains("官方 Wren usage", "官方工具 schema", "run_python", "render_chart")
                .doesNotContain(
                        "sql-analysis", "wren_run_sql", "wren_describe_model", "query_type");
    }

    @Test
    void defaultSysPromptUsesRelevantExamplesSqlAndCompleteFinalAnswer() {
        assertThat(DataAgentConfig.DEFAULT_AGENT_SYS_PROMPT)
                .contains("memory", "完整最终答案", "只有用户点赞后", "诊断查询", "answer_question")
                .doesNotContain("BUSINESS", "DIAGNOSTIC");
    }

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

    @Test
    void physicalQueryToolsAreAbsentFromAgentToolkits() {
        List<String> toolNames =
                java.util.stream.Stream.concat(
                                Arrays.stream(DataAgentToolkit.class.getDeclaredMethods()),
                                Arrays.stream(WrenMcpToolkit.class.getDeclaredMethods()))
                        .map(method -> method.getAnnotation(Tool.class))
                        .filter(java.util.Objects::nonNull)
                        .map(Tool::name)
                        .toList();

        assertThat(toolNames)
                .doesNotContain(
                        "wren_read_knowledge",
                        "wren_describe_model",
                        "wren_run_sql",
                        "wren_recall_examples",
                        "wren_query_cube",
                        "wren_answer_queries")
                .doesNotContain("prepare_data_context", "query_structured_data");
    }

    @Test
    void customKnowledgeRetrievalIsNotAnAgentTool() {
        assertThat(
                        Arrays.stream(DataAgentToolkit.class.getDeclaredMethods())
                                .map(method -> method.getAnnotation(Tool.class))
                                .filter(java.util.Objects::nonNull)
                                .map(Tool::name)
                                .toList())
                .doesNotContain("retrieve_evidence");
    }

    /**
     * Answer-count consistency (ADR 0008): the observed answer claimed "共 8 位" above a table
     * listing 9 accounts. The duty spans every tool (SQL, Python, retrieval) and belongs with the
     * "do not fabricate data" principle, so it lives in the always-on prompt, not in a skill.
     */
    @Test
    void defaultSysPromptRequiresCountConsistency() {
        assertThat(DataAgentConfig.DEFAULT_AGENT_SYS_PROMPT)
                .contains("不编造结果", "截断")
                .doesNotContain("以明细为准重算");
    }

    /** Reads the {@code @Tool#description} the framework exposes to the model for a tool name. */
    private static String toolDescription(Class<?> toolkitType, String toolName) {
        for (Method method : toolkitType.getDeclaredMethods()) {
            Tool tool = method.getAnnotation(Tool.class);
            if (tool != null && toolName.equals(tool.name())) {
                return tool.description();
            }
        }
        throw new AssertionError(
                "no @Tool named '" + toolName + "' on " + toolkitType.getSimpleName());
    }

    @Test
    void queryAgentUsesPlatformPromptWithoutLegacyWorkspaceContext() {
        var builder = io.agentscope.harness.agent.HarnessAgent.builder();
        DataAgentConfig.configureQueryPrompt(builder, DataAgentConfig.DEFAULT_AGENT_SYS_PROMPT);
        assertThat(DataAgentConfig.DEFAULT_AGENT_SYS_PROMPT)
                .contains("官方指导", "原样遵循官方 schema")
                .doesNotContain("[DATA_SOURCES_OVERVIEW]", "query_type");
    }

    /** spec096: tables, then render_chart by default, then run_python only for computation. */
    @Test
    void visualizationIsTieredAndPythonReadsDataFiles() throws Exception {
        assertThat(DataAgentConfig.DEFAULT_AGENT_SYS_PROMPT)
                .contains("可视化分三层", "默认用 render_chart", "同一问题最多 2 次", "读取查询返回的数据文件")
                .contains("不要 import")
                .doesNotContain("根据用户需要使用 run_python 或 render_chart");
        String python =
                io.agentscope.dataagent.tools.data.RunPythonTool.class
                        .getMethod(
                                "runPython",
                                io.agentscope.core.agent.RuntimeContext.class,
                                String.class,
                                String.class)
                        .getAnnotation(io.agentscope.core.tool.Tool.class)
                        .description();
        assertThat(python)
                .contains("render_chart", "最多调用 2 次", "超过 20 行的数据字面量会被拒绝")
                .doesNotContain("应主动可视化", "_insights.md");
    }

    /** 2026-10-25: sources are MySQL 8.0+ or Doris; date ranges are literal so rules gate them. */
    @Test
    void sqlIsMysqlEightWithLiteralDateRanges() {
        assertThat(DataAgentConfig.QUERY_EFFICIENCY_RULES)
                .contains(
                        "MySQL 8.0 语法",
                        "不要用 CURDATE()、NOW()、DATE_SUB",
                        "log_time >= '2026-09-26' AND log_time < '2026-10-26'")
                .doesNotContain("平台不会解析或生成日期范围");
        assertThat(DataAgentConfig.MODELING_SCRIPT).contains("MySQL 8.0 语法", "不用 CURDATE()");
    }

    /**
     * 2026-10-26 end-to-end run: without a document the assistant recommended guessed status
     * codes, and the recommended answers wrote a wrong definition the document then contradicted.
     */
    @Test
    void confirmationsDoNotRecommendGuesses() {
        assertThat(
                        toolDescription(
                                io.agentscope.dataagent.tools.data.ModelingConfirmationTool.class,
                                "request_user_confirmation"))
                .contains("没有依据时", "暂不确定，先记为待确认");
        assertThat(DataAgentConfig.MODELING_COMPACT_TOKENS).isLessThan(128_000);
        assertThat(DataAgentConfig.MODELING_COMPACT_MESSAGES).isGreaterThan(80);
    }

    /** spec094: external questions go online; knowledge-base numbers stay with SQL. */
    @Test
    void queryPromptKeepsWebSearchToExternalInformation() {
        assertThat(DataAgentConfig.QUERY_EFFICIENCY_RULES)
                .contains(
                        "# 联网搜索",
                        "不走问数流程：不召回问答、不读取模型、不执行 SQL，也不因缺少日期先询问",
                        "只能来自 SQL，不用搜索结果代替或补充统计值",
                        "不写内部数据、查询结果、客户或员工名称",
                        "注明来源网址",
                        "只针对知识库数据查询");
    }

    /** Every listed skill is rendered into each model call, so each agent lists only its own. */
    @Test
    void eachAgentListsOnlyTheOfficialSkillsItUses() {
        assertThat(DataAgentConfig.QUERY_SKILLS).containsExactly("usage");
        assertThat(DataAgentConfig.MODELING_SKILLS)
                .containsExactlyInAnyOrder("generate-mdl", "enrich-context");
    }
}
