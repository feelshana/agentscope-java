# spec 008: 沙箱会话目录隔离

## 背景与目标

防止跨会话文件访问，每个会话使用独立的沙箱子目录。

## 方案概述

`SharedSandboxFilesystem` 按 sessionId 创建子目录，所有文件操作限制在该目录内。

## 影响面

- 后端：`SharedSandboxFilesystem.java`（+136/-20 行）

## 验收标准

1. Given 会话 A 创建文件，When 会话 B 尝试访问，Then 返回文件不存在
2. Given 会话删除，When 检查沙箱目录，Then 对应子目录被清理

## 不做的事

- 跨会话文件共享（安全考虑，明确禁止）

## 测试要求

- 跨会话文件访问隔离测试
- 会话删除时目录清理测试

## 关联

- ADR 0014
