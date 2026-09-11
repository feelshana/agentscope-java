---
name: python-analysis
description: 使用 Python 完成数据可视化与深度分析。当用户问题含视觉分析语义（趋势、对比、构成、分布、完成得怎么样、通过图形展示一下）且需要丰富标注（数据标签、考核目标参考线、达成率、趋势方向）时，以及考核指标达成分析、多指标双 Y 轴对比、堆叠柱状图/多子图、数据透视、统计回归等 render_chart 无法覆盖的场景时使用。通过 run_python 工具在沙箱中执行代码，页面上会自动展示执行的代码与生成的产物。
---

# Python 数据分析技能

用 Python 代码完成 SQL 与 `render_chart`（ECharts）无法覆盖的数据分析与可视化。

## 何时使用本技能

**触发信号：问题含视觉分析语义——由问题性质判定，与用户是否字面提到"画图/图形"无关。** 用户问"趋势/走势如何"、"对比怎么样"、"构成是什么样"、"分布如何"、"考核指标完成得怎么样"时，即使没有明说"画图"，也属于视觉分析语义：先按 [[sql-analysis]] 技能查数，数据到手后按图形复杂度选择工具。决策口吻参考：*"用户问趋势，属于视觉分析语义，可以生成 1 张趋势图。数据已有，直接画图。"* 若会话中已查过本轮所需数据，直接复用上一轮查询结果，不必重查 SQL：

| 场景 | 推荐工具 |
|------|---------|
| 考核指标达成分析：目标参考线 + 达成率 + 数据标签 + 统计摘要 | `run_python`（本技能） |
| 多指标对比（双 Y 轴，两个量纲/两条趋势放一张图） | `run_python`（本技能） |
| 折线/散点需要逐点数据标签或文字标注 | `run_python`（本技能） |
| 堆叠柱状图、多子图、双 Y 轴等组合图表 | `run_python`（本技能） |
| 数据透视、窗口函数、多表合并等复杂转换 | `run_python`（本技能） |
| 统计检验、线性回归、趋势线（含 R² 标注） | `run_python`（本技能） |
| 直方图等分布分析 | `run_python`（本技能） |
| 需要导出 CSV 数据文件给用户下载 | `run_python`（本技能） |
| 只要一张简单图表，无标注诉求 | `render_chart`（[[chart-rendering]] 技能） |
| 只要数字答案，无需图形 | `run_sql_preview`（[[sql-analysis]] 技能） |

**判断口诀：图上要写字（标签/参考线/达成率）就用 run_python；只要个形状就用 render_chart。**

不要因为数据已经查到就跳过可视化——"趋势怎么样"、"完成得怎么样"这类问题，一张带参考线的图表加达成率统计，比纯文字汇报更有说服力。

## 步骤

1. **先用 SQL 拿到原始数据。** 调用 `list_data_sources` 确认数据源，`describe_table` 确认列名，然后用 `run_sql_preview` 执行查询。不要在 Python 中直接连接数据库——沙箱没有网络，始终通过 SQL 工具取数后再用 Python 处理。

   问题涉及考核/目标时，先调用 `read_knowledge` 查知识库中的 KPI/考核目标值（如"日均目标 2000 万"）——参考线与达成率都以此为基准；知识库没有就问用户要目标值，不要编造。

2. **分两次调用 run_python：先探查，再画图。** 第一次传一段探查代码，确认列名、类型与取值范围：

   ```python
   import pandas as pd
   data = [ ...run_sql_preview 返回的行... ]  # 硬编码为 DataFrame
   df = pd.DataFrame(data)
   print("shape:", df.shape)
   print("columns:", df.columns.tolist())
   print("dtypes:\n", df.dtypes)
   print(df.head(10))
   print(df.describe())
   ```

   看清 dtypes（数字列可能是字符串）、日期格式（如 `stat_date` 为 yyyyMMdd 字符串）、量级（是"万"还是"个"）之后，再写正式分析代码。这一步能避免列名拼错、类型搞错导致正式脚本失败。

3. **编写正式分析代码。** 基本模板：

   ```python
   import pandas as pd
   import matplotlib
   matplotlib.use('Agg')  # 必须：沙箱是无头环境
   import matplotlib.pyplot as plt

   # 中文字体（沙箱镜像内置 Noto CJK）
   plt.rcParams['font.sans-serif'] = ['Noto Sans CJK SC', 'DejaVu Sans']
   plt.rcParams['axes.unicode_minus'] = False

   # 日期列常见格式：yyyyMMdd 字符串 → 中文标签
   df['date'] = pd.to_datetime(df['stat_date'].astype(str), format='%Y%m%d')
   df['x_label'] = df['date'].dt.strftime('%-m月%-d日')  # "9月1日" 格式

   # ── 可视化 ──
   fig, ax = plt.subplots(figsize=(12, 6))
   ax.plot(df['x_label'], df['value'], marker='o', linewidth=2)
   ax.set_title('标题', fontsize=16)
   plt.xticks(rotation=30, ha='right')
   plt.tight_layout()
   plt.savefig('outputs/活跃用户趋势.png', dpi=120, bbox_inches='tight')
   plt.close()

   # ── 数据导出 ──
   df.to_csv('outputs/活跃用户趋势_data.csv', index=False)
   ```

   产物必须保存到 `outputs/` 目录下（工具只收集该目录）。文件名用主题描述而非固定名，如 `活跃用户与播放量趋势对比.png`、`活跃用户与播放量趋势对比_data.csv`——页面会展示产物名，语义化命名让用户一眼看懂每个产物是什么。

4. **考核指标分析模式。** 考核/目标达成类问题的标准画法——参考线 + 数据标签 + 达成率：

   ```python
   target = 2000  # 考核目标（来自 read_knowledge 或用户），单位与数据一致

   # 折线 + 逐点数据标签
   ax.plot(df['x_label'], df['value_wan'], marker='o', linewidth=2, color='#2196F3', zorder=3)
   for i, row in df.iterrows():
       ax.annotate(f"{row['value_wan']:.1f}", xy=(row['x_label'], row['value_wan']),
                   textcoords='offset points', xytext=(0, 10),
                   ha='center', va='bottom', fontsize=9)

   # 考核目标参考线（红色虚线 + 文字标注）
   ax.axhline(y=target, color='red', linestyle='--', linewidth=1.5)
   ax.text(df['x_label'].iloc[-1], target, f'考核目标：{target}万',
           color='red', fontsize=10, va='bottom', ha='right')

   # Y 轴范围留出空间：数据与目标线都要完整可见
   y_min = min(df['value_wan'].min(), target) * 0.95
   y_max = max(df['value_wan'].max(), target) * 1.05
   ax.set_ylim(y_min, y_max)
   ```

   并生成统计摘要 `outputs/活跃用户趋势_insights.md`（考核场景的关键交付物）：

   ```python
   from scipy import stats
   slope, intercept, r_value, p_value, std_err = stats.linregress(range(len(df)), df['value_wan'])

   insights = f"""# 关键统计

   - 数据范围: {len(df)} 天
   - 起点: {df['value_wan'].iloc[0]:.1f}万，终点: {df['value_wan'].iloc[-1]:.1f}万
   - 变化: +{df['value_wan'].iloc[-1] - df['value_wan'].iloc[0]:.1f}万
   - 均值: {df['value_wan'].mean():.1f}万，标准差: {df['value_wan'].std():.1f}万
   - 趋势: {'上升' if slope > 0 else '下降'}（线性斜率 {slope:.2f}万/天，R²={r_value**2:.3f}）
   - 考核目标({target}万): 达成情况 {df['value_wan'].max()/target*100:.1f}%（以最高值计）
   """
   with open('outputs/活跃用户趋势_insights.md', 'w', encoding='utf-8') as f:
       f.write(insights)
   ```

5. **多指标对比模式（双 Y 轴）。** 用户要把两个量纲或两条趋势放一张图对比时（如"活跃用户 vs 播放量"）：

   ```python
   from scipy import stats
   fig, ax1 = plt.subplots(figsize=(12, 6))

   # 左轴：指标 A（蓝色）
   ax1.plot(df['x_label'], df['metric_a_wan'], marker='o', color='#2196F3', label='活跃用户数')
   ax1.set_ylabel('活跃用户数（万人）', color='#2196F3')
   ax1.tick_params(axis='y', labelcolor='#2196F3')

   # 右轴：指标 B（橙色，独立量纲）
   ax2 = ax1.twinx()
   ax2.plot(df['x_label'], df['metric_b_wan'], marker='s', color='#FF9800', label='总播放量')
   ax2.set_ylabel('总播放量（万人）', color='#FF9800')
   ax2.tick_params(axis='y', labelcolor='#FF9800')

   # 趋势方向标注：图上直接写 ↑/↓（按回归斜率判定）
   slope_a = stats.linregress(range(len(df)), df['metric_a_wan']).slope
   slope_b = stats.linregress(range(len(df)), df['metric_b_wan']).slope
   ax1.annotate('活跃用户↑' if slope_a > 0 else '活跃用户↓',
                xy=(0.02, 0.95), xycoords='axes fraction', fontsize=11,
                color='#2196F3', fontweight='bold')
   ax2.annotate('播放量↑' if slope_b > 0 else '播放量↓',
                xy=(0.02, 0.88), xycoords='axes fraction', fontsize=11,
                color='#FF9800', fontweight='bold')

   # 合并双轴图例
   lines1, labels1 = ax1.get_legend_handles_labels()
   lines2, labels2 = ax2.get_legend_handles_labels()
   ax1.legend(lines1 + lines2, labels1 + labels2, loc='upper right')

   plt.tight_layout()
   plt.savefig('outputs/活跃用户与播放量趋势对比.png', dpi=120, bbox_inches='tight')
   plt.close()
   ```

   （上面列名/指标名是示例，替换为实际场景的指标。）insights.md 为每个指标各写一组统计——起点→终点、变化量+变化率、线性回归斜率、极值——并用一句话给出两者关系结论（同向/反向/发散）。

6. **调用 `run_python`。** 传入代码和简述：

   ```
   run_python(code=<探查或正式代码>, description="9月活跃用户考核达成趋势图")
   ```

   工具会在沙箱中执行代码并返回结构化报告，页面上会自动渲染：
   - 执行的 Python 代码（代码块）
   - 标准输出（print 的内容）
   - 产物（`outputs/` 下的 PNG 图表、CSV 表格、insights.md 摘要）

   标准交付物为三件套（语义化命名）：`<主题>.png`（图表）+ `<主题>_data.csv`（图表底层数据）+ `<主题>_insights.md`（关键统计摘要）。

7. **成文汇报。** 图表渲染后，最终回复按这个结构组织（参考 TCDataAgent 的 write_answer 风格）：

   ```
   ## <分析主题>
   **数据范围：** 日期跨度、样本量、单位换算说明（如"原始值除以10000换算为万人"）

   （图表 + 一句话说明轴/颜色含义）

   **关键发现：** 每个指标一组——起点→终点、绝对变化+变化率、斜率与趋势方向、极值

   **对比结论：** 指标间关系的交叉解读。如"活跃用户↑但播放量↓ → 人均播放量下降，
   可能存在内容供给不足或新用户消费习惯未养成，值得进一步关注"——从数据矛盾推出业务洞察

   **后续建议：** 主动提出 1-2 个可继续深挖的方向（按渠道/地区归因、拆解人均指标等）
   ```

   不要只贴图不解释；考核场景必须给出达成率。

## matplotlib 常用模板

### 堆叠柱状图（构成分析，render_chart 不支持）
```python
fig, ax = plt.subplots(figsize=(12, 6))
bottom = 0
for col, color in zip(['small_file_size_gb', 'expired_file_size_gb', 'other_gb'],
                       ['#f59e0b', '#ef4444', '#6366f1']):
    ax.bar(df['project_name'], df[col], bottom=bottom, label=col, color=color)
    bottom += df[col]
ax.set_ylabel('存储量 (GB)')
ax.legend(loc='upper right')
plt.xticks(rotation=45, ha='right')
plt.tight_layout()
plt.savefig('outputs/各租户存储构成.png', dpi=150, bbox_inches='tight')
```

### 散点图 + 逐点标注（标注异常/重点关注的项目）
```python
fig, ax = plt.subplots(figsize=(10, 7))
ax.scatter(df['file_count'], df['expired_ratio'], s=80, color='#6366f1', alpha=0.7)
for _, row in df.iterrows():
    ax.annotate(row['project_name'], (row['file_count'], row['expired_ratio']),
                fontsize=9, xytext=(5, 5), textcoords='offset points')
ax.set_xlabel('文件总数')
ax.set_ylabel('过期文件占比')
```

### 线性回归趋势线（scipy）
```python
from scipy import stats
x = range(len(df))
slope, intercept, r_value, p_value, std_err = stats.linregress(x, df['size_gb'])
ax.plot(x, intercept + slope * x, 'r--', label=f'趋势线 (R²={r_value**2:.3f})')
```

## 反模式

- ❌ 在 Python 中直接连接数据库——沙箱无网络，必须先用 `run_sql_preview` 取数。
- ❌ 编造考核目标值——从 `read_knowledge` 或用户处获取；两者都没有就明确说明"无目标值，仅展示数据"。
- ❌ 不探查直接写正式代码——列名/类型写错会浪费一整次执行；先跑探查脚本。
- ❌ 调用 `plt.show()`——沙箱没有显示设备；用 `plt.savefig()` + `plt.close()`。
- ❌ 把产物保存到 `outputs/` 之外的目录——工具只收集 `outputs/` 下的文件。
- ❌ 字体设置为 `SimHei` 等本机字体——沙箱镜像内置的是 Noto CJK，中文标注用 `Noto Sans CJK SC`。
- ❌ 图上不写字——有参考线/标签/达成率诉求还用 `render_chart`，那是本技能的场景。
- ❌ 只贴图不解读——每张图后必须附结论，考核场景必须给出达成率。
- ❌ 多指标只各自罗列不下对比结论——"A涨B跌"背后的人均/结构变化才是关键洞察。
- ❌ 会话里已有数据还重查 SQL——复用上一轮查询结果，直接进入画图环节。

## 何时委托

如果用户要求"做一份包含多张图表和文字分析的完整报告"，先用本技能生成各张图表，再**派生 `report-writer` 子代理**把图表结论整合为成文报告。
