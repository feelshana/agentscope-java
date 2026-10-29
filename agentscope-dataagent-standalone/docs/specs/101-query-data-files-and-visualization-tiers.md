# spec101：问数结果文件通道与可视化分层

> 状态：已实施，待真实验收（2026-10-29）。是 spec102（分析技能）的前置。

## 背景与目标

问数改为官方 Wren MCP 后，查询结果不再落成文件：生成「数据文件」的 `WrenToolkit#dataFileNote` 只在已不注册的旧工具箱里，`run_python` 的描述却仍要求读取 `data/<文件>.csv`。日志中模型因此把几十行结果手抄成 Python 字面量，有抄错和截断的风险，违反"统计只来自 SQL 返回值"。

另外，提示词（"根据用户需要"）与 `run_python` 描述（"无需明说也应主动可视化"、CSV 三件套）相互矛盾，助手常用耗时 10 秒以上的 Python 画本可毫秒完成的图。`render_chart` 只靠问题文字猜图表类型：按日序列被画成条形图，数字 ID 列被当成指标而画成散点图。

目标有三点：
- 查询结果可靠地交给 Python；
- 明确"表格 / 声明式图表 / Python"三层分工；
- 让 `render_chart` 覆盖常见业务图表，并允许助手显式指定图表类型和字段。

## 方案概述

1. **结果文件通道**
   - 把 `WrenToolkit#dataFileNote` 及其 CSV 渲染、容量检查抽成独立组件 `QueryDataFiles`。
   - `WrenToolForwarder#callAsync` 在官方 `run_sql` / `query_cube` 成功、且返回 `columns/rows` 时调用它，把下面这一行追加到结果末尾：
     `**数据文件：** data/<sha12>.csv（N 行 × M 列；run_python 中 pd.read_csv(...) 读取）`
   - 沿用旧逻辑：
     - 文件名按内容寻址；
     - 文件超过上限时只给提示；
     - 经 `ArtifactStore` 保存，`run_python` 执行时按需恢复，纯 SQL 回答不创建沙箱；
     - 失败时降级，查询结果照常返回。
   - 回执 `[WREN_QUERY_RECEIPT]` 与官方输出不变。
2. **字面量数据防护**
   - `RunPythonTool` 执行前做检查：代码里出现大段行数据字面量（嵌套列表或元组超过 20 行），且没有读取 `data/` 下的文件时，拒绝执行，提示改用数据文件。
   - 判断只做语法层面的计数，不做语义推断。
3. **可视化分层，写入问数提示词**
   - 单值、≤2 行或明细清单：只给表格。
   - 其余默认用 `render_chart`，覆盖趋势、排行、对比、占比、堆叠、双轴、参考线、数据标签、散点、热力。
   - 只有需要在 SQL 结果之上再做统计计算时，才用 `run_python`：回归、预测、分布与分位、异常、多结果集合并后派生；用户要求导出文件或做多图报告时也用 `run_python`。
   - 同一问题最多调用 2 次 `run_python`。
   - Python 算出的派生数值在回答中标明来源。
   - `run_python` 的描述同步收窄：删除"主动可视化"和三件套要求，只保留适用场景与数据文件读取方式。
4. **`render_chart` 参数扩展（均可选，缺省时走推断）**
   - `chart_type`：line / bar / hbar / pie / scatter / stacked_bar / dual_axis / heatmap；
   - `x`；
   - `y`（列表）；
   - `y2`（右轴）；
   - `series`（分组列）；
   - `labels`（数据标签）；
   - 原有的 `mark_line_value` / `mark_line_label`。

   `ChartBuilder` 推断规则的修正：
   - 存在日期列且至少 3 行时优先画折线；
   - 列名以 `id` / `_id` 结尾、或取值像编号的数字列当作维度；
   - 未传 question 时用指标名生成标题。

   服务端照旧生成完整 ECharts option，前端渲染协议不变。
5. **官方 usage 技能的环境说明**：问数提示词补一句，技能中的 `wren` 命令行在本环境对应同名 MCP 工具；Python 沙箱中没有 `wren` 包，不要 import。

ARCHITECTURE_zh.md 同步：问数工具链与 `run_python` 数据交接一节。

## 影响面

- **后端：**
  - 新增 `QueryDataFiles`；
  - 修改 `WrenToolkit`（改为调用新组件）、`WrenToolForwarder`、`RunPythonTool`；
  - 修改 `DataAgentToolkit#renderChart`、`ChartBuilder`；
  - 修改 `DataAgentConfig` 问数提示词。
- **前端：** 预计无改动。图表 option 仍由服务端生成；如果热力图、双轴在 `ToolCallBlock` 渲染有问题，再做最小修正。
- **数据：** 无。
- **文档：** ARCHITECTURE_zh.md；本 spec 实施后更新索引。

## 验收标准

1. Given 选择了"测试"知识库，When 问"9 月每天访问量"，Then `run_sql` 结果末尾有「数据文件」行；回答只执行 SQL，用 `render_chart` 画折线，不调用 `run_python`。
2. Given 第 1 条的会话，When 追问"用线性回归看看趋势斜率"，Then `run_python` 用 `pd.read_csv('data/…')` 读取数据，没有抄写数据，最多调用 2 次，回答里标明斜率由 Python 计算。
3. Given 模型把 30 行结果写成 Python 列表，When 调用 `run_python`，Then 工具拒绝执行并提示使用数据文件。
4. Given 问"访问人数最多的项目 Top10"，结果含 project_id 列，Then 图表是排行条形图，不是散点图。
5. Given 助手传入 `chart_type=dual_axis, y=[访问次数], y2=[访问人数]`，Then 渲染为双 Y 轴图；传 `labels=true` 时显示数据标签。
6. Given 查询结果超过文件上限，或 ArtifactStore 写入失败，Then 查询结果照常返回，只附带降级提示。
7. Given 用户 A 的会话生成了数据文件，Then 用户 B 的会话无法通过 `restore_artifact` 或路径读取该文件。

## 不做的事

- 不新增分析技能（见 spec102），不改官方 Wren 工具的参数和输出。
- 不让 Python 直接连接数据源，不放开沙箱网络。
- 不做前端图表编辑器，也不做用户手动改图表类型。
- 不改联网搜索。

## 测试要求

- `QueryDataFilesTest`：
  - CSV 转义；
  - 内容寻址；
  - 超限提示；
  - 写入失败降级；
  - 非表格结果不生成文件。
- `WrenToolForwarder` 测试：
  - `run_sql` 成功时追加文件行；
  - 出错或非表格结果时原样返回。
- `RunPythonTool` 测试：
  - 大段字面量被拒绝；
  - 读取 `data/` 的代码放行；
  - 小段常量放行。
- `ChartBuilderTest`：
  - 按日序列画折线；
  - ID 列当作维度；
  - 显式参数覆盖推断；
  - 双轴、堆叠、热力图的 option 结构。
- 多租户：跨会话、跨用户读取数据文件被拒（复用 ArtifactStore 现有隔离用例并补充一条）。
- `DataAgentConfigTest`：提示词包含三层规则和"最多 2 次"，`run_python` 描述不再含"主动可视化"。

## 关联

- 前置于 spec102。
- 复用 specs/016、ADR0030 的 CSV 交接设计。
