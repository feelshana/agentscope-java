# 当前任务与接手入口

> 只保留当前任务。2026-10-21 及之前的详细检查点见 [archive/CURRENT_TASK-until-20261021.md](archive/CURRENT_TASK-until-20261021.md)。

## 最新检查点：2026-10-29｜spec099 新手引导已实施（未提交）

- 后端：`OnboardingService`/`OnboardingController`（`/api/onboarding`，只操作当前用户）；`dataagent_user` 增加 `onboarding_*` 五列（迁移 0098，含完成时间：完成当天保留完成卡片）；`ChatController#recordOnboarding` 记录"问数/分析"里程碑。
- 前端：`api/onboarding.ts`（按令牌缓存）、`components/Onboarding.tsx`（清单、起步问题、空状态、侧栏折叠入口、帮助面板、一次性提示）；接入 ChatPanel 首页、SessionsSidebar、AppShell、ModelingHubPage；知识库页 `?create=1`/`?datasource=1` 深链；数据源连接失败提示改用上传。
- 验证：后端新增测试 13 项通过；浏览器验收 admin（2/4→问数 3/4→确认分析 4/4、折叠入口、帮助面板、两个提示及"知道了"持久化）与新测试账号（0/4 完整清单、两种准备方式深链、连接失败提示、不再显示后空状态、建模页空状态）。
- 测试残留：本地库新增两个测试账号 `onboard-test-*`（其一创建时密码字段错误无法登录）；admin 的引导开始时间被改早一天用于验收折叠态。
- 未完成：spec099 移入 done 待用户确认；提交时排除 `application.yml` 本地密钥改动。

## 上一检查点：2026-10-29｜spec096–098（已提交 dfa71bef 并推送）

- 未完成：启动 Docker 后按 spec097 验收 1–4（出图与报告）；平台库执行 `docs/migrations/0097`（ddl-auto 关闭的环境）。

## 上一检查点：2026-10-29｜spec095 业务术语、caveats 停用（已提交 9ec35a3b 并推送）

- 未完成：spec095 真实验收；平台库执行 `docs/migrations/0096` 删表。

## 上一检查点：2026-10-26｜问数联网搜索（spec094、ADR0114）与建模提速（未提交）

- 已完成：问数助手新增 `web_search`（DeepSeek 官方搜索 API，`WebSearchTool`），只发搜索词；问数与建模移除 harness 的 `web_search`/`web_fetch`；问数提示词"联网搜索"一节（外部问题不走问数流程）；前端"联网搜索"标签。另含 10-25 提交后的建模提速：工程现状注入、跳过官方预检、语言提醒、建模压缩阈值 240 条/8 万 token、MCP 启动类加载器修复。
- 验证：spotless、`mvn test` 804 项 0 失败 20 跳过、package、前端 build 与 19 项测试通过。
- 未完成：用户在 `.claude/launch.json` 配置 `DEEPSEEK_API_KEY` 后重启，按 spec094 三问真实验收；提交时排除 `application.yml` 本地密钥改动；E2E 测试知识库（E2E-A/B）是否删除待用户答复。

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
  - 无业务文档的自动建模不得写 `knowledge/rules/`（代码约束）；`knowledge/caveats/` 已停用（ADR0116），数据事实写表/列描述，其余推断只在回复列出；
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
