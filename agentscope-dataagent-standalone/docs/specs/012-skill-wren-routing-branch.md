# spec 012: 技能文档补齐 wren 通道分流分支

> 状态：已实施（2026-09-29 修补；实施先于文档）。

## 背景与目标

2026-09-28 会话（`logs/LLM.log`，M4 落地后首个真实问数，知识库「测试MDL」已发布
v2）暴露 prompt 分层缺口：系统提示词常驻层有双通道路由硬规则（4.6），但
`sql-analysis` / `python-analysis` 技能的操作步骤只写了直查通道。模型加载技能后服从
技能的显式步骤序列，把逻辑模型区块里的 `group_id` 当 source_id 调
`prepare_data_context` 报错，自行纠正后才走 wren 通道（最终 `wren_query_cube` 返回
真实数据，链路本身验证通过）。

行为结论（双指令源效应，ADR 0007 分层模型的实证补充）：**技能层步骤化指令在通道
选择上覆盖常驻层概述性规则**——两者不一致时模型跟技能走。分流规则必须两边同步
点名具体工具，只在常驻层写硬规则不够。

## 方案概述

四个 SKILL.md 同步修改（权威源 `src/main/resources/shared/agents/data-agent/skills/`
+ 仓库根 `shared/` 落盘副本，fc /b 字节一致；`SharedSkillContentTest` 从 classpath
断言）：

- `sql-analysis/SKILL.md` 步骤 2 新增「先判定问数通道」bullet：目标表在「知识库
  『…』— 已发布语义模型」区块（逻辑模型 + Cube 清单）→ 必须走 `wren_run_sql` /
  `wren_query_cube`，**不要**调 `prepare_data_context` / `query_structured_data`
  （group_id 不是 source_id，直查工具会报错）；反模式区新增对应 ❌ 行
- `python-analysis/SKILL.md` 步骤 1 改双分支（物理表区块 → prepare + query；
  语义模型区块 → wren 工具，逻辑列清单已随区块注入）；反模式区改写取数条 + 新增
  wren 分流行

技能属 shared 层 seed 机制（`SharedWorkspaceSeeder` sha256 簿记三态策略）：未被手改
的部署副本随版本自动升级，新会话即生效。

## 影响面

- 后端：无 Java 代码改动（纯技能文档）+ `SharedSkillContentTest` 补 wren 分流守卫
- 前端：无
- 数据：无
- 文档：本 spec；ARCHITECTURE_zh.md 4.6 双通道路由段补引用

## 验收标准（Given-When-Then）

1. Given 已发布组的表渲染为逻辑模型区块，When 读 sql-analysis 步骤 2，Then 含
   「先判定问数通道」bullet 且点名「不要调 `prepare_data_context` /
   `query_structured_data`」与「group_id 不是 source_id」
2. Given 两处技能文件（resources 权威源 / 仓库根 shared/ 副本），When 字节比对，
   Then 一致
3. Given `SharedSkillContentTest`，When 运行，Then 全绿（含对 wren 分流文案的
   classpath 断言）
4. Given 未发布组的表渲染为物理表 bullet，When 读技能步骤，Then 直查流程与改动前
   一致（prepare → query 不受影响）

## 不做的事

- 不改系统提示词常驻层（4.6 双通道硬规则已存在且正确，缺口只在技能层）
- 不改 wren 工具与预注入渲染逻辑
- 不做工具级通道硬门（DIRTY 混合态刻意保留双通道可达）

## 测试要求

- `SharedSkillContentTest` 补 `skillsRoutePublishedGroupsToWrenChannel`：断言
  sql-analysis 含「先判定问数通道」/ `wren_run_sql` / `wren_query_cube` /
  「group_id 不是 source_id」，python-analysis 含 wren 双工具与同一 source_id 警示
  ——与既有「下沉到技能层的规则必有 classpath 断言」守卫模式一致（batch-prepare、
  反摸底同款）；部署侧一致性由 `SharedWorkspaceSeeder` sha256 升级策略保证

## 关联

- ADR 0007（prompt 分层——本 spec 记录其行为补充：技能层步骤覆盖常驻层概述）；
  ADR 0018 D10 / 0020（双通道路由）
- 同批改动：specs/011（retrieve_evidence 召回升级，同源 LLM.log 分析）
- 诊断记录：`logs/LLM.log`（2026-09-28 会话，prepare_data_context 误用 group_id）
