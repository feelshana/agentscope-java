# UI 设计基线

> 改 `docs/design/*.html` 或 `frontend/src/**` 的界面前先读本文。目的：把「审美默认值」钉死，避免每次改动都退回通用 AI 样式。
> 依据：`semantic-modeling-20261113-full.html` 现有 token 与 `frontend/src/components/modeling/*` 的实际实现。

## 0. 先判断产品形态（比任何风格词都重要）

本产品是**高密度企业级数据工具**（建模工作台 / 对话建模 / 问数），不是营销落地页。以下落地页套路**禁止**使用：

- 巨大彩色光球 / Aurora 背景 / 大面积毛玻璃拟态 / Bento 营销网格
- 超过 18px 的卡片圆角、厚重弥散投影、装饰性渐变光带
- 蓝→紫装饰渐变（品牌紫 `--tint` 只用于功能强调，不铺底）

正确参照系是 Vercel / Linear / Notion / Stripe Dashboard：中性灰底、白卡片、0.5px 发丝线、极少量品牌色、信息密度优先。

## 1. Token（唯一来源：文件文首 `:root`，禁止写死色值）

| 类别 | Token | 说明 |
|---|---|---|
| 底色 | `--bg` `--canvas` | 近白灰 `#EFEFF4` / `#F2F2F7`；禁止纯白画布 |
| 表面 | `--surface` `--surface-2` `--surface-3` | 卡片 / 次级底 / 三级底 |
| 文字 | `--ink` `--ink-2` `--ink-3` `--ink-4` | 四级，正文用 `--ink` |
| 描边 | `--hair` `--hair-2` | 一律 0.5px，不用 1px 实线 |
| 品牌 | `--tint` `--tint-soft` `--tint-line` `--tint-solid` | `--tint-solid` 专供暗色实心填充（亮 `#5F4BC8` / 暗 `#6A58E0`） |
| 语义 | `--ok` `--warn` `--bad` `--teal` `--blue` `--biz` + `--*-soft` | 各自配 soft 底 |
| 阴影 | `--shadow-1` `--shadow-2` | 低透明度双层，禁单层厚投影 |
| 动效 | `--spring` | `cubic-bezier(.32,1.12,.42,1)` |

## 2. 圆角：按元素尺寸分级（不是全局一个值）

| 尺寸 | 值 | 用于 |
|---|---|---|
| 3–5px | `3/4/5` | 标签、徽章、行内 code |
| 6–8px | `6/7/8` | 按钮、输入框、图标容器（30px 高按钮 = 8px） |
| 9–11px | `9/10/11` | 列表项、内嵌面板、行内提示条 |
| 12–14px | `12/13/14` | 卡片、表格容器、对话气泡内卡片（**文档卡 `.box`/`.card`/`table.plan` 统一 14**） |
| 16–18px | `16/18` | 画布容器 `.mock`、准备清单大卡 |
| 999px | 胶囊 | chip、pill、状态点容器 |

同级同尺寸的元素**必须同值**；新增时优先复用上表数值，不引入 13.5 / 15 这类中间值。

## 3. 排版

- 字号：正文 12–13px，辅助 10.5–11.5px，区块标题 14–19px；行距 1.5–1.85。
- 字重：400 / 550 / 600 / 650 / 700 五档，禁止 800+ 与正文加粗滥用。
- 字体栈：`-apple-system, "SF Pro Text", "PingFang SC"`；**禁止** Inter / Roboto / Arial。
- 负字距只给 14px 以上标题：`-0.1px ~ -1.1px`，正文 `letter-spacing:0`。

## 4. 动效

- 交互反馈（hover / focus / 按钮态）：150–240ms。
- 滑动指示块（顶栏 `.pchip`、分段控件）：360ms，`cubic-bezier(.32,.72,0,1)`（iOS 跟手曲线）。
- 主题切换：400ms。
- 只允许 `pulse` / `blink` 这类状态指示循环动画，禁止装饰性动画。

## 5. 选中态语言（全产品统一）

**软底块 + 内描边 + 墨色文字**，四种场景同一语言：

```css
background: color-mix(in srgb, var(--tint) 14%, var(--surface));
box-shadow: inset 0 0 0 .5px color-mix(in srgb, var(--tint) 22%, transparent);
color: var(--ink);           /* 关键：墨色，不用紫字 —— 紫字在浅底上显廉价 */
```

对应 `.ptab.on` / `.rail-b.on` / `.sbtn.on` / `.ds.on`。文件里另存 `?nav=line` 备选方案（2px 底部指示线），仅作对比，不作默认。

## 5.1 说明性步骤条（`.mflow`，问数优化 / 公共视图）

「写下常问的问题 → …」这类**说明**不是执行进度，视觉重量必须低于工作台：

| 项 | 规格 |
|---|---|
| 结构 | 4 等分 grid；1px 轨道（`--hair-2`）从上一环心连到下一环心，最后一环后无线 |
| 节点 | 22px 圆环，`background:var(--surface)` 盖住轨道，`inset 0 0 0 1.5px var(--tint-line)`；数字 `700 9.5px/1 var(--mono)` 用 `--tint` |
| 文字 | 与环同左缘（**无悬挂缩进**）；标题 12.5/600 `--ink`，描述 11.5/1.65 `--ink-3` 通栏 |
| 分隔 | 不画竖分隔线（流程感由轨道承担），卡片只有一层 0.5px 描边 + `--shadow-1` |
| 变体 | `.mflow.bare` 嵌在已有卡片内（去底色/描边/阴影），禁止卡片套卡片 |
| 禁止 | 27px 实心渐变徽章、箭头装饰线、四等分竖线、10px 描述、首步实心当进度用 |

## 6. 禁止清单

- ✗ 新组件写死 hex（必须走 token；`#6A58E0` 一类已收敛为 `var(--tint-solid)`）
- ✗ 重复定义同名 class（本文件曾出现 `.box` `.seg` `.rail-b` `.sbtn` `.root` 双份冲突，已合并，勿再复制粘贴整段 CSS）
- ✗ `transition: all`（按属性列出，避免布局属性被动参与动画）
- ✗ 1px 实线描边、纯白背景、纯黑文字（用 `--hair` / `--canvas` / `--ink`）
- ✗ Bootstrap / Ant Design 默认外观、蓝紫渐变、Inter 字体
- ✗ 在 `.sp` 上扩展新语义（它同时被「文档区弹性占位」和「字段样例胶囊容器」占用，新增请另起类名）

## 7. 改完怎么验收

1. 视觉：`chrome --headless=new --screenshot` 出亮 / 暗两版整页图，肉眼比对 topbar、mock、文档区。
2. 结构：注入计算样式探针（`getComputedStyle` 抓关键选择器的圆角/底色/高度/字重），改动前后 diff，**只应出现预期内的差异**；CSS 改动零视觉回归时 diff 应为空。
3. 前端代码：`npx tsc --noEmit`；不引入新 UI 库（见 AGENTS.md 前端规则）。
