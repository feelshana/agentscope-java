# 项目长期背景

## BI免登补充（2026-10-17，ADR0081/spec069）

用户确认BI前端直接持共享密钥加密用户名，不接BI后端/IM SDK；可提取密钥并伪造管理员用户名的限制已明确接受，不能宣称可信SSO。AES-GCM ticket POST换默认固定1800秒JWT、刷新不续期、关页不撤销；唯一消费记录使用元数据库。未知用户自动建普通用户，旧userId/roles保留、管理员SQL维护。prod强制禁密码及Java演示种子并启用BI，SQL种子在prod配置关闭；开发保留密码。前端sessionStorage、options先行、同源iframe握手和新入口整页重载；DATAAGENT_BASE_PATH与代理同配。实现/本地验证完成，未提交部署，真实BI联调仍待做，见CURRENT_TASK和docs/integration/bi-login.md。

## 单机发布补充（2026-10-15，ADR0080）

用户已授权实施来源核对建议：当前Boot exec JAR和默认前端自动构建保留，额外生成tar.gz/conf/bin/lib；私有dev/prod配置不进入资源/JAR/包，目标环境通过外置配置加载，公共默认值与h2 profile保留。Linux启停脚本检查Wren依赖、进程身份和HTTP就绪，跨版本持久状态位于父目录data/。五份来源框架回归测试已补回。详见docs/single-host-release.md与ADR0080；本次覆盖了下方旧记录“打包保留当前、不采用来源发布布局”的选择。工作区实现未提交、用户服务未重载，验证见CURRENT_TASK。

## 采样与 Wren 资料复用（2026-10-16，ADR 0080/spec 068）

外部表关联每表最多1000行，内存提取样例，不执行全表计数或逐列DISTINCT；外部rowCount API为null，上传保留准确导入数。可空LONGTEXT sample_summary_json保存摘要，旧记录不自动补采样，MANAGE手动刷新、发布后进入问数。

平台派生raw/data-samples.md和knowledge/rules/data-sampling.md，官方skills不改。建模每次系统上下文构造含最多6000字符摘要与通用约束；问数仅通用约束，官方get_instructions读取已发布规则。样本不代表完整枚举/唯一性/最新日期，[enum]需业务依据，准确业务统计不能用样本替代。细节、迁移及本轮实测见CURRENT_TASK，源码完成不表示现有服务已加载。

## 当前文档事实源与来源核对（2026-10-15，ADR 0079）

当前根 pom.xml 是 Java17 standalone，application.yml 默认 MySQL 平台库，h2 profile 切文件 H2；Agent 配置为 ~/.agentscope/dataagent/agentscope.json。README/中文 README、ARCHITECTURE_zh.md、根 AGENTS.md、docs/agent-definition.md 已按实际代码对齐，根 AGENTS.md 通过项目 .gitignore 的单文件例外进入可交付范围。

来源自 10 月 9 日有 11 主线提交及 3 公共历史提交，平台功能选择性移植而非整个分支历史合并；打包和旧建模保留当前方案。逐提交、差异与来源 ADR/spec 映射见 docs/source-branch-integration-audit-20261015.md 和 docs/imported/multiAgent-Mdl-lyf/README.md。后者保留 11 ADR/8 spec，原编号可能与当前冲突。审计工具只提供祖先/文件证据，不能替代行为验收。

## 并发平台补充（2026-10-15，ADR 0078）

来源 a29cc754/6a75b6cf 的上传与问答容量已完成集成核对：单进程上传准入、磁盘暂存与回滚，ChatController 问数全局/每用户预算、有限等待和超时；建模及其他通道不经过该问答预算。Wren MCP 工具发现/查询共用公平有时限门锁和实例名额，沙箱支持空闲驱逐及进程组取消；不宣称所有 CLI 进程受同一预算。下文“容量未实现”是此前来源截至 32c6d292 的历史状态。

独立构建必须配套安装修改后的 harness/OpenAI 扩展，部署同步前端/JAR 和含 setsid 的沙箱镜像。当前配置/发布说明见 docs/platform-concurrency-and-release.md；来源 ADR/spec 因编号冲突保留在 docs/imported/multiAgent-Mdl-lyf 并由 ADR 0078/spec 067 解释适用范围。实测及尚未完成的 Docker/真实负载验收见 CURRENT_TASK，源码和构建通过不等于用户服务已重载。

## 当前共享权限补充（2026-10-15，ADR 0077）

已将用户确认的 data-agent-multiAgent-Mdl-lyf 平台增量适配到当前官方 Wren 链路。知识库 QUERY/使用、MANAGE/管理授权，创建者与当前全局管理员拥有管理权；普通问答每轮零/单/多/全选，建模单库。当前用户身份不替换为库创建者，历史/沙箱/附件/反馈仍私有；点赞 SQL 示例为库内召回资料，按库串行存储索引。下文“全部资源仅创建者可访问”的旧描述不适用于显式共享知识库。

已有取消、失败传播、删除重试和会话繁忙提示；没有实现全局/每用户问答容量预算及上传有界队列，不将这些机制当作容量承诺。源码验证通过，现有服务未重启，详见 CURRENT_TASK。

## 当前实现：ADR 0069/spec 058（2026-10-11）

用户已确认开发。下文 ADR 0068 的 Cube 禁用、独立问数示例工程及“下一轮未实施”仅为历史。主建模和问数 Agent 挂载同版官方 skills；建模通过原生文件和受限 CLI，问数通过原生 MCP listTools/callTool 的通用租户适配。SQL/Cube 由官方处理，平台只负责权限、发布、产物和反馈，不二次改写官方 MDL。

WrenPublishedProject 将已发布工程部署到 group/runtime/query-project，合并 runtime/liked-examples 的官方 knowledge/sql；官方 memory index/recall 读取统一来源，不混入草稿。WrenMcpToolkit 返回原响应与 CSV 产物；可选外层 answer_question 标记答案业务查询，回执经原生 toolCallId 关联，只有用户点赞后保存，诊断和失败查询排除。该标记依赖 Agent 遵守指令，不能声称平台能自动证明业务正确。

Cube、ref_sql、视图、关系、knowledge 均按官方契约。通用写授权可批次确认，memory index 默认生成源码样例也须授权；显式 --no-seed 仅更新派生索引。发布保持显式与租户隔离。

固定依赖 requirements-wren.txt：wrenai 0.15.0（mcp/mysql/memory-onnx）、wren-core-py 0.8.0、mcp 1.30.0。隔离环境实测中文 lancedb/ONNX 召回与 CLI/MCP 成功；MCP 2.x 与该 Wren 版不兼容。原服务环境未升级或重载，真实 LLM 效果仍待验收。页面统一资料、对话建模、检查与发布、语义资产四区；问题和文档均可选。

> 2026-10-11 最新方向：建模与问数均采用官方 Wren skills/CLI/MCP，平台仅为通用 Agent 与多租户服务，不用本体。详细方案见 ADR 0069/spec 058/实施计划，待审阅，尚未实施；下文 Cube 禁用和旧问数工具仍描述现有代码，不能误记为新方向。

更新日期：2026-10-11（Asia/Shanghai）
依据：项目规则、根 pom.xml、ADR 0063—0068、spec 055—057 和已核对代码。记忆用于导航，具体实现以代码为准。

## 目标与主流程

多租户数据分析 Agent 平台，基于 AgentScope HarnessAgent / ReActAgent。
用户上传或关联数据 → 生成基本语义模型 → 可选上传业务文档、填写问题 → Agent 完善模型、关系、业务规则和明细视图 → 工程检查 → 显式发布 → 在已发布模型上问数、分析和可视化。

建模产物以 Wren 工程 YAML 为事实源，编译形成 MDL。问数通过 Wren 编译执行逻辑模型/视图 SQL，并使用 Python 或 ECharts 分析展示。没有物理 SQL 直查回退；知识图谱链路已停用。

当前已接受取消 Cube 的产品路径，保留普通模型、SQL 定义模型和视图。预设问题可选，问题确认不阻碍工程通过后的发布。问数最终结果点赞后保存当轮成功 BUSINESS 中文 question/SQL/limit 示例；DIAGNOSTIC 不保存。点踩只记录反馈，不撤回；不根据反馈自动改写语义资产（ADR 0065/0066）。

## 技术栈与构建事实

- 后端：Java、Spring Boot WebFlux、Spring Data JPA；阻塞 JDBC/CLI 等操作需遵守 boundedElastic 规则。
- 当前 standalone 根 pom.xml：Java 编译版本属性为 17、Spring Boot 4.0.4、AgentScope 2.0.3-SNAPSHOT；实际运行 JDK 需接手时核对。
- 前端：React 18、TypeScript、Vite，ECharts；源码在 frontend/，构建会更新后端 static 资源。
- 平台元数据：H2 或 JDBC profile 的 MySQL/PG；上传数据物理表使用数据集 MySQL、ds_ 前缀；Redis 会话可选。
- 本 IDEA 项目是 standalone Maven 模块，依赖已安装/可解析的 AgentScope artifacts。根目录没有前端 package.json，npm 命令应在 frontend/ 运行。
- 配置使用 .env / application.yml；共享记忆不得记录配置中的秘密值。

根 AGENTS.md 仍有旧的 Java 21、多模块路径及 Cube 描述。本次按用户要求保留原文，记录差异供后续核对；不要据此恢复已经取消的 Cube。正式产品决策参照 ADR 0063。

## 关键模块与入口

- web/：HTTP API、权限、审计；ChatController、SemanticModelingController、AnswerFeedbackController。
- tools/data/：WrenToolkit 承担问数；建模注册原生 FilesystemTool（通过 WrenProjectFilesystem）与 OfficialWrenToolkit。旧 ModelingToolkit 仅供遗留 HTTP，未注册为 Agent 工具。
- dataset/：MdlWorkspaceService、MdlPublishService、MdlQuestionStore、DocEnhanceService、ModelingWorkflowService、AnswerQueryMemory、SqlExampleRecall。
- runtime/：Agent 装配、动态数据上下文、会话与沙箱；DataDynamicContextMiddleware（问数）与 WrenProjectContextMiddleware（建模，仅项目身份/资料位置）。
- web/config/：DataAgentConfig；web/scaffold/：WorkspaceScaffolder。
- frontend/：语义建模页面、问题表格、发布指引、聊天和答案反馈。
- docs/adr/、docs/specs/、ARCHITECTURE_zh.md：正式设计与需求。

数据资产按 ownerId / DatasetScope 隔离；保留现有框架的 ReAct、工具执行和会话能力，不在业务层重新实现。

## 示例链路与环境观察

已发布建模确认示例来自 knowledge/sql/*.md；仅有 knowledge/questions/*.yml 或执行成功不会自动成为示例。问数示例来源于最终答案点赞与成功查询回执，按用户/知识库隔离。

2026-10-10 观察：本机 Wren 0.15.0 Python 环境默认解析为 grep，尚未实测官方多语言向量召回。ADR 0066 已统一官方 memory CLI，移除项目自研检索/评分/降级；官方 grep 也是官方后端，不代表已验证向量召回。

本机历史使用的 Wren 环境：D:/workspace/wren-probe/.venv；运行时 MDL 根目录：C:/Users/roylin/.agentscope/dataagent/mdl。这些是本机观察值，不要求另一个账号或机器具有相同路径。

## 相关文档

- [ADR 0063](../adr/0063-sql-only-modeling-and-relevant-example-recall.md)
- [spec 055](../specs/055-sql-only-modeling-and-example-recall.md)
- [当前任务](CURRENT_TASK.md)

## 当前通用建模架构（ADR 0068 / spec 057）

2026-10-11 用户确认文档与代码实施。官方 generate-mdl/enrich-context/usage/onboarding 技能决定领域流程与模式，平台只提供原生 AgentScope 执行能力和当前知识库的权限适配。没有平台模式锁定、关系/视图/问题阶段工具注册，不修改官方技能。

WrenProjectAccess 绑定鉴权 ownerId + 单个 groupId；业务文档同步 raw/business-document.md。文件工具限定当前草稿，禁止平台文件、穿越、宿主路径和链接。wren_cli 采用 argv 白名单并绑定工程/MDL/memory；query/dry-run 临时绑定当前知识库连接并恢复工程配置，WREN_HOME 使用平台维护的配置而非宿主账号配置。SQL 只读，行数由参数控制。

原生 HITL 确认 write_file/edit_file 与官方 memory store 授权。预览不再做平台写前语义预检，Agent 按官方技能执行检查。官方编译退出码同步到现有页面工程状态；后续变更使旧检查失效，仅供页面指引。发布仍完整校验，build 不替换线上快照。

官方 memory 的标准 knowledge/sql 文件经显式发布后可被问数的官方召回链复用；不要求平台 verified/validation_id 扩展。建模派生索引在知识库 runtime/modeling-memory，避免二进制索引进入可发布源码。问数点赞 memory 与建模草稿 memory 保持既有独立范围。问题表格继续可选，不恢复 Cube，不处理历史数据。

## 下一轮规划边界（ADR 0069 / spec 058，未实施）

- 完整支持官方模型/ref_sql/关系/视图/Cube/knowledge；官方技能决定资产，平台退出领域阶段/SQL 路由及官方 MDL 二次改写。Wren 编译和执行不能保证 LLM 业务口径正确。
- AgentScope 原生 MCP ClientWrapper 现有公开面有 listTools/callTool，资源/提示接口须另核对当前 SDK；保留原生框架循环，不猜接口，不自建 MCP 协议。
- runtime 问数工程绑定已发布 MDL/knowledge 与 owner/group 点赞样例，调用官方 memory；草稿资料不进入线上问数，不自研召回。
- 2026-10-11 版本核实：本机/当前 PyPI wrenai 0.15.0，本机 wren-core-py 0.8.0；PyPI 状态 Beta。暂无改造后的真实业务效果数据。


## 2026-10-12 00:24:12 +08:00｜当前事实补充

ADR 0069主建模/问数适配已存在，但效果与应用最终验收未完成。deepseek-flash真实 30问及六问复测已执行，存在空结果补零、单位/叙述问题；不能把工具正确性说成官方产品效果。当前流程仍使用官方 enrich-context必要业务澄清与高影响资产提案确认，通用文件写权限批次授权。

项目当前没有 `.env`文件，实际配置为 `application.yml`和环境变量；上文“.env / application.yml”是长期约定，不是本机文件存在证据。原外部Wren运行环境未升级，用户服务未加载最新源码。隔离应用用H2时还需正确初始化配置：当前MySQL风格数据初始化语句导致启动失败。最新进度及下一步以 CURRENT_TASK.md顶部checkpoint为准；不复制配置秘密或整段日志。
