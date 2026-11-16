# 当前任务：架构优化第一阶段——CI与集成测试恢复

更新：2026-11-12 Asia/Shanghai；分支codex/architecture-phase1，基线5479d152；本轮起点工作区干净。

## 授权与范围

用户授权开始实施。先完成[spec128](../specs/128-architecture-phase1-ci-and-integration-tests.md)的独立应用CI、恢复三个禁用集成类与测试结果门禁；不实施核心类拆分、业务流程/DDL变化，不自动提交、push、合并或部署。沿用[shared-memory](../../.agents/skills/shared-memory/SKILL.md)记录；无新增ADR。

## 已实施

- .github/workflows/dataagent-ci.yml：个人/优化/主线push、所有PR及手动触发；Java17，同版框架依赖构建后standalone clean verify/npm ci；保存报告，不部署。
- 三个原Disabled集成类使用完整真实应用装配、H2/JPA/临时文件，外部SandboxClient替身，旧Primary模型替身移除；种子测试显式启用开发账号。测试JVM HOME隔离到target/test-home。
- Python汇总脚本区分跳过原因，缺报告、失败、显式禁用/未分类跳过或三个恢复类未完整执行会失败；6项脚本回归通过。CI与本地复现见[指引](../guides/dataagent-ci.md)。

## 本轮验证

- 恢复类12项全部实际通过、0跳过。首次clean verify通过：1150项、0失败/错误、21条件跳过；前端三个前缀各105项、TypeScript/Vite和JAR/tar打包通过，Spotless通过。
- CI框架依赖构建脚本本地实际install通过；actionlint1.7.12、Bash语法及汇总脚本回归通过。
- 原目录按npm ci复现遭Windows esbuild.exe占用EPERM；npm install已恢复依赖，未停止用户进程。干净隔离副本CI同款clean verify/npm ci已通过，前端三前缀各105项。
- Java17.0.14完整后端回归1150项、0失败/错误、21条件跳过；汇总门禁识别三个恢复类全部实际通过。隔离副本POM/测试源码与当前工作区SHA256一致。见[本轮报告](../test-reports/20261112-architecture-phase1-ci-and-tests.md)。
- 原目录Java17文档5项、前端三前缀各105项、TypeScript/Vite和package通过；依赖已恢复，target保留新JAR/tar。最终文档/格式/差异检查通过，业务源码和受跟踪static无差异。
- GitHub尚未触发，未连接真实模型/Wren/MySQL/Docker或生产；未修改分支保护，未提交/push/合并/部署。

## 待续

第一阶段本地实施验证完成，尚未提交。用户决定提交/推送后验首次GitHub实际运行，再按需要配置远端合并门禁；核心职责拆分和包依赖治理为后续阶段。
原个人分支提交任务见[快照](archive/CURRENT_TASK-before-architecture-phase1-20261112.md)。资料同步/双BI仍待用户运行验收；产品候选F01–F09不自动实施。
