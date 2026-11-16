# 当前任务：仪表盘 iOS 界面正式实现

2026-11-16 Asia/Shanghai；codex/architecture-phase1。用户已批准按HTML设计稿实施，沿用spec141，无新ADR。正式页面本地实现及验证完成，未commit/push/合并/部署66。

## 本轮完成

- DashboardLibrary/DashboardsPage/dashboards.css按已确认设计替换正式界面；知识库同款#f2f2f7/淡顶部光/22px白卡/柔影/悬停与按压反馈。仪表盘默认收起产品侧栏，尊重手动切换。
- 列表搜索/创建者与共享筛选/实际版本状态；新建弹窗需求恢复会话后只发送一次；发布确认/忙碌/检查/版本门禁；收起对话、展开预览/Esc、手机切换；技术说明完整保留并折叠。
- DashboardService.View增加owned布尔值，按当前用户创建关系投影，不暴露ownerId、不改变ACL或存储。
- 封面来自已打开页面的真实ECharts Canvas；精确窗口/通道+PNG大小类型门禁，最多18个当前登录/修订内存缓存。不持久、不批量查询，未打开时有明确提示。CSP/隔离未放宽。
- 预览状态显示本次查询完成时间，不声称源数据实时。生成的业务HTML/数据/已发布修订保持原状。
- spec141/ARCHITECTURE14.6同步；正式验收docs/test-reports/20261116-dashboard-ios-ui.md；原HTML及所有既有未提交源码/设计/样板保留。

## 本轮实测

- 后端DashboardServiceTest、DashboardControllerTest、DashboardQueryServiceTest、DocsConsistencyTest共14项通过；新增管理者不等于创建者的覆盖。
- 前端dashboards.test.mjs 7项在/、/dataagent/、/dify/dataagent/各通过；覆盖发布状态、原文折叠、隔离桥、恶意封面、凭证变化/修订/容量缓存。
- 最终TypeScript/Vite构建通过（尾部结果见日志），既有EChartsBlock重复引用及大包提示保留；不把预览运行库提前加载到列表。
- 实际Chrome/Playwright CLI（当前无IAB工具）1488/390/320：根页面无横溢出；3张真实卡、我创建2/共享1、3个实际封面；查询HTTP200、pageerror0。查看/编辑、收起/展开、Esc、创建必填/关闭、无修改禁发/有修改确认取消通过。
- 仅隔离UI夹具拦截创建/SSE/发布：创建/发送各1次、选库一致、路由状态消费、取消无请求、409错误保留草稿；没有执行真实LLM生成/正式发布。
- 实际alice发布版200且canEdit/canPublish/owned=false，草稿404。主体验证保持bob；不改变已有角色或成员。
- 原型及正式桌面/手机截图view_image核对，差异已记录：真实业务HTML自带主题和窄画布响应式，不套用静态4指标模板；侧栏沿用现有59px；封面仅当前登录内存。
- 最终共享记录后DocsConsistencyTest 5项通过，空白检查通过；未跑完整clean verify，未验66。

## 服务与入口

- 正式界面 http://127.0.0.1:5173/dashboards ，8080本轮加载新owned投影；本对话原Java47584已停止，新Java51616，日志.runtime/dashboard-ios-implementation/backend.log含敏感输出不整贴。
- 8890仍为独立历史设计稿。浏览器夹具/QA日志在.runtime/dashboard-ios-implementation，正式交付截图docs/design/dashboard-ios-implemented-*-20261116.png。
- 零售样板库1b339def-deeb-4188-84b6-5bda188c7fb9，看板a26cddc8-556f-4670-812d-c9ed439f0c03，修订53c510f6902b00211c1c6466b8afc8e7e8aaac370512f2ba5c22c1383e174c2b；bob管理/alice QUERY，非全员公开。

## 下一步

- 用户直接体验正式界面；后续根据反馈处理功能/流程，再经完整发布验证处理线上部署。不自动提交或部署。
