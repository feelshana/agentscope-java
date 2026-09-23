# 数据源管理弹窗界面优化 - 完成总结

## 完成时间
2026-09-23（第二轮优化）

## 修改内容

### 1. DataSourceManagerModal.tsx
**尺寸调整**：
- 宽度：880px → 1300px
- 最小高度：600px
- 圆角：16px
- 最大高度：90vh，`width: min(1300px, 96vw)`

**视觉优化**：
- 标题字号提升至 1.1rem，字重 700
- 添加数据源计数显示（"共 X 个数据源"）
- 表格容器增加圆角边框（10px）
- 错误提示改为带背景的卡片样式

**状态徽章**：
- 连通性状态改为彩色徽章（绿色/红色 + 圆点指示器）
- 类型徽章：MySQL 蓝色，PostgreSQL 紫色

**表单布局**：
- 采用 2 列网格布局
- JDBC 地址字段占满整行
- 输入框内边距增加（10px 12px）
- 字段间距加大（16px 20px）
- 数据采样选项改为水平 checkbox

### 2. DataSourceDetailModal.tsx（完全重写）
**尺寸调整**：
- 宽度：960px → 1400px
- 最小高度：650px
- 圆角：16px
- `width: min(1400px, 96vw)`

**头部信息（TC 风格）**：
- 数据源名称（`1.2rem`，字重 700）
- 类型徽章（`rgba(37,99,235,0.08)` 背景，`#2563eb` 文字，`letter-spacing: 0.04em`）
- 统计信息行："X 个数据库 · Y 个数据表"

**三列比例布局**：
- 左列（数据库）：`flex: 0 0 220px`，固定窄宽
- 中列（数据表）：`flex: 0 0 300px`，稍宽
- 右列（字段信息）：`flex: 1`，占满剩余空间
- 列内边距 `0 20px 20px`
- Section title 自带 `padding-top: 16px` + 底部分隔线

**列表项样式**：
- 选中项：`rgba(79, 70, 229, 0.06)` 极浅蓝背景（TC 风格）
- 悬停效果：`var(--da-surface-sunken)` 背景

**字段信息表格**：
- 表头：`background: var(--da-surface-sunken)`
- 类型徽章：等宽字体 + `border: 1px solid var(--da-border)` + `whiteSpace: nowrap`
- 所有单元格 `verticalAlign: top`

### 3. AssociateTablesModal.tsx
**尺寸调整**：
- 宽度：1000px → 1400px
- 最小高度：650px
- 圆角：16px
- `width: min(1400px, 96vw)`

**头部信息**：
- 标题"从数据源关联"（`1.1rem`，字重 700）
- 副标题说明文字
- 内边距 `20px 28px`（与 DataSourceDetailModal 统一）

**三列比例布局（与 DataSourceDetailModal 对齐）**：
- 左列（数据库）：`flex: 0 0 220px`，固定窄宽
- 中列（数据表）：`flex: 0 0 300px`，稍宽
- 右列（字段信息）：`flex: 1`，占满剩余空间
- 列内边距 `0 20px 20px`
- Section title 自带分隔线

**数据表项**：
- Checkbox 尺寸 16x16px
- 选中项使用 `rgba(79, 70, 229, 0.06)` 背景（与 DataSourceDetailModal 一致）
- 表名 + comment 描述垂直布局

**字段信息表格**：
- 与 DataSourceDetailModal 完全一致的表格样式
- 等宽字体类型徽章带 `1px solid var(--da-border)` 边框

**底部操作栏**：
- 背景色 `var(--da-surface-sunken)`
- 内边距 `14px 28px`
- 关联按钮显示选中数量

### 4. 文档更新
- ADR-0017：数据源管理弹窗界面优化（已更新第二轮改动）
- 本文档

## 统一的视觉语言

### 间距规范
- 弹窗头部内边距：`20px 28px`
- 列内边距：`0 20px 20px`（section title 自带 padding-top）
- 表格行内边距：`11px 14px`
- 列表项内边距：`9px 12px`
- 表头内边距：`10px 14px`
- 底部栏内边距：`14px 28px`

### 颜色系统
- 选中项背景：`rgba(79, 70, 229, 0.06)`（TC 风格极浅蓝）
- 成功状态：`#16a34a` + `rgba(22, 163, 74, 0.1)`
- 失败状态：`#e11d48` + `rgba(225, 29, 72, 0.1)`
- MySQL 类型：`#2563eb` + `rgba(37, 99, 235, 0.08)`
- PostgreSQL 类型：`#7c3aed` + `rgba(124, 58, 237, 0.1)`

### 响应式处理
- 所有弹窗使用 `width: min(Xpx, 96vw)` 确保小屏可用
- 最大高度 `90vh` 避免超出视口

## 验证结果

- TypeScript 编译通过（无错误）
- 保留原有功能逻辑
- 使用现有设计系统变量
- 响应式降级处理

## 参考

- TC DataAgent 数据源管理界面截图
- 用户反馈："弹窗很小，不够大气，做的大一些，好看一些"
- 用户反馈："三个弹窗再大一些，这个详情界面还能更好看一些吗？"
