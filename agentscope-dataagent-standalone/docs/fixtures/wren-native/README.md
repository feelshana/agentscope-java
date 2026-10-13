# Wren 0.15.0 隔离验收

这组数据和脚本分开验证官方工具集成、页面交互和真实 LLM 生成，不能把其中一种通过当作全部准确率。

## 数据口径

`verify.py` 创建独立 DuckDB/官方 Wren 工程，不连接生产数据库。订单数据在脚本 `ROWS` 中；模型使用官方 `ref_sql` 定义，`valid_orders` 视图统一过滤，`sales` Cube 在指定时间范围上聚合。

- 外部客户、状态 `paid`、金额大于 0 才计入营收与付费客户。
- 本测试退款状态的整笔订单不计入；这仅是 fixture 的约定，不是通用退款规则。
- A 跨 2024/2025 年，且在 2025 年跨月购买；B 在 2025 年跨月购买。
- 2025 年营收 **3099**、去重付费客户 **2**、有效订单 **4**。
- 2025 年 1/2/12 月客户数为 **1/2/1**，相加为 4，不能作为年度客户数。
- 内部 TEST、取消 C、退款 F、零金额 E 排除；2026-01-01 的 D 不属于 2025 年。

`questions.json` 保存 30 个固定中文问题、参考 SQL 与标准结果，包括空月份、年度/月度聚合、排名、明细和诊断。没有把“近义召回命中”计入问答正确率。空集合的 `SUM` 参考为 SQL `NULL`，不能无说明地当成有营收。

## 官方 CLI / MCP / 中文召回

在项目根目录执行（Windows 示例）：

```powershell
python -m venv target/wren-native-venv
target/wren-native-venv/Scripts/python.exe -m pip install -r requirements-wren.txt
target/wren-native-venv/Scripts/python.exe docs/fixtures/wren-native/verify.py --wren D:/workspace/data-agent/agentscope-dataagent-standalone/target/wren-native-venv/Scripts/wren.exe --output target/wren-native-fixture
```

替换 `--wren` 为本机绝对路径。输出目录必须为空，或含该脚本创建的 `.wren-native-fixture` 标记；不要指向实际知识库。首次运行下载官方多语言 ONNX 模型，需要网络。重跑全部查询为默认行为；`--reuse-queries` 仅用于复用已有查询报告并复查后续 Cube/memory/MCP，不能当作本次重新执行 30 条 SQL。

产物位于输出目录：`summary.json`、逐命令 `report.json`、带实际结果的 `questions.json`、官方 `mcp-tools.json` 和工具响应。CLI NDJSON 数字可能是字符串，脚本按数值比较。MCP 2.x 与 Wren 0.15.0 使用的 `mcp.server.fastmcp` 不兼容，因此 requirements 固定 `mcp==1.30.0`。

## 实际模型生成评估（会调用配置的模型）

`WrenLiveQueryEvaluationTest` 默认跳过。显式开启后读取当前应用的 OpenAI 兼容模型配置，使用原生 AgentScope ReAct、同版本官方 skills 与实际 `WrenMcpToolkit`/MCP。只提供上述合成数据，不读取用户知识库，不打印密钥，不自动点赞保存。

```powershell
mvn -Dskip.npm=true -Dskip.installnodenpm=true -Ddataagent.test.wren=D:/workspace/data-agent/agentscope-dataagent-standalone/target/wren-native-venv/Scripts/wren.exe -Ddataagent.test.liveWren=true -Ddataagent.test.liveCount=30 -Dtest=WrenLiveQueryEvaluationTest test
```

先执行 `verify.py`；可用 `liveCount=1` 检查最关键的 2025 年客户数。逐题记录 `target/wren-native-live/report.json`，包括原问题、最终回答、官方工具参数/SQL/响应、耗时与参考结果对照。传输失败会停止剩余问题，避免重复消耗。

自动比较忽略输出别名及官方 Cube 的时间分组列，保留行数和全部指标值；它是结果核对辅助，不能替代对最终回答、时间范围、歧义和多查询采用情况的人工审阅。该测试没有经过运行中的 HTTP/SSE、真实用户发布、Python 沙箱或点赞 UI，不能声称已完成这些端到端验收。

## 浏览器交互检查

```powershell
cd frontend
npm run build
cd ..
target/wren-native-venv/Scripts/python.exe -m pip install playwright
target/wren-native-venv/Scripts/python.exe docs/fixtures/wren-native/browser_smoke.py
```

默认使用本机 Edge；其他浏览器可传 `--browser` 可执行文件路径。脚本仅在 127.0.0.1 临时端口提供静态页面，API 全部用隔离 fixture 替换，不改用户数据。验证初始对话隐藏、资料保存、多行问题、连续提交、Cube YAML、切页保留对话、无必验问题发布及下一步入口。报告与截图在 `target/wren-native-browser/`。

完整阶段状态见 [实施计划](../../plans/058-wren-native-modeling-and-query-agents.md)，实际运行环境和未验收项见 [当前任务](../../ai/CURRENT_TASK.md)。
