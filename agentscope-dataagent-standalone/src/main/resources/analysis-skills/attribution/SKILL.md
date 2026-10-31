---
name: attribution
description: 指标归因：比较两个时间段某个指标的变化，找出贡献最大的维度值并下钻一层。只在用户于问数页面选择了“指标归因”时使用。
---

# 指标归因

用固定脚本 `skills/attribution/attribution.py` 计算两期变化的维度贡献度。你只负责确认参数、用 SQL 取数、运行脚本和解读结果，不自己编写归因算法。

## 一、参数确认（参数尚未确认的轮次）

1. 需要时调用 `describe_model` 了解指标所在模型、时间字段和可分组字段；读取业务规则中的指标口径与默认过滤。
2. 调用一次 `propose_analysis`：
   - `method`: `attribution`
   - `metric`: 指标及口径，如“访问次数（报表访问明细记录行数）”；`metric_note` 写口径来源。去重指标（人数、用户数）在 `metric_note` 中注明“去重指标，按维度分别去重，各项不可相加”
   - `current_period` / `base_period`: `YYYY-MM-DD 至 YYYY-MM-DD`；用户只说了当前期时，对比期默认取紧邻的上一个等长周期
   - `dimensions`: 预选 1 个最可能解释变化的维度，其余放进候选，由用户决定是否增加；`candidate_dimensions`: 可分组字段（中文名，最多 8 个）
   - `depth`: 1；`filters`: 业务规则中的默认过滤（中文描述）
3. 用一句话请用户确认参数，结束本轮。本轮不执行查询。

## 二、执行分析（参数已确认的轮次）

1. **取数：一条 `run_sql`**。按“期间 × 归因维度”聚合两期数据，用 CASE 生成期间列，取值就是两个期间文字；只选期间列、维度列和指标聚合列；时间条件用日期常量；遵守确认的过滤条件；跨表维度先在 CTE 中去重再关联。

   ```sql
   SELECT CASE WHEN access_time >= '2026-10-01' THEN '2026-10-01 至 2026-10-07'
               ELSE '2026-09-24 至 2026-09-30' END AS 期间,
          v.project_name AS 项目, COUNT(*) AS 访问次数
   FROM ... WHERE access_time >= '2026-09-24' AND access_time < '2026-10-08' ...
   GROUP BY 1, 2
   ```
   行数上限由平台设置，不用传 `limit`。结果超过 500 行时你只会看到前几行和总行数，完整数据在「数据文件」里，这是正常的，不要因此改写 SQL。结果被截断时平台不生成数据文件，这时减少维度或缩小时间范围后重新取数，不要用不完整的数据计算。
2. **计算：一次 `run_python`**，读取查询结果末尾「数据文件」给出的 CSV：

   ```python
   import json, subprocess, sys
   params = {"metric": "访问次数", "period": "期间",
             "current": "2026-10-01 至 2026-10-07", "base": "2026-09-24 至 2026-09-30",
             "dimensions": ["项目"], "depth": 1, "title": "访问次数变化拆解"}
   r = subprocess.run([sys.executable, "skills/attribution/attribution.py", "--input", "data/<文件名>.csv",
                       "--params", json.dumps(params, ensure_ascii=False)], capture_output=True, text=True)
   print(r.stdout or r.stderr)
   ```
   参数中的列名必须与 SQL 结果列名一致。脚本只计算，不取数。
3. 脚本输出 JSON：`ok=false` 时按 `reason` 说明原因（必要时修正 SQL 或参数后最多再运行一次），不要编造结果。

### 去重指标（人数、用户数、账号数等 COUNT(DISTINCT)）

去重指标不能按维度值相加：同一用户可能同时属于多个部门或项目，各维度值的人数之和不等于总人数，上面的宽表和加法拆解会得出错误的贡献度。此时：

1. **取数：一条 `run_sql`，输出长表**。每个维度单独按“期间 × 维度值”去重计数，并用维度为“合计”的行单独去重计算两期总数，用 UNION ALL 合并：

   ```sql
   WITH v AS (SELECT CASE WHEN access_time >= '2026-10-01' THEN '10月' ELSE '9月' END AS 期间,
                     u.department_name AS 部门, v.project_name AS 项目, v.user_account
              FROM ... WHERE access_time >= '2026-09-01' AND access_time < '2026-11-01' ...)
   SELECT 期间, '合计' AS 维度, '合计' AS 维度值, COUNT(DISTINCT user_account) AS 活跃用户数 FROM v GROUP BY 期间
   UNION ALL SELECT 期间, '部门', 部门, COUNT(DISTINCT user_account) FROM v GROUP BY 期间, 部门
   UNION ALL SELECT 期间, '项目', 项目, COUNT(DISTINCT user_account) FROM v GROUP BY 期间, 项目
   ```
2. **计算**：params 增加 `"additive": false`（长表列名默认“维度”“维度值”，不同时用 `dimension_column`、`value_column` 指定），其余同上。脚本按维度值分别比较两期人数，不做加和拆解、不自动下钻。
3. **报告**：结论写“变化最大的维度值及其人数变化”，不写成可相加的贡献度百分比；必须说明“各项变化之和不等于总变化（同一用户可能属于多个维度值）”。需要下钻时，建议用户对变化最大的维度值加过滤条件再归因一次。

## 三、报告结构

1. **结论**：一句话写变化量、变化率和贡献最大的 2–3 个维度值及贡献度。
2. **关键数字**：对比期、当前期、变化（来自 SQL，与 JSON 的 total 一致）。JSON 中 `additive=false` 时按上文“去重指标”的报告要求写。
3. **图**：原样复制 run_python 返回的图片引用（变化拆解图；有下钻时附下钻图）。
4. **贡献明细**：表格列出维度值、对比期、当前期、变化、贡献度。
5. **下钻**：贡献最大的维度值在下一维度上的拆解。
6. **方法与口径**：算法（加法分解，贡献度 = 维度值变化 ÷ 总变化，按两期结构差异选择维度）、指标口径、过滤条件、SQL 条数。

## 四、边界

- 原始数字只来自 SQL；贡献度等由脚本计算，回答中注明“由归因脚本计算”。
- 推断性解释（如节假日影响）必须标注“推断”，不能写成数据结论。
- run_python 返回沙箱或环境错误时，写明“分析计算暂不可用”，并用表格给出 SQL 得到的两期按维度对比数据，不要自行心算贡献度。
- 同一问题最多运行 2 次 run_python；不修改脚本，不自己写算法。
