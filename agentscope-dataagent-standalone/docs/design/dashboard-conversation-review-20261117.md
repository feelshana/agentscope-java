# 对话生成仪表盘：现状研究与改进建议

实施更新：用户随后明确批准“先按建议优化一版”；当前按spec145/ADR0150实现查询排队、面板运行约定、报告/定向修改/有界修复和页面撤销。下文“尚未实施/未批准”描述的是研究阶段，不再代表当前授权状态。完整原生面板配置、局部热更新、后台全浏览器验收及所有筛选验收不在此版范围。

2026-11-17 Asia/Shanghai。状态：研究讨论，尚未批准实施。只读核对当前分支、本地Wren源码、既有本地日志及官方产品文档；本轮不修改业务代码。此前未提交实现保留。

## 已确认事实

1. DashboardToolkit.PROMPT挂载官方genbi/usage，wren_genbi build返回项目上下文与构建指令，DataAgent的看板Agent背后的模型编写HTML/SQL/ECharts。试查及页面实时取数均经官方Wren与平台授权/时间窗口门禁。Wren没有直接返回一套完成的图表页面。
2. DashboardService#file将可写路径限定apps/dashboard/index.html，CSS/JS内联是当前托管选择。官方GenBI以应用目录及index入口交付，没有要求只用一个文件，也没有提供当前项目所需的原生面板实体/CRUD接口。
3. DashboardFilesystem#edit及原生edit_file支持old_string/new_string局部替换；本地LLM.log及前一日本地日志可见真实参数记录，不能将每次修改推定为整页重写。日志不代表线上全部会话或发生比例。
4. DashboardPreview按资产内容修订生成channel并重建iframe，局部代码修改后仍整页刷新/重新取数。源码修改粒度、持久交付物粒度与预览刷新粒度是三件事。
5. 官方verify.py明示结构检查、不运行浏览器，主要检查index/MDL/数据资源/密钥。平台verify还检查HTML/dataagent.query/语义模型版本与官方register/verify；不等于逐图查询和渲染验收。
6. 当前已有全局error/unhandledrejection与SQL失败显示，但生成HTML自行catch的面板失败不一定成为全局错误；没有面板标识、挂载/渲染回执与统一验收清单。预览错误不自动形成上下文发给看板Agent，用户仍常需描述/粘贴错误。

## 本轮隔离复现：并发拒绝及完成状态

DashboardPreview中active >= 6直接给查询回复错误“查询过于频繁，请刷新或减少图表”，没有队列；总请求预算120另有约束。面板数不等于查询数，但模型生成的页面常可能同时请求多项数据。

使用Chrome/Playwright与隔离API夹具，让生成页Promise.allSettled发起8个查询，模拟API每次350ms后成功返回：8个请求中6个到达模拟API且成功、另2个在前端桥直接失败；无实际服务器查询/模型调用。代码只读，未触及用户资产。脚本.runtime/dashboard-conversation-concurrency-probe.js、结果dashboard-conversation-concurrency-probe.log。

生成页自行处理失败后，没有全局未捕获异常；宿主底部仍显示“按知识库权限查询 · 本次查询完成于…”且alert数0（dashboard-conversation-concurrency-status.log）。原因是超额直接回复分支未计入宿主failed状态，宿主也没有全部面板的成功回执。该复现证明实现层也会导致部分图表失败，不代表用户所有失败均由此引起；具体会话仍需区分SQL/权限/窗口/数据/JS及布局。

## 官方产品对照

- [Power BI Copilot创建编辑](https://learn.microsoft.com/en-us/power-bi/create-reports/copilot-create-reports)：画布配助手，支持增改删图表及撤销/重做；[概览](https://learn.microsoft.com/en-us/power-bi/create-reports/copilot-introduction)说明报表助手在右侧。
- [Quick BI小Q搭建](https://help.aliyun.com/zh/quick-bi/user-guide/user-guide-for-bi-copilot)：支持整报表和单图创建，右侧对话，图表级操作先选择组件；提供修改字段/样式及撤销。
- [Sigma Assistant](https://help.sigmacomputing.com/docs/use-ai-to-build-dashboards-and-apps)：画布配助手，计划/搭建模式，支持把指定页面/元素作为对话对象，元素工具栏可直接发起编辑。
- [Claude Artifacts](https://support.claude.com/en/articles/17153992-what-are-artifacts-and-how-do-i-use-them)：对话旁独立成果画布，持续修改；作为创作交互参考，不冒充BI面板资产实现。
- [Tableau Agent in Dashboards](https://help.tableau.com/current/pro/desktop/en-us/dashboard-narratives.htm)：当前该功能用于解读现有仪表盘，文档明确不能据此创建仪表盘，不能把它当作整板生成对标证据。

以上能支持“画布+助手并列”是常见模式，不能证明某一左右位置最受欢迎。建议保留左对话右预览，优先可拖拽宽度/收起/全屏和清晰的编辑对象，后续用任务成功率/耗时/误操作评估，避免为跟风换边。

## 改进建议（尚未实施）

第一优先级处理可复现链路：桥接并发超额进入有界队列而非正常负载直接报错；预算/停止/版本变更仍需正确取消；失败、无数据和成功分开记录，不再把部分成功显示成整板可用。

保留对话整板生成，系统内部先规划面板与口径、通过Wren试查列名/类型/粒度，再生成布局与各面板。清楚需求直接执行，只问影响口径/时间的必要问题，不增加强制逐图确认。业务“最近一个月”与数据实际覆盖期应明确，不悄悄改变日期定义。

用统一运行层提供加载/空态/错误/重试/resize/筛选，给面板稳定ID及依赖查询映射。最终仍可输出HTML，模型主要设计内容/布局/图表配置；不是仅把提示词改成“少写错误”。单面板失败不阻断其他面板，网络重试与代码修复分开。

预览回传面板状态、查询错误、渲染错误和修订，形成可定位上下文给同一个看板Agent。局部优先edit_file，但共享筛选/数据函数变化要检查依赖面板；给图表选择/修改目标引用，不为每张图另建Agent。单文件不妨碍模块化，物理多文件也不自动获得面板管理。

官方verify之外补真实浏览器验收（默认及代表性筛选、各种面板的加载/空/错误/渲染状态）。SQL成功不等于数字口径正确，浏览器成功不等于所有筛选及业务定义均正确；报表状态应如实分层。自动修复有轮次/时间预算，不能重复失败无限重试；补每轮草稿快照/差异及撤销，保留上次可用预览，既有已发布版本保护继续复用。

对话显示增加简短目标/计划、实际阶段与面板结果、修改目标标签和本轮差异；技术说明继续折叠。提供停止、失败图“重新取数/修复图表”、下一步快捷建议。进度来自实际事件，不生成虚假百分比；改善文字排版及输入宽度是次于执行反馈的工作。

暂建议“GenBI/HTML交付 + 平台面板运行约定与验收”作为中间路线。完整原生面板配置模型适合独立保存/跨板复用/拖拽等更大目标，工程量和创作自由度需另评估，不因本次研究就切换。落地前需正式spec及必要架构评审，复用现有Harness运行/超时/授权，不另造ReAct循环。

## 研究边界

未复现每个真实失败看板，不能给出故障比例或保证某项改造消除全部错误；没有改动Wren官方技能、产品运行代码、现有权限或上线状态。公开资料为研究时可见功能，不代表所有版本/套餐均支持。
