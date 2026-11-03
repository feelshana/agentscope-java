# spec107：对话建设语义资产（View、ref_sql、Cube），核查和问题验证后发布

> 状态：P0、P1 已实施；验证方式已按 2026-11-02 用户反馈调整，P2 已实施未实测，P4 未开始。2026-11-02 起资产按用途选型并配默认使用规则（[ADR0124](../adr/0124-asset-kind-by-purpose-with-default-use-rule.md)）。决策见 [ADR0123](../adr/0123-conversational-semantic-assets.md)，验收见 [报告](../test-reports/20261101-spec107-p0-p1-acceptance.md)。

## 背景与目标

基础建模已能完成上线和问数，但多表 JOIN、窗口函数和固定口径没有沉淀位置：基础建模不读写 Cube/View，页面也没有入口。本功能让数据开发在语义建模页通过对话起草 View、`ref_sql`、Cube，核查通过后用问题验证，再走现有草稿发布。

受益人是有 MANAGE 权限的数据开发。普通问数用户只在资产发布后受益。

## 用户流程

```text
发起需求 → 助手澄清 → 写入草稿并自动校验 → 待核查
        → 用户核查 → 待验证 → 用问题验证 → 验证通过 → 统一发布
不通过：对话修改或手工编辑，状态回到待核查
```

1. **发起**：在语义建模页新增“语义资产”页签，提供三个入口：描述需求、粘贴已有 SQL、选择已确认问题。
2. **澄清**：助手用现有确认卡询问粒度、口径和过滤。没有依据的口径不写入资产。
3. **起草**：用户用业务语言描述需求，不说资产类型；助手按下表选择，写入草稿后自动校验。页面和回复把 `ref_sql` 模型称为"加工表"、View 称为"查询模板"，资产页给出按当前知识库模型生成的示例需求，只涉及本库的模型、字段、关系和物理表，不重复已有资产。

   | 需求 | 资产 | 校验 |
   |---|---|---|
   | 单行衍生列（如按账号后缀分类） | 模型计算字段，走草稿审阅，不进语义资产页 | `context validate --strict`、`dry-run` |
   | 把一张表按固定规则筛选、排除或去重后长期代替原表统计 | 加工表（`ref_sql` 模型） | `context validate --strict`、`dry-run`；必须用物理全限定名 |
   | 多个模型固定拼接，或窗口函数、CTE 算出的可直接查询结果 | 查询模板（View） | 同上；用逻辑模型名，可建在加工表上 |
   | 口径已定的高频指标 | Cube | `context validate --strict`、`cube query --sql-only` |

   每个加工表或查询模板都配一条默认使用规则：按官方 enrich-context 写回路由追加到 `knowledge/rules/` 的 `## Default filters` 或 `## Canonical tables`，写明哪些统计默认使用该资产、什么情况改用原模型。规则在草稿栏查看，随资产一起发布。问数助手通过 `get_instructions` 读到规则后选表，平台不做路由。
4. **核查**：核查卡展示用途、粒度、依赖、JOIN 键和基数、CASE WHEN 口径及来源、时间列、风险提示和 SQL 差异。依赖受限事实表时，用户必须从资产输出列里选择时间列；不依赖时不显示这一项。提供核查通过、对话修改、手工编辑三个操作。
5. **验证**：在资产页右侧输入业务问题，也可以点选已确认问题，只取问题文字。助手只基于当前资产生成 SQL，页面展示 SQL，用户可修改后运行。结果来自草稿副本；线上已有该资产时同时展示线上结果。用户判定“符合预期”或“有问题”。SQL 没有引用当前资产时拒绝运行，不能算验证通过。判定为“符合预期”的题默认同时保存为问答（[spec108](108-question-review-on-context-change.md)）。

6. **发布**：沿用草稿栏。新增或修改的高级资产未通过验证时，发布被拒绝并列出原因。

## 方案概述

分四期实施（原 P3 验证问答已并入 P1），每期可独立验收。

**P0 前置修复**
- 拆分 `DatasetService#markMdlDirty`：数据集和表结构变化继续递增 `modelingRevision`；语义草稿修改只更新草稿状态，不影响 `WrenQueryAccess#resolve`。`ModelingWorkflowService` 用草稿差异判断待发布。
- 时间门禁：`MdlPublishService` 发布时写入资产依赖；`FactQueryWindowPolicy#rules` 增加派生规则，表名为资产名，时间列来自核查记录，窗口实时取依赖表最小值。草稿验证使用草稿依赖。

**P1 View**
- `ModelingTurnPurpose` 增加 `ASSET`；`ModelingTurnRules` 增加资产规则；`MODELING_SCRIPT` 中的 Cube/View 禁令改为“资产轮次外”。
- `WrenProjectAccess` 仅在资产轮次放开 `views/`。外部表查询拒绝规则不变，Agent 只用 `dry-plan`、`dry-run`。
- 新增 `SemanticAssetService`、`SemanticAssetController`，核查记录存于工程 `.platform/asset-reviews.json`，不发布、不暴露给 Agent，绑定资产内容哈希。
- 新增资产状态：`DRAFT`、`REVIEW_PENDING`、`VERIFY_PENDING`、`VERIFIED`，由内容哈希和记录计算，不单独存状态。
- 验证：`AssetSqlGenerator` 只用资产名、说明、输出列和时间限制生成 SQL，不提供资产内部定义，避免模型绕过资产；`MdlPublishService#verifyAssets` 一次编译后逐题执行；经草稿依赖的时间门禁；线上已有资产时同时查询已发布快照。
- 手工编辑：保存走 `WrenProjectAccess` 路径检查、YAML 校验、工作区锁，并以 `EDIT` 写入 `DraftChangeLog`。
- `MdlPublishService#publish` 增加资产放行检查。

**P2 ref_sql**
- 放开 `models/<名称>/ref_sql.sql` 的资产轮次规则。提示词要求物理全限定名和“不用 View 的原因”。发布检查必须执行 `dry-run`。

**P4 Cube**
- 先将 Wren 升到 0.16 以上并回归。资产轮次放开 `cubes/`、`cube_proposals.md`、`cube query --sql-only`。问题验证增加 Cube 执行方式，`WrenScopedMcpClient#callTool` 按 `time_dimension` 校验 `query_cube`。

接口（均先 `(String) auth.getPrincipal()`，再经 `DatasetGroupService#getGroup` 要求 MANAGE）：

| 接口 | 用途 |
|---|---|
| `GET /api/dataset-groups/{id}/assets` | 资产列表和状态 |
| `GET /api/dataset-groups/{id}/assets/examples` | 按当前草稿模型生成的需求示例 |
| `GET /api/dataset-groups/{id}/assets/{kind}/{name}` | 文件、核查卡、验证结果 |
| `PUT /api/dataset-groups/{id}/assets/{kind}/{name}` | 手工保存草稿 |
| `POST /api/dataset-groups/{id}/assets/{kind}/{name}/review` | 核查结论和时间列 |
| `POST /api/dataset-groups/{id}/assets/{kind}/{name}/generate` | 按问题生成 SQL，不执行 |
| `POST /api/dataset-groups/{id}/assets/{kind}/{name}/verify` | 批量运行问题和 SQL |
| `POST /api/dataset-groups/{id}/assets/{kind}/{name}/verdict` | 记录用户判定 |

实施时更新 ARCHITECTURE_zh.md 第 3 章（建模与发布）、第 4 章（门禁与问数）、第 14 章，以及 AGENTS.md 第 5、6 节的建模边界。

## 影响面

- 后端：`ModelingTurnPurpose`、`ModelingTurnRules`、`DataAgentConfig`、`WrenProjectAccess`、`WrenProjectFilesystem`、`ModelingHitlMiddleware`、`OfficialWrenToolkit`、`MdlPublishService`、`FactQueryWindowPolicy`、`DatasetService`、`WrenQueryAccess`、`ModelingWorkflowService`、`WrenScopedMcpClient`；新增 `SemanticAssetService`、`SemanticAssetController`。
- 前端：`pages/configure/SemanticModelingPage.tsx` 增加页签；新增 `components/modeling/` 下的资产列表、核查卡、编辑器、验证面板；`api/` 增加资产接口；`DraftBar` 展示未通过原因。
- 数据：不新增数据库表。核查记录和依赖存于工程平台目录，MDL 不变。
- 文档：ADR、spec 索引、ARCHITECTURE_zh.md、AGENTS.md。无配置项变更。

## 验收标准（Given-When-Then）

1. Given 已发布知识库，When 用户在资产模式修改草稿，Then 问数继续使用已发布版本，不返回 409。
2. Given 用户上传新表或修改表结构，When 未重新发布，Then 问数行为与现有一致。
3. Given 自动建模或普通对话轮次，When 助手读写 `views/`，Then 被拒绝且提示沿用现状。
4. Given 资产轮次，When 助手读写 `views/`，Then 操作成功且路径逃逸、平台文件写入仍被拒绝。
5. Given 资产轮次，When 助手查询外部表数据，Then 被拒绝且不执行查询。
6. Given 用户粘贴多表 SQL，When 助手起草 View，Then 草稿出现 View，校验结果和核查卡可见。
7. Given 资产待核查，When 用户未核查就点发布，Then 发布被拒绝并显示资产名和原因。
8. Given 资产已验证通过，When 其内容被修改，Then 状态回到待核查，发布再次被拒绝。
9. Given 资产依赖受限事实表，When 没有确认时间列，Then 无法核查通过，也无法发布。
10. Given 已发布 View 依赖受限事实表，When `run_sql` 查询该 View 且没有时间条件，或超出窗口，Then 被门禁拒绝；带合规时间条件时放行。
11. Given 用户输入问题，When 生成并运行 SQL，Then 看到草稿结果（线上已有资产时同时看到线上结果），线上版本和草稿内容都没有变化；SQL 没有引用当前资产时拒绝运行。
12. Given `ref_sql` 使用逻辑名，When 发布，Then `dry-run` 失败、发布被拒绝，旧快照保持可用。
13. Given Cube 依赖受限事实表，When `query_cube` 没有范围合规的 `time_dimension`，Then 被门禁拒绝。
14. Given 只有 QUERY 权限的用户，When 调用任何资产接口，Then 被拒绝，且看不到草稿内容。
15. Given 用户只用业务语言提出默认过滤或固定拼接的需求，When 资产轮次完成，Then 助手分别起草加工表或查询模板，回复不出现 View、ref_sql 等技术名词，并在 `## Default filters` 或 `## Canonical tables` 下追加默认使用规则。
16. Given 资产和规则已发布，When 问题不提过滤条件，Then 问数使用该资产；When 用户明确要求包含被排除的数据，Then 改用原模型。
17. Given 新建加工表时先保存 `metadata.yml`，When 保存，Then 返回资产详情而不是 404；先保存 SQL 文件时提示先保存 `metadata.yml`，不留下写了一半的目录。

## 不做的事

- 不改基础建模的自动流程和官方技能文件。
- 不让 Agent 自动发布、自动核查，或根据线上问题自动改模型。
- 不做基准集、发布回归和线上点踩问题回流，这些另写 spec。
- 不做 SQL 重复逻辑的自动提炼。
- 不让 View 引用 View，不自建 `ref_sql` 物化，不改写 SQL 防扇出。
- 助手不在资产定义里写固定日期，写入时由 `WrenProjectFilesystem` 拒绝；用户手工编辑不受限，核查页显示警告。
- 不做用户级草稿分支，不新增数据库表。
- P0–P3 不开放 Cube 写入。

## 测试要求

- 单元测试：轮次路径权限；状态随内容哈希变化；发布放行；派生门禁规则（含窗口取最小值、配置实时生效）；`markMdlDirty` 拆分。
- 多租户：QUERY 用户和其他用户看不到草稿与验证结果；验证排队期间被撤销 MANAGE 时拒绝执行。
- 真实验收：
  - 用真实 Wren 跑通一个 View 和一个 `ref_sql`，覆盖 MySQL；Doris 环境可用时补测，不可用时报告注明。
  - `dry-run` 已核实：MySQL 连接器走 `EXPLAIN`，不读取业务数据行（Wren 0.15.0 `connector/mysql.py`）。Doris 走 MySQL 协议，待实测。
  - 验证 View 作为 Cube `base_object`，通过后才开放 P4 跨表 Cube。
  - 核对 `MdlQuestionStore#requireCoverage` 是否接受引用 View 的问题 SQL。
- 全量运行 AGENTS.md 第 8 节验证命令。

## 关联

- ADR：[ADR0123](../adr/0123-conversational-semantic-assets.md)；相关 [ADR0069](../adr/0069-wren-native-modeling-and-query-agents.md)、[ADR0107](../adr/0107-basic-modeling-closes-cube-and-view-files.md)、[ADR0111](../adr/0111-draft-changes-reviewed-before-publish.md)、[ADR0112](../adr/0112-rule-first-sql-window-gate.md)
- 调研：`reports/Wren 语义层渐进建模路线.md`（未提交）
