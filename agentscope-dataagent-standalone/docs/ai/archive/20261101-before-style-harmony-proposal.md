# 当前任务：同步develop-2.0并整合个人UI（2026-11-01 Asia/Shanghai）

## 授权与起点
- 用户已确认先完成主线合并，再按同事风格做后续针对性优化；接受业务术语并入“业务知识”。
- 用户先前pull留下的merge：liyufan_dev@b1a71a20合入10bde604（develop-2.0）；共同祖先5b1079a6，原22个冲突文件。
- 保留进行中的merge及所有自动合并文件，不abort/reset/stash或切分支；本轮只完成本地合并提交，不push、更新develop分支或部署。
- 双方业务记录保留，原入口分别归档archive/20261101-before-mainline-merge-personal.md和-develop.md。
- 原冲突工作树及Git stage1/2/3备份在忽略output/merge-20261101-b1a-10bd，不提交私有配置或样例Excel。
- 属合并整合/小修，无新spec/ADR；spec106为主线既有功能，ARCHITECTURE同步最终入口。

## 冲突处理
- 保留主线spec106六页签、模型/字段级关系图、QUERY线上只读、列表统计、结论先行及新手指引。
- 保留本地品牌、侧栏批量管理、回答工具条、例行任务、数据源表单、四列卡片/数据集布局及原权限边界。
- 消费主线主色#5b3df5和颜色变量、12px最小字号；这是整合基线，不代表已完成用户下一轮风格优化或与develop字节相同。
- 卡片显示主线publishedVersion，列表/窄屏为发布状态腾出行位。
- SemanticConfigPage保留主线旧地址跳转，侧栏去独立业务术语；BusinessTermsPanel复用本地表格交互，编辑状态交给共享DraftBar禁用发布。
- 保留主线“跳过并发布”在新手指引中隐藏的约束、始终展示发布阻塞原因。
- 后端源码及测试与MERGE_HEAD逐字节一致，未改写主线后端实现。
- 静态资源整套重新构建，不手工合并压缩文件；最终index-DSxYX7b5.js/index-DaKh1TcM.css/ontology-D-vBbjSo.js。

## 本轮验证
- TypeScript与npm64项×3base通过；/dify/dataagent/最终构建通过。
- spotless及6个相关测试类共82项通过，含DocsConsistencyTest5项；资源/主代码/测试代码重新编译通过。
- Maven package -DskipTests -Dskip.npm=true -Dskip.installnodenpm=true通过，使用前一步已构建产物，不重复npm或下载工具。
- 实际Vite模拟接口25项合并检查+25项数据源+27项卡片；最终编译应用模拟接口19项回答操作，共96项通过。
- 检查包含旧路由、主线页签、字段搜索/关系图、QUERY不读草稿、编辑/取消/离开术语页的发布限制、卡片发布状态、权限/分页/窄屏及IME/停止/反馈重试。
- view_image检查业务知识/关系图/QUERY/数据集；所有写入测试被本地拦截或使用独立合成服务器，没有真实导入/建模/发布/资源修改。
- 报告docs/test-reports/20261101-develop-ui-merge.md，截图assets/merge-20261101；脚本/临时日志仍在忽略output。
- 文档最终检查与merge提交状态以SESSION_LOG和Git回读为准。

## 下一步与限制
- 本地合并后已包含develop10bde604和个人b1a71a20；仍在liyufan_dev，不等于主线已收录本次UI。
- 下一轮按同事风格讨论具体页面再改；不要未经确认把本批直接合回主线。
- 后端/数据库未重启；本机现有服务的真实新版建模/LLM/两条新手全流程/66未重新验收。
- 保留原ECharts与大包构建提示、Java弃用/Mockito警告；未做无关重构或全量压测。
