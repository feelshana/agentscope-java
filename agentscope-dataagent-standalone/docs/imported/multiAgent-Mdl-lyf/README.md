# data-agent-multiAgent-Mdl-lyf 来源文档

快照：`6a75b6cf48a8ee92cf3f0bc139f327ef8e29fe3b`，2026-10-14；包括此前 `a29cc754` 上传改动及 `32c6d292` 之前已移植的平台改动。每份文件保留原文，并加来源标识。

仅作两类导航调整：添加来源标识；快照内未收录的相对链接改指 GitHub 上固定提交的原文件，避免误指当前分支同编号文档。旧链接中的来源验收仍按历史理解。

这些文件是来源决策和需求的可追溯记录。原编号与当前分支存在冲突，禁止只凭编号判定同一决策；当前集成边界见 [ADR 0078](../../adr/0078-platform-capacity-followup-integration.md)、[ADR 0077](../../adr/0077-shared-knowledge-official-wren-integration.md)、[spec 067](../../specs/067-platform-followup-integration.md)。来源的历史验收不代表本轮测试结果。

| 主题 | 来源 ADR | 来源 spec | 当前适用范围 |
| --- | --- | --- | --- |
| 知识库 QUERY/MANAGE、BI 身份 | [0050](adr/0050-knowledge-base-sharing-and-bi-identity.md) | [041](specs/041-knowledge-base-sharing-and-bi-identity.md) | 按 ADR 0077 适配当前官方 Wren；会话/附件归请求用户 |
| 连接边界与聊天可靠性 | [0051](adr/0051-connection-boundaries-and-chat-reliability.md) | [042](specs/042-connection-boundaries-and-chat-reliability.md) | 已合入的权限、连接校验、失败提示；不恢复旧语义路由 |
| 聊天 UI 与知识库编辑 | [0052](adr/0052-chat-ui-and-knowledge-base-editing.md) | [043](specs/043-chat-ui-and-knowledge-base-editing.md) | 已移植的平台界面行为，建模主流程按当前分支 |
| 原生 HITL 已解析调用 | [0053](adr/0053-native-hitl-resolved-call-boundary.md) | — | 框架通用调用边界，不增加业务审批 |
| 知识库工作台布局 | [0053 布局](adr/0053-knowledge-workspace-layout.md) | [044](specs/044-knowledge-workspace-layout.md) | 本轮核对补入的来源文档；KnowledgeWorkspace 的布局/文件树已移植，建模流程仍以当前版本为准 |
| 文件树扫描 | [0054](adr/0054-workspace-tree-scan-boundary.md) | [045](specs/045-workspace-tree-scan-boundary.md) | 通用目录扫描边界 |
| 停用图谱/旧树 | [0068](adr/0068-suspend-knowledge-graph-and-tree.md) | [057](specs/057-suspend-knowledge-graph-and-tree.md) | 不建设 KG；当前官方 Cube 支持按当前 ADR 0069 |
| 可重试删除 | [0069](adr/0069-retryable-knowledge-base-deletion.md) | [058](specs/058-retryable-knowledge-base-deletion.md) | 已移植删除日志、取消与重试 |
| 问答容量 | [0070](adr/0070-chat-capacity-and-queuing.md) | [059](specs/059-chat-capacity-and-queuing.md) | 本轮集成；当前决策为 ADR 0078 |
| Wren 门锁与懒沙箱 | [0023](adr/0023-wren-instance-call-serialization.md)、[0047](adr/0047-lazy-sandbox-and-orphan-cleanup.md) | — | 本轮公平门锁、容量与空闲驱逐 |

上传提交没有单独新增 ADR/spec；实施约束及验收合并到当前 spec 067、ADR 0078 和系统文档。来源云演练/体验/发布记录是历史参考，当前发布使用根 pom.xml 与 README；不照搬来源不同的打包说明。

2026-10-15 全范围核对后，档案包含 11 份 ADR、8 份 spec。另补入 production-readiness-review-20261009、frontend-ui-migration-review-20261009、experience-guide/results-20261010 与 previews/ 设计资料；这些不是当前运行页面。完整逐提交状态见 [集成核对表](../../source-branch-integration-audit-20261015.md)，当前文档事实源见 ADR 0079。
