# 架构治理本地检查点（2026-11-12）

用户询问先提交还是继续后续；当前已累积spec128–131的独立CI、测试分层/旧链清理、依赖约束与MDL只读抽取，选择先固定本地检查点，再推进新的职责拆分。分支codex/architecture-phase1，父提交5479d152；本报告随检查点提交保存，提交ID以git log核实，不自动push、合并或部署。

## 提交范围

- spec128：独立应用CI、同checkout框架依赖构建、报告门禁，恢复/隔离原禁用场景。
- spec129：日常后端/前端选测与完整检查分层、上下文收窄、无调用旧链清理与条件测试迁移。
- spec130 / ADR0144：生产字节码依赖门禁、历史类对原因、通用计时/上下文键归runtime、五条主线资源边界。
- spec131：MdlReadService和MdlTypes归位、保留发布委托/写链路，旧JSON兼容与读写边界验证。

这是把前几轮尚未提交的成果固定成一个可审查/可回退版本；不冒充每阶段已分别提交。后续独立改造及时单独提交。本机配置、运行数据、依赖目录、target、临时变异探针及.runtime验证副本不进入提交。

## 本次完整验证

Windows、Java17.0.14；当前受版本控制/非忽略源码复制到.runtime/checkpoint-ci-validation，不复制本机私有配置或node_modules；在副本使用npm ci，避免触碰正在使用的开发目录。框架使用此前同checkout已构建的本地依赖，框架源码在这些阶段没有变更，本次不重复上游全部框架测试。

实际执行：

```text
mvn -B -ntp -f <隔离副本>/agentscope-dataagent-standalone/pom.xml clean verify -Dfrontend.npm.install.arguments=ci
python .github/scripts/dataagent_test_report.py <隔离副本>/agentscope-dataagent-standalone/target/surefire-reports
```

| 检查 | 本次结果 |
| --- | --- |
| 完整后端 | 166类1091项：1070通过，0失败/错误，21条件跳过 |
| 三个必跑集成类 | JpaPersistenceIntegrationTest 4、H2DemoSeedTest 1、MarketContributionServiceTest 7；均实际执行、0跳过 |
| 架构与新读侧 | ArchitectureDependencyTest 5、MdlReadServiceTest 6、MdlTypesCompatibilityTest 1均通过，未按标签过滤 |
| 前端测试 | 22文件×三个部署前缀，各105项通过，合计315 |
| 格式/构建/打包 | Spotless、TypeScript、Vite、普通JAR、可执行JAR、tar通过 |
| Python | 报告脚本6项、独立工具逻辑15项通过；并非全部Python专项 |
| 工作流/报告 | actionlint、完整XML报告门禁通过 |
| Maven总耗时 | 3分36秒，含新依赖安装、前后端编译测试及打包 |

日志在.runtime/checkpoint-full.log与checkpoint-summary.log；干净Surefire和架构TSV在隔离副本target。源码快照1855个文件已核对与待提交代码一致；此后只补交付记录，最终文档检查另行运行。不混入主目录选测产生的旧XML，也不把21项条件跳过当作通过。

## 限制与下一步

未执行真实LLM/Wren/MySQL/Docker条件场景、线上入口、重启/恢复、生产迁移、容量或灾难演练。源码构建通过不代表正在运行的服务已加载，也不代表已验证GitHub Actions/远端分支保护。本次本地提交不触发远端CI或部署。

下一批先梳理发布校验/计划检查与快照协调的调用、错误契约及锁顺序，再选择小批抽取；跨资源恢复仍需独立临时环境演练。资料同步/双BI等已有功能的用户运行验收继续保留，F01–F09不自动实施。
