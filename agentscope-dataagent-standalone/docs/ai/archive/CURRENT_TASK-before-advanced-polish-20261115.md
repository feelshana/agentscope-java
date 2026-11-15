# 当前任务：资料页专项视觉优化

2026-11-15 Asia/Shanghai；codex/architecture-phase1，基线d0f8cad5。按用户截图继续spec140，优化资料页；不提交/push/部署，不改后端或AI助手对话。

## 本轮完成

- 数据表/业务文档宽屏并列、窄屏堆叠；原右侧大说明卡收为底部草稿提示和原问数优化入口。两卡不人为拉齐高度，压缩重复说明和留白。
- 数据卡显示真实纳入/总字段数和全部纳入/未纳入，统一字段按钮，长名称可换行；保留助手忙碌禁用与原字段回调。
- 文档标题计数、状态软底、真实字符数、既有历史版本；名称和阅读按钮仍开原只读正文。替换/删除用带名称/title的次级图标，setup保留原文字动作。
- 加载/失败重试/空态和已删除草稿行保留；没有自动摘要、文档已吸收统计或新API/依赖/存储。上传/替换/删除、正文读取、发布和权限边界不变。
- spec140、ARCHITECTURE、DECISIONS、SESSION_LOG与报告同步；前一检查点存archive/CURRENT_TASK-before-materials-polish-20261115.md。

## 本轮验证

- 相关五文件ROOT前缀21项通过：business-document-reader、semantic-workbench、guided-tour、onboarding、onboarding-actions。本轮不重复三前缀或前轮SQL测试。
- 真实IAB完整工作台1920/1280/850/390宽，body无横溢出；前两种并列，后两种堆叠。真实4表/2文档首屏可同时看到（1920）。
- 原文档名称/阅读按钮、手机阅读正文/目录/表格、字段入口关闭不保存、数据管理/口径检查跳转通过。未在实际知识库做写操作或发布。
- 独立Playwright全部业务API拦截：部分字段/字符数、字段回调/忙碌、阅读/按需读取、数据/口径回调、长名称手机、空数据、文档失败重试/空态、替换状态/回调、删除目标/阅读入口、setup原按钮通过。写操作均为夹具。
- 一次夹具重截因HMR重置测试控件而等错标题超时，按新快照修正重跑通过；详见报告。非产品异常，正式代码无测试控件。
- TypeScript/Vite构建通过，静态资源更新、既有主包体积提示保留；DocsConsistencyTest 5项通过，空白检查通过。未跑完整clean verify或真实LLM/Wren/提取/发布全链路。

## 累计已完成与状态

- 前轮问数优化默认概况、四步与试问并排、列表头集中操作/六状态色、已确认问答阅读/编辑分流、首步去添加；公共视图圆环轨道；右栏详/AI/✓；顶部三段布局和下划线页签均保留。
- 前轮三页落地、长SQL排版、弹窗居中、文档只读阅读与底栏完整窄屏修复仍保留，所有实施为未提交状态；历史验证见本轮报告和archive。
- 本轮只改ModelingMaterials、BusinessDocumentsPanel及ModelingOptimization.css。没有后端/API/DB/权限/Agent或发布改造，无ADR或SQL迁移。
- 用户原5173 Vite/8080 IDEA服务保留；临时挂载入口移除、独立浏览器关闭、IAB覆盖重置，证据在忽略的.runtime/modeling-tabs-implementation。
- 外部full设计稿、ui-baseline.md、method-flow-20261115.html保留，不覆盖或作为本任务成果。

## 下一步

用户刷新资料页复核。未获自动提交或部署授权，保持本地修改。
