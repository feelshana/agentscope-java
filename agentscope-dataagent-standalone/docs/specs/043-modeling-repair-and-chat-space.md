# spec 043：建模提案修正与对话空间

## 背景与目标

解决问题提案预检失败后反复“继续”却无法前进的问题，并让对话成为建模工作区的主要内容。保留文档优先入口与上传反馈。

## 方案概述

确认卡在预检失败时显示“让助手修正”，经原有确认 API 拒绝提案并附上错误。原生拒绝完成后，中间件将反馈加入该次会话的推理上下文。完善问题 SQL 与数据事实提示。文档状态栏压缩为两行，全文 dialog 查看；进度栏 300px，助手回复宽度 100%。同步架构第 11 章。

## 影响面

- 后端：ChatController、ModelingHitlMiddleware、DataAgentConfig、ModelingToolkit。
- 前端：chat API、ModelingHitlCard、ModelingChatPanel、BusinessDocumentGuide、SemanticModelingPage。
- 数据：无迁移，不修改用户既有问题、模型或待确认提案。
- 文档：新增 ADR、spec 与验证记录；更新架构。

## 验收标准

1. Given SQL 含 LIMIT，When 预检失败，Then 采用禁用且可要求助手修正，反馈包含真实错误。
2. Given 点击修正，When 原生拒绝完成，Then 同一会话助手收到错误，不执行被拒写操作；后续提案仍需确认。恢复流仅返回 done 时，页面回读原生会话的 ASKING 状态展示新卡片。
3. Given feedback 越限或批准同时附反馈，When 请求，Then 400；无反馈拒绝保持兼容；其他会话不出现反馈。
4. Given 已上传文档，When 查看全文，Then 以 dialog 展示，关闭后聊天高度不变；上传状态仍可见。
5. Given 1440×900 工作区，Then 文档状态栏常态不超过 90px，对话可用区域明显增加；窄屏无横向溢出。

## 不做的事

- 不自动确认用户提案、不自动改写用户 SQL、不放宽 SQL 或租户门禁，不另实现 ReAct/HITL。

## 测试要求

覆盖原生拒绝后的反馈注入、一次性与调用隔离、反馈参数校验、TopN SQL 门禁。浏览器验证失败卡片与文档弹窗、桌面和窄屏布局；完整后端测试、打包、前端构建。

## 关联

- [ADR 0052](../adr/0052-modeling-preview-repair-feedback.md)
- [ADR 0053](../adr/0053-native-hitl-resolved-call-boundary.md)：已有非 suspended 原生工具结果的调用不再次暂停、不恢复为待确认卡。
