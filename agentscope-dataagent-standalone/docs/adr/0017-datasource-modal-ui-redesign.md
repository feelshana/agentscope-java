# ADR-0017: 数据源管理弹窗界面优化

## 状态

已接受 (2026-09-23)

## 背景

数据源管理功能涉及三个核心弹窗组件：
1. **DataSourceManagerModal** - 数据源列表与 CRUD 操作
2. **DataSourceDetailModal** - 数据源详情浏览（数据库 → 数据表 → 字段三级导航）
3. **AssociateTablesModal** - 从数据源关联库表到知识库

原有设计存在以下问题：
- 弹窗宽度偏小（880px / 960px / 1000px），即使数据源很少也显得紧凑
- 视觉层次不够清晰，缺乏"大气"的产品感
- 状态展示（连通性）使用纯文本，不够直观
- 表单布局较为密集，输入框间距不足
- 三级导航的列宽比例不够合理，右侧字段信息区域偏窄

## 决策

参考 TC DataAgent 的侧边面板设计风格，对三个弹窗进行以下优化：

### 1. 尺寸与布局调整

| 组件 | 原宽度 | 新宽度 | 最小高度 |
|------|--------|--------|----------|
| DataSourceManagerModal | 880px | 1300px | 600px |
| DataSourceDetailModal | 960px | 1400px | 650px |
| AssociateTablesModal | 1000px | 1400px | 650px |

所有弹窗统一使用 `border-radius: 16px`（原 12px），`maxHeight: 90vh`，`width: min(Xpx, 96vw)` 响应式降级。

### 2. DataSourceManagerModal 优化

**视觉层次**：
- 标题字号提升至 `1.1rem`，字重 700
- 添加数据源计数显示（"共 X 个数据源"）
- 表格容器增加圆角边框（`border-radius: 10px`）

**状态徽章**：
- 连通性状态改为彩色徽章（pill badge）
- 已连通：绿色背景 `rgba(22, 163, 74, 0.1)` + 绿色圆点
- 连接失败：红色背景 `rgba(225, 29, 72, 0.1)` + 红色圆点
- 类型徽章：MySQL 蓝色，PostgreSQL 紫色

**表单布局**：
- 采用 2 列网格布局（`grid-template-columns: 1fr 1fr`）
- JDBC 地址字段占满整行（`grid-column: 1 / -1`）
- 输入框内边距增加至 `10px 12px`
- 字段间距加大（`gap: 16px 20px`）
- 数据采样选项改为水平布局的 checkbox

**表格行**：
- 行高增加至 `padding: 14px 16px`
- 数据源名称字重 500，颜色加深
- 操作按钮右对齐，间距 8px

### 3. DataSourceDetailModal 优化

**头部信息（TC 风格）**：
- 数据源名称（`1.2rem`，字重 700）+ 类型徽章（`rgba(37,99,235,0.08)` 背景，`#2563eb` 文字，`letter-spacing: 0.04em`）
- 统计信息行："X 个数据库 · Y 个数据表"（`0.82rem`，`var(--da-text-muted)`）
- 头部内边距 `20px 28px`

**三列比例布局**：
- 左列（数据库）：`flex: 0 0 220px`，固定窄宽
- 中列（数据表）：`flex: 0 0 300px`，稍宽
- 右列（字段信息）：`flex: 1`，占满剩余空间
- 列内边距 `0 20px 20px`，section title 自带 `padding-top: 16px`
- 列间用 `1px solid var(--da-border)` 分隔

**Section 标题**：
- 字号 `0.88rem`，字重 600，颜色 `var(--da-text)`
- 底部 `1px solid var(--da-border)` 分隔线
- 子标题用 `var(--da-text-muted)` 显示当前选中项（如"数据表 · test_data"）

**列表项样式**：
- 内边距 `9px 12px`
- 选中项：`rgba(79, 70, 229, 0.06)` 极浅蓝背景（TC 风格）
- 悬停效果：`var(--da-surface-sunken)` 背景

**字段信息表格**：
- 外层容器 `border-radius: 8px`，`1px solid var(--da-border)`
- 表头：`background: var(--da-surface-sunken)`，`padding: 10px 14px`
- 数据行：`borderBottom: 1px solid var(--da-border)`，`padding: 11px 14px`
- 类型字段：等宽字体徽章（`var(--da-mono)`，`border: 1px solid var(--da-border)`，`whiteSpace: nowrap`）
- 列名字段字重 500，所有单元格 `verticalAlign: top`

### 4. AssociateTablesModal 优化

**头部信息**：
- 标题"从数据源关联"（`1.1rem`，字重 700）
- 副标题说明文字（`0.85rem`，`var(--da-text-3)`）
- 头部内边距 `20px 28px`，与 DataSourceDetailModal 统一

**数据源选择器**：
- 独立区域，`padding: 16px 20px 0`
- Label 标签 + select 最小宽度 280px

**三列比例布局（与 DataSourceDetailModal 对齐）**：
- 左列（数据库）：`flex: 0 0 220px`，固定窄宽
- 中列（数据表）：`flex: 0 0 300px`，稍宽
- 右列（字段信息）：`flex: 1`，占满剩余空间
- 列内边距 `0 20px 20px`，section title 自带分隔线
- 列间用 `1px solid var(--da-border)` 分隔

**数据表项**：
- Checkbox 尺寸 16x16px
- 选中项使用 `rgba(79, 70, 229, 0.06)` 背景（与 DataSourceDetailModal 一致）
- 表名 + comment 描述垂直布局
- "查看"按钮悬停效果：`var(--da-primary-subtle)` 背景

**字段信息表格**：
- 与 DataSourceDetailModal 完全一致的表格样式
- 等宽字体类型徽章带 `1px solid var(--da-border)` 边框
- 表头 `var(--da-surface-sunken)` 背景

**底部操作栏**：
- 背景色 `var(--da-surface-sunken)`
- 内边距 `14px 28px`
- 按钮内边距 `9px 20px`
- 关联按钮显示选中数量

### 5. 统一的视觉语言

**圆角**：
- 弹窗外壳：16px
- 表格容器：8-10px
- 列表项/徽章：6-8px
- 输入框：8px

**间距**：
- 弹窗头部内边距：`20px 28px`
- 列内边距：`0 20px 20px`（section title 自带 padding-top）
- 表格行内边距：`11px 14px`
- 列表项内边距：`9px 12px`
- 表头内边距：`10px 14px`
- 底部栏内边距：`14px 28px`

**颜色系统**：
- 主文本：`var(--da-text)`
- 次要文本：`var(--da-text-2)`
- 辅助文本：`var(--da-text-3)` / `var(--da-text-muted)`
- 成功状态：`#16a34a` + 浅绿背景
- 失败状态：`#e11d48` + 浅红背景
- MySQL 类型：`#2563eb` + 浅蓝背景
- PostgreSQL 类型：`#7c3aed` + 浅紫背景

**阴影与边框**：
- 弹窗使用 `var(--da-shadow-pop)` + 1px 边框
- 表格容器使用 1px 边框
- 列表项无阴影，通过背景色变化提供反馈

## 影响

### 正面影响
1. **视觉体验提升**：更大的尺寸和更合理的间距让界面更"大气"，符合企业级产品标准
2. **信息层次清晰**：通过徽章、字重、颜色对比增强可读性
3. **操作反馈明确**：悬停效果、选中状态、状态徽章提供清晰的视觉反馈
4. **空间利用合理**：三列导航比例优化，字段信息区域更宽敞
5. **一致性增强**：三个弹窗采用统一的视觉语言（圆角、间距、颜色）

### 负面影响
1. **屏幕占用增加**：1300-1400px 宽度在 1366px 屏幕上会占据大部分空间（已用 `96vw` 限制）
2. **小屏适配**：最小高度 600-650px 在低分辨率屏幕上可能需要滚动

### 兼容性
- 使用现有设计系统变量（`var(--da-*)`），无需修改 `global.css`
- 保留原有功能逻辑，仅调整样式
- 响应式降级：`width: min(Xpx, 96vw)` 确保小屏可用

## 实施细节

### 修改文件
1. `frontend/src/components/DataSourceManagerModal.tsx`
2. `frontend/src/components/DataSourceDetailModal.tsx`
3. `frontend/src/components/AssociateTablesModal.tsx`

### 未修改内容
- API 层（`api/datasources.ts`）：接口无变化
- 后端控制器：无需调整
- 路由配置：弹窗由父组件控制，路由无变化

## 后续优化建议

1. **响应式优化**：针对 1366px 及以下屏幕优化列宽比例
2. **动画增强**：列表项选中/悬停可添加 `transition: all 0.2s ease`
3. **搜索功能**：数据源列表、数据库/数据表列表添加搜索过滤
4. **批量操作**：数据源列表支持批量删除、批量检测连通性
5. **拖拽排序**：数据源列表支持拖拽排序（需后端支持 `sort_order` 字段）

## 参考

- TC DataAgent 数据源管理界面截图（5 张）
- 用户反馈："弹窗我觉得没问题OK的，但是呢，我们的弹窗很小，不够大气，做的大一些，好看一些"
