---
name: forecast
description: 趋势预测：基于历史走势预测未来一段时间的指标，给出 80% / 95% 预测区间。只在用户于问数页面选择了“趋势预测”时使用。
---

# 趋势预测

用固定脚本 `skills/forecast/forecast.py`（Holt-Winters 指数平滑）预测未来走势。你只负责确认参数、用 SQL 取数、运行脚本和解读结果，不自己编写预测算法。

## 一、参数确认（参数尚未确认的轮次）

1. 需要时调用 `describe_model` 了解指标所在模型和时间字段；读取业务规则中的指标口径与默认过滤。
2. 调用一次 `propose_analysis`：
   - `method`: `forecast`
   - `metric`: 指标及口径；`metric_note` 写口径来源
   - `current_period`: 用于预测的历史范围 `YYYY-MM-DD 至 YYYY-MM-DD`（尽量取全部可用历史）
   - `granularity`: `day`（默认）/ `week` / `month`
   - `horizon`: 预测期数（用户没说时按日取 7）
   - `filters`: 业务规则中的默认过滤（中文描述）
3. 用一句话请用户确认参数，结束本轮。本轮不执行查询。

## 二、执行分析（参数已确认的轮次）

1. **取数：一条 `run_sql`**，按确认的粒度聚合，结果两列：时间、指标；时间条件用日期常量，遵守确认的过滤条件。按日至少 14 个点、按周至少 8 个、按月至少 12 个。
2. **计算：一次 `run_python`**，读取查询结果末尾「数据文件」给出的 CSV：

   ```python
   import json, subprocess, sys
   params = {"date": "日期", "metric": "访问次数", "granularity": "day", "horizon": 7, "title": "访问次数未来 7 天预测"}
   r = subprocess.run([sys.executable, "skills/forecast/forecast.py", "--input", "data/<文件名>.csv",
                       "--params", json.dumps(params, ensure_ascii=False)], capture_output=True, text=True)
   print(r.stdout or r.stderr)
   ```
3. 脚本输出 JSON：`ok=false`（如数据点不足）时，不给任何预测数字，说明已有多少点、至少需要多少点，并建议改用更长的时间范围或更粗的粒度。

## 三、报告结构

1. **结论**：一句话写预测期合计和典型水平，并提醒区间宽度。
2. **图**：原样复制 run_python 返回的图片引用（历史、预测值、80% / 95% 区间）。
3. **预测明细**：表格列出时间、预测值、80% 区间、95% 区间。
4. **方法与口径**：JSON 中的 `method` 与参数、历史点数、指标口径、过滤条件，以及 JSON `notes` 中的提醒。

## 四、边界

- 历史数据只来自 SQL；预测值和区间由脚本计算，必须同时给出区间，不能只报一个数。
- 不对预测结果做确定性承诺；对影响因素的推测必须标注“推断”。
- run_python 返回沙箱或环境错误时，写明“分析计算暂不可用”，并给出 SQL 得到的历史数据，不要自行估算。
- 同一问题最多运行 2 次 run_python；不修改脚本，不自己写算法。
