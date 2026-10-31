# Data Agent 原型升级说明（2026-10-30）

状态：独立交互原型设计；不代表生产改造、真实模型执行或66验收通过。基线为 `codex/integrate-boundary-lyf-v2@4af226b6`。

## 目标与交付边界

在 `docs/archive/previews/dataagent-design-preview-20261014.html` 的轻量侧栏、紫色主色、对话中心与工具详情基础上，重新组织当前能力。保留旧文件，新的可双击入口为 `dataagent-design-preview-20261030.html`。不接生产API、不读登录态、不存凭据、不修改当前React应用或后端。

本次是原型和研究资料升级，不新增生产流程，因此不创建实现spec或ADR；若采纳并实施页面结构、交互或后端能力变化，再另立spec与验收。产品长期边界仍以AGENTS和ARCHITECTURE第14章为准。

## 当前能力核对

以实际页面、入口调用和服务守卫为准，不能仅根据遗留接口或类型存在认定能力开放。

| 场景 | 实际依据 | 原型处理 |
|---|---|---|
| 首页、新手引导、推荐问题 | `ChatPanel`、`Onboarding`、`api/onboarding` | 入门步骤、可重开的帮助、选库后预填问题 |
| 对话与历史会话 | `SessionsSidebar`、`ChatHeader`、`ChatController` | 历史分组、搜索、单轮状态、停止/重试模拟 |
| 单知识库问数 | `singleKnowledgeSelection`、`ConversationScopeRegistry`、`WrenQueryAccess` | 零/单库；不出现“全选知识库” |
| 表格、图表、查询文件 | `WrenToolResult`、`ToolInspector`、`ChartBlock`、`artifacts`、spec101 | 结果切换、SQL依据、CSV预览/实际本地下载 |
| 分析技能 | `AnalysisPanel`、`AnalysisToolkit`、spec102 | 指标归因/异常检测/预测，先确认参数；反馈不保存问答样例 |
| 数据库与文件接入 | `DatasetsPage`、`DataSourcesPage`、`DatasetController`、`DatasetUploadProperties` | 独立数据源页面、建库/文件选择/导入状态模拟；不承诺真实连接 |
| 字段整理 | `FieldScreeningService`、`FieldScreeningPanel`、`fieldScreening` | 字段纳入、说明、关联键提示；确认后进入建模 |
| 业务文档与草稿 | `BusinessDocumentService`、`DraftReviewController`、`DraftItemService`、`ModelingTurnRules` | 多份文档、建模结果条目、修改直接进草稿、来源与待确定问题 |
| 试问与发布 | `TrialPanel`、`MdlQuestionService`、`MdlPublishService` | SQL生成/核对/修正/确认、统一发布、问数准备状态；SQL确认不强制先试运行 |
| 语义资产 | `WrenProjectAccess`、`ModelingHitlMiddleware` | 模型/关系/规则/问答；不恢复Cube/View写入和KG主线 |
| 工程内业务术语 | `BusinessTermService`、`BusinessTermController` | 按库选择；术语编辑进草稿、发布后生效 |
| 权限与人员 | `KnowledgeBaseAccessService`、`KnowledgeBaseMembersModal`、`DatasetGroupController` | QUERY/MANAGE与全局admin分开；只读角色保留详情/数据预览，隐藏写入口 |
| 全员库 | `PublicKnowledgeBasesPage` | 仅管理员配置全员QUERY，非公网公开 |
| 全局模型服务 | `AdminModelServiceController`、`ModelServiceRegistry`、`ModelServicesPage` | 配置/测试/手动激活、在途轮次模型不变、不自动故障切换 |
| 例行任务 | `ScheduledTasksPage`当前返回“后续版本开放”，不调用任务API | 仅展示未开放状态；旧原型可创建执行任务的演示移除 |
| BI身份与更多 | `BiEntryPage`、`AuthBootstrap`、`MorePage` | 中文姓名、公司资料、帮助、管理员入口；不模拟密码登录 |

## 调研与设计转译

调研日期：2026-10-30。以下为官方公开资料的能力介绍及可迁移的设计原则，不声称进入竞品登录后台实测。

| 官方来源 | 资料支持的事实 | 本项目设计判断（推论） |
|---|---|---|
| [Claude Artifacts](https://claude.com/resources/articles/build-artifacts) / [键盘可访问性](https://support.claude.com/en/articles/17457950-use-artifacts-with-a-keyboard-or-screen-reader) | 产物拥有独立空间；可在对话旁操作成果；支持键盘进出区域 | 将答案、可复核证据、可下载成果分层，避免对话被工具JSON占满；原型侧面板属于体验提案，不代表新增独立产物库 |
| [Dify Human Input](https://www.dify.ai/de/blog/the-human-input-node-bringing-human-judgment-into-automated-workflows) | 工作流可停在人工输入点并按选择继续 | 建模歧义卡就地表达“需要你决定”，与普通进度、失败和发布分别呈现 |
| [Quick BI 智能小Q](https://help.aliyun.com/zh/quick-bi/user-guide/smartq) | 自然语言问数、多轮分析、归因等有明确入口 | 一般提问保持简单，归因/异常/预测放入按需菜单，先确认参数；不增加报告Agent等现阶段没有的能力 |
| [Manus Artifacts](https://open.manus.im/docs/v2/artifacts) | 输出与上传输入分开，输出按类型组织并提供下载资源 | 对话中的CSV/图片作为成果对象，突出预览、下载与来源；不借此承诺全局收藏/协作发布等未实现功能 |

## 设计取舍

1. 可信度来自能核对。结论优先，查询口径、SQL和结果按需展开，不把模型推测画成确定事实。
2. 使用者与维护者的工作分开。普通提问者看已发布版本；管理者维护草稿；全局模型配置放“更多”，不塞进问数输入框。
3. 状态必须真实可区分。导入完成不等于建模完成，写入草稿不等于发布，发布不等于问数准备就绪。
4. 不用状态卡堆出复杂感。以阅读宽度、行列表格、轻边框、对比度和留白建立层次；数据表仍保持表格。
5. 本地原型的“运行/测试/激活/发布”均为模拟状态，顶部一直标识“交互原型 · 示例数据”；无生产外部请求。

## 视觉约定

继承旧原型紫色、白底、灰色轻侧栏、线性图标和对话/详情布局；主色 `#7560b5`，文字 `#292c38`，弱文字 `#737887`，边框 `#e9eaf0`，侧栏 `#f7f8fa`。正文14–16px，主标题26–30px；常用控件不低于36px，图标点击区40px；圆角8–12px，不用渐变、发光和装饰性KPI卡。概念图只作为设计参考，不替代可交互的代码界面。

## 原型验收范围

本地创建知识库→模拟导入→字段整理→上传文档文件名→自动建模→业务口径确认→编辑条目→SQL核对→统一发布→准备就绪→单库问数→表格/图表/依据切换→附件预览与下载→分析参数确认。补充验证只读角色、管理员入口、术语草稿、未开放任务、错误/重试、键盘Esc/Tab、桌面及390px窄屏。

仅验证本轮HTML、脚本、样式与文档一致性，不重跑应用全量测试；最终实测结果另记专项报告。
