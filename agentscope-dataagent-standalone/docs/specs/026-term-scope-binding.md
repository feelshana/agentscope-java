# specs/026 — 业务术语绑定知识库（撤全局共享）

日期：2026-10-04 ｜ 状态：已确认（用户拍板「术语也是和知识库绑定，不全局共享」，推翻 specs/011 时的「方案 A 全局字典」决策）

## 背景

- 2026-10-04 实证：订单库问数会话的 [KNOWLEDGE_BASE_OVERVIEW] 注入了红海库的 3 条术语（高频/低频/活跃用户）——`SemanticTermEntity` 是全局表，注入端不过滤。
- 术语 CRUD（SemanticTermController）**完全无鉴权**：无 Authentication、无 ownerId 校验，任何登录用户可读写全部术语（多租户漏洞，本次一并补上）。
- 现有数据：scope 字段存自由标签，红海 3 条 scope=groupId（UUID 形态），其余为 "global"。

## 目标

术语按知识库隔离：CRUD、注入、防重判定三个面都按 groupId 过滤；同名词允许存在于不同库。

## 改动清单

### 后端

1. **Entity**（SemanticTermEntity）：新增 `group_id` 列（length 64，nullable 过渡）；`scope` 退役为纯显示遗留（不再参与任何逻辑）。
2. **Repository**（SemanticTermRepository）：新增 `findByGroupIdOrderByCreatedAtDesc`、`findByGroupIdAndTerm`、`findByGroupId`。
3. **启动迁移**（ApplicationRunner 或 CommandLineRunner，放 web/config）：`scope` 形如 UUID 的行 `group_id = scope`；其余（global）保持 `group_id = null`——null 术语不再注入、仅可在原页面看到并删除（不自动删除，防误伤）。
4. **Controller**（SemanticTermController）：路径 `/api/groups/{groupId}/semantic-terms`（GET/POST）+ `/api/groups/{groupId}/semantic-terms/{id}`（PUT/DELETE）；每个端点 `(String) auth.getPrincipal()` → `groupService.getGroup(userId, groupId)` owner 校验（参照 DatasetService#get 模式）；防重查 `findByGroupIdAndTerm`（跨库同名词合法）。
5. **注入端**（DatasetService#semanticTermsText + DataDynamicContextMiddleware 术语段）：按当前会话 DatasetScope 的 groupIds 过滤（与 semanticBusinessRulesText 同款签名）；单库会话只注入该库术语。
6. **文档增强**（DocEnhanceService）：差异分析 prompt 的「# 当前术语」、TERM 提案采纳落库（createTerm）都带当前 groupId。
7. **建模工具**（ModelingToolkit 的 create_term/list_terms/delete_term）：按工具调用的 groupId 上下文读写（toolkit 已持有 groupId）。
8. **旧调用面清理**：grep `semanticTermRepository.findAll` / `findByTerm` 全部替换为按 groupId 版本。

### 前端

9. **api/semantic.ts**：路径改为带 groupId；`SemanticModelingPage` 术语卡与「语义配置」入口按当前库读写。
10. 术语卡片不再显示 scope 标签（显示库名无意义——术语卡就在库详情页内）。

## 验收

- G1：订单库问数会话注入的术语段只含订单库术语（重启后新会话验证）。
- G2：跨库同名术语可各自创建，互不冲突；A 库用户无法经 API 读写 B 库术语（403/404）。
- G3：文档增强 TERM 提案采纳后落当前库；差异分析「# 当前术语」只列当前库。
- G4：全量 mvn test 绿（Repository/Controller/注入过滤补测试，含「看不到别人术语」用例）；npm run build 绿。

## 不做

- MEMORY.md 长时记忆按库分桶（harness 层，维持现状）。
- 术语跨库引用/继承（YAGNI）。
