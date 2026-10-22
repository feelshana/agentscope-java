# ADR 0069：建模与问数统一使用官方 Wren 能力

- 状态：Accepted（2026-10-11 用户确认按完整计划开始实施；阶段进度见实施计划和共享记忆）；"未授权写入保留原生 HITL"条款已被 ADR-0111 取代（2026-10-21）
- 日期：2026-10-11
- 关联：[spec 058](../specs/done/058-wren-native-modeling-and-query-agents.md)、[实施计划](../archive/plans/058-wren-native-modeling-and-query-agents.md)。
- 生效后取代：ADR 0063 的 Cube 禁用、ADR 0064 的平台 SQL 流程、ADR 0068 的 Cube 排除及与本决策冲突的工具限制；保留旧 ADR 作为历史。

## 背景与证据

用户要求建模和问数都采用 WrenAI，本项目作为通用 Agent 调用，不建立本体或另一套语义推理体系。现有建模已经挂载官方技能和 CLI，但工程路径、命令白名单、提示词仍禁止 Cube；问数依赖平台 WrenToolkit 和 sql-analysis 规则，尚未统一到官方 usage 与 MCP 工具面。

最新 logs/LLM-modeling.log 中，官方技能建议新增 Cube，平台阻止后 Agent 改为记录指标 Markdown。其中营收和付费客户定义遗漏非内部客户过滤，技术校验通过也不能证明业务正确。问数 memory 的已观察后端是 grep，示例为空；不能宣称已经启用向量召回。

本机实测 wrenai 0.15.0、wren-core-py 0.8.0；2026-10-11 查询 PyPI，wrenai 最新也是 0.15.0，发布于 2026-09-21，标记 Beta。官方 CLI/skills/MDL/MCP/memory 满足集成所需能力，但没有本项目业务准确率的实测依据。

## 决策

1. 选择官方开源 CLI + skills + MCP 集成。AgentScope 负责现有 Agent 循环、LLM 调用、工具执行和会话；安装版官方技能负责建模和问数方法，Wren 编译器及查询工具负责检查、转换和执行。并非部署 Wren 官方完整 Web/Cloud 产品，也不能把 CLI 称为独立的自然语言转 SQL 服务。
2. 建模挂载未修改的 generate-mdl/enrich-context/usage/onboarding。通用文件工具编辑当前授权 Wren 工程；官方 CLI 执行 show/validate/build/query/cube/memory 等操作。取消平台领域阶段工具、模式锁、指标推断、强制视图或 Cube 落点。官方技能的业务澄清及高影响变更审阅保持；不得由平台叠加第二套业务确认。
3. 支持官方普通模型、ref_sql 模型、关系、视图、Cube 和 knowledge。不把 Cube 当作固定月度报表，查询参数和粒度遵守安装版官方契约；不建立项目的 Cube 适用范围评分或路由树。资产是否需要、如何定义由官方技能结合实际业务资料处理，不保证每个问题都生成资产。
4. 问数挂载官方 usage，连接当前授权已发布 Wren project 的 MCP。发现官方工具 schema 并按原参数调用，不把平台算法改名成“官方工具”。现有框架支持原生 listTools/callTool；允许薄的通用项目选择适配，把 groupId 留在平台权限层，官方 arguments 不增添 BUSINESS/question 等项目专有必填参数。资源/提示协议接口须依据实际 SDK 能力实现，不假设框架已经提供。
5. 平台只保留鉴权、租户和工程绑定、凭据隔离、超时审计、读写授权、部署发布、上传、MDL 衍生展示、结果文件、Python/图表和答案反馈。工具不得访问别人的工程、任意宿主路径或任意 profile。保留只读数据查询及 Wren strict_mode 已声明模型边界；撤掉与官方契约冲突的 SQL LIMIT/OFFSET 一律禁用规则，不自行改变业务 SQL。
6. YAML/SQL/knowledge 是工程源码，官方 context build 的 target/mdl.json 是查询语义产物。平台不能通过 RefSqlMaterializer 等逻辑重写官方编译产物。ref_sql 按安装版官方定义构造；不支持的表达原样报错或由 Agent 调整源码。平台目录/显示索引是衍生数据，不成为另一份建模事实源。
7. 草稿构建、技术检查和线上部署分开。Agent 可以在隔离草稿执行官方工具，build 不自动覆盖已发布工程。平台显式发布重新运行官方检查和构建，原子切换 MDL、knowledge、连接及 MCP 实例；失败保持原线上版本。未发布文档和规则不能进入问数上下文。
8. 预设问题、推荐问题都可选，空问题列表不阻止建模或发布。问题可用来理解需求和试问，不引入必验集合、双轮验收或自动从修改 SQL 反推语义资产。页面提供可增行的问题表格，并明确展示草稿/检查/发布状态。
9. 问答示例继续遵循用户确定的最终答案点赞门槛；点踩只记录反馈。适配新的官方 SQL/Cube 工具回执，只保存成功且实际用于答案的业务查询，失败、诊断查询不入库。多查询不能将完整原问题与局部 SQL 无条件拼接。样例通过官方 store/index/recall 管理，不自研排名或中文回退，不因样例保存自动修改模型。
10. 部署官方 memory 依赖并实测中文改写召回。已发布建模样例和点赞样例在授权问数工程内可被同一官方后端读到，草稿与线上隔离。未命中、空库、grep 后端、向量依赖故障要可诊断；不把 fallback 为 grep 伪装成向量成功。

## 取舍与边界

“完全参照官方”约束领域算法与能力契约，平台多租户部署及反馈交互仍需薄适配。外部 LLM 仍负责理解业务和生成正确源码/SQL；官方技能不是准确率保证。取消 Cube 禁用不会自动修正历史错误，必须在隔离数据上验证同一指标的 SQL/Cube 结果。

通用文件写权限和官方业务审阅是不同层次：会话内授权的同一变更不重复确认，未授权写入仍保留原生 HITL。不得开启任意宿主 shell、连接修改或无边界删除。只允许受限的工程文件回滚；平台发布及 memory 写入保留各自授权边界。

不建设 KG/本体，不复制 Wren SQL 编译器、检索器或模型路由，不迁移/删除用户旧数据，不新增之前用户排除的资产适用范围检查、可信等级或版本评分体系。LLM 流中断问题独立观测，不能用 Wren 接入改造宣称已经解决传输 EOF。

## 验证与接受条件

同一 fixture 和同一安装版 Wren，以直接 CLI/MCP 与平台适配对照参数、错误、MDL 和结果；校验越权、源码写权限、发布失败保留旧版。增加业务 ground truth：内部客户过滤、跨月客户全年去重、空值、退款状态及一对多 JOIN；有定义歧义时应澄清。准确率来自固定问题集运行结果，目标值是本项目验收指标，不是官方公布效果。

本轮先形成完整计划；实施前须确认 spec。开发完成还需全量单测、构建、真实 CLI/MCP 集成和浏览器/模型对话验收，不能用历史 486 个测试替代新链路验证。

## 官方依据

- [发布版与安装能力](https://pypi.org/project/wrenai/)
- [MDL reference](https://github.com/Canner/WrenAI/blob/main/docs/core/reference/mdl.md)
- [CLI reference](https://github.com/Canner/WrenAI/blob/main/docs/core/reference/cli.md)
- [MCP guide](https://github.com/Canner/WrenAI/blob/main/docs/core/guides/mcp.md)
- [Cube guide](https://github.com/Canner/WrenAI/blob/main/docs/core/guides/cubes.md)
- [enrich-context skill](https://github.com/Canner/WrenAI/blob/main/core/wren/src/wren/skills_content/enrich-context/SKILL.md)
- [正确性设计](https://github.com/Canner/WrenAI/blob/main/docs/core/concepts/correctness.md)

GitHub main 用于核对方向；实际工具 schema、参数及技能以同一安装发布版为准，避免跨版本拼接。
