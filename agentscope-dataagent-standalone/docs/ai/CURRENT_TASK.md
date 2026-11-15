# 当前任务：问数优化、公共视图、资料 HTML 设计

2026-11-15 Asia/Shanghai；codex/architecture-phase1。用户暂缓对话框，要求先给这三个页面的 HTML 效果。只做设计稿，不改产品、不提交/push/部署；无新spec/ADR/ARCHITECTURE要求。

## 已完成

- 新增 docs/design/semantic-modeling-optimization-20261115.html 和同名 .md；顶部三页可切换，支持有内容/空态/助手忙/首次发布前/加载失败。
- 问数优化：收紧说明，左侧问题列表/筛选/勾选，右侧原四步；保留口径、方案、SQL、确认待发布、推荐问题、MDL/规则查看；只核对SQL不查询数据。
- 公共视图：自动整理仍建设中，规划折叠，手动起草与已有资产为主；详情分用途核查/文件/问题验证，人工判定，不自动发布。保存文件重置核查验证。
- 资料：数据表字段纳入范围与业务文档分区；字段入口保留p1，文档状态/历史版本/替换删除进草稿，不虚构读入规则统计。
- 用户追加的两个四步流程已保留：问数优化在页头下，公共视图在规划区域全宽展开，默认展开可收起；均不是实时进度。
- 文档名称/阅读按钮打开右侧只读阅读层，目录/查找/字号/原文。预置Word为提取正文示例；新选.md/.txt读取本地真实文本，新.docx不解析且明确正文尚未载入，不伪造内容。
- 除本地文本读取外均为页面内存夹具，无网络请求，不调用真实助手/上传/SQL/发布。对话框本轮不改。

## 验证与限制

- 基础稿Node/PostCSS和48项离线检查已通过；本次增量Node/PostCSS、49项离线逻辑通过，43静态ID/22模板检查通过，无远程依赖。
- 正文接口getDocumentContent已存在（MANAGE），正式落地可复用，本轮未接真实API，不新增DOCX解析器。
- 先前浏览器明确拒绝此设计任务的file:预览并禁止绕过，本轮未绕过；未做真实浏览器视觉、焦点、滚动、窄屏验收。CSS适配不当作已渲染验收。
- full参考本轮只读。开工91D2C0...972257，途中由其他本地工作更新为F8DDDECFA096CB481EC0CE93F7144C28AEAC07D4555CA7393AFA65894226DCF9，保留，不覆盖。
- 基础稿DocsConsistencyTest5项通过；本次增量DocsConsistencyTest5项、空白与篇幅检查也通过。未做产品构建/真实LLM/Wren，不冒充生产验收。

## 待续与既有修改

- 用户评审新HTML后再定实施范围。真实接口、权限与确认协议仍复用当前实现，原型模拟逻辑不得替代后端。
- 对话UI仍待续，先前检查点归档 archive/CURRENT_TASK-before-modeling-tabs-design-20261115.md；更早p1代码/真实后端验证见 archive/CURRENT_TASK-before-assistant-design-20261114.md、spec139及其测试报告。
- 开工即有 ModelingChatPanel/ModelingHitlCard/ConfirmationCards/NextStepCard/TurnResultCard 和 modeling-chat.css 等本地修改，以及未跟踪modeling-chat-ui.html；均非本轮成果，不回滚、不提交。
- 本次仅新设计稿/说明/共享记忆，保留其他工作，未启停服务。
