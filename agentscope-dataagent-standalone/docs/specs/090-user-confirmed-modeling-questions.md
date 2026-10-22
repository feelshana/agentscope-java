# spec090：基础建模不生成问答对，用户问题逐题确认后保存

> 状态：已实施并验收（2026-10-20）；第 2–4 节交互已由 [spec091](091-modeling-interaction-flow.md) 调整。实施时追加 ADR，补充 ADR0069/0103，取代 MODELING_SCRIPT 中"推荐问题写回"的要求。

## 背景与目标

官方 enrich-context 在 Auto-pilot 模式下会自行补全问答对：

- 覆盖检查要求 `knowledge/sql/` 中"至少有几条典型 NL→SQL 问答对"（SKILL.md 第 198 行）；
- Auto-pilot 不逐项暂停确认（第 268–275 行）；
- 问答通过 `memory store --tags source:enrich` 写入（第 286 行）。

平台的 `MODELING_SCRIPT` 还明确要求写回问答。此外，`ModelingHitlMiddleware#isMemoryStore` 对保存操作自动放行。结果是：Agent 自拟的问题在用户不知情的情况下进入问答库，又被问数召回当作 SQL 模板，口径偏差会持续扩散。

目标：

- 问答库只接收用户提出、并逐题确认的问题与 SQL，以及问数中点赞的回答（`AnswerQueryMemory`，不变）。
- 基础建模阶段，Agent 在工具层就无法写入问答。

## 方案概述

### 1. 基础建模关闭问答写入（工具层硬约束，与 ADR0106/0107 同一模式）

- `OfficialWrenToolkit` 在建模中拒绝 `memory store`，返回"基础语义建模不生成问答对，问答由用户问题确认流程保存"。
- `WrenProjectAccess#path` 将 `knowledge/sql/` 设为不可写（可读）。
- `MODELING_SCRIPT` 删除"推荐问题写回"要求，改为：跳过官方覆盖检查中的问答对项；最终总结写明"问答对待用户在问题确认流程中补充"。
- `ModelingHitlMiddleware` 中 `memory store` 的自动放行随之失效（Agent 已无法调用），保留代码兼容。

### 2. 基础建模完成后询问用户

- 判断依据：自动建模那一轮结束，且 `ModelingWorkflowStore#current(groupId)` 显示最近一次 `context build` 成功。
- `SemanticModelingPage` 弹出对话框："是否填写需要验证的业务问题？（可跳过，不影响发布）"。
  - 跳过：直接显示发布引导。
  - 填写：复用 `QuestionIntakeForm`，一次最多 5 题（用户确认），为单轮 10 条查询上限留出修正余量。

### 3. 逐题生成 SQL 与验证

- 提交后发起一轮建模对话。Agent 对每题阅读模型和规则，生成一条只读 SQL，用 `wren_cli query` 实际执行（受时间门禁、笛卡尔积禁止和单轮查询上限约束）。
- Agent 用 `edit_file`/`write_file` 写入 `knowledge/questions/<id>.yml`（沿用 `MdlQuestionStore.Question` 格式：question、definition、sql），不调用 `memory store`。
- 平台通过 `MdlQuestionService#validate` 执行 SQL 并保存执行回执，得到状态 EXECUTED。实施时核对 `MdlPublishService#validateQuestion` 是否针对草稿工程执行；如果只能对已发布版本执行，改为经草稿 CLI 执行，并沿用相同的门禁。

### 4. 用户逐题确认

- `SemanticModelingPage` 挂载已有的 `ModelingQuestionsPanel`，每题展示：问题、Agent 对口径的说明、SQL、结果预览（前 N 行和行数）、验证状态。
- 用户可以：
  - **确认保存**：调用 `decide(accepted=true)`，状态变为 CONFIRMED，平台服务以 `memory store --nl --sql --tags source:user-confirmed` 写入草稿 `knowledge/sql/`；
  - **修改 SQL**：调用 `correctSql` 后重新 `validate`；
  - **放弃**：调用 `archive`。
- 保存只发生在平台服务中，而且只针对 CONFIRMED 的题目。

### 5. 发布与召回（已确认问答必须能被问数召回）

现有召回链路（已核对代码）：

1. 草稿 `knowledge/sql/*.md` 发布时随快照复制（`MdlWorkspaceService#isPublishExcluded` 只排除 `.platform/`、`target/`）。
2. 问数时，`WrenPublishedProject#prepare` 把发布版 `knowledge/sql` 与点赞目录 `runtime/liked-examples/knowledge/sql` 合并到运行工程。
3. `memory index --no-seed` 对 MDL 和全部 `knowledge/sql` 问答建立向量索引，`recall_queries` 从该索引召回。

因此，确认后由平台用官方 `memory store` 写入草稿 `knowledge/sql/`，**页面显式发布后**即可被问数召回，无需新增召回路径。设计约束如下：

- **不接入 `filterPublishedExamples`。** 它按 `<questionId>.md` 文件名保留文件，而官方 `memory store` 按问题文本生成文件名，接入会在发布时删掉已确认的问答。基础建模关闭写入后，`knowledge/sql/` 只剩平台确认写入一个来源，不需要发布时过滤。
- **问题与问答文件关联。** 确认时把 `memory store` 输出的 `Stored: <路径>` 记入该题回执。之后修改 SQL 再确认，或放弃已确认的题目时，先删除关联文件再重写，避免同一问题残留旧 SQL 或生成 `-2` 后缀的重复文件。
- 问题确认不是发布前置条件（保持现有边界），`publishIssues` 不接入发布阻断。
- 建模页提示："已确认问答需发布后才能在问数中被召回"。

## 影响面

- **后端：** OfficialWrenToolkit、WrenProjectAccess、DataAgentConfig（MODELING_SCRIPT）、MdlQuestionService/MdlQuestionStore（确认后由平台调用官方 `memory store` 保存，回执记录文件路径，修改或放弃时清理）。召回链路（MdlWorkspaceService 发布复制、WrenPublishedProject 合并与索引）不改。不改数据库表，不新增配置项。
- **前端：** SemanticModelingPage（建模完成弹窗、挂载审阅面板）、QuestionIntakeForm（提交消息改为"生成 SQL 并写入 knowledge/questions，不保存"）、ModelingQuestionsPanel（结果预览）。
- **文档：** 新增 ADR；同步 ARCHITECTURE_zh 建模章节、AGENTS 当前边界、README、共享记忆。

## 验收标准

1. Given 基础建模进行中，When Agent 调用 `memory store` 或写入 `knowledge/sql/`，Then 被拒绝且不执行，日志中无 `source:enrich` 新文件。
2. Given 自动建模结束且编译成功，When 页面刷新，Then 弹出"是否填写业务问题"；选择跳过后可直接发布。
3. Given 用户填写 3 个问题，When Agent 处理完，Then `knowledge/questions/` 中有 3 条记录，每条都有 SQL 和执行回执，查询总数不超过上限，`knowledge/sql/` 未新增文件。
4. Given 用户确认其中 2 题、放弃 1 题，Then `knowledge/sql/` 新增 2 个 `source:user-confirmed` 文件，每题回执都记录了对应文件路径。
5. Given 已确认 2 题并在页面发布，When 在问数中提出与其中一题相同或相近的问题，Then `recall_queries` 返回该题的问题与 SQL；被放弃的题目不会被召回。召回需有自动化测试覆盖：用真实官方 CLI 的集成测试，或模拟 `prepare` 合并与索引输入的单元测试，覆盖"确认→发布→运行工程 `knowledge/sql` 包含该文件"。
6. Given 某题已确认后修改 SQL 并重新确认，Then `knowledge/sql/` 中该题只有一个文件，且内容为新 SQL；放弃已确认的题目后，其文件被删除，重新发布后不再被召回。
7. Given 用户修改某题 SQL，Then 必须重新验证成功才能确认；未确认的题目不会写入 `knowledge/sql/`。
8. 现有测试与 `mvn spotless:check`、`mvn test`、`mvn package -DskipTests`、`npm run build` 全部通过；真实建模和问数日志验收另行记录。

## 不做的事

- 不修改官方 skill 文件，不改问数侧点赞保存逻辑。
- 不清理历史知识库中已有的 `source:enrich` 问答（只考虑通用流程）。
- 不实施"基础建模改为平台确定性生成"（另行评估）。
- 不让问题确认阻断发布，不新增数据库字段或配置项。

## 已确认决定

1. 每批最多 5 题。
2. 已确认问答标签为 `source:user-confirmed`。
3. 弹窗只在自动建模结束后出现一次，之后从页面按钮进入。
