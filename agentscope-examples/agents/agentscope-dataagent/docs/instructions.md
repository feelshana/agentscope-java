[mdl.json](..%2F..%2F..%2F..%2F..%2FWrenAI%2Fmdl.json)# 分析云运营本体 — 业务规则与数据限制

> 从 migu-ops-v1 语义模型转换。以下规则对 SQL 查询有约束力。

## 业务规则

### R1 — 活跃用户定义
活跃用户是近30天内有访问行为的用户。
- 参数: window_days = 30
- 待确认: 访问行为包含哪些来源、30天窗口的锚点日期

### R2 — 低频用户定义
低频用户是近30天访问次数小于5的用户。
- 参数: window_days = 30, threshold = 5, operator = <
- 阈值用 HAVING 执行，例如按用户分组后 HAVING click_pv < 5
- 零访问用户在观测数据里指标为空，不会被 HAVING 选中，需要单独作为一类处理
- 覆盖不足时不能给出正式低频分类，只能给已观测预览

### R3 — 模块渗透率定义
模块渗透率 = 近30天该模块用户数 / 总活跃用户数。模块只来自点击详情的"模块"列。
- 参数: window_days = 30

### R4 — 用户去重规则（强制）
凡计算用户数都要去重。适用于: click_uv、report_uv。

### R5 — 推广对象排除规则
账号以 `wx` 结尾为外协，其余为自有；推广对象排除外协和数智化部。
- 排除条件:
  - `account LIKE '%wx'` — 外协账号
  - `department = '数智化部'` — 明确排除
  - `identity_source != 'user_list'` — 账号不在用户清单，身份未确认
- 待确认:
  - `department LIKE '数智化部%'` — 数智化部子部门范围未确认
- 注意: 数智化中心不自动等同数智化部。普通分析不排除外协，只有推广用途才执行排除。

## 指标使用指南

| 指标 | 正确聚合 | 常见错误 |
|------|---------|---------|
| click_pv | `SUM(visit_count)` | 用 `COUNT(*)` 行数代替 |
| click_uv | `COUNT(DISTINCT account)` | 对行内去重列求和 |
| report_pv | `SUM(visit_count)` | 用 `COUNT(*)` 行数代替 |
| report_uv | `COUNT(DISTINCT account)` | 对 `row_uv` 列求和 |
| capacity_used_tb | `SUM(used_tb)` | 跨日期相加当累计 |
| file_ratio | `MAX(file_ratio)` | 源值可以超过100%，不截断 |
| scan_unvisited_size_gb | `SUM(CASE WHEN unvisited_3m THEN ...)` | 优化类别之间可能重叠 |

## 数据限制（必须向用户披露）

### click_window — 点击数据时间范围
导出条件写的是8月9日到9月7日，实际记录只出现在9月1日到9月7日。30天覆盖未核实，缺失不能当成零。

### cross_source_pv — 跨来源访问次数不可加
点击和报表访问没有共同事件ID，两个来源的访问次数不能相加。用户集合可以按身份取并集。

### snapshot_dates — 快照时点不可混合
容量记录日是9月6日，扫描日是9月7日和9月9日。不同时点不能相除或相加。

### unmatched_identity — 未匹配账号
部分事件账号不在用户清单中，保留在 unmatched_account 命名空间，不进入推广名单。

### membership_validity — 成员关系有效性
用户项目列表是否完整、是否代表有效授权尚未确认，只能当作当前清单成员的代理口径。
