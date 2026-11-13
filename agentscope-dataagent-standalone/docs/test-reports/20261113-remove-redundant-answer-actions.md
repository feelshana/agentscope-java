# spec138：撤回重复回答入口

2026-11-13 Asia/Shanghai；codex/architecture-phase1@bfb202b8上的本地修改，未提交/push/合并/部署。

## 结果与范围

用户复核认为本轮查询记录重复已有知识库与执行详情，统一修改问题入口不能区分服务故障和主动停止。已撤除ChatPanel中的两个入口及CitationPanel、QuestionRecovery、answerQueryRecords、专用CSS及对应测试；不恢复旧来源摘要。原TaskTrace/ToolInspector、错误说明、中断状态、输入草稿、知识库标签、CSV/Python/图表、分析和反馈路径保留。

删除Wren成功回执中仅为面板新增的queryId/queriedAt；保留缓存完整回执和文件引用的独立修正，不改逐次授权、失败不缓存和执行范围。已有历史回执不迁移，原解析器忽略额外元数据。

没有直接revert整个bfb202b8，因为该提交另含架构运行验收资料、独立建模HTML和缓存修正。此前架构治理提交不受影响。工作区起点已有semantic-modeling-20261113-p1.html修改，本轮未触碰；前后SHA256均为E25A1B88537E4DB48CD4C9CBF014DB07330EE84056B767C0BAE43785B137B4E1。

## 本轮验证

- python scripts/check.py backend --tests WrenScopedMcpClientTest,AnswerQueryMemoryTest,DocsConsistencyTest：33项，0失败/错误/跳过。覆盖缓存完整回执与文件、一次执行、失败可重试、撤权拒绝、问答记忆及文档。
- python scripts/check.py frontend --files tool-inspector.test.mjs wren-presentation.test.mjs answer-display.test.mjs tool-call-status.test.mjs conversation-polish.test.mjs python-answer-images.test.mjs：根前缀34项通过，覆盖原SQL/结果解析、历史及失败展示、图片引用。
- npm run build：TypeScript/Vite通过，保留既有大chunk提示。提交内静态产物已重建。
- 已搜索生产源码与测试，删除模块均无残留引用。保留的AnswerQueryMemory#queryId是原问答记忆键，与本轮删除的回执随机编号无关。

Spotless/最终DocsConsistencyTest及git diff --check通过。未启动服务、浏览器验收、执行真实LLM/Docker/Wren外部集成，也未运行完整clean verify；本地撤回选测不替代合并/发布前完整验证。无DDL、新接口、依赖或数据写入。
