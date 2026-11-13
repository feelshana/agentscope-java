# 当前任务：spec138 撤回重复回答入口

2026-11-13 Asia/Shanghai；codex/architecture-phase1，起点bfb202b8。用户认为查询记录与失败问题回填价值不足，授权评估后撤回。本地既有semantic-modeling-20261113-p1.html修改保留，不提交/push/合并/部署或启动服务。

## 评估与修改

查询记录重复知识库与工具详情，未形成结论证据链；失败/主动停止统一引导改问题缺少明确原因。spec138取代spec134的展示方案，无新ADR、DDL、HTTP接口或依赖。

删除CitationPanel、QuestionRecovery、answerQueryRecords及专用样式/测试与ChatPanel入口，不恢复旧来源摘要。移除仅为面板新增的queryId/queriedAt；保留完整成功回执/文件引用缓存、逐次授权、失败不缓存。原工具详情、输入草稿、错误/停止状态、知识库标签、下载、图表、分析与反馈保持。

## 验证

代码修改完成；本轮前端六个相关文件34项、后端WrenScopedMcpClientTest/AnswerQueryMemoryTest/DocsConsistencyTest共33项（0失败/错误/跳过）、TypeScript/Vite构建通过。Spotless与最终DocsConsistencyTest通过；未启动服务、重跑真实LLM或生产环境验收。此前功能与架构验收记录见[上一任务归档](archive/CURRENT_TASK-before-spec138-20261113.md)。

## 下一步

选测和前端构建已完成，详见[本轮报告](../test-reports/20261113-remove-redundant-answer-actions.md)；最终格式/文档核对通过，保持本地未提交。后续功能先确认具体用户痛点、与已有入口的差异和成功标准，候选方向不自动实施。

用户后续指出少量用户/会话阶段无需优先历史整理；本轮官方资料再调研及阶段排序已追加功能方向主记录。建议先以真实复杂业务题验证现有Agent到图文交付的缺口，再讨论F02/F03/F06、F05与F07；尚未授权新功能实施。
