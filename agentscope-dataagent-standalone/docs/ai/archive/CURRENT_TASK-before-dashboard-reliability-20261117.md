# 当前任务：仪表盘基本信息与列表管理

## 当前讨论：对话生成可靠性

用户要求先分析研究布局/对话/整页生成与局部修复/Wren能力，不实施代码。已核对本地实现、Wren源码和官方Power BI/Quick BI/Sigma/Claude资料；建议保留并列布局，优先查询排队、面板可定位及真实浏览器验收。只读确认edit_file局部修改已支持，但仍整页重载；verify仅结构检查，错误尚未自动形成面板报告给Agent。

Chrome隔离复现8并发查询仅6个到达模拟API，2个被active>=6直接拒绝；生成页自行catch后宿主仍显示查询完成，无全局告警。未访问真实数据或修改业务代码。结果与提案在docs/design/dashboard-conversation-review-20261117.md，尚未批准实施；仅保存研究记录，DocsConsistencyTest 5项及空白检查通过。此前界面实现及全部未提交工作保留。

2026-11-17 Asia/Shanghai，codex/architecture-phase1，基线590d3e4e。用户批准上一轮建议，spec144；保留此前管理员历史分类/筛选行等全部未提交改动，本轮不提交/推送/部署。

## 已完成

卡片/列表菜单与工作台可编辑名称/选填描述，新建知识库/名称必填、描述选填（按最新体验追加）；PATCH复用RUN/QUERY及创建者或MANAGE、清理状态、资产锁和提交前授权复核，expectedUpdatedAt冲突拒绝覆盖。元数据独立原子保存，无变化不更新时间，不改HTML/发布版本或调用模型。持久description/createdAt，旧JSON兼容未知，发布/校验保留。

筛选行搜索→范围→状态→排序→视图，搜索含描述/知识库；状态沿真实可见状态。默认最近更新，支持最近创建/名称，未知时间排末。默认卡片、紧凑列表共用条件/权限/动作，视图偏好按用户隔离；空态可重置，窄屏换行。

## 验证与限制

Java17相关35项失败/错误0、1个Windows symlink条件跳过；全unit1101项失败/错误0，同一跳过1项。补充PATCH成功返回字段断言后，Controller/历史HTTP/H2及JPA回归13项全部通过。前端默认前缀11项、最终TypeScript/Vite构建通过，静态资源同步；共享记录后DocsConsistencyTest 5项及空白检查通过。

Chrome隔离夹具取消/保存/409/读取/只读与清理中入口/搜索状态排序/视图记忆/新建/工作台通过。850/390/320无横溢、390弹窗无溢出；8张截图在output/playwright/dashboard-management，主要桌面/弹窗/工作台/窄屏已检查。预期409单独识别，未预期请求/写入/JS错误0，不冒充真实业务数据或登录全链路。

报告docs/test-reports/20261117-dashboard-library-management.md，设计记录更新为已批准。ARCH/spec/索引同步，无DDL/新ADR；未运行当前改动完整clean verify、真实LLM/Wren或66操作。用户IDEA未接管，需启动/重启后端加载PATCH，再刷新本地仪表盘；Vite5173保留。旧创建时间不补造。

## 最新追加：创建表单

用户截图要求已落实：可搜索知识库选择器（仅QUERY/已发布/未清理）、名称右侧显式填库名、80字截取提示、换库不覆盖名称；沿用UUID允许同名，只对可见同名非阻断提示，不加编号。新增描述500字选填及必填标记，随POST原子保存，内部三参数create兼容原未命名回退。搜索/键盘/Tab焦点/Escape/空态/无可用源/取消/一次创建及390/320通过，5图在output/playwright/dashboard-create已检查。相关后端30项及全unit1104项失败/错误0、同一Windows symlink跳过1；前端12项/最终构建通过。报告20261117-dashboard-create-dialog，共享记录后DocsConsistencyTest 5项及空白检查通过；IDEA需重启加载POST描述参数，无提交/部署。

## 最新界面微调：查看顶栏

按用户截图明确返回列表、标题/状态/描述和有文字的编辑信息入口，查看态右侧突出编辑看板、权限次操作；编辑态保留查看发布版及原显式发布/进度。仅前端，无后端接口/ACL/预览变更，spec144已追加。前端12项和最终构建通过；Chrome隔离夹具信息保存后保发布模式/HTML、切换/只读/权限/返回/取消发布通过，一次预期PATCH、无意外请求写入错误。2560下顶栏/内容左缘均510，850/390/320无横溢；7图在output/playwright/dashboard-header，主要桌面/编辑/窄屏已检查。报告20261117-dashboard-header-ui，共享记录后DocsConsistencyTest 5项及空白检查通过；刷新前端体验，无提交/部署，未接管IDEA。

## 前序保留

spec143管理员历史默认问数/四类筛选与统一统计导出、业务标题、长源码折叠及同排筛选已经完成本地验证，仍未提交。旧CURRENT已归档。上一轮发布由用户部署66，本轮不自动上线。
