> 后续交付：spec128–131已统一纳入[本地检查点](20261112-architecture-checkpoint.md)，并补当前完整验证。本报告保留原阶段的测量范围与提交状态。

> 历史基线：本报告记录阶段1/盘点时状态；后续分层、清理及最新实测见[阶段2报告](20261112-test-lanes-and-cleanup.md)。

# 2026-11-12：架构第一阶段——独立 CI 与集成测试恢复

## 范围与状态

分支 `codex/architecture-phase1`，基线 `5479d152`，起点干净。用户授权开始实施，[spec128](../specs/128-architecture-phase1-ci-and-integration-tests.md)限定第一阶段为验证基础；本轮没有生产 Java、业务 API、DDL、权限规则或前端源码变更，没有自动提交、push、合并、部署或重启用户服务。

本地工作流与测试修改已实施。GitHub 上的实际触发、Ubuntu/Temurin 环境及远端分支保护仍需推送后另验，不把本地通过描述为 GitHub 已通过。

## 实现

- [dataagent-ci.yml](../../../.github/workflows/dataagent-ci.yml)响应个人/优化/主线 push、全部 PR 和手动触发，使用 Ubuntu24.04、Java17；先从同次 checkout 安装所需框架模块及 BOM，再执行 standalone clean verify/npm ci。框架安装阶段跳过其测试，应用阶段不跳过测试。权限 contents:read，不自动部署。
- [框架依赖构建脚本](../../../.github/scripts/build-dataagent-dependencies.sh)覆盖 standalone 当前直接使用的本仓库模块，与既有容器源码构建边界一致；不依赖“本机以前安装过就算通过”。现有 Maven缓存可加速依赖下载，不能跳过当前源码的框架安装步骤。
- [测试汇总脚本](../../../.github/scripts/dataagent_test_report.py)统计一个 clean run，列出每类跳过和原因。缺失/损坏报告、失败/错误、显式禁用/未分类跳过、恢复类缺失或少于原有用例数量都会返回失败；Surefire 报告保存7天。前端结果保留在构建日志，未并入后端数字。
- 新增 `IsolatedApplicationTestSupport`：使用真实 DataAgentApp/Spring 装配、JPA、H2及临时文件，替换的只有外部 SandboxClient；不通过 mock 掉被测持久化/贡献服务取得通过。MOCK WebFlux 上下文保留真实安全和 Web 装配。
- 移除三个类的 Disabled 和重复的旧 Primary Model/BaseStore 替身；沿用生产 SwitchingModel 装配，两个种子场景显式启用开发账号和H2种子，贡献场景每例清理独立库内的贡献记录。原12项断言保留。
- pom 仅增加测试 JVM 的 user.home=target/test-home，防止完整启动测试读取或生成开发者 HOME 下的配置。Spring工作区、MDL、用户目录各自使用JUnit临时目录。生产默认账号策略和配置不变。

旧循环依赖注释不能证明当前仍有启动故障。实际源码中的 DataAgentWorkspaceConfig 已独立解析工作区，三个恢复类都完整启动成功；本次未新增生产装配绕行或允许循环引用。

## 本次实测

| 检查 | 结果 |
| --- | --- |
| 恢复的三个集成类 | 4+1+7=12项，0失败/错误/跳过 |
| 首次原目录 clean verify | 1150项，0失败/错误，21条件跳过；前端三个前缀各105项、TypeScript/Vite及JAR/tar通过 |
| 框架依赖脚本 | 本地真实 reactor install成功 |
| 干净源码副本、CI同款clean verify/npm ci | 通过；未复制ignored配置、node_modules或target |
| Java17.0.14完整后端回归 | 1150项，0失败/错误，21条件跳过；恢复12项均通过，汇总门禁通过 |
| 恢复依赖后的原目录Java17 package | 文档5项、前端三前缀各105项、TypeScript/Vite、Spotless与JAR/tar通过 |
| 汇总脚本回归 | 6项通过，含缺失/损坏报告、失败、漏跑、禁用、条件跳过、计数不一致与重复suite |
| actionlint 1.7.12 | 新工作流通过；官方二进制与发布SHA256校验一致 |
| Bash语法与文档相对链接 | 通过 |

后端总数仍为1150；跳过从33降至21，减少的12项正是本次恢复的三个类。剩余条件测试需要明确配置的真实Wren、模型、MySQL、memory或Docker夹具，未把它们算成通过。首轮本机为Windows/Java21、release17，随后已在Windows/Java17.0.14重跑全部后端测试；GitHub的Ubuntu/Temurin17环境仍待实际触发。隔离副本的POM和本轮Java测试源码与当前工作区逐文件SHA256一致。

## Windows本地安装问题与处理

原目录直接按CI执行npm ci时，Windows拒绝删除被占用的 `@esbuild/win32-x64/esbuild.exe`，报EPERM/-4048；该次命令失败，不能称为通过。没有停止开发进程，也没有强制删其原生文件。随后npm install恢复原目录依赖；使用只包含当前源码的隔离副本执行npm ci及完整verify成功，避免占用冲突。

验证副本位于仓库ignored的 `.runtime/phase1-ci-validation`，原始日志在 `.runtime/phase1-*.log`。副本用于隔离验证，不是新分支或部署；源码/测试的变更保留在当前优化分支工作区。相关操作见[CI指引](../guides/dataagent-ci.md)。

原目录恢复后的最终package已通过；target保留新exec JAR和tar，受跟踪static及前端lockfile没有差异。记录补充后复核文档5项、Spotless及git diff --check；本轮只在构建、测试和文档范围留下源码变更。

## 未验证与后续

没有在GitHub触发、下载一个完全空的第三方Maven缓存、修改远端必需检查，或进行真实模型/Wren/MySQL/Docker/生产验收。推送后核对DataAgent CI首次运行，再根据用户选择配置合并门禁；核心类拆分、包边界和数据库演进是后续阶段。首次手动触发入口要求工作流存在于默认分支，优化分支可先用push触发。
