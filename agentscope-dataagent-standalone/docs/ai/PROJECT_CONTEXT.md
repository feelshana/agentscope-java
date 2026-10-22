# 项目长期背景

> 只记录跨任务稳定、且不适合写进 AGENTS.md / ARCHITECTURE_zh.md 的背景与本机环境事实。规则看 [AGENTS.md](../../AGENTS.md)，实现看 [ARCHITECTURE_zh.md](../../ARCHITECTURE_zh.md)，决策演变看 [ADR 索引](../adr/README.md)。2026-10-21 之前的逐条背景记录见 [archive/PROJECT_CONTEXT-until-20261021.md](archive/PROJECT_CONTEXT-until-20261021.md)。

## 产品主流程

上传或关联数据 → 采样（字段说明、表角色；外部数据源事实表确认时间窗口）→ 自动基础建模（官方 enrich-context Auto-pilot）→ 可选：用户填写最多 5 个待验证问题，逐题确认 SQL → 页面显式发布 → 在已发布模型上问数（官方 MCP + Python/ECharts）→ 点赞保存业务问答。

问答库只有两个来源：建模时用户逐题确认的问题（`source:user-confirmed`）和问数点赞的回答。

## 本机环境事实（2026-10-20 观察，其他机器不一定相同）

- 服务实际使用的 Wren：`C:\Users\roylin\AppData\Roaming\Python\Python313`（wrenai 0.15.0）；另有测试环境 `D:\workspace\wren-probe\.venv`。
- 记忆后端：LanceDB + `paraphrase-multilingual-MiniLM-L12-v2`；`wren` CLI 冷启动约 4 秒、热启动约 1.2 秒。
- 运行时 MDL 根目录：`C:\Users\roylin\.agentscope\dataagent\mdl\<groupId>\`（`workspace/` 草稿、`published/` 发布快照、`runtime/query-project/` 问数运行工程）。
- 平台与数据集 MySQL 在本机；MySQL 连接池空闲连接为正常现象。
- Docker Desktop 曾因 Mihomo TUN 模式出现后台到 VM `192.168.65.7:2375` 的 `no route to host`，导致 `run_python` 沙箱不可用；需在 Mihomo 排除 `192.168.65.0/24` 或关闭 TUN 后重启 Docker Desktop。

## 日志与排查入口

| 文件 | 用途 |
|---|---|
| `logs/LLM-modeling.log` | 建模 Agent 完整对话转录 |
| `logs/LLM.log` | 问数 Agent 完整对话转录 |
| `logs/execution-timing.log` | 分段耗时（工具、索引、Wren 队列、`wren_cli.*`、`sql_window_gate`） |
| `logs/sql-window-gate.log` | SQL 时间门禁的实际判断、跳过、缓存与同轮阻止 |

LLM 转录没有时间戳，需要单独分析一次运行时，先停服务把旧日志移到 `logs/archive-<日期>/`，再重启。

## 验证方式

自动化：`mvn spotless:check`、`mvn test`、`mvn package -DskipTests`、`cd frontend && npm run build`。真实 LLM、Docker、Wren 相关测试为条件启用。Agent 的实际行为只能通过重启加载新包后的日志确认，提示词或单测断言不能代替。
