# 仪表盘基本信息与列表管理验证

2026-11-17 Asia/Shanghai；codex/architecture-phase1，保留此前spec143等未提交修改。本轮spec144/沿用ADR0149，当前分支本地体验版，不提交/推送/部署。

## 已实现

- 卡片/列表菜单及工作台头部提供编辑基本信息，名称80、描述选填500字；新建仍只知识库/名称。描述卡片两行、列表紧凑呈现、工作台单行提示，并纳入搜索。
- PATCH /api/dashboards/{id}复用RUN门禁及boundedElastic；资产锁内检查QUERY、创建者或MANAGE、清理标记与expectedUpdatedAt，提交前复核权限。冲突409拒绝覆盖；表单显式重新读取会替换未保存内容，取消无写入；无变化不更新时间。
- 元数据独立原子保存，即时可见，不触发模型/CLI或改变HTML、SQL、校验摘要、内容修订与发布版本。页面内部标题仍通过对话修改并发布。
- Asset/View增加description/createdAt，新建固定时间，旧JSON兼容null且显示创建时间未记录；校验与发布保留字段。GET及查询不更新资产时间，无DDL或配置变更。
- 搜索→归属范围→状态→排序→卡片/列表，沿用知识库视觉。状态取现有真实草稿/已发布/未发布修改/清理待重试；先由后端授权并隐去不可见草稿，再前端共用筛选。排序默认最近更新，支持最近创建/名称，未知时间排末、ID稳定打破并列。视图偏好按当前用户subject隔离保存，仅本地显示，不作为授权依据。

## 自动验证

Java17相关类DashboardServiceTest、DashboardControllerTest、DashboardDeletionServiceTest、DashboardTurnPreparationTest、DashboardQueryServiceTest、ArchitectureDependencyTest、ConversationHistoryTitlesTest：35项，失败/错误0，1项Windows符号链接能力不足条件跳过。

`python scripts/check.py backend --unit`：1101项，失败/错误0，同一符号链接用例跳过1项，BUILD SUCCESS。新增服务层覆盖旧JSON与重启、发布保留、HTML字节及CLI调用不变、只读/撤权/清理/重复保存、边界及过期冲突。补充200序列化断言后重跑DashboardControllerTest与AdminHistoryHttpTest/JpaPersistenceIntegrationTest：13项全部通过，无跳过。WebFlux路由测试覆盖PATCH参数、200字段序列化、409及RUN拒绝403；使用注入测试Principal和服务mock，不宣称验证完整登录链路；已有历史HTTP/H2及JPA测试为完整应用装配回归。

`python scripts/check.py frontend --files dashboards.test.mjs dashboard-library-management.test.mjs`：默认部署前缀11项通过。覆盖范围/描述/知识库搜索交集、真实可见状态、未知时间与稳定排序、不变更输入，以及按用户隔离/存储不可用的视图偏好，保留原预览/发布状态回归。TypeScript/Vite最终构建成功，静态资源同步；原有大包提示仍在。共享记录完成后DocsConsistencyTest 5项通过，无失败/错误/跳过；git diff --check通过。

日志在忽略目录.runtime/dashboard-management-{backend,unit,integration,frontend,build-final}.log。

## 真实浏览器隔离检查

Chrome + Playwright只使用5张合成看板与隔离API响应：搜索描述、状态过滤、最近创建未知排末、列表视图刷新记忆；基本信息取消无写、保存、409不覆盖和显式重新读取；只读及清理中隐藏编辑；新建无描述要求；工作台改名后标题更新。夹具仅允许预期的元数据PATCH（2次成功、1次刻意409），不发起模型、发布、数据查询或实际业务写入。预期409的浏览器资源错误单独排除，未预期请求、写入及JS错误0。

850/390/320页面宽度等于滚动宽度，390弹窗自身无横溢。8张截图在output/playwright/dashboard-management，已检查卡片、对齐后的列表、编辑弹窗、工作台、390窄屏。首次脚本定位描述控件误含动态计数，已为表单提供稳定无障碍名称并重新通过；最终截图明确选择视图，避免已保存偏好改变截图含义。

## 限制与体验

未接管用户IDEA、未操作线上66或真实看板，未运行真实LLM/Wren生成。当前改动未跑完整clean verify，合并发布前仍须完整门禁。用户需重启IDEA加载新增PATCH和元数据字段，再刷新仪表盘；旧创建时间不会自动补造。既有历史分类等未提交内容全部保留。
