# 当前任务：仪表盘入口、权限与删除

2026-11-17 Asia/Shanghai；codex/architecture-phase1。用户授权精简正式界面、增加权限入口及删除；沿用spec141/ADR0149，无新增ADR。本地实现/验收完成，未commit/push/合并/部署66。旧CURRENT已归档。

## 本轮完成

- 列表复用知识库/语义建模/例行任务的kw公共容器，仅一个新建入口；去除大引导及我的看板标题，保留筛选和搜索，继续沿用iOS卡片视觉。
- 新建仅知识库和名称，进入工作台手动对话，删除原自动发送路由逻辑。用户问Agent关系：实际代码为data-agent/modeling-agent/dashboard-agent三个独立配置，共用框架/模型服务，GenBI是技能和工具而非第四个Agent。
- 卡片菜单/工作台访问权限说明，管理者复用知识库成员弹窗。继承QUERY/MANAGE，全局管理员可管理全部库；未新增看板独立ACL，公开库也仅所有已登录QUERY用户查看已发布版。
- DELETE接口复核RUN和创建者/管理者；域服务锁/链接检查/删除标记阻止新执行；web协调器以原生会话门禁清理精确Agent/看板参与者，持久清单支持失败/重启重试。待清理卡片显示重试。
- 清理工程/全部修订/校验、原生上下文及双转录/历史/附件/已读/选库/当前登录封面；保留知识库/模型、其他会话/看板、共享记忆/审计。底层会话登记删除失败仍保留内存身份。
- 涉及DashboardService、DashboardDeletionService、SessionDeletionService、SessionAgentManager、ConversationScopeRegistry、DashboardController及前端Library/Permissions/DeleteDialog/Workbench/API/状态/样式。
- spec141及ARCHITECTURE14.6已同步；验收docs/test-reports/20261117-dashboard-management.md，截图docs/design/dashboard-management-*-20261117.png。旧设计稿与所有既有修改保留。

## 本轮实测

- 后端unit 1076项：失败0/错误0/条件跳过1（Windows无法创建symlink）；专项26项含同一跳过。实际链接用例未动态验证，不把跳过说成通过。
- 前端dashboards.test.mjs三个前缀各7项通过，最终TypeScript/Vite构建通过；既有ECharts大包/重复引用提示不变，列表不预载预览库。
- DocsConsistencyTest/ArchitectureDependencyTest此前12项通过；最终共享记录后的文档门禁见SESSION_LOG补记。未跑完整clean verify，未验66。
- 实际Chrome1488/390/320：四页标题x34/y33/font31/内容宽1264相同，单一新建、无根横溢出，pageerror空。权限说明→原成员弹窗通过且未改授权。
- 新建临时看板，真实创建无自动发送；手动一轮dashboard-agent回复已准备好，未生成业务HTML。取消删除保留、确认DELETE204，后续资产404/收件箱不含该会话。
- 物理核对工程16文件和原生2文件清理，登记1/摘要1/消息2/选库2行均归零；临时资源已清理，既有样板保留。现场无附件/已读/检查点，其失败重试由专项测试覆盖。
- CLI脚本成功删除后的额外读取曾误用status()，独立更正复查及物理核对通过；非产品错误。详细范围/限制据实记录，不新增真实发布。

## 服务与入口

- 正式体验 http://127.0.0.1:5173/dashboards 。用户要求自行IDEA启动，已停止本轮Java54432并核对8080无监听；前端5173保留。原Java51616此前已停止。启动日志.runtime/dashboard-management/backend.log含敏感输出，勿整贴。
- 8890是保留的历史设计稿。QA与脚本.runtime/dashboard-management，实际浏览器为Playwright CLI的retail-showroom会话，用户bob。
- 零售样板库1b339def-deeb-4188-84b6-5bda188c7fb9，看板a26cddc8-556f-4670-812d-c9ed439f0c03，既有发布版未改。alice为QUERY、bob管理，非全员公开。

## 下一步

- 用户反馈新看板bfb24d84-f0c1-402d-b2ba-187f54d30d37生成超时，已只读诊断：28次write_file空参数校验失败，查询成功，约14分钟执行中13分50秒为模型调用；600秒响应超时后后台继续约4分钟。底层是上游/截断/流式解析哪一处尚无原始SSE证据。见docs/test-reports/20261117-dashboard-generation-timeout.md；未改代码、未重跑该任务，不把界面验收当作此生成故障已修复。
- IDEA后续对照：进程7204使用上层仓库logs；新看板1b2187c2-7ba0-459a-a65c-a06a3c97ad75，00:29:38至00:33:00约202秒，5次空参数失败、第6次完整参数成功（36ms），官方verify6.5秒通过。模型193.6秒；前5次无效生成150.7秒。工作台忽略tool_result失败状态、一直显示更新；累计器JSON解析错误静默降级为空，原始上游来源仍待证据。未改代码、重启或重跑，用户IDEA服务保留，详见同一诊断报告后续章节。
- 后续需定位长工具参数、限制无进展重试、修正工具失败进度并修复取消传播；未实施修复。如需上线另执行完整发布验证，不自动提交或部署。
