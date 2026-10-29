# 2026-10-29 源码构建阶段验证

状态：只构建阶段本地验证通过，未提交推送；66新builder、源码编译、业务启动和回退未验。

## 实现范围

update-source.sh兼容旧Git的cd方式，三次fetch/单次90秒/退避，仅快进并拒绝错误分支及本地修改。build-from-source.sh要求明确干净提交与已提交控制文件，从git archive归档源和控制文件；编译容器2CPU/6GiB、最长3600秒，独立Maven/npm缓存，不挂宿主Maven仓库、生产数据或Docker socket，不启停服务，失败不进入镜像阶段。

框架两个BOM及harness/所有直接使用扩展与其core等传递模块从同提交安装（框架测试跳过）；应用verify包含Java与前端检查，使用固定Node/npm及ci。pom仅新增前端安装参数可覆盖属性，原默认install不变；正式Java/前端未改。

应用镜像仅COPY白名单JAR/requirements/预检文件，JAR在/opt/dataagent/app，配置和data/logs外置。入口支持image-check/check/run；image-check断网检查依赖和JAR，拒绝私有prod/dev profile，不连接生产DB或模型、不启动业务。

## 真实构建证据

输入为当前工作区6262文件约108MB冻结副本，复制到独立Linux目录/var/lib/dataagent-source-build-20261029，并仅在副本创建测试Git提交；原仓库HEAD、分支不变。测试提交和镜像不用于生产。

构建镜像为官方Maven3.9.9/Temurin17，DaoCloud index摘要f58d59b6273e785ac0a4477f6e9b5ba1d7731c75b906c0f7b34076f1851318cc，amd64 Id d0841781763b62e537284c5b6e5efe3f3b69b03a65b64d46a5dec054d9a35279，本地实测JDK17.0.15；运行基镜像仍为原Java17.0.21/Python/Wren。

- 第一次空项目Maven/npm缓存：依赖模块安装成功，应用Java946项/0失败/0错误/28条件跳过，前端48项通过并构建完成；不借开发机SNAPSHOT或~/.m2。
- 首次镜像封装发现经典builder不支持COPY --chmod，已改COPY后RUN chmod；不修改宿主全局BuildKit。失败未操作业务服务，原始证据保留。
- 修正后完整流程再次通过，生成测试镜像dataagent/app:1b22b798a58d-20261029091351-MCFKCY（Id前缀7bf671aa9288）；断网/只读根/1GiB的Java/Wren/MCP/MySQL/ONNX/LanceDB及应用JAR预检通过，无生产挂载。
- 10个Linux合成Git仓库保护用例：脏或未跟踪文件、未提交控制文件、错误提交/分支、缺runtime、编译失败不进入镜像或部署、三次拉取上限、正常快进；另4个真实应用镜像契约检查：断网预检、缺外置配置、非法端口、夹带私有profile拒绝。外层5项Docker测试全通过。

证据在Git忽略output/source-build-20261029：输入清单、actual-build.log、actual-build-final.log、final-test.log；Linuxjobs保存源码归档、日志、JAR摘要与镜像信息。没有全局prune或业务启停。

## 限制和下一步

66旧内核builder基础检查/依赖下载/源码构建待现场；文件尚未提交推送，不能调用旧GitHub提交中不存在的新脚本。Compose更新、业务健康/回退及生产DB/BI未完成。此前正式Java并发缺陷仍未修，async2只在旧隔离实验中，本次构建通过不等于并发或上线验收通过。
