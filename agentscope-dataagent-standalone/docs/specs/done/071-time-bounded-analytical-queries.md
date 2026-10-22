# 071 时间字段查询边界

> SQL 时间边界与配置以 [spec083](083-llm-per-table-sql-window.md) 为准；本页保留历史需求，不作为当前验收依据。

## 目标

防止建模探测和问数在有时间字段的模型上执行全表聚合或全表 DISTINCT。

## 行为

1. 对 `COUNT/SUM/AVG/MIN/MAX` 等聚合和 `SELECT DISTINCT` 检查时间字段及时间谓词。
2. 缺少时间谓词时，在 CLI/MCP 调用前返回 HTTP 422 风格的中文工具错误。
3. `LIMIT/TOP/OFFSET` 不满足边界要求。
4. 注释和字符串中的单词不参与判断；非分析型 SQL 保持原样。
5. 建模系统提示要求先使用 MDL 列描述中的采样信息，不再发起无界探测。

## 验证

覆盖无条件聚合、`DISTINCT ... LIMIT` 的拒绝，以及带 `WHERE`/`BETWEEN` 时间条件的放行；运行相关单元测试和 Maven 构建。
