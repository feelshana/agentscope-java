# ADR 0071：建模会话节奏与确认批次优化

- 状态：Accepted
- 日期：2026-10-12
- 关联：ADR 0069、ADR 0070、spec 060

## 背景

建模日志显示同一会话重复加载官方 skill，已存在文件先调用 `write_file` 再改用 `edit_file`，并在同一批修改中反复执行构建。平台已有同一模型响应内的确认合并，但缺少可核查的确认批次日志。

## 决策

1. 建模系统提示明确要求每个会话只加载一次 skill，后续使用增量状态。
2. 修改前先读取文件清单；已存在文件使用 `edit_file/patch_file`，新文件使用 `write_file`。
3. 同一方案的多个文件使用 `write_file.files_json` 批量提交。
4. 每次 YAML 修改执行 `context validate --strict`，全部修改完成后只执行一次 `context build`。
5. 新增 Cube、视图或关系后执行对应的 `cube query --sql-only` 或 `dry-plan`。
6. 只读校验不触发 HITL；高影响写入继续确认。确认事件记录批次数量和工具名。

## 取舍

平台只约束 Agent 的执行节奏，不复制官方 Wren skill 的建模规则。profile 仍由服务端运行时绑定，草稿工程不写入环境相关 profile。

## 验证

检查建模 system prompt 包含节奏规则，确认日志包含批次信息，并通过已有后端构建门禁。
