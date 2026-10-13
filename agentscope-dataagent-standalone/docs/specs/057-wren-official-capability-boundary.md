# 057 Wren 官方能力边界收敛设计

## 状态

已有首轮探索实现；2026-10-11 根据用户“全部使用 WrenAI 工具，建模 Agent 只作为通用 Agent”收敛下述实施方案。用户已确认，代码、自动化和真实 CLI 对照完成，服务重载与浏览器验收待执行。关联 ADR 0068；不将首轮平台模式锁定工具作为最终方案。

## 目标

官方 Wren CLI 和 skills 负责语义建模、语义增强、校验、构建及 memory；本项目 modeling-agent 是通用 AgentScope 执行器。官方构建生成 MDL，平台版本发布负责草稿快照切换和线上问数边界，不把两者混称为官方发布工具。

## 边界

- 官方 CLI/skill：未修改的 `generate-mdl`、`enrich-context`、`usage`、`onboarding`，以及 `context show/validate/build`、`dry-plan`、`dry-run`、`memory store/recall/index/status`。CLI 为完整执行入口，MCP 只承载其实际具备的能力。
- 平台适配：Wren project 路径映射、AgentScope 装配、CLI 错误回传、原生 `write_file`/`edit_file` 和官方 `memory store` 的 HITL 授权、审计和权限。
- 平台不实现第二套语义召回、Grill/Auto-pilot 决策树或 MDL 规则事实源。

## 运行方案

- DataAgentConfig 只声明通用 Agent 的职责、简体中文、当前工程及权限边界；移除阶段剧本、问题落点路由、关系/视图推荐、重复的官方流程和模式规则。Grill/Auto-pilot 选择遵循官方技能，选择保留在原生会话上下文；移除 set/get_enrich_context_mode 领域工具注册。
- ModelingToolkitRegistrar 不再把旧 ModelingToolkit 注册给 Agent；通用文件工具与官方 CLI 薄适配取代其领域工具面。文件读取/写入必须指向服务端绑定的草稿 project，保留原生 HITL，不给 Agent 任意宿主 shell 或跨项目路径选择权。
- 所有确定性建模检查由官方 CLI 完成，工具结果保留退出码和原始错误；薄适配只校验权限、参数、路径、执行超时与审计，不自研 YAML 语义规则或先替 Agent 做关系/视图决策。
- 上传文档可读内容映射到当前工程 raw/；模型与规则作为原生 Wren 文件维护。页面展示从实际文件/MDL读取；可选问题表格作为用户输入，不成为 Agent 的固定工作流或发布前置条件。
- 保留显式平台发布、已发布问数隔离、Cube 禁用和 ADR 0065/0066 的反馈记忆边界。旧 HTTP 服务不跟随旧 Agent 工具整体删除；只移除运行时依赖，避免破坏页面。

## 实施步骤

1. 记录官方 skill 实际来源、挂载 agent 和 CLI fallback。
2. 删除平台提示词中重复的官方 skill 流程细节。
3. 统一所有确定性检查和 memory 操作到官方 CLI。
4. 将建模工具收敛为薄的权限、HITL 和 project 文件适配层。
5. 用同一 Wren project 对比直接 CLI 与平台调用结果。

框架能力先查 agentscope-core/harness 源码，复用原生工具执行、文件系统、技能加载和 HITL 恢复；若需工程路径适配，只实现通用权限映射，不重新实现 ReAct。CLI 不可用时明确报错，不回退到平台建模算法。

## 影响面

- 后端：DataAgentConfig、ModelingToolkitRegistrar、通用工程文件/CLI 适配、ModelingHitlMiddleware 与相关调用入口。
- 前端：仅调整依赖旧 Agent 工具结果的建模任务/确认展示；保留模型展示、上传资料和显式发布入口，不另起 UI 重构。
- 数据：无历史迁移、无平台语义数据库新表；不删除既有工程或处理旧 Cube。
- 文档：同步 ARCHITECTURE_zh.md 第 2/3/5/7 章运行链路、必要的 README 与共享记忆；配置变更另同步第 12 章。

## 验收

- 日志可确认 skill 来源、CLI 入口、project 路径和退出码。
- validate/build/dry-plan/dry-run/memory 均无平台第二实现。
- 文件写入仍经过租户校验和 HITL。
- Grill/Auto-pilot 规则由官方 skill 提供，平台只保存恢复所需状态。
- 实际工具 schema 中无专用关系/视图/问题/阶段/模式工具；文件和 CLI 的领域结果来自官方工程及 CLI。
- 不同 owner/group 不能读写或通过命令访问彼此工程；拒绝跨目录、全局 profile 和发布范围绕过。
- 工程写入经原生 HITL，拒绝后文件不变；确认恢复后真实写入。官方编译可修改当前 target，不能替换已发布版本。
- 文档增强、关系/视图修改和可选问题验证能在真实工程完成，页面可展示产物，问数仍使用已发布版本；Cube 工具/写面不恢复。

## 测试要求与不做

上传业务文档仅保存资料，不自动启动旧 DocEnhanceService 大模型分析；后续由通用建模 Agent 按官方技能读取 raw 资料。保留旧 HTTP 手动入口并明确兼容标识。补上传成功与跨租户拒绝的回归用例，确认均不会启动后台旧分析。模型流式开关支持 DATAAGENT_OPENAI_STREAM，默认开启，仅用于请求方式选择及 EOF 定位，不自动重放整轮 Agent 或写工具。

补工具注册/装配、文件/CLI 越权、HITL 拒绝与恢复、官方命令退出码测试；用固定安装版 Wren 对照同一工程的 validate/build/dry-plan/dry-run/memory，并记录实际后端。完成 standalone Maven 测试/打包、前端构建和 Spotless，避免不存在的 -pl 路径。不得用模拟测试代替真实 CLI 验证。

不改官方技能，不新增平台模式选择工具或建模决策树，不恢复 Cube/KG/物理直查，不借本次方向改动推翻现有反馈策略或迁移历史数据。

## 实施与验收记录（2026-10-11）

已实现 WrenProjectAccess、WrenProjectFilesystem、OfficialWrenToolkit、WrenProjectContextMiddleware，替换 ModelingToolkitRegistrar 的运行时注册与 DataAgentConfig 提示词。原生文件工具/CLI 保留租户、权限和 HITL；前端 edit_file、memory store 确认卡和 CLI 任务标签已适配。旧 HTTP 接口继续兼容。

官方 context build 的退出码同步到现有 ModelingWorkflowStore 页面记录；失败的 validate/build 显示官方错误，成功 validate 单独不标记编译完成。发布仍重验，不能靠该记录绕过发布。query/dry-run 的服务器连接绑定执行完后恢复平台工程配置，避免影响页面工程指纹。建模 memory 派生索引位于知识库 runtime/modeling-memory，源 NL/SQL 文件仍由官方 store 写 knowledge/sql。

测试包含注册工具集合、越权/路径/受保护文件、原生精确编辑、非落盘预览、HITL 暂停和恢复、argv 约束、工程页面状态与变更失效；固定安装版 wrenai 0.15.0 的同工程 CLI 对照涵盖 show/validate/build/dry-plan/dry-run/query/store/recall/status，SQL 定义模型与视图返回总营收 3089。错误字段真实 query 返回非零并原样回传。dry-plan 成功不代表查询一定可执行。

服务未重启；真实模型对话中的官方模式选择、模型请求 schema 与浏览器交互尚需重载后验收，不将 CLI/JUnit 验证说成 LLM 端到端。向量召回未验证，不宣称官方 grep 具有语义向量效果。自动化最终结果在 CURRENT_TASK.md 最新检查点及 SESSION_LOG.md。
