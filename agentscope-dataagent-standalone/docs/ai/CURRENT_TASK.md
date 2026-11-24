# 当前任务 · 多表口径资产（spec149）

2026-11-24，分支 develop-2.0（未提交）。前次任务见[管理员表同步](archive/CURRENT_TASK-before-caliber-assets-20261124.md)。

依据[对话建模与 Cube+View 对比验收](../test-reports/20261124-wren-dialog-vs-cube-view.md)，用户确认 [spec149](../specs/149-caliber-views-and-cubes.md) 与 [ADR0155](../adr/0155-caliber-assets-view-plus-cube.md)：每表一份口径清单（使用场景 + 口径表）为唯一事实源，生成 View + Cube；文档多份增量上传、对话、表格三入口只改草稿，统一在口径页核查；问数按场景选表、按口径 `query_cube`。待确认项按建议：Wren 0.18.x、未核查清单不随版本发布、建模后出现提议卡片、不可相加硬拒绝、顺序 P0 → P1 → P2 → P4 → P3。

## P0（本地完成，未提交）

- `requirements-wren.txt` 升到 wrenai 0.18.0 / wren-core-py 0.8.2；`wren-compat/sitecustomize.py` 补丁扩展到 0.18（接受 dialect）。
- `MdlBuildChecks#explainAll`：验证、问答保存、发布时问答检查在 dry-plan 后追加 EXPLAIN；`MdlBuildChecks#selfReferences` 拒绝与引用列同名的 Cube 成员。
- 本机真实回归通过（列校验 1054 拒绝且不落盘；LIMIT 5 返回 5 行）。

## P1（本地完成，未提交）

- 口径清单与生成器 `CaliberList`/`CaliberCompiler`；口径轮次独占 `calibers/`，`WrenProjectFilesystem#caliberGuard` 校验与 `CaliberSources` 行来源/冲突；发布检查生成文件一致。
- 文档"适用模型"声明与上限 100；`CaliberService` 状态、取值检查；前端"口径"页签 `CaliberPanel`。
- 真实口径轮次在 B 起草 13 个口径，取数与标准答案一致；界面未目视验收。

## P2（本地完成，未提交）

- `CaliberReviews` 逐行核查与验证、按表发布（未通过退回已发布版本、手写 Cube 不发布）、规则重复警告；口径页签核查与验证界面；起草提示词收紧。
- 验证展示口径取数段；P4 选表规则文件与 `query_cube` 口径门禁本地完成。
- B 已由用户核查、验证、发布；三问验收完成，`query_cube` 空时间范围门禁已补。
- P5（选表键、指标说法与默认指标、范围维度、`run_sql` 原表门禁）本地完成；Wren 等值筛选改为 `eq`。示例业务文档：`E:/总部常规性分析/会员规模-取数口径.md`。

## 待办

- 66 按新 requirements 重装 Wren 并回归；duckdb 2.x 等原生依赖在 Anolis 7.9 的 glibc 兼容性未核对。
- 受限事实表 `query_cube` 时间维度门禁；P3：口径编辑与对话修改、冲突选择。
- 度量中文名被助手沿用模型列描述里的猜测（文档未定义），P2 核查时需人工确认。
- 真实多表验收（spec149 验收 14–17）需要用户提供活跃用户等表和文档；B 若要按省份、渠道问数，需按新文档重新起草、核查并发布。
- 本机启动注意：JVM 需 `-Djdk.net.unixdomain.tmpdir=E:/jdk-tmp -Dfile.encoding=UTF-8`（CSV 上传乱码 bug 未修）。
