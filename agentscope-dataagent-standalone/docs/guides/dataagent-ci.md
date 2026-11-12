# 独立应用 CI

CI 自动验证本次代码，不部署应用。[工作流](../../../.github/workflows/dataagent-ci.yml)的固定检查名为 `DataAgent build and test`，与原框架 CI 分开。代码已配置不等于已在 GitHub 运行；首次推送后的实际结果应从 Actions 核实。

## 触发与检查范围

- push：main、develop-2.0、liyufan_dev、wangchao_dev、codex/**；pull_request：所有目标分支。未按路径跳过，避免未来必需检查一直 pending。
- workflow_dispatch：支持手动执行；GitHub 的手动触发入口要求工作流先存在于默认分支。优化分支首次验收可通过 push 触发。
- Ubuntu 24.04 / Temurin Java 17。checkout 使用触发版本；PR 默认验证合并候选，框架和应用来自同一次 checkout，不另外拉取主线替换依赖。
- [依赖构建脚本](../../../.github/scripts/build-dataagent-dependencies.sh)安装应用直接使用的同仓库模块、两个 BOM 及其 reactor 依赖。此步骤的 `-DskipTests` 只跳过框架测试，框架测试继续由原工作流负责。
- 应用执行 `mvn clean verify -Dfrontend.npm.install.arguments=ci`，不跳过后端测试。现有 Maven 生命周期执行 Spotless、后端测试、前端三个部署前缀的测试、TypeScript/Vite build 和应用打包。npm ci 使用已提交 lockfile。

## 查看结果

在 GitHub 的 Actions → DataAgent CI 或 PR Checks 查看每一步日志。汇总显示后端总数、失败、错误、跳过与原因；Surefire与target/architecture静态报告以 `dataagent-test-reports-*` 附件保存 7 天。前端每个前缀的数量与结果在构建日志中，不混入后端统计。

[汇总脚本](../../../.github/scripts/dataagent_test_report.py)遇到缺失/损坏报告、失败/错误、显式禁用或原因未分类的跳过返回失败；三个已恢复集成类还必须分别执行至少 4、1、7 项且全部通过。条件/assumption 跳过明确列出，不当作通过，也不证明真实模型/Wren/MySQL/Docker已验收。CI 使用 clean，不能混合不同日期的 target XML 统计。

CI 权限只有 contents:read，不使用生产凭据、生产数据库、宿主 Docker socket或自动部署步骤。检查失败后是否禁止合并取决于远端分支保护；本轮未修改该设置。

## 本地复现

在仓库根目录、Git Bash 或 Linux Bash 执行：

```bash
python3 -m unittest discover -s .github/scripts -p 'test_dataagent_test_report.py' -v
bash .github/scripts/build-dataagent-dependencies.sh
mvn -B -ntp -f agentscope-dataagent-standalone/pom.xml clean verify -Dfrontend.npm.install.arguments=ci
python3 .github/scripts/dataagent_test_report.py agentscope-dataagent-standalone/target/surefire-reports
```

Windows 可使用已安装的 Python 命令 `python`。JpaPersistenceIntegrationTest保留完整应用装配与外部SandboxClient替身；H2DemoSeedTest使用文件H2/JPA切片，MarketContributionServiceTest使用真实JPA/事务/文件与工作区、沙箱注册表替身。三类原12项均保留，不启动Docker。Maven 测试 JVM 的 user.home 指向 target/test-home，Spring 测试的工作区、MDL及用户目录也独立隔离；不读取开发者 HOME 下的 agentscope.json。开发账号只在两个种子测试中显式启用。

Windows 上已有开发进程可能占用 esbuild/rollup 原生文件，使会重建 node_modules 的 npm ci 报 EPERM。本轮已遇到并改在隔离源码副本验证；当前开发目录依赖经 npm install 恢复，不为此停止用户进程。复现全新安装时使用独立目录或 worktree，并另行隔离运行数据。

上述user.home隔离由Maven Surefire注入，IDE直接运行JUnit不会自动继承pom设置。完整应用测试虽隔离了Spring工作区，但Bootstrap静态默认路径仍读取JVM的user.home。IDE可委托Maven测试，或为测试专门设置独立的-Duser.home目录；该参数不应加入正常应用启动配置。全量测试保留价值、慢测与日常分层建议见[盘点报告](../test-reports/20261112-test-value-and-development-impact.md)；当前分层实现见下文。

完整构建会生成 src/main/resources/static；审查生成结果，避免把不同部署前缀的产物混入无关修改。源码构建验证与真实服务验收分开：CI不验证真实模型回答、线上BI入口、生产数据库迁移或备份恢复。

## 日常检查与完整检查

应用目录下执行（Windows用`python`，其他环境可用`python3`）：

```bash
python scripts/check.py backend --tests QueryDataFilesTest
python scripts/check.py backend --tests 'WrenScopedMcpClientTest#databaseWindowRefusalPreventsMcpExecutionWithoutReadingMdl'
python scripts/check.py backend --unit
python scripts/check.py backend --tests H2DemoSeedTest,MarketContributionServiceTest,JpaPersistenceIntegrationTest
python scripts/check.py frontend --files auth-session.test.mjs
python scripts/check.py frontend --all-prefixes
python scripts/check.py full
```

后端单元/完整通道也自动执行ArchitectureDependencyTest，输出target/architecture静态依赖报告；职责与历史例外维护见[依赖契约](../reference/architecture-boundaries.md)。

后端入口跳过所有npm目标；`--unit`排除integration和external标签，仍包含无需Spring的权限、文件、进程模拟等回归。13个上下文/持久化类标记integration，真实环境测试标记external；混合类只给外部环境方法打标签，保留本地回归。`backend`无筛选参数运行全部后端。拼错类/方法、前端文件或前缀会失败，不允许零测试假绿。

前端入口默认只跑根前缀，`--files`只选择指定文件；`--all-prefixes`覆盖三个部署前缀。原`npm test`无参数仍是22文件×三个前缀，`npm run build`仍执行类型检查与产品构建。选测不自动执行产品构建。

完整入口执行原`mvn clean verify`，CI额外使用npm ci并检查报告，均不使用上述标签过滤。合并/发布前保持完整验证；日常按实际改动选测，涉及数据库/HTTP/装配追加相关integration。现有启动、打包和部署命令不变。Python依赖较多的条件测试仍单独验收；15项可独立运行的工具逻辑测试已纳入CI，另有6项报告脚本回归。验证结果见[本轮报告](../test-reports/20261112-test-lanes-and-cleanup.md)。

## 维护

新增应用直接依赖的同仓库模块时，同步依赖构建脚本；当前依赖边界与 docker/build-in-container.sh 一致。引入新的条件测试时，在报告中保留可读跳过原因。不要用 Disabled、忽略失败或过滤原测试让检查变绿。

参考：[GitHub 工作流](https://docs.github.com/en/actions/concepts/workflows-and-actions/workflows)、[触发事件](https://docs.github.com/en/actions/reference/workflows-and-actions/events-that-trigger-workflows)。
