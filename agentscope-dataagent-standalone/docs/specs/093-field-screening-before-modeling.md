# spec093：建模前字段整理（筛选纳入字段、补充说明）

> 状态：用户已确认（2026-10-23），已实施，待真实验收。沿用 [spec092](092-draft-and-review-modeling-workflow.md) 的草稿与发布流程。SQL 定义模型（ref_sql）经讨论暂不做。

## 背景与目标

上传的 Excel 和关联的外部表会把全部字段带进语义模型。ETL 批次、哈希、入库时间等技术字段会拖慢建模、产生无意义的确认问题，并干扰问数。目标：采样完成后、建模开始前，由建模人员逐表确认纳入哪些字段并补充说明；未纳入的字段完全不进模型。

## 方案概述

### 1. 流程

上传 / 关联 → ① 采样 → **② 字段整理（每次必做）** → ③ 时间范围（仅事实表）→ ④ 业务文档（可选）→ ⑤ 开始自动建模。建模页顶部显示五步进度。字段整理未完成时不能进入 ③，也不能开始建模。

### 2. 字段整理

- **AI 预判**：采样完成后，对每张新表调用一次模型，输入只有字段名、类型、采样样例和空值率，不查数据库。每个字段判为 BUSINESS / TECHNICAL / UNKNOWN，附一句理由。业务字段默认纳入，技术和不确定字段默认不纳入，不确定的整行高亮。模型不可用时全部视为 UNKNOWN、全部纳入，不阻塞流程。
- **用户操作**：勾选或取消单个字段；"按 AI 建议勾选""全不选"批量操作；按类别筛选；编辑业务说明（预填采样生成的说明）。用户改过的说明来源记为 user。
- **强制规则**：
  - 事实表的时间字段（时间范围配置中的 timeColumns，未配置时取采样识别的时间列）纳入且锁定；
  - 同名出现在多张表中的字段标为"关联键"，取消时提示"取消后无法与其他表关联"；
  - 每张表至少纳入 1 个字段；
  - 每张表点"确认此表"才算完成，状态为待整理 / 整理中 / 已确认。
- **存储**：纳入状态、AI 判断及理由、用户说明，保存在平台库 `DatasetEntity` 的字段元数据中（`column_schema_json` 中每列增加 `included`、`role`、`roleReason`、`descriptionSource`），另加表级 `field_screening_confirmed`。
- **生效**：
  - 生成基础模型（`MdlSeeder#reconcile`）时只写入 `included=true` 的字段；
  - reconcile 补列时同样跳过未纳入字段，不能在发布时把排除的字段加回来；
  - 用户说明写入列描述，并在 `properties` 标注 `description_source: user`；
  - 建模提示词与上下文说明：`description_source: user` 的描述不得改写，只补充没有说明的字段。

### 3. 建模后调整

- "资料"页每张表新增"字段"入口，打开同一整理界面，显示当前状态。
- 保存后：
  - 新纳入的字段写入草稿模型；取消纳入的字段从草稿模型删除；说明的修改写入列描述；
  - 修改记入 `DraftChangeLog`，来源为 edit；
  - 该表登记为"需要重新建模"，建模对话自动排队一条只处理这张表的请求：为新字段补说明，检查引用了被删字段的规则、关系和问题 SQL，并列出受影响项。
- 删除前，平台扫描草稿中的 `knowledge/rules`、`relationships.yml`、`knowledge/questions` 和已确认问答。引用了被删字段的项会列给用户，确认后才删除。
- 和其他修改一样，在草稿栏统一发布。

### 4. 主流程变更

需同步 ARCHITECTURE_zh.md：第 14 章（入口与增量建模、建模页与入口），以及数据集生命周期与外部表关联相关描述。

## 影响面

- **后端**：
  - `DatasetService`：采样后写入初始纳入状态，调用 AI 预判；
  - 新增 `FieldScreeningService`：AI 预判、保存整理结果、建模后调整、删除前引用检查；
  - `MdlSeeder#reconcile`：只处理纳入的字段；
  - `WrenProjectContextMiddleware` 与 `DataAgentConfig.MODELING_SCRIPT`：增加用户说明不得改写的规则；
  - 新接口 `GET/PUT /api/dataset-groups/{id}/datasets/{datasetId}/fields`，以及 `POST .../fields/suggest`。
- **前端**：
  - 新增字段整理页组件（上传和关联两条路径共用）；
  - `SemanticModelingPage`：五步进度，字段整理放在时间范围之前；
  - "资料"页的字段入口；
  - `api/datasets.ts`。
- **数据**：
  - `dataagent_dataset` 新增列 `field_screening_confirmed`（BOOLEAN，默认 false），迁移脚本 `docs/migrations/0095-field-screening-mysql.sql`；
  - `column_schema_json` 扩展字段为向后兼容的 JSON，不需要迁移；
  - 历史数据集视为全部纳入、已确认，不做数据迁移。
- **文档**：ARCHITECTURE_zh.md 对应章节；README 升级说明加入 0095；spec 索引。

## 验收标准

1. Given 关联 3 张外部表，When 采样完成，Then 进入字段整理页，每个字段都显示 AI 判断和理由，技术字段默认不纳入。在 3 张表都确认之前，"完成整理"不可点，也不能开始建模。
2. Given 事实表 report_visit 的时间字段 visit_time，Then 该字段默认纳入且无法取消。
3. Given 取消 etl_batch_id 等技术字段后完成建模并发布，Then 草稿和已发布的 `models/report_visit/metadata.yml` 都不含这些字段，再次发布也不会加回来；问数时查询该字段失败。
4. Given 用户把 account 的说明改为"访问账号，wx 结尾为外协"，When 自动建模完成，Then 列描述保留该内容，标注 `description_source: user`，助手没有改写。
5. Given 已发布的知识库，When 在"资料"中为 report_visit 新纳入 asset_type 并取消 project_name，Then 两项修改进入草稿；保存前提示关系 report_visit_project 会失效；保存后对话自动排队"重新建模此表"，且只处理 report_visit。
6. Given 上传 Excel，Then 同样经过字段整理，规则与外部表一致。
7. Given 用户 bob 没有该知识库的 MANAGE 权限，When 调用字段整理接口，Then 返回无权限，也看不到字段和样例。
8. Given 模型服务不可用，When 进入字段整理，Then 字段全部标为 UNKNOWN 并默认纳入，可以继续流程。

## 不做的事

- 不做 SQL 定义模型（ref_sql）、视图或物化表的创建。
- 不在字段整理中改字段名、类型，也不做数据清洗。
- 不对历史知识库做批量字段整理或数据迁移。
- AI 预判不执行任何数据库查询，只用已有的采样资料。
- 不自动删除已确认问答，只列出受影响项，由用户处理。

## 测试要求

- `FieldScreeningService`：预判结果解析与降级；时间字段锁定；关联键提示；至少纳入 1 个字段；删除前引用检查（规则、关系、问题 SQL）。
- `MdlSeeder#reconcile`：未纳入字段不写入，再次 reconcile 不加回；纳入字段新增时补列。
- 接口：MANAGE 校验，覆盖"看不到别人的数据"；未确认时开始建模被拒。
- 提示词：`DataAgentConfigTest` 断言 user 来源的说明不得改写。
- 前端：`npm run build`；按验收标准 1–8 真实走一遍并记录日志。

## 关联

- 沿用：[spec092](092-draft-and-review-modeling-workflow.md)（草稿、DraftChangeLog、建模页）
- 讨论：2026-10-23 会话中的字段整理原型
