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

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.skill.SkillFilter;
import io.agentscope.core.skill.repository.FileSystemSkillRepository;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.core.state.InMemoryAgentStateStore;
import io.agentscope.dataagent.dataset.DatasetContextProvider;
import io.agentscope.dataagent.dataset.MdlCatalog;
import io.agentscope.dataagent.dataset.WrenSkillsLocator;
import io.agentscope.dataagent.runtime.DataAgentBootstrap;
import io.agentscope.dataagent.runtime.config.ChannelConfigEntry;
import io.agentscope.dataagent.runtime.marketplace.GitDataAgentMarketplace;
import io.agentscope.dataagent.runtime.marketplace.LocalApprovalMarketplace;
import io.agentscope.dataagent.runtime.marketplace.NacosDataAgentMarketplace;
import io.agentscope.dataagent.runtime.marketplace.UserMarketplaceRegistry.DataAgentMarketplaceFactoryRegistration;
import io.agentscope.dataagent.runtime.session.DataDynamicContextMiddleware;
import io.agentscope.dataagent.runtime.session.GroupScopedMemoryNamespace;
import io.agentscope.dataagent.runtime.session.WrenScopedToolkitMiddleware;
import io.agentscope.dataagent.tools.data.DataSourceRegistry;
import io.agentscope.dataagent.tools.data.ModelingToolkitRegistrar;
import io.agentscope.dataagent.web.middleware.DebugLoggingMiddleware;
import io.agentscope.dataagent.web.middleware.ModelingHitlMiddleware;
import io.agentscope.dataagent.web.persistence.jpa.DatasetGroupRepository;
import io.agentscope.dataagent.web.persistence.jpa.IdentityLinkRepository;
import io.agentscope.dataagent.web.persistence.jpa.SessionRegistryRepository;
import io.agentscope.dataagent.web.toolbus.ToolEventBus;
import io.agentscope.dataagent.web.toolbus.ToolNotificationMiddleware;
import io.agentscope.dataagent.web.workspace.UserControlFilesystem;
import io.agentscope.dataagent.web.workspace.UserSandboxRegistry;
import io.agentscope.extensions.model.dashscope.DashScopeChatModel;
import io.agentscope.extensions.model.openai.OpenAIChatModel;
import io.agentscope.harness.agent.IsolationScope;
import io.agentscope.harness.agent.filesystem.spec.LocalFilesystemSpec;
import io.agentscope.harness.agent.gateway.channel.ChannelConfig;
import io.agentscope.harness.agent.gateway.channel.DmScope;
import io.agentscope.harness.agent.gateway.channel.chatui.ChatUiChannel;
import io.agentscope.harness.agent.memory.compaction.ToolResultEvictionConfig;
import io.agentscope.harness.agent.sandbox.SandboxClient;
import io.agentscope.harness.agent.sandbox.impl.docker.DockerFilesystemSpec;
import io.agentscope.harness.agent.sandbox.impl.docker.DockerSandboxClientOptions;
import io.agentscope.harness.agent.tools.ToolsConfig;
import io.agentscope.harness.agent.tools.ToolsConfigLoader;
import io.agentscope.harness.agent.workspace.WorkspaceManager;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Spring Boot configuration for the agentscope-dataagent web module.
 *
 * <p>Assembles a {@link DataAgentBootstrap} from {@code .agentscope/agentscope.json} in the working
 * directory (defaults to {@code dataagent.workspace}), then registers a {@link ChatUiChannel} with
 * {@link DmScope#PER_PEER} so each authenticated user gets an isolated agent session and namespace.
 *
 * <h2>Property prefix</h2>
 *
 * <p>All config keys live under {@code dataagent.*}.
 *
 * <h2>Filesystem topology</h2>
 *
 * <p>DataAgent is a multi-tenant deployable. Every {@link
 * io.agentscope.harness.agent.HarnessAgent} runs against a per-{@code (userId, agentId)} live
 * Docker sandbox owned by {@link UserSandboxRegistry}: the {@link
 * io.agentscope.dataagent.runtime.gateway.HarnessGateway} attaches that sandbox to the
 * {@link io.agentscope.core.agent.RuntimeContext} as a {@link
 * io.agentscope.harness.agent.sandbox.SandboxContext#getExternalSandbox() external sandbox} per
 * call, so the harness takes its Priority-1 acquire path and the agent reads/writes through the
 * exact same container the browser workspace controllers use.
 *
 * <p>Multi-replica deployments must front the app with sticky load-balancing by {@code userId} —
 * the registry is in-memory only, and two pods would otherwise spin up independent containers
 * for the same user.
 *
 * <h2>Model wiring (priority order)</h2>
 *
 * <ol>
 *   <li>If a {@link Model} Spring Bean is already present (provided by another
 *       {@code @Configuration}), it is used as-is.
 *   <li>Otherwise, if {@code dataagent.openai.api-key} is set, an {@link OpenAIChatModel} is
 *       created automatically — point {@code dataagent.openai.base-url} at any
 *       OpenAI-compatible endpoint (DeepSeek, vLLM, one-api, DashScope compatible-mode, ...).
 *   <li>Otherwise, if {@code dataagent.dashscope.api-key} is set, a {@link DashScopeChatModel} is
 *       created automatically.
 *   <li>If none of the above is available, the app starts without a model (agent calls will fail
 *       until one is configured).
 * </ol>
 *
 * <p>Note: model wiring uses <em>method-parameter</em> injection in {@code @Bean} methods (not
 * field-level {@code @Autowired}) to avoid a circular-dependency with the {@code Model} bean
 * defined in this same class.
 *
 * <h2>Agent config</h2>
 *
 * <p>If {@code ~/.agentscope/dataagent/agentscope.json} does not exist, a minimal default agent
 * config is auto-generated so the app starts without manual setup.
 */
@Configuration
public class DataAgentConfig {

    private static final Logger log = LoggerFactory.getLogger(DataAgentConfig.class);

    /**
     * 内置系统提示词默认值。当 {@code dataagent.agent.sys-prompt} 未配置时使用。
     *
     * <p>提示词三层分工（ADR 0007）：
     * <ul>
     *   <li>常驻人格/流程 → AGENTS.md（本常量）</li>
     *   <li>常驻动作点硬门 → {@code @Tool} 描述</li>
     *   <li>按需操作手册 → SKILL.md</li>
     * </ul>
     */
    static final String DEFAULT_AGENT_SYS_PROMPT =
            """
            # 通用数据分析助手
            使用同版本官方 Wren usage 技能、MCP 工具和知识完成问数，不建立另一套 SQL 或指标路由。
            运行上下文列出受限事实表时，先遵守其中的日期约束；进入实际分析时加载官方 usage，直接使用当前知识库提供的官方工具 schema，
            官方工具的 arguments 原样遵循官方 schema。get_instructions、上下文、知识、
            memory、SQL 与 Cube 的使用方式以官方指导及实际返回为准。
            查询只能使用已授权已发布工程；不得猜测物理表、切换连接或访问其他租户。
            业务规则、知识与相似问答均通过 官方工具调用 get_instructions、list_knowledge、recall_queries；不调用平台自定义知识检索。
            日期澄清完成或问题不需要日期时，每个进入实际分析的新问题先调用一次官方 recall_queries，使用当前问题作为 question，limit 使用官方默认的小数量；它是相似业务问答的唯一召回入口。
            不要遍历工程文件或调用平台自定义检索；业务规则、知识目录和相似问答只通过官方 get_instructions、list_knowledge、recall_queries 获取。
            answer_question 是平台可选的答案来源标注，不会传入 Wren：
            仅对准备实际用于最终答案的业务查询填写对应问题，诊断查询不要填写。
            一条 SQL 只回答子问题时标注该子问题，不让局部 SQL 冒充完整原问题。
            只有用户点赞后才保存成功的业务样例；执行成功不代表业务正确，不自动修改语义资产。
            根据用户需要使用 run_python 或 render_chart 分析 Wren 返回的数据文件。
            不编造结果，真实说明歧义、失败和截断；已有结果足够时给出完整最终答案。
            保留证据中的空值与空结果，不擅自补零；不补造未声明的单位或时间范围。查询成功但结果为空时如实回答无数据，不改日期或追加探查寻找有数据的时间。
            只呈现最终结论、必要口径和可核实依据，不呈现内部草稿推理。
            用户未要求时不主动导出文件。回答及图表使用简体中文。
            语言硬约束：所有面向用户的思考摘要、工具调用前说明、工具调用后说明和最终回答必须使用简体中文；不要输出英文解释。SQL、Python、官方工具原始字段和错误原文可以保留原格式。
            Top N 或单条结果优先使用官方 query_cube 的 order_by 与 limit；没有合适 Cube 时使用 run_sql 的标准 SQL（可按官方语法使用 LIMIT/OFFSET）。不要把 View（例如 valid_orders）传给 describe_model；View 用 get_mdl/describe_schema，Cube 用 describe_cube。
            """;

    /**
     * Platform layer over the official Wren skills, sent on every modeling turn. It keeps every
     * rule that overrides an official default (Cube/View, Q&A pairs, Auto-pilot, user-written
     * descriptions, rule sources); tool-enforced limits are one line each, and turn-specific
     * rules come from {@code ModelingTurnRules} by the page-declared turn purpose.
     */
    public static final String MODELING_SCRIPT =
            """
            # 通用 Wren 工程助手

            你是使用官方 Wren 工具完成当前知识库建模任务的通用 Agent。流程、模式选择与文件结构以官方技能正文为准，不另建平台建模流程或自行复刻官方业务规则。
            先用 load_skill_through_path 加载官方 enrich-context（需要时 generate-mdl）的 SKILL.md 与所需 references；同一会话中每个官方 skill 只加载一次，后续轮次直接使用已加载内容。
            不再通过 wren_cli skills list/get 重复获取同一技能，不为了解命令语法加载技能全文或反复执行 --help；wren_cli 命令和参数以工具描述为准。

            # 平台对官方流程的调整（每轮有效）

            - 默认选择官方 Auto-pilot 模式：模型描述、列描述和基础关系属于技术性变更，直接写入草稿并自动校验，不逐项确认；不要在对话中用文字列出方案等待回复后再写，修改由用户在发布前统一审阅。
            - Cube/View 不在基础建模范围：不读取、不写入 cubes/ 与 views/ 下的任何文件，不调用 cube 命令，不读取 Cube 提案参考；官方技能中创建或检查 Cube/View 的步骤一律跳过，发现的需求只在最终说明中记为提案。
            - 问答对不由建模生成：不调用 memory store，不写 knowledge/sql/；官方覆盖检查中"knowledge/sql 至少有几条问答对"一项跳过，也不为此自拟样例问题。问答只来自用户提交的问题，写入 knowledge/questions/<ID>.yml，由用户在试问面板确认，页面发布后可被问数召回。仅重建本地索引时使用 ["memory","index","--no-seed"]。
            - 列 properties 中带 description_source: user 的描述由建模人员在字段整理时填写，必须原样保留，不得改写、删减或用 edit_file 覆盖；模型中没有的字段是建模人员排除的字段，不要重新加入，也不要在规则或 SQL 中引用。
            - knowledge/rules/ 中每条规则只能来自业务文档原句、用户对话或用户的确定，所注依据必须能在该来源中找到。采样和表结构能确定的列含义直接写入，不要向用户确认；文档没写、但不同理解会改变统计结果的口径（例如"访问次数"按记录行数还是按 visit_count 求和）必须用 request_user_confirmation 提问，不得自行选定后标注为文档依据；其余推断写入 knowledge/caveats/。
            - 需要用户决定的事项只能用 request_user_confirmation 提出（第一个选项为推荐），不在回复正文里提问或列待办；提交后不等待，继续完成其余工作。
            - 主键、唯一性及关系基数不能由1000行采样证明；没有约束、全表验证或用户确认时保留未知。关系的 join_type 必须与两侧粒度一致：被连接侧的连接键未确认唯一，或已知同一键可能对应多行时，不得声明 MANY_TO_ONE 或 ONE_TO_ONE，应使用 MANY_TO_MANY 或暂不写入该关系并说明原因；关系描述与基数不得矛盾。
            - 候选关系按字段命名判断；连接键类型不同或采样值格式不一致（采样可能含脏数据）都不影响关联，不得据此否定、改写或跳过候选关系。
            - 表角色只提出 FACT/DIMENSION/UNKNOWN 与 analysis_date_columns 建议，用户明确确认后才写 table_role_confirmed: true。
            - 平台查询窗口是动态运行策略：具体天数和 maxWindowDays 禁止写进模型/列描述、MDL properties 或业务规则，只写"使用明确日期条件的小窗口查询"等通用建议；保留真正的业务时间口径（例如滚动30天活跃）。
            - 不把字段样例当作统计结果，不编造业务定义或确认状态；技术执行成功不等于业务口径正确。
            - SQL 使用 MySQL 8.0 语法（外部数据源为 MySQL 8.0 及以上或 Doris）；日期范围按当前日期换算成日期常量，用 >=、< 或 BETWEEN 表达，不用 CURDATE()、NOW()、DATE_SUB、INTERVAL 等函数。

            # 文件与校验

            文件路径相对 /project，只通过 read_file/list_files/glob_files/grep_files/write_file/edit_file 操作当前工程；运行上下文已列出工程文件，需要的文件在同一轮一次性并行读取，未变化且已读取的文件不重复读取。
            已存在文件的局部修改用 edit_file，整体替换可直接用 write_file；同一文件多处修改优先合并为一次 edit_file，确需多次时各次 old_string 不得重叠。一个模型文件要改写一半以上列描述时，用一次 write_file 写出整个 metadata.yml；互不依赖的多个文件在同一轮一次性发起，不重复写入未变化文件。
            开始修改前不执行校验；一批相关修改全部完成后只执行一次 context validate --strict，仅在失败或再次修改后重验。不要单独执行 context build：草稿未编译或已修改时，query、dry-plan、dry-run 或 memory describe 前由平台自动先执行 context build，发布时也会编译。上线由用户在页面发布。

            # 工具硬性边界

            以下由工具直接执行，违反会被拒绝；被拒绝后停止该类尝试，不改写成等价查询重试，不能把未执行描述为成功：
            建模不查询外部数据源表的数据，试问 SQL 只用 dry-plan 检查、不执行 query（探查边界见运行上下文"表来源与探查边界"）；查询必须只读、带明确日期条件并通过逐表时间门禁；单轮查询数量有上限；禁止 CROSS JOIN 和 FROM 后逗号并列多表的笛卡尔积写法；不切换租户、工程、连接或平台管理文件，不使用宿主 shell。
            收到 SQL_TIME_WINDOW_REJECTED：用户指定的业务范围被拒绝时展示原因并请用户确认范围；自拟样例被拒绝时说明尚未验证，不要求用户替样例选日期。检查服务异常时说明检查未完成、查询未执行，不归咎于用户日期或数据库。

            # 语言

            所有面向用户的文字一律使用简体中文，包括工具调用前后的一句话说明，不要输出任何英文句子。读者是业务人员，不是开发者。

            # 回复写法

            过程说明：每次最多一句、不超过 30 字，只说要做什么（如"读取三张表的字段与样例"），不写文件路径、配置键名、命令，不复述修改细节，不描述自己的纠错过程。
            不提及平台内部机制：写入被拦截、技能或目录编号、记忆存储、问答对生成、检查命令名称等都不要出现在回复中。
            最终回复只用业务语言，不列文件路径、YAML 键名、命令和校验输出；逐项修改与校验结果由页面展示，只在失败时说明原因。篇幅按运行上下文"本轮任务要求"。
            """;

    /** Query-stage rules that prevent redundant official-tool calls and oversized answers. */
    public static final String QUERY_EFFICIENCY_RULES =
            """
            # 日期理解

            是否因缺少日期而先询问，只由运行上下文的"受限事实表日期约束"决定：上下文列出受限事实表时按其执行；未列出时不因缺少日期追问。
            只查询回答当前问题所必需的信息。用户已提供日期也不代表可以额外探查时间覆盖、数据分布或寻找有数据的区间；禁止把这类探查单独执行或附加在业务SQL中。日期列的MIN/MAX、日期排序取首尾记录等操作，仅在用户问题本身明确要求对应日期信息时，才在其指定范围和门禁内使用。
            用户已写明月日（如“10月7日”）或相对日期（如“最近30天”）即视为已提供日期：未写年份按当前年份理解，不为年份或日期再向用户确认，在答案中说明所用日期。指标口径（如“访问量”）先按已发布的业务规则和召回示例理解，规则未定义且存在实质歧义时才询问。
            用户补充日期后通过已有对话历史继续原问题；相对日期由你结合运行时当前时间理解，不需要探查数据库最新日期来解释“最近30天”。表结构查询、业务规则解释及不受时间限制的表查询不强制询问日期。

            # 问数执行边界

            简单问数在已有上下文已明确模型、字段和业务口径时，完成召回后直接生成SQL，不重复describe_model；字段或含义不明确时仍按需读取，不猜测字段。
            日期澄清完成或问题不需要日期后，每个进入实际分析的新问题最多调用一次 recall_queries；召回为空时继续使用已发布 MDL，不要重复召回或改写同一问题反复尝试。
            SQL 使用 MySQL 8.0 语法（外部数据源只接入 MySQL 8.0 及以上或兼容它的 Doris）。日期范围先按运行上下文的当前日期换算成具体日期，再写成时间列与日期常量的比较：起始用 >=、结束用 < 次日，或 BETWEEN；不要用 CURDATE()、NOW()、DATE_SUB、DATE_ADD、INTERVAL 等函数表达范围，平台按常量直接校验时间范围。例如当前日期 2026-10-25 时，"最近30天"（含今天）写作 log_time >= '2026-09-26' AND log_time < '2026-10-26'；"上个月"写作 log_time >= '2026-09-01' AND log_time < '2026-10-01'。
            用户已写明月日但未写年份时，按当前年份理解，例如“10月6日”按今年10月6日处理，不为年份向用户提问，在答案中说明。生成SQL后，共用时间门禁只检查当前知识库已登记事实表各自的时间字段和最大窗口，未登记表跳过。运行上下文中的数据库策略优先于历史MDL/描述中固化的平台窗口；修改限制应到知识库的事实表时间配置，不能要求修改MDL或重新发布。
            主动澄清是你的预判，不得伪称已经调用工具门禁或伪造平台allowed/tables结果。需要修改问题范围时请用户选择，不擅自拆分原问题反复查询，也不向用户提出拆分成多次查询再汇总的方案。
            对完全相同的工具参数和 SQL 不要重复调用。已经得到足以回答问题的结果后立即结束，不要为了“确认”再次执行同一查询。
            优先一次查询完成分组、排序和 Top N；普通语法错误最多修正重试一次。遇到 SQL_TIME_WINDOW_REJECTED 必须停止本轮所有SQL尝试。用户请求的范围缺失或超限时展示真实逐表明细，请用户确认范围或调整配置，不自行缩小、拆分或改写原问题，不切换Cube/View绕过。自增可选探查被拒绝时放弃探查，按已有证据回答，不足时说明限制，不要求用户为探查修改原问题。检查服务异常时说明检查未完成、查询未执行，不描述为数据库拒绝或用户日期错误。
            收到SQL成功执行但结果为空时正常结束并如实说明，不为确认同一范围追加统计或重复分组，不改日期找数据。空结果不等于任一指标为0，不补造计数、金额或其它统计值；只有工具实际返回的数值才能作为统计结论。
            召回SQL只是示例：执行前对照当前问题和已确认业务规则，保留必要状态、软删、退款、内部账号等过滤和关联；缺少已确认过滤时补齐，不新增未经确认的业务要求。
            run_sql/query_cube 返回的结果可能按 Wren 的有效 limit 截断；以返回的 row_count/truncated 为准，向用户说明范围，不要把上下文裁剪提示误报为数据库失败。需要完整明细时 limit 不得小于预期行数；只需结论时直接用聚合 SQL 返回汇总值。
            按某个主体排名或计数（例如按负责人、项目、部门）时，GROUP BY 只放该主体；工作区、部门等附加属性用聚合函数（如 GROUP_CONCAT、MAX）带出或另行查询，不得加入分组导致同一主体被拆成多行。
            表格列名和图表标题必须与 SQL 字段的实际含义一致；回答中描述的执行过程必须与实际工具调用一致，执行了查询就不能声称未重新统计。
            答案中的计数、合计、分组分布和排名必须来自工具实际返回的数值；不得对多行结果心算或手工汇总，需要新的统计时补一条聚合 SQL（例如按工作区、负责人分组计数），沙箱不可用时同样如此。
            """;

    @Value("${dataagent.openai.api-key:}")
    private String openaiApiKey;

    @Value("${dataagent.openai.base-url:}")
    private String openaiBaseUrl;

    @Value("${dataagent.openai.model-name:gpt-4o}")
    private String openaiModelName;

    @Value("${dataagent.openai.stream:true}")
    private boolean openaiStream;

    @Value("${dataagent.openai.http-version:HTTP_1_1}")
    private io.agentscope.core.model.transport.HttpVersion openaiHttpVersion =
            io.agentscope.core.model.transport.HttpVersion.HTTP_1_1;

    @Value("${dataagent.dashscope.api-key:}")
    private String dashscopeApiKey;

    @Value("${dataagent.dashscope.model-name:qwen-max}")
    private String dashscopeModelName;

    @Value("${dataagent.dashscope.stream:true}")
    private boolean dashscopeStream;

    @Value(
            "${DATAAGENT_AGENT_SYS_PROMPT:${dataagent.agent.sys-prompt:"
                    + DEFAULT_AGENT_SYS_PROMPT
                    + "}}")
    private String agentSysPrompt;

    @Value("${dataagent.agent.name:data-agent}")
    private String agentName;

    @Value("${dataagent.agent.subagents-enabled:false}")
    private boolean subagentsEnabled;

    @Value("${dataagent.workspace:}")
    private String workspaceDir;

    /**
     * Docker image for the agent-runtime sandbox filesystem spec. Mirrors {@code
     * DataAgentWorkspaceConfig#sandboxImage} so the spec-default acquire path and the
     * {@code UserSandboxRegistry} external-sandbox path (Priority-1) start the same image.
     */
    @Value("${dataagent.sandbox.image:agentscope/dataagent-sandbox:latest}")
    private String sandboxImage;

    // -----------------------------------------------------------------
    //  Model bean — OpenAI-compatible first, DashScope fallback. Only
    //  created when the matching api-key is set AND no other Model bean is
    //  already present in the context. Both are skipped when the property is
    //  blank so Optional<Model> injection sites receive Optional.empty().
    // -----------------------------------------------------------------

    /**
     * Creates an {@link OpenAIChatModel} bean when {@code dataagent.openai.api-key} is configured
     * and no other {@link Model} bean is present. Works with any OpenAI-compatible endpoint —
     * set {@code dataagent.openai.base-url} to point at compatible gateways (DeepSeek, vLLM,
     * one-api, DashScope compatible-mode, ...); a trailing {@code /v1} in the base URL is
     * handled by the client. Blank base URL falls back to the official OpenAI endpoint.
     */
    @Bean
    @ConditionalOnMissingBean(Model.class)
    @ConditionalOnExpression("'${dataagent.openai.api-key:}' != ''")
    public Model openaiModel() {
        String baseUrl =
                openaiBaseUrl != null && !openaiBaseUrl.isBlank() ? openaiBaseUrl.trim() : null;
        log.info(
                "Building OpenAIChatModel: model={}, baseUrl={}",
                openaiModelName,
                baseUrl != null ? baseUrl : "https://api.openai.com (default)");
        var transport =
                io.agentscope.core.model.transport.JdkHttpTransport.builder()
                        .config(
                                io.agentscope.core.model.transport.HttpTransportConfig.builder()
                                        .httpVersion(openaiHttpVersion)
                                        .build())
                        .build();
        io.agentscope.core.model.transport.HttpTransportFactory.register(transport);
        log.info("Model HTTP protocol: {}", openaiHttpVersion);
        return OpenAIChatModel.builder()
                .httpTransport(transport)
                .apiKey(openaiApiKey)
                .baseUrl(baseUrl)
                .modelName(openaiModelName)
                .stream(openaiStream)
                .generateOptions(
                        GenerateOptions.builder()
                                .additionalBodyParam(
                                        "thinking", java.util.Map.of("type", "disabled"))
                                .build())
                .build();
    }

    /**
     * Fallback when no OpenAI key is configured: creates a {@link DashScopeChatModel} bean when
     * {@code dataagent.dashscope.api-key} is set and no other {@link Model} bean is present
     * (including {@link #openaiModel()}). Skipped entirely when the property is blank so that
     * {@code Optional<Model>} injection sites receive {@code Optional.empty()} instead of a
     * null-valued bean.
     */
    @Bean
    @ConditionalOnMissingBean(Model.class)
    @ConditionalOnExpression(
            "'${dataagent.openai.api-key:}' == '' && '${dataagent.dashscope.api-key:}' != ''")
    public Model dashscopeModel() {
        log.info("Building DashScopeChatModel: model={}", dashscopeModelName);
        return DashScopeChatModel.builder()
                .apiKey(dashscopeApiKey)
                .modelName(dashscopeModelName)
                .stream(dashscopeStream)
                .enableThinking(false)
                .build();
    }

    // -----------------------------------------------------------------
    //  Jackson ObjectMapper — used by MarketContributionService and other
    //  web-layer components that need JSON serialization.
    // -----------------------------------------------------------------

    /**
     * Provides a shared Jackson {@link ObjectMapper} for components that rely on constructor
     * injection. Registers any Jackson modules found on the classpath (e.g. Java Time).
     */
    @Bean
    @ConditionalOnMissingBean(ObjectMapper.class)
    public ObjectMapper objectMapper() {
        return new ObjectMapper().findAndRegisterModules();
    }

    // -----------------------------------------------------------------
    //  Core bootstrap — model injected as method parameter (no field
    //  @Autowired) to avoid circular dependency with dashscopeModel() above.
    // -----------------------------------------------------------------

    /**
     * Assembles the {@link DataAgentBootstrap}, loading agent config from {@code agentscope.json}
     * and starting the {@link ChatUiChannel} for per-user isolated sessions.
     *
     * <p>Every agent built by the bootstrap declares a {@link DockerFilesystemSpec} (per-user
     * isolation scope) sharing the same {@link SandboxClient} used by {@link UserSandboxRegistry}
     * — except sandboxless agents (currently the metadata-only modeling assistant), which are
     * built on the harness-default local filesystem over a dedicated empty workspace root and are
     * skipped by the gateway when borrowing a container, so they never depend on Docker.
     * The actual container per turn is supplied by the gateway via
     * {@link io.agentscope.harness.agent.sandbox.SandboxContext#getExternalSandbox()} — see
     * {@link io.agentscope.dataagent.runtime.gateway.HarnessGateway#setUserSandboxRegistry}.
     *
     * @param modelOpt the {@link Model} to use, or empty if none is configured
     * @param toolEventBus the shared tool-event bus for real-time SSE streaming of tool calls
     * @param sandboxClient client used by every {@link DockerFilesystemSpec} (same instance the
     *     {@link UserSandboxRegistry} uses, so spec-defaults and registry-managed sandboxes share
     *     one Docker store)
     * @param userSandboxRegistry registry attached to the gateway after bootstrap so per-call
     *     turns receive the right per-user sandbox
     */
    @Bean
    public DataAgentBootstrap builderBootstrap(
            Optional<Model> modelOpt,
            ToolEventBus toolEventBus,
            SandboxClient<DockerSandboxClientOptions> sandboxClient,
            UserSandboxRegistry userSandboxRegistry,
            Optional<AgentStateStore> sessionOpt,
            DataSourceRegistry dataSourceRegistry,
            Optional<DatasetContextProvider> contextProviderOpt,
            MdlCatalog mdlCatalog,
            DatasetGroupRepository datasetGroupRepository,
            io.agentscope.dataagent.web.session.ConversationScopeRegistry conversationScopes,
            SessionRegistryRepository sessionRegistryRepository,
            org.springframework.beans.factory.ObjectProvider<ModelingToolkitRegistrar>
                    modelingToolkitProvider,
            WrenSkillsLocator wrenSkillsLocator,
            io.agentscope.dataagent.dataset.WrenProjectAccess wrenProjectAccess,
            io.agentscope.dataagent.dataset.WrenQueryAccess wrenQueryAccess,
            io.agentscope.dataagent.runtime.wren.WrenQueryGateway wrenQueryGateway,
            io.agentscope.dataagent.dataset.WrenPublishedProject wrenPublishedProject,
            io.agentscope.dataagent.dataset.FactQueryWindowPolicy windowPolicy,
            io.agentscope.dataagent.web.persistence.jpa.DatasetRepository datasetRepository)
            throws IOException {
        Path cwd = resolveCwd();
        ensureAgentscopeConfig();

        DataAgentBootstrap.Builder builder =
                DataAgentBootstrap.builder()
                        .cwd(cwd)
                        .sessionStoreRepository(sessionRegistryRepository);

        if (modelOpt.isPresent()) {
            builder.model(modelOpt.get());
        } else {
            log.warn(
                    "No model configured. Set dataagent.openai.api-key (env"
                            + " DATAAGENT_OPENAI_API_KEY or OPENAI_API_KEY) or"
                            + " dataagent.dashscope.api-key in application.yml, or provide a Model"
                            + " bean. Agent calls will fail until a model is available.");
        }

        // AgentStateStore backend selection is independent of the workspace filesystem now that
        // workspaces
        // are sandbox-backed: each user's sandbox is reached through the in-memory
        // UserSandboxRegistry under sticky load-balancing. Operators should still provide a
        // distributed AgentStateStore bean for production so conversation state survives pod
        // restarts.
        AgentStateStore stateStore = sessionOpt.orElseGet(InMemoryAgentStateStore::new);
        if (sessionOpt.isEmpty()) {
            log.warn(
                    "No distributed AgentStateStore bean configured ({}); using"
                            + " InMemoryAgentStateStore. For multi-replica deployments, provide"
                            + " a DistributedStore or a distributed AgentStateStore bean"
                            + " (e.g. from agentscope-extensions-redis).",
                    AgentStateStore.class.getName());
        }

        // The modeling assistant is a metadata-only agent (relations / cubes / MDL validation)
        // and must run without the per-user Docker sandbox: it is exempt from the sandbox
        // filesystem spec below and the gateway skips borrowing a container for it, so
        // conversational modeling works even when Docker is unavailable. Its dedicated, empty
        // local workspace keeps the harness on the default local filesystem without pulling in
        // the main agent's workspace content (AGENTS.md etc.).
        Set<String> sandboxlessAgents = Set.of(ModelingToolkitRegistrar.MODELING_AGENT_ID);
        Path modelingWorkspace =
                DataAgentBootstrap.DEFAULT_WORKSPACE_ROOT.resolveSibling(
                        "workspace-modeling-agent");
        Files.createDirectories(modelingWorkspace);

        builder.configureAllAgents(
                (agentId, b) -> {
                    if ("data-agent".equals(agentId)) {
                        configureQueryPrompt(b, QUERY_EFFICIENCY_RULES + "\n" + agentSysPrompt);
                        mountWrenSkills(b, wrenSkillsLocator, QUERY_SKILLS);
                    }
                    if (ModelingToolkitRegistrar.MODELING_AGENT_ID.equals(agentId)) {
                        // Register before tool notifications so a paused call never looks executed.
                        b.middleware(
                                new ModelingHitlMiddleware(
                                        (ctx, tool) -> {
                                            var registrar =
                                                    modelingToolkitProvider.getIfAvailable();
                                            if (registrar == null) return "error: 建模工具尚未就绪，未执行写入";
                                            return registrar.preflight(ctx, tool);
                                        }));
                    }
                    b.middleware(new ToolNotificationMiddleware(toolEventBus));
                    b.middleware(
                            new io.agentscope.dataagent.web.middleware.ExecutionTimingMiddleware());
                    // The modeling assistant's transcript goes to its own file
                    // (logs/LLM-modeling.log) so semantic-modeling sessions stay separate from
                    // data-agent chat traffic; every other agent keeps the shared logs/LLM.log.
                    b.middleware(
                            ModelingToolkitRegistrar.MODELING_AGENT_ID.equals(agentId)
                                    ? new DebugLoggingMiddleware("LLM_MODELING_DEBUG")
                                    : new DebugLoggingMiddleware());
                    b.stateStore(stateStore);
                    if (sandboxlessAgents.contains(agentId)) {
                        // specs/029 (ADR 0039): the modeling agent runs without a sandbox, so
                        // its long-term memory used to be scoped per user only — one shared
                        // MEMORY.md across every knowledge base, letting one group's modeling
                        // context leak into another group's conversation through the
                        // <memory_context> injection (diagnosed 2026-10-04). Route the local
                        // filesystem namespace through the conversation's DatasetScope so every
                        // single-KB modeling session gets its own "kb-<groupId>" memory
                        // namespace; multi-KB / scoped-less turns fall back to the user level.
                        b.filesystem(
                                new LocalFilesystemSpec()
                                        .namespaceFactory(GroupScopedMemoryNamespace.INSTANCE));
                    } else {
                        DockerFilesystemSpec spec =
                                new DockerFilesystemSpec().client(sandboxClient);
                        if (sandboxImage != null && !sandboxImage.isBlank()) {
                            spec.image(sandboxImage.trim());
                        }
                        // isolationScope() returns the supertype; chain it last.
                        b.filesystem(spec.isolationScope(IsolationScope.USER));
                        if ("data-agent".equals(agentId)) {
                            // Coordination records and transcript indexes are platform metadata,
                            // not Python inputs. Avoid a Docker exec for every inbox/index check.
                            // Session files remain namespaced by user; bus keys use server-owned
                            // session ids and the shared wakeup queue must stay globally visible.
                            Path controlRoot = cwd.resolve(".runtime").resolve(agentId);
                            b.filesystemRoute(
                                    "agents/",
                                    new UserControlFilesystem(controlRoot.resolve("agents")));
                            b.filesystemRoute(
                                    ".agentscope/bus/",
                                    new io.agentscope.harness.agent.filesystem.local
                                            .LocalFilesystem(controlRoot.resolve("bus"), true, 10));
                        }
                    }

                    // DataAgent does not need filesystem tools (read_file, write_file,
                    // edit_file, list_files, grep_files, glob_files) or shell execute.
                    // run_python has its own SandboxBackedFilesystem and is unaffected.
                    b.disableFilesystemTools();
                    b.disableShellTool();

                    if (!subagentsEnabled) {
                        b.disableSubagents();
                    }

                    // Exclude run_python from eviction: even with path-based
                    // artifact references the result is small, but as a safety
                    // net we prevent eviction unconditionally so the full tool
                    // result (stdout + artifact metadata + file paths) always
                    // survives in session history for the frontend to parse.
                    Set<String> excludedTools =
                            new HashSet<>(ToolResultEvictionConfig.DEFAULT_EXCLUDED_TOOLS);
                    excludedTools.add("run_python");
                    b.toolResultEviction(
                            ToolResultEvictionConfig.builder()
                                    .excludedToolNames(excludedTools)
                                    .build());

                    // Workspace tools.json declares MCP servers (e.g. the WrenAI semantic
                    // layer) for the built-in agent. The agent filesystem is sandbox-backed and
                    // unreachable while HarnessAgent is being constructed (no sandbox exists at
                    // bootstrap time), so the loader inside HarnessAgent skips the file
                    // silently. Load it here from the local workspace root — a WorkspaceManager
                    // without a filesystem layer reads local disk — and pass it as an explicit
                    // override. Assumes the built-in agent uses the default workspace root,
                    // which is how agentscope.json auto-generation configures it.
                    ToolsConfig toolsConfig =
                            ToolsConfigLoader.load(
                                            new WorkspaceManager(
                                                    DataAgentBootstrap.DEFAULT_WORKSPACE_ROOT))
                                    .orElse(null);
                    // Built-in Wren agents must not inherit a global, unscoped MCP server.
                    ToolsConfig effectiveTools = scopedWrenToolsConfig(agentId, toolsConfig);
                    if (effectiveTools != null) b.toolsConfig(effectiveTools);

                    // Wren-only dynamic context injection: [DATA_SOURCES_OVERVIEW] and
                    // [KNOWLEDGE_BASE_OVERVIEW] are rebuilt from the per-call DatasetScope
                    // and appended to the system prompt on every turn. The MDL catalog exposes
                    // only a compact directory of published logical models and cubes; field-level
                    // Details are retrieved on demand through the official describe_model tool.
                    if (ModelingToolkitRegistrar.MODELING_AGENT_ID.equals(agentId)) {
                        b.middleware(
                                new io.agentscope.dataagent.runtime.session
                                        .WrenProjectContextMiddleware(
                                        wrenProjectAccess, windowPolicy, datasetRepository));
                    } else if ("data-agent".equals(agentId)) {
                        b.middleware(
                                new WrenScopedToolkitMiddleware(
                                        wrenQueryAccess,
                                        wrenQueryGateway,
                                        wrenPublishedProject,
                                        mdlCatalog,
                                        windowPolicy));
                        b.middleware(
                                new io.agentscope.dataagent.runtime.session
                                        .WrenQueryContextMiddleware(
                                        wrenQueryAccess,
                                        windowPolicy,
                                        wrenPublishedProject::publishedRoot));
                    } else {
                        b.middleware(
                                new DataDynamicContextMiddleware(
                                        dataSourceRegistry,
                                        contextProviderOpt.orElse(null),
                                        mdlCatalog,
                                        datasetGroupRepository));
                    }
                });

        // Conversational modeling assistant (specs/013 M1, ADR 0024 D2): registered
        // programmatically so it becomes a global agent (AgentCatalogService.isGlobal) without
        // touching existing agentscope.json files. Tools are attached later by
        // ModelingToolkitRegistrar (runs after this bootstrap bean builds every agent).
        builder.configureAgent(
                ModelingToolkitRegistrar.MODELING_AGENT_ID,
                b -> {
                    b.name("建模助手")
                            .description("使用官方 Wren 技能、CLI 和通用文件工具完成当前知识库工程任务")
                            .sysPrompt(MODELING_SCRIPT)
                            .disableWorkspaceContext()
                            .maxIters(30)
                            // A browser can leave an interrupted modeling turn with orphaned
                            // tool calls in the persisted session. Resume with synthetic error
                            // results so the user can continue the draft conversation.
                            .enablePendingToolRecovery(true)
                            // Sandbox-free posture (see the sandboxlessAgents note above): the
                            // dedicated empty workspace above keeps this agent off the main
                            // agent's shared workspace content.
                            .workspace(modelingWorkspace);
                    mountWrenSkills(b, wrenSkillsLocator, MODELING_SKILLS);
                });

        DataAgentBootstrap bootstrap = builder.build();

        // Hand the gateway a reference to the per-user sandbox registry so every run(...) turn
        // injects the user's live container as SandboxContext.externalSandbox (Priority-1 acquire
        // in SandboxManager). The gateway is constructed inside DataAgentBootstrap, which lives
        // below the web layer and cannot depend on UserSandboxRegistry directly.
        bootstrap.gateway().setUserSandboxRegistry(userSandboxRegistry);
        // Metadata-only agents never borrow a container (see setSandboxlessAgents); every other
        // agent keeps the per-user Docker sandbox injection.
        bootstrap.gateway().setSandboxlessAgents(sandboxlessAgents);

        // Build the chatui channel using the file-config's bindings & dmScope (if any),
        // so admin-edited bindings in agentscope.json are honored. Falls back to
        // PER_ACCOUNT_CHANNEL_PEER so each ChatGPT-style conversation gets its own isolated
        // session (the conversationId flows through as MsgContext.group → |g: segment).
        ChannelConfigEntry ce =
                bootstrap.loadedConfig().getChannels() != null
                        ? bootstrap.loadedConfig().getChannels().get(ChatUiChannel.CHANNEL_ID)
                        : null;
        ChannelConfig configuredChatuiCfg =
                ce != null
                        ? ce.toChannelConfig(ChatUiChannel.CHANNEL_ID)
                        : ChannelConfig.builder(ChatUiChannel.CHANNEL_ID)
                                .dmScope(DmScope.PER_ACCOUNT_CHANNEL_PEER)
                                .build();
        ChannelConfig chatuiCfg = conversationScopedChatUiConfig(configuredChatuiCfg);
        ChatUiChannel webChannel = ChatUiChannel.create(chatuiCfg);
        bootstrap.start(webChannel);
        bootstrap.gateway().setConversationScopes(conversationScopes);

        log.info(
                "DataAgentBootstrap initialized: cwd={}, chatui dmScope={}, bindings={}",
                cwd,
                chatuiCfg.dmScope(),
                chatuiCfg.bindings().size());
        return bootstrap;
    }

    static ToolsConfig scopedWrenToolsConfig(String agentId, ToolsConfig source) {
        if (!"data-agent".equals(agentId)
                && !ModelingToolkitRegistrar.MODELING_AGENT_ID.equals(agentId)) return source;
        ToolsConfig scoped = new ToolsConfig();
        if (source != null) {
            scoped.setAllow(source.getAllow());
            scoped.setDeny(source.getDeny());
        }
        scoped.setMcpServers(java.util.Map.of());
        return scoped;
    }

    /**
     * Mounts the official wren skills (ADR 0035, specs/022) onto the modeling agent via the
     * harness-native skill repository, so {@code load_skill_through_path} serves the same
     * SKILL.md files the CLI channel does — the modeling playbook has a single source of truth
     * inside the installed wrenai package. Fail-soft: when the package (or its skills_content)
     * is absent the agent starts without the mount and keeps the {@code wren_skills_get} CLI
     * channel. Each agent lists only the skills its task uses: every listed skill is rendered
     * into the system prompt of every model call.
     */
    /** Querying answers with the official usage skill only. */
    static final List<String> QUERY_SKILLS = List.of("usage");

    /** Modeling enriches a platform-created project; it never initializes one or needs usage. */
    static final List<String> MODELING_SKILLS = List.of("generate-mdl", "enrich-context");

    private static void mountWrenSkills(
            io.agentscope.harness.agent.HarnessAgent.Builder b,
            WrenSkillsLocator locator,
            List<String> skills) {
        var skillsRoot = locator.locate();
        if (skillsRoot.isEmpty()) {
            log.warn(
                    "Official wren skills directory not found (installed wrenai package missing or"
                        + " no SKILL.md inside); install requirements-wren.txt in the configured"
                        + " CLI environment. Modeling can use wren_cli skills get; querying can use"
                        + " the official MCP get_instructions tool.");
            return;
        }
        try {
            b.skillRepository(new FileSystemSkillRepository(skillsRoot.get()));
            b.skillFilter(SkillFilter.only(skills.toArray(String[]::new)));
            log.info(
                    "Mounted official wren skills from {} (allowlist: {})",
                    skillsRoot.get(),
                    skills);
        } catch (Exception e) {
            log.warn(
                    "Failed to mount official wren skills from {}: {}; restore the configured"
                            + " official CLI environment before acceptance",
                    skillsRoot.get(),
                    e.getMessage());
        }
    }

    /**
     * Registers the {@link LocalApprovalMarketplace} factory under the {@code "local"} type so
     * {@code UserMarketplaceRegistry} can hydrate per-user marketplaces backed by approved
     * contributions on disk.
     *
     * <p>The factory reads from {@code ${dataagent.shared-root}/agents/data-agent/skills} — the
     * per-agent slice for the built-in {@code data-agent}, which is the same directory the
     * per-(user, data-agent) sandbox projects in as its lower layer, so an approved skill is
     * immediately visible to every tenant of {@code data-agent} without extra wiring. Skills
     * approved for other agents live under their own {@code shared/agents/<agentId>/skills/}
     * slices and surface through those agents' own overlays; this local marketplace does not
     * cross-list them.
     */
    @Bean
    public DataAgentMarketplaceFactoryRegistration localMarketplaceFactory(
            DataAgentBootstrap bootstrap) {
        Path sharedSkills =
                bootstrap
                        .cwd()
                        .resolve("shared")
                        .resolve("agents")
                        .resolve("data-agent")
                        .resolve("skills");
        return new DataAgentMarketplaceFactoryRegistration(
                LocalApprovalMarketplace.TYPE,
                (userId, id, props, wsf) -> new LocalApprovalMarketplace(id, sharedSkills));
    }

    /**
     * Registers the {@link GitDataAgentMarketplace} factory under the {@code "git"} type. Each
     * per-user marketplace gets its own clone target under
     * {@code ${dataagent.workspace}/.cache/marketplaces/{userId}/{marketplaceId}} so distinct
     * users configuring the same upstream do not contend on a shared working copy.
     *
     * <p>Properties: {@code remoteUrl} (required), {@code branch} (optional).
     */
    @Bean
    public DataAgentMarketplaceFactoryRegistration gitMarketplaceFactory(
            DataAgentBootstrap bootstrap) {
        Path cacheRoot = bootstrap.cwd().resolve(".cache").resolve("marketplaces");
        return new DataAgentMarketplaceFactoryRegistration(
                GitDataAgentMarketplace.TYPE,
                (userId, id, props, wsf) -> {
                    String remoteUrl = stringProp(props, "remoteUrl");
                    if (remoteUrl == null || remoteUrl.isBlank()) {
                        throw new IllegalArgumentException(
                                "git marketplace '" + id + "' requires property 'remoteUrl'");
                    }
                    String branch = stringProp(props, "branch");
                    Path clone = cacheRoot.resolve(userId).resolve(id);
                    return new GitDataAgentMarketplace(id, remoteUrl, branch, clone);
                });
    }

    /**
     * Registers the {@link NacosDataAgentMarketplace} factory under the {@code "nacos"} type.
     *
     * <p>Properties: {@code serverAddr} (required), {@code namespaceId} (optional, defaults to
     * {@code "public"}), {@code username} / {@code password}, {@code accessKey} / {@code
     * secretKey}.
     */
    @Bean
    public DataAgentMarketplaceFactoryRegistration nacosMarketplaceFactory() {
        return new DataAgentMarketplaceFactoryRegistration(
                NacosDataAgentMarketplace.TYPE,
                (userId, id, props, wsf) -> {
                    String serverAddr = stringProp(props, "serverAddr");
                    if (serverAddr == null || serverAddr.isBlank()) {
                        throw new IllegalArgumentException(
                                "nacos marketplace '" + id + "' requires property 'serverAddr'");
                    }
                    return new NacosDataAgentMarketplace(
                            id,
                            serverAddr,
                            stringProp(props, "namespaceId"),
                            stringProp(props, "username"),
                            stringProp(props, "password"),
                            stringProp(props, "accessKey"),
                            stringProp(props, "secretKey"));
                });
    }

    private static String stringProp(java.util.Map<String, Object> props, String key) {
        if (props == null) return null;
        Object v = props.get(key);
        return v == null ? null : v.toString();
    }

    @Bean
    public io.agentscope.dataagent.web.identity.IdentityLinkStore identityLinkStore(
            IdentityLinkRepository repository) {
        return new io.agentscope.dataagent.web.identity.IdentityLinkStore(repository);
    }

    @Bean
    public ChatUiChannel chatUiChannel(DataAgentBootstrap bootstrap) {
        return (ChatUiChannel)
                bootstrap
                        .channelManager()
                        .getChannel(ChatUiChannel.CHANNEL_ID)
                        .orElseThrow(
                                () ->
                                        new IllegalStateException(
                                                "ChatUiChannel not registered in ChannelManager"));
    }

    // -----------------------------------------------------------------
    //  Internal helpers
    // -----------------------------------------------------------------

    static ChannelConfig conversationScopedChatUiConfig(ChannelConfig configured) {
        if (configured.dmScope() == DmScope.PER_ACCOUNT_CHANNEL_PEER) {
            return configured;
        }
        log.warn(
                "Ignoring chatui dmScope={} because browser conversation isolation requires {}",
                configured.dmScope(),
                DmScope.PER_ACCOUNT_CHANNEL_PEER);
        return ChannelConfig.builder(ChatUiChannel.CHANNEL_ID)
                .defaultAgentId(configured.defaultAgentId())
                .dmScope(DmScope.PER_ACCOUNT_CHANNEL_PEER)
                .bindings(configured.bindings())
                .build();
    }

    private Path resolveCwd() {
        if (workspaceDir != null && !workspaceDir.isBlank()) {
            return Paths.get(workspaceDir).toAbsolutePath().normalize();
        }
        return Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize();
    }

    /**
     * Auto-generates a minimal {@code ~/.agentscope/dataagent/agentscope.json} if it doesn't
     * exist, so the app can start without manual setup. The generated config defines a single
     * GLOBAL {@code data-agent} pre-wired with the {@code chatui} channel and lets the bootstrap
     * fall through to {@link DataAgentBootstrap#DEFAULT_WORKSPACE_ROOT} for the workspace
     * location.
     *
     * <p>The workspace root is the read-only shared seed (template content, default {@code
     * AGENTS.md} / {@code skills/} / {@code subagents/} / {@code knowledge/} shipped on disk).
     * {@link UserSandboxRegistry} projects it into every fresh container; user-writable files
     * live inside the container.
     */
    static void configureQueryPrompt(
            io.agentscope.harness.agent.HarnessAgent.Builder builder, String prompt) {
        // The query agent uses the packaged official Wren repository below. Do not also scan the
        // per-user sandbox workspace skills on every turn: that scan starts Docker before the
        // first Wren call and makes a failed orphan cleanup look like a slow LLM response.
        builder.sysPrompt(prompt).disableWorkspaceContext().disableDefaultWorkspaceSkills();
    }

    private void ensureAgentscopeConfig() throws IOException {
        Path configFile = DataAgentBootstrap.DEFAULT_CONFIG_PATH;
        Path workspaceRoot = DataAgentBootstrap.DEFAULT_WORKSPACE_ROOT;

        Files.createDirectories(configFile.getParent());
        Files.createDirectories(workspaceRoot);

        // Always ensure workspace scaffolding (AGENTS.md etc.) runs — safe because
        // scaffold() uses writeIfMissing internally. This fixes the case where
        // agentscope.json exists from a prior setup but AGENTS.md was never created.
        io.agentscope.dataagent.web.scaffold.WorkspaceScaffolder.scaffold(
                workspaceRoot, "Data Agent", agentSysPrompt);

        if (Files.exists(configFile)) {
            return;
        }

        String agentsJson =
                """
                {
                  "main": "data-agent",
                  "agents": {
                    "data-agent": {
                      "name": "Data Agent",
                      "description": "租户隔离的数据分析助手。连接内部 SQL 数据源，起草查询，校验结果并渲染图表。",
                      "maxIters": 20
                    }
                  },
                  "channels": {
                    "chatui": {
                      "defaultAgentId": "data-agent",
                      "dmScope": "PER_ACCOUNT_CHANNEL_PEER"
                    }
                  }
                }
                """;

        Files.writeString(configFile, agentsJson);
        log.info("Auto-generated DataAgent config at {}", configFile);
    }
}
