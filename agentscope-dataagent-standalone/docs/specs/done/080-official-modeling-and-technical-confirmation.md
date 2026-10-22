# spec080：官方建模流程与技术变更自动确认

## 目标

采样完成后自动进入语义建模工作台，使用完整官方 Wren skills。模型和基础关系的技术写入自动通过，业务高影响资产仍由官方流程确认。

## 行为

- 建模 Agent 仅挂载官方 `generate-mdl`、`enrich-context`、`usage`、`onboarding`。
- 不生成或挂载 `enrich-context-lite`，不增加深度 Agent 和问题历史素材管线。
- `models/**`、`relationships.yml` 及关系决策工具通过技术确认后继续执行。
- Cube、View、计算列和业务口径冲突继续进入官方确认流程。
- 每批变更仍执行官方 `context validate`、`context build`，页面显式发布后才恢复问数。

## 验收

1. LLM 建模日志只显示官方 `enrich-context`，不出现 `enrich-context-lite`。
2. 模型和基础关系写入不产生用户确认卡片。
3. Cube/View 写入仍产生官方确认卡片。
4. 采样完成后自动进入现有建模页，现有 revision、校验和发布流程保持有效。
