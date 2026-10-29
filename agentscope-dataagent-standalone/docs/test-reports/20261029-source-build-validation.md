# 2026-10-29 源码构建阶段验证

状态：源码构建已随9d378d8b提交；用户截图确认66 builder检查、Java946项0失败/0错误/28跳过及应用镜像封装/静态预检通过。MySQL配套/属性修复已由用户随47e431df提交，演练库核对通过；本轮应用控制、初始化修正及测试/文档尚未提交推送，66应用未启动。

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

66旧内核builder基础检查/依赖下载/源码构建已由用户截图确认通过；助手未远程执行。应用Compose更新、业务健康/回退及生产DB/BI未完成。此前正式Java并发缺陷仍未修，async2只在旧隔离实验中，本次构建通过不等于并发或上线验收通过。

## 临时MySQL8.0配套实测

用户已确认先使用独立Compose临时库验证部署。66截图仅证明镜像已拉取，尚未启动；同一镜像（Id 6cd09145362dfe6831b14545de3d5fd6cc75c37cfd6ef8561429c1fc73518b39，来源index 7dcddc01…）在本地Docker实测如下，不能替代66旧内核验收：

- rehearsal-mysql.sh仅为本项目生成Compose/首次两库DDL/私有随机密码，不发布宿主端口。prepare断网先验mysql版本，start等待37表健康并验证两账号。没有删除命令、全局清理或Dify操作。
- 真实MySQL首次启动成功，平台37表/0用户，上传库空库。平台账号拒绝CREATE，上传账号能建立ds_测试表但拒绝读平台用户；runtime镜像中的MySQLdb通过Docker DNS mysql成功连接，覆盖Wren使用的驱动与网路。
- 重复prepare前后私有env哈希相同；自建测试数据写入后停止/再start仍为123，原表和密码保留；已有未标记目录拒绝覆盖；检查HostConfig.PortBindings为空。
- 真实集成外层1项用例两轮通过，最后一轮30.334秒；首轮34.622秒。每轮内部覆盖上述操作，本地Compose插件2.29.1，66实际为2.27.1待回传。没有称为两轮业务全流程通过。
- 证据output/mysql-rehearsal-20261029/test.log与test-final.log；最后项目dataagent-mysql-test-081a74ca和/var/lib/dataagent-mysql-test-081a74ca，测试库容器已停止，数据/配置/网络保留。未操作其他MySQL项目、正式平台库或66。

运行条件测试：DATAAGENT_MYSQL_REHEARSAL_TESTS=1，指定已下载MySQL镜像和Linux Compose插件路径后运行src/test/python/test_rehearsal_mysql.py；默认跳过真实Docker用例。此次不改Java/前端，不要求因部署脚本重建应用镜像。应用首次启动、真实问答/BI、并发和回退未验。

收口验证：Bash语法、Spotless、DocsConsistencyTest通过；Python默认34项，17通过/17条件跳过（新MySQL真实用例已单独显式启用通过）。未重跑Java946或前端构建，因本批无Java/前端/POM变更；66截图946属于前一提交的现场构建结果。

## 2026-10-30：真实应用Compose、故障恢复与初始化修正

用户文字回传66演练库核对通过，应用未启动。源码本地HEAD为用户已提交的47e431df；本轮新增应用控制/模板与测试，Java/前端/POM及application-entrypoint未变，不需因控制脚本重建66已有镜像。

本地使用既有源码构建应用镜像dataagent/app:1b22b798a58d-20261029091351-MCFKCY（不是66现场镜像）、相同MySQL8.0.46镜像，独立根/var/lib/dataagent-app-test-9dd088fb、UID1000/实际socket组、2CPU/6GiB运行，离线模型从既有缓存复制到本轮专属目录。最终真实集成外层1项197.469秒通过：

- 平台37表和DML账号下prod validate/never成功启动，未用root业务连接或自动建表；普通UID能运行Java/Wren，主应用只读根+同路径持久挂载及只读缓存。
- 应用预检实际两库连接、离线向量(2,384)、宿主Docker及断网只读根兄弟沙箱读写通过；不调用真实大模型。
- 实际POST /api/auth/bi-account-login首次创建user；停启后同一userId、旧JWT有效、附件文件及runtime.env哈希保留，密码入口关闭。
- 不存在候选镜像被拒绝且旧容器ID不变；故意注入坏JAVA_OPTS的候选镜像通过静态预检但JVM启动失败，控制器返回失败并恢复原镜像/健康；另一个有效候选升级后显式rollback，image ID回到原值。
- 自建应用和MySQL最终均停止，数据/配置/网络/试验镜像保留；没有操作既有Dify、其他MySQL或66。证据output/app-rehearsal-20261029/test-3.log与test-final.log，前者首轮基础启动91.484秒通过，后者增加更新/恢复覆盖。

联测保留两次失败：首次非root准备路径下，官方MySQL入口source 02-users.sh后受到set -u影响，报MYSQL_ONETIME_PASSWORD未定义且重启；早期socket健康可能过早通过。已把生成脚本设可执行，子进程不污染官方入口，健康改TCP127.0.0.1排除临时socket进程。其次Compose run不支持--pull参数，已移除并由模板pull_policy=never约束；本地插件2.29.1。日志test.log/test-2.log保留，不把此前只检查表/账号的通过当作初始化从未报错。新增MySQL回归断言要求官方init process done且无unbound variable。

修正只作用于之后新建的演练目录，prepare不覆盖现有66配置/密码/数据；用户已成功核对的库不重建、不重放DDL。实际66 Compose2.27.1/旧内核应用启动、65前缀/真实BI、管理员/模型/LLM及并发仍未本轮验收，不能从本地健康推断上线全流程通过。

收口：修正后的MySQL真实回归30.837秒通过（含init process done/no unbound variable断言），证据mysql-regression.log；Bash语法、Spotless、DocsConsistencyTest和Python默认35项（17通过、18条件跳过）通过；真实应用/MySQL两项已分别显式开启验证。不重复未改动Java/前端的946项或构建，不把默认跳过当真实通过。
