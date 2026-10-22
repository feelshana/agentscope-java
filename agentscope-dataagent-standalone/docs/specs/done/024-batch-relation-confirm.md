# specs/024 — 关系决策批量多选确认（decide_relations）

## 背景

当前关系确认交互为「一次一条」：`list_modeling_state` 提示词明文要求 agent 对每条候选调用一次
`decide_relation`（一次一张 HITL 卡），候选多时用户被迫连续点卡；agent 还可能绕开决策工具直接
`patch_file` 逐条改 relationships.yml（2026-10-04 join_type 逐条修补死锁即由此而来）。

用户诉求：
1. 候选关系**一次性列出**供用户**多选**；
2. 一次完成验证与写入；
3. 每条展示：表名.字段 → 表名.字段、join 类型（中文含义）、业务含义（如「一个用户有多条访问记录」）；
4. **不要关系图**（现单条卡内嵌 ReadOnlyRelationGraph，去掉）。

与 ADR 0031 不冲突：0031 拒绝的是自建审批表与修改 core/harness；本方案是应用层新工具 + 前端卡片，
恢复机制完全复用既有 RequireUserConfirmEvent → hitl_request → confirmModeling 链路（SSE 协议不变）。

## 方案

### M1 后端：ModelingToolkit 新增 decide_relations 批量工具

- 入参 `relations_json`（JSON 数组字符串，规避 harness 反射对嵌套 POJO 列表的不确定性）：
  `[{"relation_id","action","join_type"?,"swap"?,"source_columns"?,"target_columns"?,"note"?}, ...]`
- **validate-then-execute**：先全量校验（JSON 可解析、relation 存在于本知识库、action ∈
  CONFIRM/ADJUST/REJECT/SKIP、join_type 合法、列配对非空且等长、列存在），任一无效则整批拒绝
  （error 文本，不部分执行）；全部通过后逐条执行既有 `confirmRelation` / `rejectRelation`，SKIP 跳过。
- CONFIRM/ADJUST 仍走 specs/019 §7 平台直写路径（结构化渲染 upsert relationships.yml，天然幂等）。
- 执行完毕后对本工程跑一次 `context validate --strict`，结果（含 warning）作为诊断报告附在返回文本中
  （写入本身是平台结构化渲染，validate 是诊断而非拒写闸——与 write_file 的 gate 语义不同）。
- 返回汇总：确认 N 条（逐条 edgeLabel+note）、否决 M 条、跳过 K 条、validate 结论。
- `decide_relation`（单条）保留兼容；description 指引「多候选时优先 decide_relations」。

### M2 装配与提示词

- `ModelingHitlMiddleware.WRITE_TOOL_NAMES` 加入 `decide_relations`（confirmModeling 白名单引用该
  集合，自动覆盖，无需改 ChatController）。
- `DataAgentConfig` 建模系统提示词：「逐条提交 decide_relation」改为「一次性用 decide_relations
  批量提交全部候选」。
- `list_modeling_state` 候选队列渲染：每条补业务含义（description）与数据集名（替换裸 datasetId），
  尾注改为「把候选一次性整理成清单，用 decide_relations 批量提交，不要逐条」。

### M3 前端：ModelingHitlCard 批量卡 + 去图

- 新分支 `decide_relations`：清单卡——每行 checkbox（默认勾选）+ `表A.字段 → 表B.字段`（复合键用
  + 连接）+ 基数中文（复用 JOIN_LABEL）+ 含义（note ?? relation.description）；底部「全选/全不选」；
  动作按钮：「采用勾选项（其余暂不处理）」主按钮、「采用勾选项（其余全部否决）」次按钮、跳过。
  提交时按勾选状态改写 relations_json 中每条 action（未勾选 → SKIP 或 REJECT）。
- 单条 `decide_relation` 卡：移除 ReadOnlyRelationGraph 区块（含义/依据/技术详情保留）。

### 排除项

- 不改 agentscope core/harness、SSE 协议帧结构、confirmModeling 端点。
- 不动 write_file/patch_file 的三重闸（specs/023）；本工具走平台结构化直写。
- 不为批量卡做 join_type/列编辑 UI（复杂调整仍走单条卡的「调整关系」）。

## 验收（GWT）

1. Given 3 条 PENDING 候选，agent 调 decide_relations（2 CONFIRM + 1 REJECT）→ 用户勾选 2 条提交 →
   2 条 CONFIRMED 且 relationships.yml 各有一次 upsert、1 条 REJECTED，返回文本含 validate 结论。
2. Given 入参含不存在的 relation_id → 整批拒绝，工程与候选队列零变化，错误文本指出第几条无效。
3. Given 全部不勾选提交 → 全部 SKIP，工程文件与队列零变化。

## 测试

- ModelingToolkitTest：批量混合决策（CONFIRM+REJECT+SKIP）、整批拒绝（无效 id / 列配对不等长）、
  JSON 解析失败、validate 失败仅诊断不回滚、FakeWrenCli 复用。
- 前端：npm run build 通过（批量卡为纯渲染 + 提交前改写 action，无新依赖）。
