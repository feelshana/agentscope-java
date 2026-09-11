---
name: echarts-chart
description: 当需要生成数据可视化图表时使用此技能。提供完整的ECharts图表JSON规范，支持折线图、柱状图、饼图、多系列对比图、双轴图。前端会自动解析<chart>标签中的JSON并用ECharts渲染。
---

# ECharts 图表生成规范

本项目使用 ECharts 渲染数据图表。在 `<chart>` 标签内输出合法 JSON，前端自动渲染。

## 核心原则

1. `<chart>` 内只写 JSON，不用代码块包裹
2. `data` 数组必须包含查询结果的**全部**数据行，严禁抽样
3. 每次最多输出一个图表
4. 仅1个数据点时不输出图表

---

## 一、图表类型选择指南

| 场景 | 类型 |
|------|------|
| 单指标时间趋势 | `line` |
| 多指标时间趋势对比 | `multi_line` |
| 单指标分类对比/排名 | `bar` |
| 多指标分类对比 | `multi_bar` |
| 占比构成 | `pie` |
| 两个量纲差异大的指标同时展示 | `dual_axes` |

---

## 二、各类型 JSON 格式规范

### 1. 折线图 `line`（单系列时间趋势）

```json
{
  "type": "line",
  "title": "图表标题",
  "data": [
    {"label": "3-25", "value": 1200},
    {"label": "3-26", "value": 1350}
  ]
}
```

**字段说明：**
- `label`：X轴刻度，建议格式 `M-D`（如 `3-25`）或 `YYYY-MM-DD`
- `value`：数值，必须为 number 类型

---

### 2. 多系列折线图 `multi_line`（多指标趋势对比）

```json
{
  "type": "multi_line",
  "title": "多指标趋势对比",
  "categories": ["3-25", "3-26", "3-27"],
  "series": [
    {"name": "指标A", "data": [1200, 1350, 1100]},
    {"name": "指标B", "data": [800, 950, 870]}
  ]
}
```

**字段说明：**
- `categories`：X轴刻度数组，长度须与每个 series.data 一致
- `series`：系列数组，每项含 `name`（图例名）和 `data`（数值数组）
- 系列数建议 ≤5 个，过多影响可读性

---

### 3. 柱状图 `bar`（单系列分类对比）

```json
{
  "type": "bar",
  "title": "各产品数据对比",
  "data": [
    {"label": "产品A", "value": 5600},
    {"label": "产品B", "value": 3200}
  ]
}
```

---

### 4. 多系列柱状图 `multi_bar`（多维度分类对比）

```json
{
  "type": "multi_bar",
  "title": "各产品多指标对比",
  "categories": ["产品A", "产品B", "产品C"],
  "series": [
    {"name": "实际值", "data": [5600, 3200, 4100]},
    {"name": "目标值", "data": [6000, 3500, 4000]}
  ]
}
```

**字段说明：**
- `categories`：X轴分类标签数组
- `series`：系列数组，每项含 `name` 和 `data`
- 适合"实际vs目标"、"本期vs上期"等对比场景
- 系列数建议 ≤3 个

---

### 5. 饼图 `pie`（占比构成）

```json
{
  "type": "pie",
  "title": "产品占比分布",
  "data": [
    {"label": "产品A", "value": 45},
    {"label": "产品B", "value": 30},
    {"label": "产品C", "value": 25}
  ]
}
```

**注意：** 类别数建议 ≤8，过多时聚合为"其他"

---

### 6. 双轴图 `dual_axes`（两个量纲差异大的指标）

```json
{
  "type": "dual_axes",
  "title": "用户数与完成率趋势",
  "categories": ["3-25", "3-26", "3-27"],
  "series": [
    {"name": "用户数", "type": "bar", "data": [12000, 13500, 11000], "yAxisIndex": 0},
    {"name": "完成率(%)", "type": "line", "data": [85.6, 92.3, 78.1], "yAxisIndex": 1}
  ],
  "yAxis": [
    {"name": "用户数"},
    {"name": "完成率(%)"}
  ]
}
```

**字段说明：**
- `series[].type`：`bar` 或 `line`
- `series[].yAxisIndex`：`0` 用左轴，`1` 用右轴
- `yAxis`：双轴名称定义，数组长度必须为 2
- 适合"数量级"和"百分比"同时展示的场景

---

## 三、输出位置规则

- **精确取数型**：可在数据列表后直接输出 `<chart>`
- **分析洞察型**：`<chart>` 必须紧跟在 `</report>` 之后，严禁在 `<report>` 内部

```
</report>
<chart>
{"type":"line","title":"...","data":[...]}
</chart>
```

---

## 四、常见错误示例（禁止）

```json
// ❌ 错误：data 只放代表性数据点（抽样）
{"type":"line","data":[{"label":"3-25","value":1200},{"label":"3-31","value":1800}]}

// ❌ 错误：multi_line 用了 data 而非 categories+series
{"type":"multi_line","data":[{"label":"3-25","value":1200}]}

// ❌ 错误：dual_axes 缺少 yAxisIndex
{"type":"dual_axes","series":[{"name":"用户数","data":[100,200]}]}
```
