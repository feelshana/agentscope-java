# 项目长期背景

更新日期：2026-10-10（Asia/Shanghai）
依据：项目 AGENTS.md、根 pom.xml、ADR 0062/0063、spec 054/055 及本次日志分析。记忆用于导航，具体实现以代码为准。

## 目标与主流程

多租户数据分析 Agent 平台，基于 AgentScope HarnessAgent / ReActAgent。
用户上传或关联数据 → 生成基本语义模型 → 可选上传业务文档、填写问题 → Agent 完善模型、关系、业务规则和明细视图 → 工程检查 → 显式发布 → 在已发布模型上问数、分析和可视化。

建模产物以 Wren 工程 YAML 为事实源，编译形成 MDL。问数通过 Wren 编译执行逻辑模型/视图 SQL，并使用 Python 或 ECharts 分析展示。没有物理 SQL 直查回退；知识图谱链路已停用。

当前已接受取消 Cube 的产品路径，保留普通模型、SQL 定义模型和视图。预设问题可选，问题确认不阻碍工程通过后的发布。问数最终结果点赞后保存成功业务查询示例，点踩撤回；不根据反馈自动改写语义资产。

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
- tools/data/：WrenToolkit、ModelingToolkit，问数及 YAML 建模工具。
- dataset/：MdlWorkspaceService、MdlPublishService、MdlQuestionStore、DocEnhanceService、ModelingWorkflowService、AnswerQueryMemory、SqlExampleRecall。
- runtime/：Agent 装配、动态数据上下文、会话与沙箱；DataDynamicContextMiddleware。
- web/config/：DataAgentConfig；web/scaffold/：WorkspaceScaffolder。
- frontend/：语义建模页面、问题表格、发布指引、聊天和答案反馈。
- docs/adr/、docs/specs/、ARCHITECTURE_zh.md：正式设计与需求。

数据资产按 ownerId / DatasetScope 隔离；保留现有框架的 ReAct、工具执行和会话能力，不在业务层重新实现。

## 示例链路与环境观察

已发布建模确认示例来自 knowledge/sql/*.md；仅有 knowledge/questions/*.yml 或执行成功不会自动成为示例。问数示例来源于最终答案点赞与成功查询回执，按用户/知识库隔离。

2026-10-10 观察：本机 Wren 0.15.0 Python 环境默认解析为 grep，尚未实测官方多语言向量召回。代码已接入官方 memory CLI，并提供项目中文词项降级；“去掉降级只用官方向量”仍待讨论。

本机历史使用的 Wren 环境：D:/workspace/wren-probe/.venv；运行时 MDL 根目录：C:/Users/roylin/.agentscope/dataagent/mdl。这些是本机观察值，不要求另一个账号或机器具有相同路径。

## 相关文档

- [ADR 0063](../adr/0063-sql-only-modeling-and-relevant-example-recall.md)
- [spec 055](../specs/055-sql-only-modeling-and-example-recall.md)
- [当前任务](CURRENT_TASK.md)
