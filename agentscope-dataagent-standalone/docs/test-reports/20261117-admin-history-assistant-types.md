# 管理员对话历史助手分类验证

日期：2026-11-17 Asia/Shanghai。分支codex/architecture-phase1，基线590d3e4e；spec143，本轮仅本地体验，未提交或部署。

## 结果与行为

- 保留问数、建模、仪表盘记录，管理页默认问数，可切换问数/建模/仪表盘/全部；未知助手在全部中显示其他。类型按明确服务端路由识别，不按标题猜测或误用内部Agent UUID。
- 列表、摘要、用户统计、CSV使用相同类型及其他筛选；跨类型用户去重。问数显示提问数，建模/看板显示对话轮次，全部显示用户消息数。仍计入匹配会话中的全部USER消息，包括自动命令/模板分段；日期仍筛会话最后活动，提示据实解释。
- 建模标题优先历史选库名称快照；缺快照仅所有者能回退当前名称，其他情况明确缺失。看板使用受限单文件元数据中的名称。后台更新现有展示索引，搜索及CSV同步受益；不修改原文/活动时间/原生上下文，转录未变或缺失也能补标题。
- 长用户源码默认折叠，展开仍为纯文本，详情保持只读。普通侧栏、原助手运行、权限来源、发布及保留期限不变。无DDL、新框架或新ADR。

## 自动验证

Java17执行`python scripts/check.py backend --tests ConversationHistoryTitlesTest,AdminHistoryHttpTest,JpaPersistenceIntegrationTest,ArchitectureDependencyTest,AnswerFeedbackControllerTest,SessionStateLifecycleTest,SessionTranscriptNamespaceTest,SessionDeletionCleanupTest,DashboardDeletionServiceTest`：35项，失败/错误/跳过均0，BUILD SUCCESS。

覆盖真实HTTP/H2的内部UUID/显式路由优先级/未知路由、默认与四类筛选、统计和CSV一致、用户交集、无效类型400、非管理员403及角色撤销；既有标题后台回填后的搜索/导出；知识库历史名称与外部改名边界、看板损坏/缺失/不安全路径；持久化、原生会话生命周期及删除回归。

前端`python scripts/check.py frontend --files admin-history-types.test.mjs admin-replay.test.mjs`：三个部署前缀各8项通过，无失败/跳过；API筛选在列表/用户统计/导出一致，类型用语、长消息阈值和纯文本转义及原视觉回看通过。`npm run build`的TypeScript/Vite通过，静态构建资源同步；原有大包提示仍存在。

共享记录完成后执行`python scripts/check.py backend --tests DocsConsistencyTest`：5项通过，失败/错误/跳过均0，BUILD SUCCESS。

原始结果保留在忽略目录`.runtime/admin-history-types-backend-final.log`、`admin-history-types-frontend-final.log`、`admin-history-types-build.log`。

## 浏览器验证

真实Chrome通过Playwright运行隔离只读API夹具，19条合成会话，不使用真实用户或后台生成。四类切换、分页归零、统计抽屉、CSV下载、搜索重置、长源码默认折叠/展开及历史HTML不执行均通过。850/390/320宽度下document滚动宽度与视口一致；没有意外API请求、写入请求、浏览器错误。

8张截图位于`output/playwright/admin-history-types/`：query-desktop、modeling-desktop、dashboard-desktop、all-desktop、detail-desktop、query-850、query-390、query-320。已检查主要桌面、详情及390移动截图；最终截图等待动画结束，避免过渡状态被当成最终效果。脚本及结构化结果在`.runtime/admin-history-types/browser-check.js`、`browser-result.log`。

## 体验限制

未操作66或线上数据；当前改动未运行完整clean verify，合并发布前需补齐。本地8080检查时未启动，因此浏览器结果是隔离界面验证，真实业务接线以HTTP/H2测试覆盖；用户启动/重启IDEA后可查看本地真实历史。旧标题由现有后台任务分批更新，不承诺刷新瞬间全部完成。没有批量删除、迁移原生消息或变更原权限。

## 体验后筛选行调整

用户要求类型筛选与其他控件平齐，已移到日期右侧并统一36px高，窄屏自动换行；沿用spec143。默认前缀相关前端8项及TypeScript/Vite最终构建通过，未重复无变更的后端业务测试。Chrome隔离夹具复验全部原交互通过，1488桌面的搜索/用户/日期/类型四组top均75.5、高均36；850/390/320无页面横溢，意外请求/写入/浏览器错误均0。8张新截图在output/playwright/admin-history-filter-row，已检查桌面对齐效果。无提交或部署。
