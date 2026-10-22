# spec 020: 视图 schema 发现对齐（prompt 注入 statement + describe 错路由引导）

> 状态：已实施（2026-10-04）。诊断实证见 2026-10-04 LLM.log（知识库 8addb559，v5/v6 会话）。

## 背景与目标

问数注入端在 `[KNOWLEDGE_BASE_OVERVIEW]`「语义视图」小节只渲染视图名+描述（无列结构），问数
LLM 想看视图字段时唯一的确定性入口是 `WrenToolkit#wrenDescribeModel`，而其白名单只含逻辑模型
（与上游 WrenAI `mcp_server.py#describe_model` 行为一致）。实证结果：LLM 对已发布视图
「用户活跃度分层」反复 describe 全部报「未知或无权访问的已发布逻辑模型」（错误不带名字、不引导
正确工具），替代路径（从基础模型写 SQL）又被视图守卫拦截，形成死锁。上游 wrenai 不死锁的原因是
另有三条视图暴露通道（`describe_schema` 输出视图 statement 全文、memory 按 view 类型索引、
工作流 prompt 显式分工）；平台采用 push 模式预注入，必须把等价信息送进 prompt。

目标：① 视图的 schema（statement）直接进注入，LLM 免 describe 即可写 `SELECT ... FROM 视图`；
② describe 误传视图/Cube 名时一步给出正确工具路由，消除重试循环。

## 方案概述

1. 注入端 `DatasetService#semanticViewsText`：每个视图在名称+描述之下追加一行
   `SQL: <statement>`（空白折叠为单行、超长截断加省略号），对齐上游
   `memory/schema_indexer.py#_describe_view`（SQL 原文即 schema 文本）的做法。
2. 工具端 `WrenToolkit#wrenDescribeModel`：model_names 校验失败时按命中资产类型分支——
   命中已发布 views → 报「X 是已发布视图而非逻辑模型，请直接用 wren_run_sql 按视图名查询」；
   命中已发布 cubes → 报「X 是 Cube 而非逻辑模型，请用 wren_query_cube 查询」；
   均未命中 → 维持原「未知或无权访问的已发布逻辑模型」。错误信息带命中名字（名字来自调用方
   入参，无租户泄漏面）。整批拒绝语义不变（第一个坏名即拒绝）。

两处均消费既有数据（工程 `views/` 文件的 statement、published 快照的 views/cubes 数组），
无新 IO、无 schema/存储变更。同步 ARCHITECTURE_zh.md 4.6 工具表与 5.6「语义视图」小节。

## 影响面

- 后端：`WrenToolkit`（describe 校验分支 + 错误文案）、`DatasetService#semanticViewsText`
  （视图行渲染）与新增静态渲染辅助方法
- 前端：无
- 数据：无
- 文档：`ARCHITECTURE_zh.md`（工具表、语义视图小节）、`docs/adr/0034`

## 验收标准（Given-When-Then）

1. Given 已发布且含视图、Cube 的知识库，When describe 传视图名，Then 返回错误且文案含
   「已发布视图」与 `wren_run_sql` 引导，不含「未知或无权访问」。
2. Given 同上，When describe 传 Cube 名，Then 文案含「Cube」与 `wren_query_cube` 引导。
3. Given 同上，When describe 传完全未知的名字，Then 返回与现状一致的
   「error: 未知或无权访问的已发布逻辑模型」。
4. Given describe 传混合批次（合法模型名 + 视图名），Then 整批拒绝且错误指出视图名并给路由
   引导（不发生部分成功的误解）。
5. Given 组工作区存在视图文件，When `semanticViewsText` 渲染，Then 每个视图名称与描述之下
   出现折叠为单行的 `SQL:` 行，内容来自视图 statement。
6. Given statement 含换行/缩进/超长，Then 折叠为单行且超出上限时截断加「…」，不破坏
   注入小节的逐行列表格式。
7. Given `onlyGroups` 为空或指向他组，Then 不渲染任何视图（既有租户边界回归不变）。

## 不做的事（明确排除项）

- 不扩 `wren_describe_model` 支持视图/Cube 的正向描述（上游同样不做；视图无独立列清单可描述，
  解析 SQL 推断输出列有编造风险）。
- 不新增 `wren_describe_views` 之类的新工具（statement 注入后冗余）。
- 不改视图守卫（specs/018、ADR 0032 语义保持）。
- 不处理「DRAFT 视图被注入但 run_sql 查不到」的发布前窗口期（`semanticViewsText` 读工程文件
  含草稿是 specs/019 §7 的既定决策，如需收紧另立 spec）。
- 不动 `[DATA_SOURCES_OVERVIEW]` 与建模侧任何流程。

## 测试要求

- `WrenToolkitTest`：新增「视图名引导 / Cube 名引导 / 混合批次指名引导」用例（未知名维持原文案
  由既有用例锁定）。
- 视图渲染：提取静态渲染辅助方法并新建轻量测试类，覆盖折叠、截断、空 statement 跳过。
- `DataDynamicContextMiddlewareTest`：语义视图小节 mock 样例补 SQL 行断言，锁住「注入含
  statement」契约。

## 关联

- ADR：0034（视图 schema 走 prompt 注入而非 describe 扩权，describe 错路由给引导文案）
- 上游参考：WrenAI `core/wren/src/wren/mcp_server.py#describe_model`、
  `memory/schema_indexer.py#_describe_view`/`_view_record`
- 诊断记录：2026-10-04 LLM.log describe 三连败 + run_sql 被视图守卫拦截（知识库 8addb559）
