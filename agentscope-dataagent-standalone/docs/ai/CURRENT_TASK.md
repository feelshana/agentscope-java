# 当前任务与接手入口

> 只保留当前任务。2026-10-21 及之前的详细检查点见 [archive/CURRENT_TASK-until-20261021.md](archive/CURRENT_TASK-until-20261021.md)。

## 最新检查点：2026-10-25｜建模与问数提速、试问只核对 SQL、数据源限定 MySQL 8.0+/Doris（已提交）

- 已完成：spec093 字段整理；试问不查数据（只 dry-plan，用户核对 SQL）；发布竞态修复与"重新发布"兜底；建模提示词按轮次分段（`ModelingTurnRules`）；两个助手只挂各自技能；查询结果紧凑化；门禁规则优先（ADR0112）；数据源 MySQL 8.0+/Doris 与日期常量（ADR0113）；问数准备提示与按钮置灰；知识库/语义建模页字号与列表改版。
- 实测（flash 模型 2026-10-25）：自动建模 44 秒、问数 8–19 秒、门禁全部规则判断（毫秒级）；关系按命名候选直接提问。
- 本机模型：`qwen/deepseek-v4.1-flash`（仅 `.claude/launch.json`）；`application.yml` 中默认 API Key 的本地修改未提交，建议改为无默认值并轮换密钥。
- 未完成：spec093 验收标准 1–8 正式验收；"报表/仪表盘"口径由用户在建模对话补规则；无 MySQL 5.7/Doris 实例，版本检查只做了单元测试。

## 上一检查点：2026-10-22｜spec092 §7 对话为中心的建模页已实施，待真实验收

- 建模页改为"建模对话｜资料｜语义模型"：左对话、右试问、底部草稿栏；取消审阅与发布页、示例问题弹窗、确认弹窗（未提交）。
  - 后端：`DraftItemService` + `GET/POST /modeling/draft/items`（模型说明、关系、Markdown 条目逐条编辑/删除，`since` 按轮次过滤）；推荐问题改为 3 个；`TemporalSamples` 支持不补零、无分隔符日期与数字日期键。
  - 前端：`TurnResultCard`/`DraftItemsList`（AI 建模结果）、`ConfirmationCards`（需要你确定的部分，助手空闲时出现）、`TrialPanel`（试问，自动试运行，答对设为示例写 knowledge/sql）、`DraftBar`（自动检查、软提示待处理项）；开始建模前提示上传文档，点按钮才开始；上传数据弹窗去掉文档；页面请求按序排队。删除 `DraftReviewPanel`、`ModelingQuestionsPanel`、`AddQuestionsDialog`、`ConfirmationDialog`、`VerifyQuestionsPrompt`。

- 2026-10-22 下午：按原型细化页面（页头一行、工具步骤折叠、建模结果紧凑行与字段表、逐题确定、试问按需打开、草稿栏一行上拉）；修日志问题：确认工具失败不计数并忽略多余"其他"选项、外部表候选关系改为让用户确定、外部表拒绝先于时间门禁、不补零文本时间不按字符串比较范围。

- 2026-10-22 傍晚：时间条件由平台按列格式生成（`TemporalCondition`，撤销 LIKE 提示词）；门禁放行跨请求复用；确认问题归一化去重；规则依据要求；对话自动跟随；"下一步"卡与草稿栏可发布高亮。

## 上一检查点：2026-10-21 晚｜spec092 已实施

### 当前任务

- 建模交互改为"草稿 + 发布前审阅"：[spec092](../specs/092-draft-and-review-modeling-workflow.md) 与 [ADR0111](../adr/0111-draft-changes-reviewed-before-publish.md) 已实施（未提交）。
  - 后端：`DraftChangeLog`、`DraftReviewService`（差异、撤销、放弃）、`BusinessDocumentService`（raw/docs 多文档）、`ModelingActivity`（运行中禁止发布/撤销）、`DraftReviewController`；`ModelingHitlMiddleware` 不再拦截文件写入；`WrenProjectFilesystem#rulesGuard`；`ModelingTurnPurpose` 增加 auto/document；`WrenProjectContextMiddleware#newTables`；workflow `affectedQuestionIds`；模型指纹排除 raw/。
  - 前端：建模页四标签与顶部"审阅并发布（N）"、`DraftReviewPanel`、`BusinessDocumentsPanel`、知识库上传弹窗可附文档；删除 `MdlPublishPanel`、`BusinessDocumentGuide`。
  - 验证：spotless、`mvn test` 751 项 0 失败 20 条件跳过、`npm run build` 通过；真实验收待做（spec092 验收标准 1–10）。
  - 补充（晚）：只问真正需要确认的——`request_user_confirmation` 工具 + `ModelingConfirmationService`（.platform/confirmations，每轮≤3）+ 弹窗 `ConfirmationDialog`，答复以 purpose=confirm 交助手写入规则或列描述；审阅页"本次修改"只展示、可编辑/撤销；左侧"语义建模"入口 `ModelingHubPage`；文档上传即刷新、助手修改实时刷新。`mvn test` 757 项 0 失败。
- 用户已确认的决定（2026-10-21）：
  - 取消逐项确认卡，修改直接进草稿，发布前统一审阅、按文件撤销、可放弃全部草稿；
  - 无业务文档的自动建模不得写 `knowledge/rules/`，推断只进 `knowledge/caveats/`（代码约束）；
  - 多人共用一份草稿，不做分支，审阅显示作者；助手运行中不可发布；
  - 多业务文档：新增、替换（保留历史）、删除，均为草稿修改，上传后自动排队交给助手；问数不直接读文档；
  - 上传数据弹窗可附业务文档；采样后自动建模；已建模库新增表只处理新表；
  - 点踩只记录反馈，不跳转、不生成问答；暂不做问数反馈列表；
  - 不提供"停止建模"按钮；
  - 派生资产（计算字段、视图、ref_sql、Cube）不预先生成，以后从已确认问答沉淀，另立 spec；基础建模继续使用 Wren；不单独做"文档直给 vs Wren"对比评测。

### 已实施、未提交、待真实验收

- spec091：推荐问题预填、问题直接确认、发布时 dry-plan 退回（738→742 项测试通过）。
- 外部表约束：基础建模轮次禁止查询外部表，只有 `purpose=question` 轮次可查；问题轮次禁 MIN/MAX、空结果后换日期重试拒绝。
- 问数：超窗口直接请用户给范围、不提拆分；问答 frontmatter `source: user-confirmed`。
- 前端：确认卡/排队状态提示、问题弹窗在排队请求后才出现（这些在 spec092 实施后会被替换）。
- 服务 17:26 重启；最近日志归档在 `logs/archive-20261021-run6-stuck/`。

### 未完成 / 下一步

1. 按 spec092 验收标准 1–10 真实验收；旧单文档接口 `PUT /dataset-groups/{id}/knowledge` 已无前端使用，待确认后删除。
2. 问数侧是否去掉 Cube 工具（`list_cubes` 等）待用户决定。
3. 业务口径待定："报表"是否仅指 `object_type='报表'`。
4. Docker（Mihomo TUN）待用户处理，`run_python` 沙箱不可用。
5. 首次"整理文档"时：压缩 ARCHITECTURE 5.6；给与当前规则冲突的旧 ADR 补状态行。
6. 所有修改尚未提交 Git。
