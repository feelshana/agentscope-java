---
name: anomaly
description: 异常检测：按天或按小时检查指标是否有异常波动，标出异常点和偏离程度。只在用户于问数页面选择了“异常检测”时使用。
---

# 异常检测

用固定脚本 `skills/anomaly/anomaly.py` 识别时间序列中的异常点。你只负责确认参数、用 SQL 取数、运行脚本和解读结果，不自己编写检测算法。

## 一、参数确认（参数尚未确认的轮次）

1. 需要时调用 `describe_model` 了解指标所在模型和时间字段；读取业务规则中的指标口径与默认过滤。
2. 调用一次 `propose_analysis`：
   - `method`: `anomaly`
   - `metric`: 指标及口径；`metric_note` 写口径来源
   - `current_period`: 检查的时间范围 `YYYY-MM-DD 至 YYYY-MM-DD`
   - `granularity`: `day`（默认）或 `hour`
   - `filters`: 业务规则中的默认过滤（中文描述）
3. 用一句话请用户确认参数，结束本轮。本轮不执行查询。

## 二、执行分析（参数已确认的轮次）

1. **取数：一条 `run_sql`**，按天（或小时）聚合，结果两列：时间、指标；时间条件用日期常量，遵守确认的过滤条件。至少需要 7 个时间点。
2. **计算：一次 `run_python`**，读取查询结果末尾「数据文件」给出的 CSV：

   ```python
   import json, subprocess, sys
   params = {"date": "日期", "metric": "访问次数", "granularity": "day", "title": "每日访问次数与正常范围"}
   r = subprocess.run([sys.executable, "skills/anomaly/anomaly.py", "--input", "data/<文件名>.csv",
                       "--params", json.dumps(params, ensure_ascii=False)], capture_output=True, text=True)
   print(r.stdout or r.stderr)
   ```
3. 脚本输出 JSON：`ok=false` 时按 `reason` 说明（如数据点不足），不要编造结果。

## 三、报告结构

1. **结论**：一句话写发现几个异常点，最显著的日期、方向和偏离幅度。
2. **图**：原样复制 run_python 返回的图片引用（实际值、预期值、正常范围、异常点）。
3. **异常明细**：表格列出时间、实际、预期、偏离（含倍数 σ）、方向。
4. **方法与口径**：JSON 中的 `method`（季节项按星期、工作日 / 周末或未估计，以 JSON 为准）、阈值、指标口径、过滤条件。

## 四、边界

- 实际值只来自 SQL；预期值、正常范围、异常判断由脚本计算，回答中注明。
- 对异常原因的推测（如节假日、活动）必须标注“推断”，并建议用户结合业务确认。
- run_python 返回沙箱或环境错误时，写明“分析计算暂不可用”，并给出 SQL 得到的逐日数据表，不要自行判断异常。
- 同一问题最多运行 2 次 run_python；不修改脚本，不自己写算法。
