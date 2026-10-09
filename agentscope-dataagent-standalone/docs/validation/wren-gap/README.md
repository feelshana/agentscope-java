# 问数与语义建模差距验证包

沙箱启动后的最新结果见 [问数与 Python 实测](../2026-10-07-sandbox-e2e-verification.md)，包含 12 题判定、偶发沙箱错误与真实图表/CSV。

后续本机服务已启动，真实建模、发布、MySQL 查询及沙箱阻断结果见 [本机验证报告](../2026-10-06-local-e2e-verification.md)。下文服务未启动的描述保留上一轮测试背景。

日期：2026-10-06。测试不修改业务代码。

## 数据和可复跑命令

本包生成7张表、10042行：customer 4、order 12、refund 4、order_item 9、
product 4、calendar_month 4、event 10005。
物理表和CSV均为ds_前缀；逻辑模型不带该前缀。
金额使用整数分，避免独立基准中的浮点累计误差。

```powershell
python docs/validation/wren-gap/generate.py
D:/workspace/wren-probe/.venv/Scripts/python.exe docs/validation/wren-gap/verify.py --wren D:/workspace/wren-probe/.venv/Scripts/wren.exe
D:/workspace/wren-probe/.venv/Scripts/python.exe docs/validation/wren-gap/execute_official.py
```

替换为你的已安装Wren Python/CLI路径。本机版本为wrenai 0.15.0。
generate.py只依赖Python标准库；verify.py需要Wren，pandas探针可选；
execute_official.py需要已安装的Wren、DuckDB及相应connector依赖。

- ds_*.csv：可上传到平台新建的专用测试知识库；不要上传oracle-results.json作为业务知识。
- fixture.mysql.sql：仅在新建的隔离测试数据库中执行，包含CREATE TABLE和INSERT，无DROP、无DELETE。
  CREATE TABLE不使用IF NOT EXISTS，重复导入会失败，以免悄悄累加数据。
- semantic-source.md：上传给建模助手的业务口径。
- questions.json：12道问数题、独立SQL、预期结果。SQL是SQLite基准，不应直接当作平台工具参数；
  execute_official.py将ds_表名替换为逻辑模型名后测试官方执行。
- official-project/：人工准备的官方最小语义项目，JSON文法是合法YAML；
  不是建模agent自动生成的模型，不能用它评价agent建模成功率。
- oracle-results.json：独立SQLite基准结果。
- official-results.json：官方CLI正常和故障注入探针，含原始输出。
- official-execution-results.json：官方SDK在隔离DuckDB文件上的固定SQL执行结果。
  同时包含Cube总额/客户/月度的真实执行、月份CTE别名修复和严格模式对照。
- diagnostics.json：JOIN放大和样本截断的独立反例。
- build-results.json：当前仓库检查命令和退出码。

runtime-data/、mutation-project/和官方project的target是可再生运行产物。

## 平台与官方agent对照实验

当前服务8080/8082未监听；本轮未运行平台HTTP建模对话、HITL、问数或Docker Python。
本包的官方SQL执行也不包含LLM。因此不提供“平台准确率 vs 官方agent准确率”。

平台侧：新建测试知识库 → 上传7张CSV → 上传semantic-source.md → 对话建模并人工审阅HITL
→ 校验/发布 → 固定12道题问数 → 保存SSE、逻辑SQL、工具结果、最终答案和Python产物。
另外执行同用户跨知识库、不同用户越权、变更未发布、发布失败保留旧快照的测试。

官方agent侧：固定同一LLM/模型版本/采样参数，提供同样的数据结构与业务文档；
按本机generate-mdl/enrich-context/usage官方skills创建独立项目；
对同一12题执行至少3次，保存工具调用和结果。
不能拿平台整个agent流程与官方单条CLI执行耗时作比较。

分开评估：
1. 建模：关系/复合键/基数/默认过滤/退款归属/单位/空月份是否正确，确认次数，失败修复次数。
2. 固定MDL问数：执行结果正确率、数值正确率、澄清问题比例、token、耗时和工具调用数。
3. 各自建模后问数：测端到端效果，定位错误是在建模还是SQL生成。
4. Python：完整性标志、编码保真、NULL语义、可复现分析代码及结果。
5. 可视化：工作区/已发布一致性、关系键展示、指标依赖、口径出处和发布版本。

禁止自动把失败查询写为已确认NL→SQL示例。
每个用例记录PASS/FAIL/NOT_RUN，NOT_RUN不得按PASS计分。
