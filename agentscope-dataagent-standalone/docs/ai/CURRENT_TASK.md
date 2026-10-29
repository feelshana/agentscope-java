# 当前任务：66从GitHub源码构建应用镜像（2026-10-29 Asia/Shanghai）

## 目标与授权边界
- 用户选择GitHub源码交付，66拉源码后在构建容器编译/封装应用镜像。用户已反馈源码拉取完成；66命令由用户执行回传，助手未连接66。
- 不改宿主Java8/glibc、不装全局Maven/Node、不动既有Dify，不全局prune，用户已授权一次本地提交，明确由用户自己push，助手不得push。正式数据库未取得，当前只构建，不启动生产业务。
- 本次整理以codex/integrate-boundary-lyf-v2@b82bb7f48220889436e0efd49d515651f900d717为父版本；有效运维/SQL/文档修改归入同一次提交，最终HEAD以git log为准。
- 上一A/B测试见[归档](archive/20261029-container-ab-complete.md)和[报告](../test-reports/20261029-container-runtime-ab-regression.md)。正式Java并发阻塞仍未修，async2只在旧output隔离实验，不在本次源码改动中。

## 66已核实
- 用户截图：Git1.8.3.1、Docker客户端/daemon26.1.4、Compose2.27.1，runtime/sandbox两镜像在；GitHub HTTPS200且ls-remote目标分支b82bb7f4可读，默认HEAD不同。
- 用户反馈已clone，尚未回传实际本地HEAD；不将默认分支代替codex/integrate-boundary-lyf-v2。旧Git用cd方式，不用git -C/switch/worktree，不升级宿主。
- 已给66拉取m.daocloud.io/docker.io/library/maven:3.9.9-eclipse-temurin-17命令，未收到拉取/旧内核运行结果。

## 本轮实现（随本次本地提交交付，待用户push）
- [spec099](../specs/099-source-build-deployment.md)、[ADR0118](../adr/0118-source-build-on-deployment-host.md)，只构建阶段完成，完整部署未完成。
- docker/update-source.sh：指定分支/干净工作区，3次fetch/单次90秒/退避，仅快进，不reset/clean/切分支/启停服务。
- docker/check-builder.sh：无生产挂载的断网512MiB临时容器，检查Java/javac/Maven；本地脚本实际运行通过。
- docker/build-from-source.sh：要求干净明确HEAD及已提交控制文件，git archive固定源和构建控制文件；默认~/dataagent-build专属jobs/cache与flock，2CPU/6GiB/3600秒，无宿主Maven/生产data/socket挂载到编译容器。
- docker/build-in-container.sh和build-maven-settings.xml：两个BOM/harness/所需扩展及core从源码安装（框架测试跳过），应用verify/前端ci，固定Node20.19.2/npm10.9.2，公共依赖用项目范围国内源。
- docker/application.Dockerfile/application-entrypoint.sh：基于原runtime镜像，白名单JAR在/opt/dataagent/app，生产conf与data/logs外置；image-check不启动业务，拒绝私有prod/dev profile。兼容经典Docker builder，不依赖COPY --chmod。
- pom新增frontend.npm.install.arguments可覆盖属性，默认install不变，仅源码发布使用ci；正式Java/前端无改动。
- src/test/python/test_source_build.py：10个Linux合成Git保护用例+4个真实应用镜像契约用例，外层5测试全部通过。

## 本轮真实验证
- 测试根output/source-build-20261029（Git忽略）；冻结当前6262文件约108MB，在独立Linux/var/lib/dataagent-source-build-20261029/source新建测试Git提交，不触碰原仓库HEAD。
- 初次空Maven/npm缓存：源码框架安装成功，应用Java946/0失败/0错误/28跳过，前端48通过并构建；未借开发机SNAPSHOT。
- 初次封装经典builder不支持COPY --chmod，已修为COPY+RUN chmod；保留失败日志。修正后全流程再次成功，测试镜像dataagent/app:1b22b798a58d-20261029091351-MCFKCY（7bf671aa9288），断网/只读根静态预检通过。
- 测试标签与提交仅隔离副本，不是正式生产版本。builder index摘要f58d59b6…、本地JDK17.0.15/Maven3.9.9；runtime仍17.0.21/Python/Wren。
- [构建报告](../test-reports/20261029-source-build-validation.md)、[66指引](../guides/66-source-build.md)已更新；guard测试包含更新失败3次且HEAD不变、快进成功、编译失败不进镜像/部署、缺配置/非法端口/私有profile拒绝。
- 未启停业务服务，测试容器正常自动退出，镜像/作业文件保留；本地原18766/18860及其他Docker项目保留。

## 下一步
1. 用户在66拉Maven镜像后执行构建工具基础检查，确认旧内核JVM/javac/Maven可运行；用透明脚本创建并自动移除本轮检查容器，遵守此前运维要求。
2. 用户已明确授权本地一次提交所有当前项目需要的改动，由用户自己push。已审阅为构建/环境、数据库SQL、文档和验证配套；两份重复中途检查点移至Git忽略output/precommit-cleanup-20261029，私有交接/日志/实验JAR/真实人员SQL不入提交。66旧b82没有新入口，等用户push再更新。
3. 66更新目标分支，check-builder→build-from-source明确HEAD，回传本次日志/镜像标识，构建失败不碰旧服务。
4. 在取得生产两库后，继续实现/验证Compose启动、健康验收/回退、持久目录和生产配置；新镜像不能套旧tar的runtime-entrypoint。
5. 正式并发修复单独落地并回归；本次只构建测试不等于10–15并发、66业务或上线验收。

## 本次提交收口
- 更新主入口为66-source-build，旧安装/tar文档注明备选，复用已验环境，不删除有用的历史安装材料。固定摘要拉Maven避免标签漂移。
- 本次提交父版本为b82bb7f4；提交号以git log为准。用户自行push后按指引更新源码→check-builder→build-from-source，不直接启动服务。
