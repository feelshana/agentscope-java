# spec 053: 可配置的历史会话保留任务

> ADR 0061 的实施规格。

## 方案概述

- `SessionDeletionService` 统一页面删除与保留任务的完整会话清理顺序。
- `SessionRetentionJob` 使用 Spring 定时调度，从 `session_registry` 选择超过保留期的主会话。
- 页面删除继续校验当前用户与 agent；保留任务只处理平台注册的主会话，并使用运行状态与会话门禁避免删除执行中的会话。
- 配置位于 `dataagent.history-retention`，通过环境变量覆盖。

## 配置

| 配置 | 默认值 | 说明 |
| --- | --- | --- |
| `enabled` | `false` | 是否创建并运行保留任务 |
| `retention-days` | `90` | 最后活动超过多少天后成为候选 |
| `cron` | `0 0 3 * * *` | 每天 03:00 |
| `zone` | `Asia/Shanghai` | 调度时区 |
| `batch-size` | `50` | 任务内部处理批次 |
| `max-per-run` | `500` | 单次最多候选数 |
| `dry-run` | `true` | 只统计，不删除 |
| `mode` | `full-session` | 当前唯一支持的删除方式 |

## 验收标准

1. Given 默认配置，When 应用运行，Then 不执行正常历史过期删除。
2. Given 启用且 dry-run，When 调度触发，Then 日志包含候选数和预计附件字节，数据不变化。
3. Given关闭 dry-run，When 存在超过保留期的空闲主会话，Then 完整删除会话消息、运行登记、历史文件、已读状态和附件。
4. Given 候选会话正在回答，When 任务运行，Then 跳过该会话并保留全部数据。
5. Given 单条删除失败，When 处理同批其他候选，Then 任务继续并汇总成功、跳过与失败数量。
6. Given 候选超过限制，When 单次任务完成，Then 删除数不超过 `max-per-run`，其余留待后续运行。

## 验证边界

Java 编译与格式检查用于验证实现可构建。尚未执行真实时间调度、批量删除、失败注入和 Docker 运行联调，启用前应先在测试环境运行 dry-run。

## 关联

- [ADR 0061](../adr/0061-configurable-history-retention.md)
- [会话与附件部署说明](../session-history-and-artifacts.md)
