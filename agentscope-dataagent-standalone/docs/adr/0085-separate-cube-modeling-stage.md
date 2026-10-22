# ADR 0085：将 Cube 从基础语义建模中隔离

## 状态

已接受

## 背景

官方 `enrich-context` skill 会在发现命名聚合指标时提出并验证 Cube。对用户行为、播放等通用问数场景，这会引发多轮 Cube/View 写入、build 和 `cube query --sql-only`，显著延长基础建模时间；同时固定指标口径尚未经过业务确认。

## 决策

不修改官方 skill。项目通过自己的建模阶段提示、文件写授权和 CLI 适配器，将基础语义建模限定为 Model、Relationship、View 和 Knowledge。基础建模阶段禁止写入 `cubes/`，禁止执行 `cube` CLI。发现指标时只记录提案，后续由独立指标建模模块创建、验证和发布 Cube。问数阶段继续发现已发布 Cube，覆盖问题时优先 `query_cube`，否则使用 View/Model 的 `run_sql`。

## 后果

官方 skill 可升级且不被修改；基础建模更快、更稳定。Cube 的创建需要额外模块和用户确认，指标不会自动进入发布版本。
