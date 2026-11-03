# 当前任务：语义资产与问答复核（spec107、spec108，2026-11-03 Asia/Shanghai）

## 分支与状态
- 分支：`feature/semantic-asset-modeling`，基于本地 `develop-2.0` 的 `d0d3be67`，全部改动未提交、未推送。
- `develop-2.0` 比 `origin/develop-2.0` 多 2 个提交（含 liyufan_dev 合并），等用户验收后再决定推送。
- 服务 8080 运行本分支打包的 jar；5173 未启动。桌面应用可能自动停止服务。
- `application.yml` 有本地改动，含本地密钥，不得提交。

## 目标
- spec107：数据开发通过对话起草加工表（ref_sql）、查询模板（View），核查、问题验证后发布（ADR0123、ADR0124）。
- spec108：口径变更后按依赖复核已确认问答；资产验证"符合预期"的题可存为问答（ADR0125）。

## 已完成
- spec107 P0–P2（见 [报告](../test-reports/20261101-spec107-p0-p1-acceptance.md)）；资产按用途选型并配官方默认使用规则。
- 2026-11-03 问数：首次查询先 `get_instructions`；平台逐列合计；`wren://mdl` 还原中文。
- 2026-11-03 资产页：示例按当前模型生成（`AssetExampleService`）；"在哪里说"对照表；已确认试问可修改。
- 2026-11-03 spec108 实施并验收（[报告](../test-reports/20261103-spec108-acceptance.md)）：`QuestionDependencies` 快照与比对、`REVIEW` 状态、发布暂扣、资产验证存为问答、资产轮次规则冲突提示词；修复 `filterPublishedExamples` 未被调用。

## 未完成
- spec108 验收 11（资产轮次规则相反时提问）未实测。
- 页面不能删除加工表；资产删改后需同步默认使用规则。
- 培训文档（自动建模对话、语义资产、数据开发使用方法）待写。
- P4 升级 Wren 后开放 Cube。
- 下一步卡片在资产轮次后应指向语义资产页，当前仍提示发布。
- 调研报告 `reports/`、笔记 `research_notes/` 未跟踪，是否入库待用户决定。

## 待验证
- Doris 上的 `dry-run`（MySQL 已核实走 EXPLAIN）。
- View 作为 Cube `base_object`。

## 其他待办（用户已知）
- liyufan 合并后的验收：新手指引、回答工具条、数据源页、例行任务页、66。
- 建模助手把术语写到 `knowledge/rules/general.md`，术语表读 `naming.md`，两者不一致。
- spec105/106 待验收后移入 `done/`。

## 验证
- 2026-11-03（spec110 一期后）：`spotless:check`、`tsc` 通过；全量 `mvn test` 1092 项，28 跳过，仅 `ProcessWrenCliTest` 1 个已知错误。
- 已知：`ProcessWrenCliTest` 在 Windows 清理临时目录失败，改动前已存在。

## spec109（ADR0126 已接受，已实施）
- 方向：建模对话唯一入口；资产先提议（`propose_semantic_asset`）后起草；默认过滤只写规则；加工表不作默认；补删除资产。
- 实施前实测通过（[报告](../test-reports/20261103-spec109-pre-tests.md)）：查询模板 MySQL 函数与窗口函数可用；只写规则 5/5 加了过滤，明确包含时放开。新发现：查询模板要保留默认过滤所需列、不用 IFNULL 掩盖空值，已写入 spec109。
- 本地模型名已改为 `qwen/deepseek-v4.1-flash-1017`（权益包额度只对它可用），备份在会话 scratchpad。
- 测试知识库：newuser10 的"spec109实测"（`e71fc858`）。


## spec110 问数教练（ADR0127 已接受）
- 一期已实施：COACH 轮次与 `propose_modeling_change`（规则/关系/计算字段/查询模板）；问数教练页签（问题、方案表、核对 SQL、MDL 与规则面板）；验证只 dry-plan 不执行；窗口/聚合视图提示；对话只在用户明确要求时提议并自动起草。
- 第 8 节"问数优化"已实施（[报告](../test-reports/20261103-spec110-section8-acceptance.md)）：16、18–20 通过，17 单题不提议通过、两题复用未触发；助手只列缺口不提问时页面标为"按假设处理"。
- 验收见 [报告](../test-reports/20261103-spec110-phase1-acceptance.md)；遗留：COACH 规则歧义未触发确认卡、对话被拒提议的措辞；二期为基准问题批量重新生成。

## 待办（以后继续）：公共资产提炼（未写 spec/ADR，方向已讨论）
- 背景：参考 Codex 方案，视图应来自全部已确认问答（试问、问数优化、问数点赞、资产验证）中反复出现的逻辑，而非问数优化的一批题。页签"语义资产"已改名"公共视图"，显示"建设中"说明，已建视图的核查验证保留。
- 拟定方案：JSqlParser 统计 SQL 中重复的关联、过滤、CASE WHEN、窗口/子查询（证据），助手按官方去处提议（过滤→规则、关联→关系、CASE→计算字段、多步计算→视图），门槛约 3 题、口径矛盾不提炼；批准后起草；用来源问题重新生成 SQL、新旧并排确认作为验证（不执行，与"只核对 SQL"一致）；手动"扫描可复用逻辑"，约 5 题确认后可用。
- 已完成：问数优化不再提议视图（ADR0128，工具拒绝 kind=view）。届时：写 spec111，必要时新 ADR 调整 ADR0123 资产单独出题验证；页面"查询模板"与"公共视图"用语统一。

## 下一步
spec109、spec110 一期已实施并验收；所有改动等用户验收后提交（提交前按 maven-rewrites-static 记忆最后重建生产 static）；再写培训文档。
