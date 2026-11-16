# spec128：架构优化第一阶段——独立应用 CI 与集成测试恢复

## 背景与目标

用户已从个人分支提交 `5479d152` 创建 `codex/architecture-phase1`，并授权开始实施。先补充可靠的自动验证，作为后续职责拆分的基线；本轮不实施核心业务重构。此前初评见[架构与可维护性初评](../test-reports/20261112-architecture-maintainability-assessment.md)。

## 方案概述

- 新增 standalone GitHub Actions 工作流，覆盖个人、优化与 develop-2.0 分支 push、PR 和手动触发。从同一次 checkout 构建所需框架依赖，再执行独立应用 `clean verify`，复用 Maven 中的前端测试/构建并使用 npm ci。
- 恢复 JpaPersistenceIntegrationTest、H2DemoSeedTest、MarketContributionServiceTest；保留真实 Spring 装配/JPA/文件落地验证，隔离测试目录及外部服务，校正过时的开发种子与模型装配假设，不新增生产演示账号。
- CI 展示失败/错误/跳过原因并保存报告，三个恢复类必须被发现并执行，不能通过禁用或过滤测试变绿。

## 影响面

构建工作流、测试及测试隔离配置、验证指引和共享记忆。不改业务 API、权限契约、DDL、生产配置或运行主流程；无长期业务架构取舍，不新增 ADR。

## 验收标准

1. Given 干净源码和无既有本地 SNAPSHOT 的 Maven 仓库，When 执行 CI 构建步骤，Then 同版框架依赖可解析，应用编译、格式检查、前后端测试与打包均执行。
2. Given 无 Docker/Wren/模型服务和生产数据库，When 执行三个恢复类，Then 原有 12 项集成用例实际运行且无跳过，不接触开发者运行数据。
3. Given 报告缺失、指定恢复类未执行/跳过或测试失败，When 汇总报告，Then 检查失败，并明确区分环境条件跳过与显式禁用。
4. Given 第一阶段完成，When 执行本地完整检查，Then 记录真实通过、失败与条件跳过，原功能及格式/文档门禁通过。

## 边界与验证

不自动 commit/push、修改远端分支保护、部署或连接生产；GitHub 实际触发须推送后验证，本地不冒充已在 GitHub 运行。真实模型/Wren/Docker 条件验收仍独立进行。先跑恢复类定位问题，再跑全量、package、前端 build 与工作流静态/可本地复现检查。
