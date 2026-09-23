# ADR 0014: 沙箱会话目录隔离

- 状态：已采纳（从 data-agent-merge 合并）
- 日期：2026-09-22
- 来源：commit bba96245

## 背景

原沙箱文件系统所有会话共享同一目录，存在跨会话文件访问风险。

## 决策

`SharedSandboxFilesystem` 按 sessionId 创建独立子目录，每个会话只能访问自己的文件。

## 理由与权衡

- 安全性：防止会话间文件泄露
- 隔离性：会话删除时清理对应目录，不留残留
- 代价：轻微性能开销（目录创建）

## 影响面

- 后端：`SharedSandboxFilesystem.java`（+136/-20 行）
