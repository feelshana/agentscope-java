# spec 014: 文档驱动语义差异分析与多类型提案

> 状态：已实施（2026-10-02）

## 背景与目标

当前数据表关联流程已采集 JDBC 注释、低基数枚举样例，并通过 `columnSchemaJson` 写入 MDL 列描述；关系、Cube、View 和术语也已有人工或对话建模入口。因此本规格不重复建设列描述采集，而是补齐 WrenAI `enrich-context` 风格的语义差异分析：

1. 熟悉业务表的开发人员上传说明文档后，系统自动比较文档声明与当前语义状态；
2. 仅把 `NEW`、`PARTIAL`、`CONFLICT` 差异保存为带来源和置信度的多类型提案；
3. 用户在语义建模页逐条采纳或忽略，高影响资产不得自动落库；
4. 用户不上传文档时，可把同样内容粘贴到建模对话，由建模助手按相同路由规则调用既有工具落库；
5. 采纳后的 Relationship、Cube、View 进入 MDL 草稿并标记 DIRTY，Term 与 Business Rule 保存即进入后续问数上下文。

## 范围与路由

| 文档声明 | 提案类型 | 采纳落点 |
|---|---|---|
| 业务词、别名、枚举含义 | `TERM` | `SemanticTermEntity` |
| 默认过滤、软删除、权威表、币种、财务周期、NULL/哨兵语义 | `BUSINESS_RULE` | 组级 `SemanticBusinessRuleEntity` |
| 单列或复合键、方向、基数 | `RELATIONSHIP` | `DatasetRelationEntity`（CONFIRMED） |
| 单表聚合指标与可执行维度 | `CUBE` | `SemanticCubeEntity`（DRAFT） |
| HAVING、窗口、CTE、多表预聚合 | `VIEW` | `SemanticViewEntity`（DRAFT） |
| 当前平台无安全落点或与已确认资产冲突 | `MANUAL_FIX` | 只展示，不允许直接采纳 |

Calculated Column 暂无独立实体和 CRUD；相关声明生成 `MANUAL_FIX`，不得伪装为 Cube 或 View。

## 差异语义

每条候选必须标记：

- `NEW`：当前语义状态中没有等价资产，可采纳；
- `PARTIAL`：已有资产只覆盖部分口径，可采纳为新增资产，不覆盖既有确认内容；
- `CONFLICT`：与已确认资产语义冲突，只展示人工处理建议；
- `COVERED`：当前状态已经完整覆盖，不保存提案。

去重键由 `groupId + type + 规范化标题 + 规范化 payload` 计算；同组已有 PENDING 或已采纳等价项时不重复创建。新任务开始时，旧任务仍未决的提案保留审计记录，但不在“最新任务待审”中混入。

## 两条入口

### 文档上传

```text
PUT /api/dataset-groups/{groupId}/knowledge
  → 保存文档（现有）
  → 触发 KG 构建（现有）
  → DocEnhanceService.triggerAnalyzeQuietly(ownerId, groupId, text, filename)
  → 创建 RUNNING 任务
  → boundedElastic 异步读取当前语义状态、调用 LLM、校验并保存提案
  → READY / FAILED
```

上传主流程不因模型未配置、任务已运行或分析失败而失败。

### 对话建模

建模助手先调用 `list_modeling_state`、`list_terms` 与 `list_business_rules`，把粘贴内容拆成原子声明，按本规格路由。每条高影响变更先展示来源、差异和草案，得到用户明确同意后调用 `add_relation`、`create_cube`、`create_view` 或 `create_term`；业务规则通过新增 `create_business_rule` 保存。遇到 `CONFLICT` 停止并询问，不得覆盖已确认资产；收尾调用 `validate_mdl`。

## 数据模型

### `dataagent_doc_enhance_task`

- `task_id`, `owner_id`, `group_id`
- `source_type`：`UPLOAD | MANUAL`
- `source_label`
- `status`：`RUNNING | READY | FAILED`
- `error_message`
- `created_at`, `finished_at`

### `dataagent_doc_enhance_proposal`

- `proposal_id`, `task_id`, `owner_id`, `group_id`
- `proposal_type`：`TERM | BUSINESS_RULE | RELATIONSHIP | CUBE | VIEW | MANUAL_FIX`
- `classification`：`NEW | PARTIAL | CONFLICT`
- `title`, `summary`, `payload_json`
- `source_quote`, `confidence`：`HIGH | MEDIUM | LOW`
- `fingerprint`
- `status`：`PENDING | ADOPTED | IGNORED`
- `decision_error`, `created_at`, `decided_at`

### `dataagent_semantic_business_rule`

- `rule_id`, `owner_id`, `group_id`
- `name`, `content`, `source_label`
- `created_at`, `updated_at`

业务规则严格按 ownerId/groupId 隔离，只注入当前会话可见知识库。

## LLM 合约

输入包含：文档原文、组内表与物理列、列描述、已有关系/Cube/View/术语/业务规则。模型只输出一个 JSON 对象：

```json
{
  "proposals": [
    {
      "type": "TERM|BUSINESS_RULE|RELATIONSHIP|CUBE|VIEW|MANUAL_FIX",
      "classification": "NEW|PARTIAL|CONFLICT|COVERED",
      "title": "短标题",
      "summary": "自包含中文说明",
      "payload": {},
      "source_quote": "原文片段",
      "confidence": "HIGH|MEDIUM|LOW"
    }
  ]
}
```

约束：

- 表和列必须解析为当前组的物理数据集 ID/物理列名；无法解析时降级为 `MANUAL_FIX`；
- View SQL 仅允许一条 `SELECT/WITH`，并继续经过 `MdlSuggestionService#createView` 校验；
- Cube 成员必须使用当前可解析列；
- Relationship 两侧必须属于当前组，复合键列数一致；
- 来源引文最长 500 字符；summary 最长 1000 字符；payload 最长 12000 字符；
- `COVERED` 不入库；模型不得直接修改任何语义资产。

## 采纳状态机

```text
PENDING ──adopt──> ADOPTED
PENDING ──ignore─> IGNORED
CONFLICT / MANUAL_FIX：只能 ignore 或人工在现有编辑器处理
```

采纳路由复用既有服务：

- TERM → `MdlSuggestionService#createTerm`
- RELATIONSHIP → `MdlSuggestionService#addManualRelation`
- CUBE → `MdlSuggestionService#createCube`
- VIEW → `MdlSuggestionService#createView`
- BUSINESS_RULE → `SemanticBusinessRuleRepository#save`

MDL 类型采纳后调用 `DatasetGroupService#markMdlDirty`。采纳失败时提案保持 PENDING，并记录/返回中文错误；不得部分写入。发布仍只在语义建模页执行。

## API 与鉴权

所有端点先调用 `DatasetGroupService#getGroup(userId, groupId)`：

- `POST /api/dataset-groups/{groupId}/modeling/enhance`：按已上传文档手动重跑；
- `GET /api/dataset-groups/{groupId}/modeling/enhance`：最新任务与其提案；
- `POST .../enhance/proposals/{proposalId}/adopt`；
- `POST .../enhance/proposals/{proposalId}/ignore`。

提案服务再次校验 ownerId/groupId；跨租户、跨组和不存在统一返回 404。

## 前端

在语义建模页“业务术语”之前新增“文档语义增强”卡：

- 展示 RUNNING、READY、FAILED 和重新分析入口；
- RUNNING 每 3 秒轮询；
- 按类型、差异分类、置信度显示提案；
- 展示 summary、来源引文和 payload 摘要；
- NEW/PARTIAL 提供采纳/忽略；CONFLICT/MANUAL_FIX 只提供忽略并提示人工处理；
- 采纳后刷新 overview、MDL 和提案列表。

## 验收标准

1. 上传文档不受增强分析失败影响，模型可用时异步生成多类型提案。
2. 相同内容重跑不会产生重复待审提案，已覆盖项不生成提案。
3. 采纳 TERM/BUSINESS_RULE 后下一轮问数上下文可见，且业务规则不会跨租户或跨组泄漏。
4. 采纳 RELATIONSHIP/CUBE/VIEW 后实体落库、组状态变为 DIRTY，MDL 可校验。
5. CONFLICT/MANUAL_FIX 不可直接采纳，不覆盖已确认资产。
6. bob 访问或操作 alice 的任务/提案返回 404。
7. 未配置模型、无文档或模型返回非法 JSON 时任务为 FAILED，保留中文错误。
8. 把验证文档正文粘贴到建模对话，可通过现有工具和 `create_business_rule` 达到等价落点。
9. 本地 MySQL 验证表包含关系、复合键、软删、币种和复杂指标场景；附可上传文档与问数清单。

验证资产：

- `docs/validation/014-retail-fixture.sql`：本地 MySQL 五张幂等测试表与固定样例数据；
- `docs/validation/014-retail-semantic-source.md`：可上传或粘贴到建模对话的业务文档；
- `docs/validation/014-retail-verification.md`：双入口操作步骤、12 个问数问题与预期答案。

## 测试要求

- `DocEnhanceServiceTest`：解析、COVERED 过滤、去重、类型校验、状态机、采纳路由、冲突不可采纳、失败状态；
- `SemanticModelingControllerTest`：四个端点委托和跨租户 404；
- `DataDynamicContextMiddlewareTest`：无自由文档时术语/业务规则仍可注入，业务规则按组隔离；
- `ModelingToolkitTest`：业务规则工具的租户隔离与落库；
- 前端 `npm run build`；后端测试与 Spotless 全绿。

## 不做的事

- 自动采纳或自动发布；
- 覆盖、删除既有已确认资产；
- 新建通用 Calculated Column 编辑能力；
- 重复采集列描述或枚举样例；
- 执行文档中的任意 SQL 或非 SELECT/WITH SQL。

## 关联

- ADR 0028：文档驱动语义差异分析与人工采纳
- specs/010：关系、Cube 与 MDL 发布
- specs/011：术语与 View
- specs/013：对话式建模
- WrenAI `enrich-context`：Lane 分析、落点路由、验证与回滚原则
