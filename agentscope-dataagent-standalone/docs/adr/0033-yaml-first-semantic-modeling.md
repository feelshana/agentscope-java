# ADR 0033: 语义资产 YAML-first（文件唯一事实源与三项拍板）

- 状态：已采纳（实施规格 specs/019）
- 日期：2026-10-04
- 来源：官方能力调研（wrenai 0.15.0 CLI 实测 + docs.getwren.ai CLI/MDL 参考全文 + 官方仓库 README）+ 前序断点源码核实 + 用户三项拍板

## 背景

前序分析确认三类结构性断点均源于「平台自建语义编译器 + DB 为事实源」：① cube measure 被封闭为四元组（agg 枚举 + column + caseFilter），模型写自由 expression 被忽略后报「聚合函数非法」；② 跨表投影列名在发布期才合成为「对端数据集名_列名」，DocEnhance 提示词不可见，跨表条件写不对列名；③ cube 无 dry-run 校验，坏 caseFilter 穿透至问数期。

官方能力调研（wrenai 0.15.0）：OSS 已完整覆盖语义上下文层全链路——5 个官方剧本（generate-mdl / enrich-context / onboarding / dlt-connector / genbi）、MDL 全字段面（ref_sql 派生模型、计算列、对象列/投影列、is_hidden 选择性暴露、复合主键、cube measures expression 自由表达式 + hierarchies、knowledge/ 布局）、确定性校验链（context validate 已含视图 dry-plan、build、dry-plan 免库、dry-run、cube query --sql-only）、memory 14 子命令、serve mcp（17 工具/5 资源/工作流 SOP）；官方 Open core 边界明确不做（商业版）：用户/组与行列级权限、UI/仪表盘/嵌入 API、高级审计。官方人机确认仅有 grill/auto-pilot（剧本纪律）与 git sync（PR 评审）两种弱形态，无审批产品——平台 HITL 卡填的正是该留白。

用户拍板「全部调用 wrenAI」并要求三项设计确认，2026-10-04 全选推荐项：文件所有权＝播种＋项目自持；发布语义＝工作区即真相；关系写面＝relationships.yml。

## 决策

1. **D1 文件为语义资产唯一事实源**：`<mdlRoot>/<groupId>/workspace/` 采用官方 schema_version 5 布局，承载 models/relationships.yml/cubes/views/knowledge/；DB 语义实体（cube/view/term/rule）的「事实源」地位退役（表结构保留，停止作为写入与渲染来源）；关系候选建议队列保留在 DB（工作流队列，非语义资产）。
2. **D2 文件所有权＝播种＋项目自持**：平台仅在数据集进入知识库时播种缺失的物理模型文件（table_reference + 物理列 + 描述）；已存在文件仅追加缺失列、冲突/删除列写入「请人工修复」清单，绝不重写；数据集移出时删除对应文件并输出引用修复清单。
3. **D3 发布语义＝工作区即真相**：写入经 HITL 确认即生效于工作区；发布＝完整校验＋快照＋版本号；上传新表触发的自动发布包含工作区全部已确认内容（确认已前移，无需 DRAFT/PUBLISHED 状态机，后者退役）。
4. **D4 关系写面＝relationships.yml**：页面确认与 agent 关系工具统一写工程文件；DB 仅保留候选队列状态。
5. **D5 agent 工具面**：平台侧文件工具（list/read/write/patch，host 执行、路径锁组工作区、禁写 wren_project.yml 等平台文件）＋官方命令包（skills list/get、context show/instructions、dry-plan、dry-run、cube list/describe/query --sql-only，memory 可选）；写工具入 `ModelingHitlMiddleware.WRITE_TOOL_NAMES`，写前三重闸（YAML parse → scratch 副本 dry-plan → 写后 context validate），失败单文件回滚；8 个结构化写工具退役。
6. **D6 发布链补 cube 试跑**：validate/build 后逐视图 dry-run（沿用 ADR 0032）＋逐 cube `cube query --sql-only`（新增）；失败 error issue 中止发布、保留旧快照。
7. **D7 读面切换**：MdlPublishService.view()/preview() 改从工程文件解析（优先官方结构化输出），API 形状不变；知识注入 [KNOWLEDGE_BASE_OVERVIEW] 切 knowledge/rules 文件；问数链路（serve mcp、push 预注入）维持 ADR 0020 分叉不变。
8. **D8 DocEnhance v2**：文档上传 → 建模会话按 enrich-context 剧本增强（文档入会话上下文）→ 文件变更走 HITL；六类结构化提案路径退役；官方 cube_proposals 决策树、防重守卫、双验证照搬。
9. **D9 版本 pin**：wrenai 0.15.0（+ `mcp<2`）；skill 清单动态化（以已装版本 `wren skills list` 为准）；升级走新 ADR。

## 理由与权衡

- 为何「播种＋自持」而非「平台重渲染＋合并」：重渲染制造双源（DB 与文件），合并逻辑脆且与官方「只增不改」哲学冲突——本轮三类断点的共同根源就是双源。代价是对账逻辑需处理列冲突，复杂度远低于合并。
- 为何「工作区即真相」而非「保留草稿两层」：每次写入已过 HITL 确认，「未发布＝未确认」的旧承诺由确认前移承接；在文件世界维护草稿标记复杂且与 wren 工程兼容性有风险。
- 为何「关系写文件」而非「DB 为源」：agent 手写关系是官方剧本的组成部分（enrich-context 会补复合键关系），DB 为源会出现「手写被渲染覆盖」的双源打架。
- 断点消除机制：cube 自由表达式、投影列命名可见性、cube 校验三项断点分别由「文件直写官方表达式」「agent 自定列名」「逐 cube dry-run」结构性消除，替代此前的提示词补丁与封闭枚举。
- 平台定位：只保留官方明确不做且平台已有积累的四件事——多租户权限、HITL 确认产品、上传资产与播种、快照治理；平台代码净减少（编译器退役），与 wren 的耦合从「复刻语法」变为「透传文件＋调用 CLI」。
- 代价与风险：读面重构面广（view/preview/关系/知识注入）；LLM 写 YAML 正确性靠工具三重闸＋HITL 兜底；每写预检一次 wren 子进程（秒级、低频，可接受）。
- 明确不决策项：genbi/cloud/OSI/dbt 采纳、存量数据迁移工具、Doris 连接能力、对话内发布。

## 影响面

- 修改/新增：MdlPublishService（assembler 重构、view()/preview() 读面、发布链补 cube dry-run、播种器协作）、新增播种/对账组件（dataset 包）、ModelingToolkit（文件与 CLI 工具、退役写工具）、DocEnhanceService（enrich 会话化）、MdlSuggestionService（关系写面）、DataDynamicContextMiddleware（知识切源）、DataAgentConfig（建模 prompt 与剧本投递）、ModelingHitlMiddleware（WRITE_TOOL_NAMES）、前端 ModelingHitlCard（YAML diff 卡）与语义建模页/文档增强页微调。
- 测试：播种对账、文件工具三重闸与回滚、发布链 cube dry-run 中止、读面回归、DocEnhance 路径、跨组越权；黄金用例端到端对账（3089.00/2、月度 99/2990/0）。
- 文档：ARCHITECTURE_zh.md（装配、工具链、语义建模章节）、specs/019、README 配置表（如有新配置项）。
