# 仪表盘发布整合验收（2026-11-17）

状态：整合后的Java17完整验证通过，适合受现有权限约束的线上体验；整合提交bcc3d26af909534c935c8f9caa08c7446adb2ce6已原子推送到远端liyufan_dev与develop-2.0，ls-remote确认一致；66仍由用户部署。

用户授权评估额度、提交本地修改，并在验证适合后合入个人分支和部署主线。本地功能提交7866772a，整合基线origin/liyufan_dev与origin/develop-2.0均876d899d；保留主线3项独立提交及功能分支8项提交。

额度由16384改默认32768（独立配置，范围1024至65536且受服务限制）。历史两次实际输出14180/13817，16384余量仅约13%至16%；当前服务32768探针HTTP200、40405ms、12468输出tokens，工具JSON完整、HTML37080字符逐字一致，无实际工具写入。无需默认继续提高65536。历史生成/浏览器结果见[截断修复报告](20261117-dashboard-output-limit-fix.md)。

隔离worktree D:/da-r1117，未停止或接管用户IDEA8080和Vite5173。保留双方文档，GenBI ADR使用0149避开主线0146至0148。DatasetService完整构造器同时注入共享源授权和事务删除清理，补Spring装配行为回归；管理员回看新增类对迁移后的runtime.session引用同步更新。静态资源从合并源码重建，未选择任一侧旧bundle。

同checkout SDK依赖安装成功；core流式累加/输出限制/中断/流协议43项通过，Python工具15项和报告脚本6项通过。应用完整clean verify进行中，不能提前记为通过。Java21整合轮1163项仅架构门禁1项失败（共享源授权新增到web.auth.UserStore反向依赖），其余业务通过，23条件跳过。已抽取runtime.auth.UserIdentity只读契约，由原UserStore默认方法适配，未扩大依赖例外清单；补当前角色变动回归，并使用Java17重跑完整门禁。首轮发现管理员回看旧包引用编译失败已修正；前端三个前缀各129项已通过，后续后端与打包以最终结果为准。

本批不新增平台DDL，不修改Python/Wren锁定版本或七表/六表cron。66共享数据源既有迁移状态未知，按[升级指引](../guides/66-dashboard-upgrade.md)核对。合成样板文件随源码保存，但本机知识库、用户和已发布资产不会自动迁到66。本机验证不代替66实际网络/模型额度/迁移及发布验收，也不保证任意生成HTML首次无错。

Java17首轮：架构/新增装配/管理员回看/真实共享源HTTP回归通过；旧ExternalDataSourceConnectionTest的UserStore纯mock未执行新增默认身份方法，导致8项401相关断言失败。夹具改为CALLS_REAL_METHODS，继续使用原findById用户数据与真实默认方法，不给未知用户补权限、不删除断言；修正后重新完整验证。

## 最终验证结果

- Java17.0.14：`mvn -B -ntp clean verify -Dfrontend.npm.install.arguments=ci`成功，04:46；后端1164项，失败0、错误0、条件跳过23。1141项实际通过，不把跳过计为通过。
- 前端27文件，`/`、`/dataagent/`和`/dify/dataagent/`均各129项通过；TypeScript/Vite、Spotless、JAR及tar.gz打包通过，保留现有大包体积提示。
- `dataagent_test_report.py`成功，必需集成JpaPersistenceIntegration/H2DemoSeed/MarketContribution分别4/1/7项且无跳过；没有显式禁用或未分类跳过。
- core六类43项在Java17通过；同checkout SDK依赖安装成功。Python工具15项和报告脚本6项通过；身份/来源/架构专项17项通过。
- 条件跳过包括未显式配置的真实模型/Wren/MySQL/Docker/Python场景及本机无法创建symlink的1项；完整列表在本次隔离target/surefire-reports和.runtime/release-test-summary.md。此前真实GenBI/浏览器证据与此次整合检查分开记录，未在66重跑。
- 最终文档更新后DocsConsistencyTest 5项通过；Git空白、冲突标记、暂存路径及疑似凭据模式检查无问题。未加入.runtime、日志、私有配置或运行数据。

## 结论与范围

本次整合保留主线共享数据源、管理员只读回看和对话知识库浏览，保留本分支架构恢复/建模UI/仪表盘/截断及取消修复。未新增数据库迁移，不修改既有BI同步脚本或cron；不重置用户/模型服务/知识库。实际管理权限与排队撤权由回归覆盖，不宣称所有外部模型或任意生成网页首次无误。

GitHub Actions远端运行结果未作为本地已通过项目；部署脚本会再次在目标构建环境完整验证。66已有共享数据源结构、实际模型额度、网络和上线后体验仍按升级指引现场核对。本地合成样板需单独导入与发布。
