# ADR0109：问答对只来自用户确认，基础建模不生成问答

## 状态

已接受，2026-10-20。实施 spec090。补充 ADR0069/0103，取代 MODELING_SCRIPT 中"推荐问题必须写回"的要求，以及问题 SQL 禁止 LIMIT/OFFSET 的旧限制。

## 背景

官方 enrich-context 在 Auto-pilot 下会自行补全问答对：

- 覆盖检查要求 `knowledge/sql/` 中有典型问答对；
- 不逐项暂停确认；
- 通过 `memory store --tags source:enrich` 写入。

平台提示词还要求必须写回，`ModelingHitlMiddleware` 对 `memory store` 自动放行。结果是：Agent 自拟的问题在用户不知情的情况下进入问答库，又被问数召回当作 SQL 模板。

## 决策

- **基础建模关闭问答写入（工具层）：**
  - `OfficialWrenToolkit` 在 execute 和 preflight 中拒绝 `memory store`；
  - `WrenProjectAccess#path` 拒绝写入 `knowledge/sql/`；
  - 提示词要求跳过官方"问答对覆盖"检查项，不自拟样例。
- **用户问题：**
  - 自动建模那一轮结束，且 `ModelingWorkflowStore` 显示编译成功后，页面弹出一次询问，可跳过；
  - 每批最多 5 题，题目 ID 由页面生成；
  - Agent 为每题生成只读 SQL、试运行一次，写入 `knowledge/questions/<ID>.yml`；
  - 写入该目录无需单独的确认卡，最终确认在逐题审阅中完成。
- **逐题确认：**
  - "检查与发布"页挂载 `ModelingQuestionsPanel`；
  - `MdlQuestionService#validate` 在执行前经过 `FactQueryWindowPolicy` 时间门禁；
  - `MdlQuestionStore#requireReadSql` 复用 `OfficialWrenToolkit#requireReadOnly`（只读、禁止笛卡尔积），允许官方 LIMIT/OFFSET；结果截断时仍不能确认。
  - 用户确认后，由 `MdlQuestionStore#decide` 写入 `knowledge/sql/<ID>.md`，带 `tags: [source:user-confirmed]`；放弃或退回时删除该文件。
- **召回：** 沿用现有链路。`copyToStaging` 发布时复制 `knowledge/sql`，`WrenPublishedProject#prepare` 合并发布版与点赞问答，`memory index --no-seed` 建立索引后，`recall_queries` 即可召回。不接入 `filterPublishedExamples`，问题确认也不阻断发布。

## 后果

- 问答库只包含用户确认的问答和问数点赞的回答，召回模板的可信度提高；没有用户问题的知识库，发布后没有建模问答。
- 历史知识库中已存在的 `source:enrich` 问答不做清理。
- 官方技能第 9 步的审计报告可能仍列出"缺少问答对"，这是预期结果。
