# 当前任务：数据源实施、历史圆点移除、知识库四列图（2026-11-01 Asia/Shanghai）

## 授权与基线
- 用户批准数据源列表/连接表单按附图实施；授权自行决定历史圆点，选择移除误导标记。
- 知识库要求保留原生成图风格、缩小成桌面四列，仅重新出图，未批准正式实施。
- liyufan_dev@5b1079a6叠加既有UI WIP；无提交/push/切分支/部署/真实DB变更，暂存为空。
- 局部前端，不新增spec/ADR；ARCHITECTURE11章补实际状态。

## 本轮完成
- DataSourcesPage名称本地筛选、服务器提示、四列81px主行、单条失败展开/技术详情；查看/检测文字入口，配置/删除进原WorkspaceMenu。
- DataSourceFormModal连接/凭据分组、主机/端口同排、localhost说明、只读账号建议、底栏检测状态；Dialog可选className支持720px表单。
- 原API/测试保存逻辑、sourceId、SSL/采样、编辑空密码、参数修改清结果/迟到保护保持；未改详情库表浏览或后端。
- SessionsSidebar移除is-unread使用与AppShell紫点样式，不新建通知机制，选中/批选/删除/后端已读API保留。
- 四列新图docs/previews/kb-four-up-20261101/01-knowledge-four-up.png；直接参考用户更喜欢的原图，权限标签/淡紫底/信息带/紫色入口/石墨按钮保留。
- 四库为排版示例，无创建资源；DatasetsPage与旧19139原型未更新，待用户认可。
- 用户附件/C:/路径报错已用正确Windows明确路径读到同图，不需重传。

## 验证
- tsc、npm test64项×3base、/dify/dataagent/构建通过。
- 最终index-B85rKpXV.js/index-C7141QIY.css/ontology-CcfOuBB1.js，保留旧ECharts/大包提示。
- 真实5173只读9项通过（5源81px、错误/搜索/详情/配置/密码/禁存/圆点），无真实写入。
- 真实Vite组件+源API浏览器内完整拦截25项通过：校验、默认端口、脱敏、迟到/新成功、创建编辑参数、SSL/采样/空密码、删除拒绝、空/失败重试、手机/44px/Escape等。
- 编译页面截图在docs/test-reports/assets/sources-20261101，真实原始图与脚本在忽略output/playwright/sources-implementation-20261101。
- 已view_image核对用户两认可图与实际组件桌面/窄屏；圈点移除不影响选中。
- DocsConsistencyTest与最终Git核对见SESSION_LOG；无真实LLM/业务建模/66或无关Java全量。

## 下一步
- 用户刷新5173数据源页验收；后端静态模式需加载新包，本轮不重启业务。
- 知识库只等四列新图选择，勿把本轮源实施扩成知识库实装。
- 报告docs/test-reports/20261101-datasources-and-history-ui.md；前记录归档archive/20261101-before-datasource-implementation.md。
