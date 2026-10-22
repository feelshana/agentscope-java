# spec089：按表来源分层探查、门禁明细为准与 CLI 契约对齐

## 背景与目标

2026-10-20 的日志暴露了以下问题：

- 建模违规全表探查；
- `memory store` 选项被拒；
- 技能重复加载；
- 确认卡内容错误；
- 关系基数自相矛盾；
- 门禁在等于上限时误拒；
- 问数为年份做了多余追问。

目标是：上传表不加门禁但允许官方探查，外部表保持禁止；工具调用与官方 CLI 契约一致；门禁结论与明细一致。

## 方案概述

- `WrenProjectContextMiddleware`：按 `DatasetRepository.findByGroupId` 生成表来源与探查边界，并注入服务时间。时间块与 `WrenQueryContextMiddleware.serviceTime` 共用。
- `DataAgentConfig`：
  - 更新 `MODELING_SCRIPT`、`MODELING_PROBE_RULES` 和 `QUERY_EFFICIENCY_RULES`；
  - `builderBootstrap` 传入 `DatasetRepository`。
- `OfficialWrenToolkit`：
  - 新增 `skillsMounted` 构造参数，由 `ModelingToolkitRegistrar` 根据 `WrenSkillsLocator` 传入；
  - 拒绝未知选项时列出可用选项；
  - 重写工具描述；
  - 解析、Cube 拒绝和门禁移到加锁前；
  - `preflight` 中执行门禁。
- `SqlWindowGate.parse`：由逐表明细重算 `allowed`，并调整提示词的输出顺序。
- 前端：`ModelingHitlCard` 通过 `wrenCliProposal` 按 store/index 渲染确认卡。
- 同步 `ARCHITECTURE_zh.md` 的建模与门禁章节。

## 影响面

- 后端：上述类。无新接口、配置项或数据库字段。
- 前端：`ModelingHitlCard.tsx`、`utils/wrenToolPresentation.ts`，无协议变更。
- 文档：ADR0103、ARCHITECTURE_zh、AGENTS、双语 README、共享记忆。

## 验收标准

1. 建模上下文列出上传表与外部表。未知来源归为外部表。包含当前时间、日期和时区。
2. 等于上限、但模型判为拒绝的明细被放行；模型判为放行、但明细超限的被拒绝；明细为空的拒绝维持不变。
3. `memory store --source` 被拒，错误中列出 `--tags`。技能挂载时 `skills get` 被拒，且 CLI 未执行。`cube list` 不执行 CLI。
4. `memory store` 被门禁拒绝时，preflight 返回错误，不弹确认卡。
5. 确认卡中，index 显示重建索引说明，store 显示问题与 SQL。
6. 加载新包后：上传库建模无 `skills get`、无选项被拒；外部表无探查；问数不为年份提问。日志可核验。

## 后续收窄（ADR0104）

上传表探查限定为时间范围与关系验证两类；全表验证唯一可声明 MANY_TO_ONE；建模明确已存在文件与先编译；问数统计必须来自 SQL 返回值。

## 不做的事

- 不为上传表增加时间配置或门禁，不修改历史工程或数据。
- 不增加 SQL 解析、日期解析器、自动重试或新接口。
- 不强制拦截同一文件的多次 `edit_file`，不修改官方 skill。
