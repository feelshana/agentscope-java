# 仪表盘模块：会话与后续优化评估

2026-11-17。状态：三处明确UI要求已实施；其余为基于当前源码的产品/工程评估，不是已批准的新功能。沿用spec145，无新ADR/接口/会话存储或权限变更。

## 本轮已经处理

普通用户气泡移除“你”，正文/诊断附件提示保留，自动修复消息仍注明来源。顶栏改为语义Link“仪表盘 › 当前名称”，点击仪表盘回列表，长名称省略但状态保留。PRODUCT_ICONS.chart新增线框/实心，现有收起/展开导航的active逻辑不变；默认非选中图标保留原轮廓。输入补name/autoComplete，保持外层焦点反馈。

## 是否需要新会话

建议当前保持“每个用户在每张看板上持续编辑”的设计。看板是一件持续修改的作品，需要保留指标口径、时间范围和前次修改的来由。恢复历史是有意的；不等于把其他看板、问数或其他用户的聊天混入本轮。

证据：DashboardsPage的currentSession/turns与发送均使用看板ID；ChatTurnPreparation#resolveGroupScope用该conversationId找到服务器资产并固定知识库。原生会话查询还包含当前用户与Agent，用户各自恢复记录。因此直接复制问数的随机新conversationId按钮会与当前资产绑定契约冲突，不能只清掉UI列表而保留旧模型上下文。

操作区分：同一作品加指标/改布局，继续现有对话；独立主题，使用新建看板。若真实体验证明长会话影响质量，再设计“开启新一轮创作”：保留看板与历史，创建独立会话轮次，携带当前草稿及已确认口径，并可回看旧轮次。不是删除聊天，也不作为当前常驻按钮。本轮不实施此能力。

## 当前版式可稳定，后续优先可靠性

现有选库/创建、信息维护、范围/状态/排序/视图、继承授权、删除清理、独立查询、草稿/显式发布、面板重试与单次修复已经形成完整体验路径，建议保留，不反复改导航或继续堆常驻入口。后续建议按下列顺序单独立项验收。

### 1. 生成任务恢复与离开反馈（高优先级）

`frontend/src/pages/DashboardsPage.tsx:91` — 卸载时中断当前AbortController，busy/阶段是本地状态；历史恢复不等于任务进度恢复。长任务应有原生运行任务身份、真实状态与可恢复订阅，刷新后可判断继续/完成/失败；交互需要明确离开行为和未发送输入的保存策略。复用Harness运行/取消机制，不新造Agent循环。目前不宣称后台一直生成。

### 2. 多人编辑冲突与历史版本（高优先级）

`src/main/java/io/agentscope/dataagent/dataset/DashboardService.java:421` — withWorkspace的资产锁约束单次工具动作，不能据此认为整轮多用户生成已互斥；publish/restore有expectedRevision，文件修改仍应做跨用户同时生成、过期read后写入的并发验收。建议明确整轮编辑占用或修订冲突拒绝，提示谁的修改尚未完成；此处为源码推断，未构造丢失更新复现，不能声称已有线上故障。

`frontend/src/pages/DashboardsPage.tsx:51` — 当前“本轮撤销”的指针在组件内存中，服务端快照有界保存；刷新后缺少历史快照选择入口。下一步可将草稿/发布版本及恢复入口做成真实版本历史，按授权和期望修订操作，保留聊天来由。未实施版本列表/对比或发布回滚UI。

### 3. 发布前的代表性运行验收（高优先级）

`frontend/src/components/dashboardBridgeRuntime.ts:56` — chart自检能发现零尺寸/缺Canvas或SVG，但有绘制元素不保证系列有值或所有筛选有效。建议在默认条件及代表性筛选下执行查询/渲染检查，覆盖空态/异常/日期边界，提供明确报告，并人工核对关键指标口径。

`src/main/java/io/agentscope/dataagent/dataset/DashboardService.java:483` — 正式发布会重做官方文件检查与权限/修订复核，当前没有后台浏览器验收服务。浏览器自报状态不能替代服务端校验，也不能宣称数字口径全正确；新增自动验收应独立设计，不把前端“通过”直接当可信权限条件。

## 界面检查与验证

按[Web Interface Guidelines](https://raw.githubusercontent.com/vercel-labs/web-interface-guidelines/main/command.md)核查本轮导航、焦点、语义控件、长文本/空态和减少动效。明确范围内已采用Link导航、aria-current/标签、可见键盘焦点、长名称min-width/ellipsis及reduced-motion；没有新增阻断UI问题。上述后续事项是当前实现的能力边界及建议，不将通用指南当产品功能授权。

本轮前端相关四文件21项，三个路径前缀分别通过；TypeScript/Vite构建通过，静态资源同步，既有包体积提示保留。Chrome隔离接口验证用户消息无身份标签、面包屑返回、选中/未选中及展开/收起图标、历史恢复后无新模型调用、850/390/320无横溢。1次受控SSE/1检查点，意外请求/JS错误0；并未验证真实知识库、多人并发或后台任务恢复。

已查看desktop/mobile及outline/solid截图，output/playwright/dashboard-detail-polish-*.png。本轮不重跑真实LLM/Wren/全后端或完整发布门禁，既有未提交改动保留，不提交/推送/部署。共享记录后DocsConsistencyTest 5项及空白检查通过，失败/错误/跳过0；临时QA浏览器已关闭，用户IDEA/Vite保留。
