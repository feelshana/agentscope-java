# 知识库与数据集视觉协调验收（2026-11-01）

## 范围与基线

用户认可[两页方案](../previews/style-harmony-20261101/README.md)后授权实施。起点liyufan_dev@d6ad3ba3，既有未提交内容仅上轮方案和共享记录。此次局部前端改造，无后端/API/数据库/权限边界调整，无新spec/ADR，不提交、推送、切分支或部署。

实施AppShell侧栏、DatasetsPage卡片/列表、DatasetGroupPage和WorkspaceMenu可选文字触发器；共享主色在global.css与theme.ts的primary系列同步。原Logo不变，历史圆点不恢复。卡片未发布且有MANAGE时显示添加数据/进入建模；已发布提问仍由canQuery约束，删除中仍走原恢复分支。

## 验证

- TypeScript noEmit通过。
- npm test：64项×根路径、/dataagent/、/dify/dataagent/三种base通过。
- npm run build：生产前缀/dify/dataagent/通过；最终index-X5kQSKjT.js、index-ulJeVWYf.css、ontology-h0teAWte.js。保留既有ECharts动态/静态混用及大包提示，没有借本轮扩大重构。
- spotless:check通过；DocsConsistencyTest5项通过，文档收尾再次检查。
- 独立Playwright CLI实际Vite组件26项通过：四/三/二/单列、列表、搜索、菜单回焦/编辑取消、QUERY管理入口、无QUERY禁提问、空库/未发布准备入口、错误展开、缺原文件禁重试、未统计/零行、局部横滚、无运行异常和业务写入。
- 同一独立浏览器加载最终编译包，/dify/dataagent/前缀6项通过：8卡片、四列、详情路由/表格、设置菜单/Escape、无遗漏API/页面异常/写入。
- 所有业务API由本地fixture拦截；没有登录真实账号、读取/写入真实业务数据，没有真实上传、发布或LLM验收。localhost:5173的页面HEAD响应200，用户服务和浏览器未接管。
- Browser插件接口本轮不可用，采用独立Playwright Chromium；没有绕过用户Chrome网址检查。19142/19143仅为本轮独立开发/编译验收服务，收尾关闭，用户5173/8080及历史预览服务保留。

## 图稿对照与尺寸检查

已使用view_image直接对照两张认可图、最终浏览器桌面/窄屏/列表/失败和编译包截图。ImageGen图存在文字及比例近似，按获准README的明确规格实施。

| 对照点 | 最终结果与证据 |
| --- | --- |
| 品牌/侧栏 | 原SVG与字标保留；224px侧栏，白底新建，淡紫选中与细标记；修正首次试排新建按钮图文靠右问题。 |
| 卡片比例 | 1600px视口四列，每卡316px宽、247.56px高；取消独立大图标行、灰底统计盒、大号数字。 |
| 信息层级 | 图标/15px名称同行；说明最多两行；发布/权限同行；12px以上数据量、创建人和日期。创建人无头像/图标，保留明确文字标签与悬停全名。 |
| 操作配色 | 主色#6654ce、白底控制、淡紫卡片动作；移除本批侧栏/卡片/上传的黑色填充，保留键盘焦点。 |
| 详情比例 | 无64px书本装饰块；24px标题、36px控制、50px发布状态实测（含28px可点击模型链接与上下内边距），导入摘要36px。较44px试排目标多6px以保留点击区域。 |
| 数据表 | 表头40px、行高64px；来源两行、数值居中、查看与菜单保留；未知行数与零值没有互换。 |
| 内容差异 | 示例名/数量由隔离API给出；真实页面显示真实后端数据。已有说明照常展示，空说明可编辑；尚未发布统一沿用实际“待建模”标签，未凭数据量推断模型已就绪。 |
| 窄屏 | 390px单列，操作换行，侧栏抽屉机制保留；表格在内部横向滚动，页面无横向溢出。 |

## 实际组件截图（合成数据）

- [知识库生产包](assets/style-harmony-20261101/compiled-knowledge.png)
- [数据集生产包](assets/style-harmony-20261101/compiled-datasets.png)
- [知识库列表](assets/style-harmony-20261101/knowledge-list.png)
- [知识库窄屏](assets/style-harmony-20261101/knowledge-mobile.png)
- [数据集窄屏](assets/style-harmony-20261101/datasets-mobile.png)
- [导入失败状态](assets/style-harmony-20261101/datasets-failure.png)

## 限制与交付

仅验证本次界面、相关已有回归与生产资源，不重复全量后端测试、建模压测、打包Java或66验收。未实际执行删除/上传/模型发布。改动保留本地待用户验收，暂存为空；可从localhost:5173/configure/datasets刷新后查看。本轮临时CLI截图/日志在持久证据归档后清理，仅关闭本轮创建的浏览器和服务。
