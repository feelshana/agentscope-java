# ADR 0011: 会话隔离与批量删除

- 状态：已采纳（从 data-agent-merge 合并）
- 日期：2026-09-21
- 来源：commit 9c0209e0, 9de599af

## 背景

原会话管理采用共享状态模式，切换会话时后台流会中断，且缺少批量删除功能。
用户需要独立的消息状态管理和批量操作能力。

## 决策

1. **会话隔离**：每个会话独立消息状态，切换会话时后台流继续运行
2. **批量删除**：前端 SessionsSidebar 支持多选删除，后端 SessionController 提供批量删除接口
3. **JPA 实体迁移**：新增 `SessionRegistryEntity`、`SessionReadStateEntity` 等实体，替代原有内存存储

## 理由与权衡

- 用户体验提升：切换会话不丢失后台任务
- 数据一致性：JPA 持久化替代内存存储，重启不丢失
- 代价：增加数据库查询开销（已通过索引优化）

## 影响面

- 后端：`SessionStore.java`、`SessionAgentManager.java`、`SessionController.java`
- 前端：`SessionsSidebar.tsx`、`ChatPanel.tsx`
- 数据：新增 JPA 实体和 Repository
