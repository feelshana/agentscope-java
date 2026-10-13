# ADR 0068：建模 Agent 采用通用执行能力与官方 Wren

- 状态：Accepted（2026-10-11 用户确认文档修改与代码开发；实施方案见 spec 057）
- 日期：2026-10-11
- 关联：spec 057、ADR 0063、ADR 0064—0067。取代建模运行时的平台领域剧本及专用工具方向，不修改旧 ADR。

## 背景

用户要求全部使用 WrenAI 工具，本项目建模 Agent 只作为通用 Agent。当前 MODELING_SCRIPT、ModelingToolkit 与专用关系/视图/问题工具仍定义第二套建模流程，即使已挂载官方技能，也会产生重复规则和确认入口。

核对安装版 wrenai 0.15.0 与官方源码：enrich-context/generate-mdl 由通用 Agent 读取原始资料、编辑 Wren YAML，并调用官方 CLI 校验、构建、查询及 memory。官方 MCP 提供查询、上下文、知识读取和 store_query，未覆盖完整 YAML 工程写入及 validate/build。不能通过仅挂载 MCP 宣称已完成全部建模替换。

## 决策

1. modeling-agent 继续使用 AgentScope HarnessAgent/ReActAgent，不自建执行循环；领域知识、模式选择、澄清、资产落点和增强流程来自未修改的官方 generate-mdl/enrich-context/usage/onboarding 技能。平台不在 MODELING_SCRIPT、工具描述或模式工具中复制官方步骤。
2. 领域操作全部委托官方 Wren CLI；官方 MCP 可承载已覆盖的能力，但不是完整建模的唯一入口。不把平台服务算法包装为同名“官方工具”。文件读写和技能加载使用通用 AgentScope 能力；平台适配仅约束可访问的当前 Wren project，不决定如何建模。
3. 建模 Agent 不再注册专用关系推断/确认、create_view、问题验证、建模阶段推进或 set/get_enrich_context_mode 等工具。已有 HTTP 页面能力不因停止 Agent 注册而整体删除；模式选择按官方技能在原生会话上下文中保留，不维护另一套平台模式决策树。
4. 服务器根据鉴权后的 ownerId、DatasetScope 绑定单个草稿工程。通用文件工具及 CLI 调用不得选择其他知识库、全局 profile 或任意宿主路径；路径、命令和参数限制属于权限适配，不属于语义建模算法。官方规则输出和错误按原样回传，只附调用状态与展示元数据。
5. 平台保留租户隔离、会话、超时/审计、文件写入 HITL、上传资料到 raw/ 的映射和产物展示。Wren context build 负责生成 MDL；平台“发布”仍负责将草稿形成已发布版本并切换问数边界。不能将官方 build 称为平台版本发布，也不让通用 CLI 绕过发布权限。
6. 保留既有产品决定：不启用 Cube、预设问题可选、无物理 SQL 回退、已发布模型问数、点赞后的样例策略遵循 ADR 0065/0066。这些是平台使用边界，不复刻官方业务推断规则。官方技能中的可选 Cube 分支不等于用户授权恢复 Cube。

## 取舍

通用文件编辑与 Wren CLI 必须在租户项目范围内运行，不能简单开启宿主机任意 shell。若框架文件系统不能直接映射工程，应使用薄的文件路径适配，不保留平台专用 YAML 结构或业务预检决策树。HITL 是写权限确认，官方 Grill 是业务澄清，两个层次不得合并为新领域流程或宣称 Auto-pilot 完全无需用户参与。

旧工具方法、HTTP 服务和前端需要分开处理：本轮退出 Agent 工具注册和提示词依赖，只有确认无其他调用后才删除遗留实现。UI 读取实际 Wren 工程/MDL，而不是要求 Agent 调用旧工具维护另一份状态。

## 验证

核对实际模型请求中的工具 schema 与系统提示，确认无旧领域工具或平台流程；同一隔离工程分别运行直接 CLI 和平台适配，对照 show/validate/build/dry-plan/dry-run/memory。测试越权、路径穿越、绝对路径和 profile 切换防护；验证官方技能加载、原生 HITL 拒绝/恢复、草稿与线上版本隔离以及错误回传。

## 官方依据

- [官方 enrich-context](https://github.com/Canner/WrenAI/blob/main/core/wren/src/wren/skills_content/enrich-context/SKILL.md)
- [官方 generate-mdl](https://github.com/Canner/WrenAI/blob/main/core/wren/src/wren/skills_content/generate-mdl/SKILL.md)
- [官方 MCP 工具实现](https://github.com/Canner/WrenAI/blob/main/core/wren/src/wren/mcp_server.py)
