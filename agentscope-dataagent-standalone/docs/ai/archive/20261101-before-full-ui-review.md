# 当前任务：认可版品牌、侧栏与业务术语实施（2026-10-31）

## 用户授权与基线
- 用户已认可第二版侧栏/批量管理和业务术语设计，明确要求开始改造，并统一对话助手图标及浏览器标签页品牌。第一版仅例行任务获认可，知识库卡片/建模列表不改造。
- 当前liyufan_dev@5b1079a61be0d7a5b1ffc1975801e75d458f8c16；上一轮共享记录WIP保留，没有提交/push/切分支或66操作。
- 两Excel继续在忽略的data/demo-samples；66的a85版本已由用户验收，不重部署/初始化DB。

## 已完成
- 第二版图片：Git根output/ui-review-20261031-v2/01-sidebar-and-batch.png、02-business-terms.png及DESIGN-REVIEW.md完整提示词；由built-in image_gen生成且逐张目视检查。
- Brand/BrandMark + brand-mark.svg：认可折带标识矢量化，单行“红海 DataAgent”、去侧栏副标题；展开/收起侧栏、助手回答、登录/BI入口和favicon共用图形。标题改为红海 DataAgent，favicon经Vite部署前缀/哈希处理。
- SessionsSidebar/AppShell.css：石墨新建对话、白色选中导航、轻量历史列表；批量模式顶部“全选已加载”+半选状态，下方固定已选计数/删除入口；原删除确认、分页、部分失败保留，删除中禁用选择。
- SemanticConfigPage及局部CSS：知识库/版本/待发布上下文，定义和同义词、状态标签、轻量编辑/删除，保留自由格式/已删除恢复/只读等状态。没有填入概念图示例业务数据。
- DraftBar新增terms展示变体，复用原发布检查和其他成员确认；发布仍含整个知识库草稿，编辑中禁用发布，原建模页展示不变。术语删除图标直接沿用原确认，未额外引入更多菜单。
- 例行任务已按认可方向修改ScheduledTasksPage.tsx与secondary-pages.css：去掉大块未开放标识和无数据表头，展示日历时钟插画、清楚说明、既有/chat和/configure/datasets入口。不实现任务调度，不调用任务API。
- 插画为image_gen从认可图提取的透明PNG，frontend/src/assets/scheduled-tasks-calendar.png（1448×1086 RGBA、480429字节）；装饰img空alt，无外部CDN/新依赖，页面局部Windows中文字体fallback。
- 生产/dify/dataagent/构建已更新src/main/resources/static；无新依赖或后端/API修改。此次小优化不新增spec/ADR，架构第11章已同步。

## 验证与限制
- TypeScript及Vite生产前缀构建通过（npm run build），仍有原有大包/ECharts静动态导入提示，不展开无关拆包。
- npm test：64项×三种base路径全部通过；是既有回归检查，不代替此次UI真实交互验收。
- DocsConsistencyTest本轮5项全部通过；git diff --check通过。
- 用户表示已开启浏览器后、再次继续时，读取local-web-development仍返回“Local browser use is disabled in Work settings”。未改走Playwright/CDP/原生窗口绕过。
- 本轮新品牌/侧栏/术语还没有实际渲染截图、窄屏、键盘、删除和术语编辑发布浏览器验收；不能称已逐像素对图通过。上轮例行任务桌面检查是历史证据。
- 验证/对图清单见docs/test-reports/20261031-brand-sidebar-terms-ui.md；最终文档检查状态以该报告为准。
- 仅相关构建/文档检查；不重跑无关LLM/DB/大规模测试，不改线上资源。

## 下一步
- 用户可刷新现有localhost:5173前端检查品牌、术语和批量管理；favicon旧缓存可Ctrl+F5。若使用后端静态页需加载新构建，未擅自重启任何服务。
- 浏览器允许后用IAB补桌面/窄屏及交互验收，按认可图核对实际截图，不重做既有实现。既有19131/19133服务保留，但19133是fixture页面，不当作真实业务验收。
- 既有本地服务保留，未提交/推送。

## 历史入口
- 第一版图：Git根output/ui-review-20261031-v1，其中仅03-scheduled获认可；其余不作为实现设计。
- 上轮Git/示例文件记录：archive/20261031-before-ui-concept-review.md；分支同步实际已完成。
