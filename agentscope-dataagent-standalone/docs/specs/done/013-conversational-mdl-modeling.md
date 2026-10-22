# spec 013: 对话式语义建模与 MDL 只读可视化

> 状态：已批准（ADR 0024），待实施。合并自「对话式建模 M1-M3 评估」与「cube 建模增强
> P0/P1/P2（复合键/fan-out 预检/CASE 口径）」两份提案。

## 背景与目标

表单建模（specs/010 M1-M4）已上线，但三类语义表单表达不了：复合键关系（实测 fan-out
×10 膨胀：province 单列键 vs province×stat_date 表粒度）、CASE 口径、业务语义缺口
（枚举/单位/NULL/魔法值）。WrenAI v5 官方建模主线即对话式（generate-mdl + enrich-context
技能剧本由宿主 agent 执行），交互形态已被官方验证。本规格把该模式移植进平台：内置建模
助手 agent 对话完成建模，成果在语义建模页以只读 ERD 可视化（参照经典 wren-ui）。

## 方案概述

架构决策见 ADR 0024（D1-D7）。要点：建模助手独立 agent（不挂问数工具面）；对话确认
动作 = @Tool 调用 = 实体写入（复用 MdlSuggestionService 既有方法）；YAML 仍由
MdlPublishService 从实体确定性拼装，发布链路零改动。

### M1 对话式建模闭环 + 复合键

**后端**：

- 新建 `ModelingToolkit`（`tools/data/`，参照 `WrenToolkit` 模式：`@Tool`/`@ToolParam`
  中文描述、`DatasetScope` + `RuntimeContext` 参数框架注入、入参校验）：
  - `list_modeling_state`：组建模快照（模型卡/关系及状态/cube/组状态机）——剧本开场
    与漂移自愈的事实源；
  - `suggest_relations` / `confirm_relation` / `reject_relation` / `add_relation`：
    调 `MdlSuggestionService#refreshRelations/confirmRelation/rejectRelation/addManualRelation`
    （add_relation 支持复合键列对列表）；
  - `create_cube` / `update_cube` / `delete_cube`：调既有 cube CRUD；update/delete 对
    已发布同名/存量 cube 需带显式确认标记（D7）；
  - `validate_mdl`：调 `MdlPublishService#validate`，返回 issues 列表。
- 建模助手 agent：`DataToolkitRegistrar` 同款后置注册模式挂 ModelingToolkit；
  剧本写进建模助手的 system prompt（简体中文）。
- 复合键：`DatasetRelationEntity` 增列对存储（`source_columns`/`target_columns` JSON，
  迁移脚本把现单列值升格为单元素列表）；`MdlPublishService` 关系条件渲染
  `a.c1 = b.c1 AND a.c2 = b.c2`；本地 issue 校验逐列对校验列存在性。
- 剧本 prompt（grill 规则移植，简体中文，工具描述同步守卫用 classpath 断言）：
  1. 开场：调 `list_modeling_state` 汇报状态与待办（PENDING 关系数、无关系模型、
     无 cube 模型）；
  2. 关系确认（**一次一题**）：呈现候选（两端表/列对、joinType 建议、confidence、
     reason），**必带推荐答案**；用户 accept → `confirm_relation`；edit → 按口述修改
     后落库；skip → 会话内不回访；
  3. **复合键追问**：关系确认后追问「两张表是否还有第二对齐列（如日期）？」——附
     表粒度提示（行数/COUNT DISTINCT 来自 joinType 探测同款查询）；
  4. 无候选时三级降级（官方 generate-mdl Step 3）：外键元数据（外部源）→ 命名约定
     推断（`<表名>_id` 同款语义，中文列名走 LLM 建议）→ 问人，落 `add_relation`；
  5. cube 阶段：`suggest_relations` 同轮跑 `suggest/cubes` 提案（成员 + reason）逐个
     确认，CASE 口径在对话中口述确认（M3 前由 LLM 转成员 expression）；
  6. 收尾：`validate_mdl` 汇报 issues，引导前往语义建模页发布（M1 无对话内发布）。

**前端**：`SemanticModelingPage` 加「对话建模」入口（拉起建模助手会话并携带 groupId）。

### M2 只读 MDL 可视化

- 后端新端点 `GET /api/dataset-groups/{groupId}/modeling/mdl/view`：从
  `MdlPublishService#assemble` 的 ModelSpec/RelationSpec/CubeSpec 暴露只读 VO（草稿态），
  与已发布快照（解析 published 目录）对照 + changed 标记；前端不解析 YAML。
- 前端 `SemanticModelingPage` 新增「MDL 视图」Tab + `MdlGraphView` 组件
  （`@antv/g6`，参照 `OntologyGraphView` 模式）：
  - ERD 画布：模型卡（逻辑模型名/描述/列清单；关系列与计算列带来源标注）、关系连线
    （joinType + 键对标注，复合键逐对列出）；
  - cube 卡列表：指标/维度/时间维度分组展示 expression；
  - 发布状态条：版本/发布时间/changed（未发布显示草稿预览 +「未发布」标注）。
- **只读**：无编辑按钮、无拖拽连线；编辑引导到对话建模或表单页签。

### M3 发布期基数预检 + 口径增强

- fan-out 预检（发布校验内，warning 不阻断）：对 CONFIRMED 关系跑
  `COUNT(DISTINCT 键列) vs COUNT(*)`（joinType 探测既有先例）；两侧不满足声明的
  joinType 基数时 issue 点名关系并建议补复合键——对话中呈现给用户。
- CASE 口径问答：measure 的 CASE 条件作为推荐答案的一部分（MdlSuggestionService
  提案 + reason），对话确认；表单侧同步展示 expression 预览。

### M4（可选）语义缺口问答

enrich-context 十类缺口的高杠杆子集：枚举值含义/单位/NULL 语义/魔法值/软删默认过滤 →
写入列描述与知识库规则文档（`SemanticTerm`/组文档既有承载），同时服务直查通道的
上下文注入（双通道共享收益）。

## 影响面

- 后端：`ModelingToolkit`（新）、建模助手 agent 装配（registrar + agent 定义）、
  `DatasetRelationEntity` 复合键列（迁移）、`MdlPublishService`（condition 渲染/
  逐列校验/预检/view 端点数据）、`MdlSuggestionService`（复合键 payload）
- 前端：`SemanticModelingPage`（对话入口 + MDL 视图 Tab）、`MdlGraphView`（新，G6）、
  `semanticModeling.ts` API
- 数据：`dataagent_dataset_relation` 复合键列迁移
- 文档：实施期同步 ARCHITECTURE_zh 2.5/5.4/5.6/11；README 配置表（如新增建模助手
  agent 配置项）

## 验收标准（Given-When-Then，可测试）

1. Given 组内两张同粒度表（province × stat_date），When 建模助手对话确认关系并补
   「+日期」第二列对，Then 实体存复合键、发布后 relationships.yml 条件含 AND、跨表
   cube 查询结果与直查真值一致（无 ×10 膨胀）。
2. Given 建模助手会话存在 PENDING 关系候选，When LLM 逐条呈现，Then 每轮恰好一个问题
   且带推荐答案；确认动作经工具调用落库，语义建模页表单刷新可见同一状态。
3. Given 组已发布（v1）后对话又确认了新关系，When 查看组建模状态，Then 组为 DIRTY、
   旧版本继续服务问数、发布后版本 v2（与表单行为一致）。
4. Given 未发布组，When 打开 MDL 视图，Then 显示草稿预览与「未发布」标注；已发布组
   显示发布快照（版本/时间/changed）。
5. Given 对话产出的关系与 cube，When 发布，Then 走与表单完全相同的
   validate --strict → build → snapshot → version+1 链路（无旁路）。
6. Given 用户 bob 无该组权限，When bob 的建模会话调任何建模工具，Then 拒绝且看不到
   alice 组的任何建模数据（含建议、列名、行数）。
7. Given CONFIRMED 关系声明 ONE_TO_ONE 但探测发现 COUNT(DISTINCT) < COUNT(*)，When
   发布校验，Then issues 含 warning 点名该关系并建议复合键（M3）。

## 不做的事

- MDL 视图不做编辑/拖拽连线（只读；编辑走对话或表单）
- 不引入新 UI/图形库（G6 已有；ERD 用 G6，禁止 ECharts graph 混用二套画布）
- 建模助手不暴露 wren 查询工具（run_sql/query_cube 属问数 agent）
- 不移植 auto-pilot 模式（仅 grill；apply-then-audit 与平台人审底线冲突）
- M1 不做对话内发布（发布 = 语义建模页显式动作，ADR 0018 D9 延续）
- 不改 specs/010 已实施的表单建模与发布链路行为

## 测试要求

- `ModelingToolkitTest`：工具→服务层映射、复合键列对校验与渲染、租户越权（bob 不可
  见 alice 组——「看不到别人的数据」用例）、已发布 cube 覆盖的确认标记门
- `MdlPublishServiceTest` 扩展：复合键 condition 渲染、逐列对本地校验、基数预检 issue
- 剧本守卫：classpath 断言剧本 prompt 与工具描述同步（SharedSkillContentTest 同款模式）
- `npm run build` 通过

## 关联

- ADR 0024（本规格的架构决策）
- ADR 0018 D9/D10（建模状态机/双通道）、ADR 0007（剧本为新指令层的分层义务）
- specs/010（表单建模基线，本规格不改其行为）
- 官方剧本移植源：wren skills generate-mdl / enrich-context（grill 规则、三级降级、
  十类缺口）；可视化参照：经典 wren-ui 建模页（ERD/模型卡）
- fan-out 实测：target/wren-probe/joinmdl（验收标准 1 的证据原型）
