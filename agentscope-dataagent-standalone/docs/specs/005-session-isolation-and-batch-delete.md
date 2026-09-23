# spec 005: 会话隔离与批量删除

## 背景与目标

实现会话级别的消息状态隔离，支持批量删除会话，提升多会话管理体验。

## 方案概述

1. 后端新增 `SessionRegistryEntity`、`SessionReadStateEntity` 等 JPA 实体
2. `SessionStore` 重构为 JPA 实现，支持会话注册和读取状态追踪
3. 前端 `SessionsSidebar` 支持多选和批量删除
4. `ChatPanel` 切换会话时保持后台流继续

## 影响面

- 后端：`SessionStore.java`、`SessionAgentManager.java`、`SessionController.java`
- 前端：`SessionsSidebar.tsx`、`ChatPanel.tsx`
- 数据：新增 4 个 JPA 实体和 Repository

## 验收标准

1. Given 用户有多个会话，When 切换会话，Then 后台流继续运行
2. Given 用户选中多个会话，When 点击批量删除，Then 所有选中会话被删除
3. Given 会话被删除，When 重新加载，Then 会话不再显示

## 不做的事

- 会话间消息迁移（后续版本考虑）

## 测试要求

- 新增会话切换时后台流不中断的测试
- 批量删除后数据一致性测试

## 关联

- ADR 0011
