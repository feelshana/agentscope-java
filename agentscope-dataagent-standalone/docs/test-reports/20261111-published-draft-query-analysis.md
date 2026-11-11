# 已发布知识库有草稿时问数失败：代码排查（2026-11-11）

## 范围与状态

用户要求提交现有个人分支并合入主线，同时分析线上V14已有发布、V15草稿未发布时其他用户问数失败。现有功能提交c943edfa已推送个人分支liyufan_dev并快进合并、推送develop-2.0，无冲突。本报告仅分析现有逻辑，不包含问数判定修复；未连接66、修改线上数据库或部署。上线本批历史功能前须按0143迁移核对平台库。

## 已从代码确认的行为

1. ADR0128第6项与架构明确区分数据变更和语义草稿。`DatasetGroupService#markMdlDirty`、`WrenProjectAccess#changed`及字段整理/模型编辑只进入`DatasetGroupRepository#markDraftChanged`，将mdl_state设为DIRTY，不增加modeling_revision。因此普通草稿不会单独导致问数停止。
2. `WrenPublishedProject#prepare`从published目录复制模型、规则和编译MDL至runtime/query-project，以已发布mdlVersion复用，不读取workspace草稿。V14可继续服务，只有成功发布才增加版本、替换快照并触发派生工程更新。草稿“V15待发布”不等于数据库已切到V15。
3. 截图原文来自`WrenQueryAccess#resolve`的409异常。它的确切条件是`DatasetGroupEntity#isModelingPending`，即modeling_revision > published_modeling_revision；不是单看DIRTY或未发布草稿，也不是同事缺少MANAGE权限。尚未有任何发布版本的知识库另有“尚未发布”门禁。
4. 发现过宽的变更分类：`DatasetService#updateDescription`、`updateColumnDescriptions`、`refreshSample`和旧`saveKnowledge`均调用`DatasetService#markMdlDirty`，进而`requireModeling`递增修订号。描述、字段说明/显示名、重新采样也会让旧发布版本被拒绝。新增/移除数据集同样使用此标记。
5. 两类说明编辑路径存在不一致：建模工程内字段编辑走语义草稿，数据集详情页字段说明走上述数据服务接口，会设置硬门禁。即使后来只继续编辑普通模型，这个旧标记也不会自行解除；发布时`MdlPublishService`只覆盖该次发布开始时的修订，保留并发新变更。
6. 前端知识库详情使用workflow.queryAvailable（同时检查isModelingPending）控制提问入口；聊天选库卡的canQuery表示访问权限且非删除中，不等于发布可用性。历史会话/已选卡仍可发送请求，后端再次校验后把409原因显示在答案区。因此隐藏前端错误不能解决后端拦截。

## 对线上这一次的结论边界

截图可以确认命中了“有未覆盖的数据修订”的后端门禁。不能凭截图确认是哪个编辑请求设置标记，也不能确认66运行版本与本地完全一致。若只做普通模型、关系、规则或计算字段编辑，按当前设计应继续使用V14；需要结合线上运行修订、操作日志及该库两项修订字段确定为何已有硬门禁。不是看到V15待发布就能断定该状态来自普通草稿。

可由服务器操作者只读核对（不在本轮执行）：

```sql
SELECT id, name, mdl_state, mdl_version,
       modeling_revision, published_modeling_revision
FROM dataagent_dataset_group
WHERE id = '<知识库ID>';
```

修订差值只能证明门禁尚未覆盖，不能证明物理表安全。不得直接把两项修订强制改相同、删除保护判断或未经核对就发布草稿。

## 建议方案（尚未实施）

- 从未发布：继续提示先完成建模并发布，不能直接问未发布数据。
- 已发布且仅改语义模型、字段说明/显示名、业务规则、计算字段、文档或样本：保留已发布工程可用，新口径发布后才用于问数。优先修正现有说明/采样接口的变更分类，避免停用整个知识库。
- 仅新增不影响旧快照的数据集：旧版继续可用，新表等发布后再进入问数范围；作为后续增量优化，须核对来源注册和权限，不直接取消所有requireModeling。
- 删除旧版依赖的物理表/字段、改变类型或使原连接失效：旧模型可能确实失效，应提示受影响对象并保留保护。未来如要求这类变更也不中断，需保留旧物理数据版本或做发布前资源生命周期治理。
- 页面分别标识“线上V14可用，有草稿待发布”和“已发布模型依赖的数据已失效”，避免统一显示为待发布，降低误判。

以上不新增架构决策；修正分类属恢复既有ADR0128约定，具体实施与验收留待后续。不能保证尚未读取现场数据的V14可通过强制解除标记恢复。

## 本次验证

在已合并c943edfa源码上执行：`mvn -B test -Dtest=WrenQueryAccessTest,ModelingRevisionTest,DatasetServiceMdlDirtyTest,WrenPublishedProjectTest -Dskip.installnodenpm=true -Dskip.npm=true`。15项全部通过，无跳过，验证真实JPA原子修订/普通草稿不增加硬门禁、问数权限与未发布门禁、说明接口现有requireModeling调用及发布工程复用。测试通过说明当前实现具有这些行为，不表示上述过宽分类合理或已修复。

原功能提交前已有全量1131项（33条件跳过）、前端三个路径各104项、Spotless、构建及封装验证；本次重新前端构建成功并提交最终静态资源。最终DocsConsistencyTest 5项全部通过。未执行真实线上库或LLM端到端验收。
