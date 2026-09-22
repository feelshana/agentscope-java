# spec 002: 禁「摸底查询」硬门上移 + 回答计数一致性自检

## 背景与目标

ADR 0007 落地后重跑「领导访问分析」同题（`logs/LLM.log`，会话 `main-cd465cf5`），
跨表 JOIN/CTE 硬门与批量 prepare 全部生效，但暴露两类新错误：第 2 轮并行发出三条
纯摸底查询（职位分布 `GROUP BY position_name`、两条 `MIN/MAX/COUNT`），对最终答案
毫无贡献——职位取值提示 prepare 已返回，MIN/MAX 只被用来在回答里写「数据范围」；
回答又出现「共 8 位领导」却列出 9 个账号、「MG视频量质构效日报（4 位）」实际 3 位
的自相矛盾。根因是全程 0 次 `load_skill_through_path`，`sql-analysis` 第 3 步的
「不要发纯『摸底』查询」与第 4 步的「先执行校验，再汇报」都没进入上下文。
本 spec 落地 ADR 0008：把「违反即错」的门搬到每轮必然下发的载体上。

## 方案概述

四处文案改动 + 文档同步，全部在 prompt / 技能 / 文档层，不动任何执行逻辑：

1. **常驻层 A：`query_structured_data` 的 `@Tool` 描述追加禁摸底门。**
   `DataAgentToolkit#queryStructuredData` 的描述文本块，在「详细写法与反模式见
   sql-analysis 技能。」**之前**插入一行（保持 `\` 续行风格与现有句式）：

   ```
   不要发仅用于了解数据规模、日期范围或取值分布的探查查询（COUNT(*) / MIN / MAX / 无筛选的全表 GROUP BY 分布统计）；\
   结论本身需要的聚合不在此列。\
   ```

   只写函数名锚点，不写完整示例 SQL——示例属手册层（技能第 3 步已有
   `SELECT COUNT(*)` / `SELECT MIN(date)` 的完整句式与替代做法）。
2. **常驻层 A：`prepare_data_context` 的 `@Tool` 描述补能力陈述。**
   `DataAgentToolkit#prepareDataContext` 的描述文本块末尾（「只确认单表时改用
   source_id + table 两个参数。」之后）追加：

   ```
   输出的字段描述通常已含枚举与取值提示（如「包含领导等值」），据此直接写 WHERE，无需再探查取值。\
   ```

   与第 1 条的分工是「门的替代方案」而非重复禁令：一处说不要探查，一处说不必探查的理由。
3. **常驻层 B：`DEFAULT_AGENT_SYS_PROMPT` 的「# 回答规则」追加计数一致性义务。**
   `DataAgentConfig#DEFAULT_AGENT_SYS_PROMPT` 中，作为「# 回答规则」段的**第一条**
   （排在「默认用 markdown 表格呈现结构化数据。」之前，因为内容正确性先于呈现形式）：

   ```
   - 结论中的每一个计数都必须与自己列出的明细行数一致；不一致时以明细为准重算后再回答。
   ```

   放常驻层 B 而非技能：该义务跨所有工具（SQL / Python / 检索汇总都可能产出计数），
   与「不编造数据」同级，按 ADR 0007 准绳必须在模型选定技能之前就成立。
4. **按需层：修正 `sql-analysis` 技能与实际输出不符的表述。**
   `src/main/resources/shared/agents/data-agent/skills/sql-analysis/SKILL.md` 第 2 步，
   「确认列名、类型、描述和维度值样例」改为
   「确认列名、类型、字段描述（其中通常已含枚举与取值提示）」——
   `DataAgentToolkit#buildTableSection` 只输出 `| 英文名 | 原始名 | 类型 | 描述 |` 四列，
   从不返回样例值（javadoc 明确 no bulky sample values）。第 3 步的禁摸底完整版
   （含示例 SQL）与第 4 步的校验清单**保持原样**，不新增计数一致性条目（已由常驻层 B 承载）。

   改的是 `src/main/resources/shared/`（唯一事实源，ADR 0007）；根 `shared/` 的运行态副本
   由 `SharedWorkspaceSeeder` 按 manifest 三态策略自动升级——该文件未被手改过
   （磁盘 hash == manifest 记录值），故属于「出厂已变 → 覆盖为新版本」分支，无需人工干预。

**文档同步**（AGENTS.md 硬性规则：改工具链 → 同步 ARCHITECTURE_zh.md）：
`ARCHITECTURE_zh.md` §4.5 三层分工表——「常驻·动作点硬门」行的「放什么」列追加
禁摸底门；「常驻·人格与流程」行的「放什么」列追加计数一致性自检；表后的准绳段
补一句 ADR 0008 的「门 vs 手册」切分方式（常驻层写一句话禁令/义务，按需层写例子、
替代做法与校验清单；漂移源是同层同句抄两遍，不是门与手册分工）。

## 影响面

- 后端：`DataAgentToolkit`（`queryStructuredData` / `prepareDataContext` 两处 `@Tool`
  描述字符串）、`DataAgentConfig`（`DEFAULT_AGENT_SYS_PROMPT` 一行）
- 前端：无
- 数据：无
- 文档：`src/main/resources/shared/agents/data-agent/skills/sql-analysis/SKILL.md` 第 2 步、
  `ARCHITECTURE_zh.md` §4.5、实施后把 ADR 0008 状态由「提议」改为「已采纳」
- 运行态：`workspace/AGENTS.md` 由 scaffold 一次性生成，**已存在的部署不会自动刷新**；
  验证前需删除 `~/.agentscope/dataagent-standalone/workspace/AGENTS.md` 让其重建，
  或用 `DATAAGENT_AGENT_SYS_PROMPT` 覆盖（ADR 0007 适用条件）

## 验收标准（Given-When-Then，可测试）

1. Given 默认配置，When 反射读取 `query_structured_data` 的 `@Tool` 描述，
   Then 同时含禁摸底门（「探查查询」「COUNT(*)」「MIN」「MAX」）与原有跨表硬门
   （「禁止把上一步查询结果作为字面量复制进 IN」「JOIN」「CTE」「SELECT DISTINCT」），
   且不含完整示例 SQL 句（`SELECT MIN(`）。
2. Given 默认配置，When 读取 `prepare_data_context` 的 `@Tool` 描述，
   Then 含「取值提示」与「直接写 WHERE」，且不含「样例值」/「维度值样例」
   （不得声称返回实际不存在的输出）。
3. Given `DEFAULT_AGENT_SYS_PROMPT`，When 断言其文本，Then 「# 回答规则」段含
   计数一致性义务（「与自己列出的明细行数一致」），且原有四条规则
   （markdown 表格、不主动生成图表、不主动生成 PDF/Excel/PPT、全中文输出）全部保留。
4. Given classpath 下的 `sql-analysis/SKILL.md`，When 读取，Then 不含「维度值样例」，
   仍含「1–3 张表」「一次调用批量取回」与禁摸底完整版（「摸底」）。
5. Given 应用以新提示词重启（AGENTS.md 已重建），When 重跑同一道「有哪些领导访问了，
   分别访问了哪些模块，哪些报表？」并查看 `logs/LLM.log`，Then：无仅为了解规模/日期范围
   而发的 `COUNT`/`MIN`/`MAX` 查询（结论本身需要的聚合除外）；回答中每个声称的计数
   与其所列明细行数一致；跨表 SQL 仍为 CTE 去重 + JOIN 形态（不回退）。

## 不做的事（明确排除项）

- 不做服务端 SQL 拦截（正则识别摸底查询）——无法与合法聚合区分，ADR 0008 已否决。
- 不改 `prepare_data_context` 的输出结构，不加 DISTINCT 样例值列（会撞 4000 字符驱逐阈值）。
- 不处理「模块口径未清洗」问题（把「加载中」「panel」「首页」当模块）——属知识库
  `[KNOWLEDGE_BASE_OVERVIEW]` 与数据集描述质量议题，另行评估。
- 不改技能加载机制，不强制加载 `sql-analysis`，不把技能全文常驻。
- 不动 `checkCrossTable` / `resolveScoped` / `visible` 等可见性与越权逻辑。
- 不改 `retrieve_evidence` / `render_chart` / `run_python` 的描述与行为。
- 不改 `chart-rendering` / `python-analysis` 两个技能。

## 测试要求

- `DataAgentConfigTest` 新增三个用例：`queryToolDescriptionForbidsProbeQueries`（验收 1）、
  `prepareToolDescriptionStatesValueHints`（验收 2）、
  `defaultSysPromptRequiresCountConsistency`（验收 3）；
  既有 `defaultSysPromptDoesNotRestateSkillLevelDetail` 的反重复断言保持有效。
- `SharedSkillContentTest` 新增两个用例：`sqlAnalysisDoesNotClaimSampleValues`（不含
  「维度值样例」）、`sqlAnalysisKeepsAntiProbeHowTo`（仍含「摸底」完整版）。
- 本 spec 不涉多租户/越权逻辑变更，无需新增「看不到别人的数据」用例（既有 17 个
  `DataAgentToolkitTest` 用例须全绿，证明描述改动未影响执行路径）。
- 验证命令：`mvn -o spotless:apply`、`mvn -o test`（期望全绿且用例数 +5）；
  运行时验收（验收 5）需重启应用后重跑同题并人工核对 `logs/LLM.log`。

## 关联

- ADR：[0008-anti-probe-queries-and-count-consistency](../adr/0008-anti-probe-queries-and-count-consistency.md)
  （实施后把状态改为「已采纳」）；分层依据见
  [0007-prompt-layering-and-shared-single-source](../adr/0007-prompt-layering-and-shared-single-source.md)
- 前序 spec：[001-join-first-and-batch-prepare](001-join-first-and-batch-prepare.md)
  （JOIN 优先与批量 prepare 已在本次日志中验证通过）
- 证据：`logs/LLM.log`（会话 `main-cd465cf5`，2026-09-22 17:01:27→17:02:08）

## 实施记录（2026-09-22）

- 改动 1–4 全部按计划落地，文案与插入位置未作调整。
- 测试实际 **+6** 而非 +5：除计划的 5 个用例外，`SharedSkillContentTest` 另加
  `sqlAnalysisGatesDelegationOnToolAvailability`。原因是实施 spec 003 时发现
  `sql-analysis` 技能无条件教模型「委托 `data-explorer`」「派生 `report-writer`」，
  而子代理编排关闭后 `agent_spawn` 不在工具集里，因此把技能第 2 步、反模式段与
  「## 何时委托」段的委托表述一并改为「仅当工具集中存在 `agent_spawn` 时适用」。
  该改动属 spec 003 的连带影响，记录在此是因为它落在同一个技能文件上（ADR 0009 决策 4）。
- 验证：`mvn -o spotless:apply` 通过；`mvn -o test` → **112 tests / 0 failures / 12 skipped**，
  其中 `DataAgentConfigTest` 8 个（+3）、`SharedSkillContentTest` 9 个（+3）、
  `DataAgentToolkitTest` 17 个全绿（证明描述改动未影响执行路径）。
- 运行时验收（验收 5）已执行（2026-09-22 18:23 会话，同题重跑），结果见下。

### 运行时验收结果（18:23 会话）

**已生效**

- 摸底查询归零：`COUNT(*)` / `MIN(` / `MAX(` 在整份日志中 **0 匹配**（上次 3 条）；
  工具调用 7 → 5 次（1 次批量 prepare + 4 次 query）。
- 第 2 轮模型直接引用 prepare 返回的字段描述（「职位字段枚举值包含领导」）就写
  `WHERE position_name LIKE '%领导%'`，未再探查取值分布——改动 2 的预期效果。
- CTE + 维表去重守住：`WITH leaders AS (SELECT DISTINCT account FROM 用户表 WHERE ...) ... JOIN`。
- 回答计数全部自洽（逐项核对）：「9 位领导」= 4 位有模块 + 5 位仅报表；
  「xujie 共 12 次」= 6+2+1+1+1+1；「驾驶舱_高质量用户数据被 6 位访问」= 6 个账号；
  「4 位有模块点击」= 4 个账号。上次的「共 8 位却列 9 个」「量质构效日报 4 位实为 3 位」
  两类错误未再出现。

**未生效**

常驻层 B（改动 3 的计数一致性规则）**没有进入上下文**。日志里
`<agents_context></agents_context>` 是空标签——`WorkspaceScaffolder` 只在 `agentscope.json`
缺失时执行，删掉 `AGENTS.md` 并不会被重建（本 spec 原先写的「删除 AGENTS.md 让 scaffold
重新生成」是**错的**，正确做法见 `ARCHITECTURE_zh.md` §4.5 的「运维事实」）。
因此上面的计数正确**不能归功于改动 3**（连同原有的「不编造数据」「全中文输出」也一并丢了），
验收 3 需在 AGENTS.md 恢复后重跑。技能也仍未加载（`load_skill_through_path` 0 次调用），
这反过来印证了 ADR 0008 的判断：门必须常驻。

**新发现并已修复**

第 4 次查询把上一步的 9 个账号字面量复制进 `WHERE account IN (...)`，只为取
`department_name`——而该列在第 1 次查询结果里已经有了。原门的表述限定于「做跨表筛选」，
模型据此认为「补维度属性」不在禁止之列。已把工具描述与技能第 3 步、反模式段的表述
收紧为「跨表筛选和补维度属性（部门、职位等）都算」，并给出正确做法（把维表列加进
已有 CTE 的 `SELECT DISTINCT` 列表）；`DataAgentConfigTest#queryToolDescriptionCarriesCrossTableGates`
与 `SharedSkillContentTest#sqlAnalysisCarriesCrossTableDedupRules` 各加一条「补维度属性」守卫断言。

**证据格式说明**

上述匹配数与计数均取自旧格式的 `logs/LLM.log`（每轮全量 JSON dump）。该日志已于同日
改造为只含 role / type / text 的人工可读转录并按会话去重（见
[spec 004](004-llm-log-readable-transcript.md)），复核时不能再按「出现 N 次」取样，
需按转录顺序累加阅读。
