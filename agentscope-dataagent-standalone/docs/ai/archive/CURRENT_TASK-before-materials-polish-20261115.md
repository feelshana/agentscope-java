# 当前任务：添加问题引导、原右栏与顶部样式收敛

2026-11-15 Asia/Shanghai；codex/architecture-phase1，基线d0f8cad5。本轮按用户最新四图/五点继续spec140，不提交/push/部署，不改AI助手对话框或后端。

## 本轮增量

- 首步新增浅紫“去添加”，列表头添加改紫底白字，与页头同开原弹窗；已确认问答70px入口、浅紫书本图标、标题层级、小尺寸紫色展开/收起，保留原生summary键盘语义和懒加载。
- 公共视图改22px圆环/细轨道/无竖分割，移除内嵌灰卡；问数实心编号保留。右栏恢复详/AI/✓与原选中软底，取代前轮SVG方案。
- 用户图4为顶部，实施左侧返回/库名/版本状态、居中下划线四页签、右侧分段布局控件，窄屏分行；底部草稿栏保留。返回仍过原离开校验，无权限/业务变化。
- 本轮实际IAB完整页1920/850/390宽，三处添加弹窗、问答点击/Enter开合、语义模型/并排/详情切换、返回入口通过；body无横溢出，手机公共视图两列。未写实际业务数据，服务/用户Chrome/IDEA不动，临时页关闭且尺寸重置。

## 已完成

- 问数优化默认处理进度，不在异步读取后自动选第一题；用户选题进入原详情，列表头可返回概况。显式新增问题后的原定位保留。
- 四步说明缩为上节点/下标题说明、细线连接，与原试问入口并排；按用户明确要求保留紫底白字编号，优先于ui-baseline的空心编号建议。并排/窄屏按容器调整。
- 列表头集中标题/返回、计数、筛选、添加；删除底部灰色重复按钮区。六种状态以轻底色/SVG区分，真实计数与过滤不变。
- 已确认问答独立折叠，首次展开才挂载，折叠保留编辑；长题换行、选中浅紫、简明来源/权限标签。只读项用ReadableCode，编辑项沿用原问题/SQL编辑与保存/删除API；未保存切换提示、失败保留输入。
- 前轮三页/长SQL/居中弹窗/完整工作台/文档阅读仍保留；无后端/API/DB/权限/Agent/发布变更，无新依赖、ADR或SQL迁移。
- spec140、ARCHITECTURE、DECISIONS、SESSION_LOG和本轮报告已同步。旧检查点见archive/CURRENT_TASK-before-coach-overview-20261115.md。

## 验证

- 本轮相关六个前端文件ROOT前缀24项通过（readable-sql、business-document-reader、semantic-workbench、guided-tour、onboarding、onboarding-actions）。前轮72项为历史，本轮未重跑三个前缀。
- 前轮真实IAB完整工作台1280/1920/850/390，默认概况、选题返回、原试问开合与两个只读样例通过；本轮重复覆盖见上方增量。
- 前轮隔离Playwright真实组件12项编辑/失败/切换/删除/懒加载断言通过，写请求均为夹具；本轮未改保存/删除，不重复执行。
- TypeScript/Vite构建通过，静态资源更新；既有主包体积提示保留。DocsConsistencyTest 5项通过，diff空白检查通过；未运行完整clean verify。详见docs/test-reports/20261115-modeling-optimization-pages.md。
- 未重跑真实LLM/Wren/SQL/发布全链路；完整IAB仅只读，用户同时修改的业务状态不归本次成果。页面既有推荐GET可能调用原辅助生成。

## 文件与运行状态

- 当前增量：QueryCoachPanel、ModelingMethodFlow、ConfirmedExamplesSection、SemanticAssetsPanel、AppShell.css、SemanticModelingPage及CSS；前轮三页与ReadableCode等文件仍为未提交实施。
- 所有新实施未提交/push/部署；用户原5173 Vite/8080 IDEA服务保留，刷新即可看效果。临时验收入口已删除、任务浏览器关闭、IAB覆盖重置，证据在忽略的.runtime/modeling-tabs-implementation。
- 外部full设计稿、ui-baseline.md、method-flow-20261115.html保留，不覆盖或作为本任务成果。

## 下一步

用户本地刷新问数优化页复核。当前没有自动提交或部署授权。
