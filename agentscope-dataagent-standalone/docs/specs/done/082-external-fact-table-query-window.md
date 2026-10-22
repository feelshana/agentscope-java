# 外部事实表问数时间范围配置

> SQL 时间边界与配置以 [spec083](083-llm-per-table-sql-window.md) 为准；本页保留历史需求，不作为当前验收依据。

## 目标

在 Wren 语义建模对话框打开前，让用户为带数据时间字段的外部事实表设置问数时间范围，并将设置用于后续 SQL 门禁。

## 规则

- 候选来自采样完成且 `origin=datasource` 的表；采样摘要必须识别出至少一个日期/时间字段。
- 用户确认后才写入表级配置；未写入的表不参与问数门禁。
- 配置模式为 `LIMITED` 或 `UNLIMITED`。`LIMITED` 仅允许 7、14、31、90 天。
- 配置在建模页面可查看和再次编辑。
- 上传 Excel/CSV、维度表和无时间字段表不显示该弹窗。

## 接口

- `GET /api/dataset-groups/{groupId}/fact-query-windows`：返回候选表及已保存配置。
- `PUT /api/dataset-groups/{groupId}/fact-query-windows`：保存单表配置，请求包含 `datasetId`、`mode` 和可选 `days`。

## 验收

外部表采样识别时间字段后进入弹窗；保存后刷新仍能显示配置；限定窗口、不限制和未配置表分别按规则处理；接口要求知识库 MANAGE 权限。
