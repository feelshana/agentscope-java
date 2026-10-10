# DataAgent 待办

## 知识库工作台页面

- 2026-10-10：暂停知识图谱/树结构目录展示，默认关闭 GraphRAG；合并 Wren 建模与问答反馈提交，待整体试用，见 [ADR 0068](adr/0068-suspend-knowledge-graph-and-tree.md) / [spec 057](specs/057-suspend-knowledge-graph-and-tree.md)。

- 第一版视觉与第二版卡片菜单已迁移到真实前端，见 [ADR 0053](adr/0053-knowledge-workspace-layout.md) / [spec 044](specs/044-knowledge-workspace-layout.md)。待用户预览及真实上传、关联、编辑场景验收。
- 字段类型变更：当前更新接口仅支持字段说明；类型只读。数据集描述已补齐独立保存接口，待人工验收。

## 上线准备与 Linux 发布

- 审查清单：[2026-10-09 上线准备审查](production-readiness-review-20261009.md)。
- 优先建议：生产密钥/管理员初始化、版本化数据库迁移、WebFlux 阻塞调用、问答并发和 Wren 进程预算、单实例 Linux 发布及备份恢复演练。
- 状态：代码审查已完成，新增事项待排期；本次仅补文档，尚未实施上述能力。
- 集群部署文档已标记为历史目标示例，当前版本不能直接据此认定具备完整多实例能力。

## 知识库共享权限与 BI 身份接入

- 状态：2026-10-10 已确认两档权限及历史保留规则；身份协议及多库范围待确认，尚未实施。
- 设计草案：[ADR 0050](adr/0050-knowledge-base-sharing-and-bi-identity.md)、[spec 041](specs/041-knowledge-base-sharing-and-bi-identity.md)。
- 已确认：QUERY/MANAGE 两档、全局管理员管理所有库、不分组织、QUERY 仅卡片和问答且可下载本人结果、撤权保留已有历史与附件、首期不做行列权限。
- 待确认：BI 稳定身份及可信免登录、同步/停用和管理员映射；是否首期一次仅选一个知识库。
- 部署已选 65，开通业务数据库访问；10 并发需完善资源预算后验证，见 [部署与容量评估](deployment-and-capacity-review-20261010.md)。
- 恢复条件：产品和安全规则确定后，更新 ADR/spec，再排期实现、迁移和验收。

## 整体代码审查其余事项

2026-10-09：用户先处理第 1、3、4、5 项，其余暂缓。待讨论：

- 第 2 项：令牌失效、用户状态与角色变更、外部源凭证加密。
- 第 6 项：长会话历史增量投影与运行状态缓存。
- 第 7 项：上传资源限制、导入事务与失败补偿。
- 第 8 项：会话知识库范围的身份键及持久化。
- 第 9 项：通用前端/后端职责拆分、统一请求层与按需加载。

前端历史样式已选择迁移：侧栏、回答排版、过程展示、知识库改名及数据源表单见 [ADR 0052](adr/0052-chat-ui-and-knowledge-base-editing.md) / [spec 043](specs/043-chat-ui-and-knowledge-base-editing.md)。完整结果卡片化和真实浏览器验收仍待开展。
