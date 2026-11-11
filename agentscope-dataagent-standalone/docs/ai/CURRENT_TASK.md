# 当前任务：整批合并与已发布知识库问数排查

更新：2026-11-11 Asia/Shanghai。个人分支 `liyufan_dev`，共同主线 `develop-2.0`。

## 用户授权与完成状态

- 用户授权提交当前全部本地个人分支修改、push并合入主线，同时要求分析线上V14已发布/V15草稿时其他用户问数失败的原因与合理逻辑。
- 已fetch，两分支原同为f7118541，无同事新增分叉。现有spec125/126、批准设计稿、历史检索投影及0143迁移、详情/管理/帮助UI和最终重建静态资源统一提交c943edfa。
- c943edfa已push origin/liyufan_dev，快进合入本地develop-2.0并push origin/develop-2.0，远端核对相同；当前已返回个人分支。
- 本报告与共享检查点另作后续文档提交同步，最终提交以Git为准。没有执行66迁移或部署。

## 排查事实

- 409原文来自WrenQueryAccess#resolve，判据是modeling_revision > published_modeling_revision，不是简单DIRTY。
- 普通模型/关系/规则/计算字段编辑只改语义草稿，问数仍使用published快照；未发布V15不会自动覆盖V14。
- 确认现有数据服务的说明/字段说明/刷新样本/旧知识文档操作也递增硬门禁，分类过宽；详情编辑与工程草稿编辑表现不一致。
- 建议仅说明/样本等变动归草稿；旧物理表/字段删除、类型/连接破坏仍保护。新增无关表不阻断旧快照可另做增量优化。
- 本轮只分析，没有修改门禁逻辑或线上修订。该库具体哪次操作触发、66实际运行修订尚未验证，不把本地定位冒充线上根因已确认。

## 验证与交付边界

- 原功能提交前实测：前端三路径各104项，构建，Spotless、后端1131项（33条件跳过）、打包和浏览器隔离验证通过。
- 本次重新前端构建44.12秒通过并提交最终资源。
- WrenQueryAccessTest、ModelingRevisionTest、DatasetServiceMdlDirtyTest、WrenPublishedProjectTest共15项通过，无跳过。验证当前行为，不代表分类问题已修复。
- 最终文档一致性检查结果见排查报告；未真实生产用户操作、LLM问数或服务器部署。
- 升级本批代码需按docs/migrations/0143-history-message-projection-mysql.sql核对平台库缺列/索引；未在66执行。

下一步：用户查看分析；如实施分类修正，按报告补“说明/采样仍用旧发布版、真实物理破坏仍阻断”的回归，并核对线上旧修订来源，不能直接清标记或自动发布未审草稿。

排查：[本轮报告](../test-reports/20261111-published-draft-query-analysis.md)。UI任务原检查点见[spec126归档](archive/CURRENT_TASK-before-published-draft-analysis-20261111.md)。
