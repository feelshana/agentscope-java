> Source snapshot: data-agent-multiAgent-Mdl-lyf @ 6a75b6cf (2026-10-14). Original numbering and historical validation retained. See ../README.md and current ADR 0078 for applicability; this is not evidence of validation on the current branch.

# ADR 0050：知识库共享权限与 BI 身份

日期：2026-10-08；更新：2026-10-13。
状态：知识库权限及普通问答每轮选库已实施；BI 身份协议待确认。

## 已确认决策

1. 65 单实例部署，开通 65 对业务数据库的访问；Docker 和 Wren 使用同机环境。
2. 首期不分组织，不做行列级权限；全局管理员管理所有知识库，创建者天然管理。
3. 知识库只有 QUERY（使用）与 MANAGE（管理）；管理包括使用及数据维护、建模、发布、授权和删除。
4. QUERY 看卡片、问答、下载本人生成结果；详情、预览和维护需要管理权限。
5. 分享库数据时保留真实库所有者；当前操作用户的会话、沙箱和附件保持私有。
6. 撤权阻止之后的新查询与续问；已有本人历史和附件保留。
7. 用户于 2026-10-13 最终确认：普通问答可不选/单选/多选/全选，同一会话每轮可切换，保留上下文，新查询仅用本轮范围；全选每次发送展开最新授权。保存提问范围快照。建模仍只处理当前单库。
8. BI 用户同步和管理员 SQL 设置按既定发布方式实施；可信身份接入协议需与 BI 同事确认。
9. 2026-10-13 确认首发沿用单库单来源：需要不同来源时创建多个知识库分别建模，普通问答多选。首发不扩展单库上传/外部表混合或多个外部数据源；实际跨来源问答仍需外部源验收。

## 决策依据与未完成部分

后台维护统一权限判断，接口和工具使用同一套规则。共享问答授权后解析真实库归属，结果仍归提问用户。成员、列表及选库分页加载元数据，查询模型/数据库时复核当前权限。

BI 同步确定用户目录，可信身份交换确定当前请求是谁。稳定用户 ID、同步/停用、管理员映射、iframe 来源和会话过期/退出仍需确认，尚未实现。10 并发仍需资源预算和实测。

## 唯一实施说明

权限矩阵、数据表、接口、界面操作及验收场景统一维护在 [spec 041](../specs/041-knowledge-base-sharing-and-bi-identity.md)，本 ADR 只记录决策。

- [会话与附件持久化](https://github.com/feelshana/agentscope-java/blob/6a75b6cf48a8ee92cf3f0bc139f327ef8e29fe3b/agentscope-dataagent-standalone/docs/adr/0060-durable-session-history-and-artifact-lifecycle.md)
- [简单发布指南](https://github.com/feelshana/agentscope-java/blob/6a75b6cf48a8ee92cf3f0bc139f327ef8e29fe3b/agentscope-dataagent-standalone/docs/manual-release-guide.md)
- [部署与容量评估](https://github.com/feelshana/agentscope-java/blob/6a75b6cf48a8ee92cf3f0bc139f327ef8e29fe3b/agentscope-dataagent-standalone/docs/deployment-and-capacity-review-20261010.md)
