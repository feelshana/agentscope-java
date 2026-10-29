# 当前任务：66从GitHub源码构建应用镜像（2026-10-29 Asia/Shanghai）

## 目标与授权边界
- 用户选择GitHub源码交付，66拉源码后在构建容器编译/封装应用镜像。用户已反馈源码拉取完成；66命令由用户执行回传，助手未连接66。
- 不改宿主Java8/glibc、不装全局Maven/Node、不动既有Dify，不全局prune，用户已授权一次本地提交，明确由用户自己push，助手不得push。正式数据库未取得，当前只构建，不启动生产业务。
- 本次整理以codex/integrate-boundary-lyf-v2@b82bb7f48220889436e0efd49d515651f900d717为父版本；有效运维/SQL/文档修改归入同一次提交，最终HEAD以git log为准。
- 上一A/B测试见[归档](archive/20261029-container-ab-complete.md)和[报告](../test-reports/20261029-container-runtime-ab-regression.md)。正式Java并发阻塞仍未修，async2只在旧output隔离实验，不在本次源码改动中。

## 66已核实
- 用户截图：Git1.8.3.1、Docker客户端/daemon26.1.4、Compose2.27.1，runtime/sandbox两镜像在；GitHub HTTPS200且ls-remote目标分支b82bb7f4可读，默认HEAD不同。
- 最新截图确认clone为9d378d8b，固定摘要Maven镜像的JDK17.0.15/javac/Maven3.9.9在66旧内核上基础检查通过。旧Git用cd，不升级宿主。
- 2026-10-29最新现场截图：Java946/0失败/0错误/28跳过，Maven BUILD SUCCESS（3:06），应用镜像已封装并完成Java17.0.21/Wren及包内容静态预检；未启动业务。镜像引用及完整SHA以66本次jobs/*/build-result.txt为准。

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
1. 66源码构建和镜像静态预检已通过，不需重复构建；保留本次作业目录、镜像和日志。工作区修复的逐文件哈希输出未单独回传，不能把构建成功等同于已获得该项完整证据。
2. 本地9d378d8b由用户push且66已取得；此后.gitattributes二进制属性修复及共享记录仍未提交，助手不自动提交/推送。
3. 66截图确认MySQL8.0镜像6cd09145…/来源index7dcddc01…已拉取。新增docker/rehearsal-mysql.sh及真实集成测试在本地通过：37表、分库账号、Docker DNS/MySQLdb、重复不改密码、停启数据保留、不映射宿主端口、拒绝未标记目录。待用户提交push，再66 update-source→prepare→start，不需重建原应用镜像；生产两库继续等待。
4. 应用Compose启动、配置生成、健康验收/回退和持久目录/离线缓存挂载尚未实现/验证；65同源Nginx/真实BI后续联调。新镜像不能套旧tar入口。演练库根/data/dataagent/rehearsal-db、项目dataagent66-rehearsal-db，不复用Dify。
5. 正式并发修复单独落地并回归；本次只构建测试不等于10–15并发、66业务或上线验收。

## 本次提交收口
- 更新主入口为66-source-build，旧安装/tar文档注明备选，复用已验环境，不删除有用的历史安装材料。固定摘要拉Maven避免标签漂移。
- 本次提交父版本为b82bb7f4；提交号以git log为准。用户自行push后按指引更新源码→check-builder→build-from-source，不直接启动服务。

## 66现场旧Git二进制换行判定（构建阻塞已解除）
- 用户截图确认66已成功clone 9d378d8b，固定builder镜像拉取及Java17.0.15/javac/Maven3.9.9基础检查在内核3.10上通过。实际构建尚未进入编译，被干净工作区检查拦截。
- git status显示字体、wav/mp4/pdf/pb等二进制资源被标修改；根.gitattributes仅自动text/eol与部分binary，遗漏这些类型。截图仍在分页器，原始哈希未完整回传，不能断言磁盘字节已被改。
- 本地已补根.gitattributes对应binary声明（未提交/未push），git check-attr确认text/diff/merge unset，未改资源字节。给66的局部修复必须先逐一验证所有dirty文件原始哈希等于HEAD、无暂存/模式变化，再仅追加.git/info/attributes；有真实差异则停止。
- 最新截图已越过工作区保护并完成镜像构建，原阻塞解除；66业务启动/数据库/离线模型运行及BI尚未验。MySQL配套本地已验，说明见66-source-build与构建报告；正式并发问题仍独立待修。
