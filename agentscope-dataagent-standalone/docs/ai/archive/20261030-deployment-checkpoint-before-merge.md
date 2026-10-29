# 当前任务：66应用已启动，待65入口/BI/管理员与模型验收（2026-10-30 Asia/Shanghai）

## 目标与授权边界
- 用户选择GitHub源码交付，66拉源码后在构建容器编译/封装应用镜像。用户已反馈源码拉取完成；66命令由用户执行回传，助手未连接66。
- 不改宿主Java8/glibc、不装全局Maven/Node、不动既有Dify，不全局prune。提交/push仍由用户决定；正式数据库未取得，用户已确认先用独立MySQL演练完成服务启动，不把演练称为正式上线。
- 本地分支codex/integrate-boundary-lyf-v2，用户已自行提交47e431df（MySQL脚本和属性修复），本轮从干净工作区开始，无自动提交/push。
- 上一A/B测试见[归档](../archive/20261029-container-ab-complete.md)和[报告](../../test-reports/20261029-container-runtime-ab-regression.md)。正式Java并发阻塞仍未修，async2只在旧output隔离实验，不在本次源码改动中。

## 66已核实
- 用户截图：Git1.8.3.1、Docker客户端/daemon26.1.4、Compose2.27.1，runtime/sandbox两镜像在；GitHub HTTPS200且ls-remote目标分支b82bb7f4可读，默认HEAD不同。
- 最新截图确认clone为9d378d8b，固定摘要Maven镜像的JDK17.0.15/javac/Maven3.9.9在66旧内核上基础检查通过。旧Git用cd，不升级宿主。
- 2026-10-29最新现场截图：Java946/0失败/0错误/28跳过，Maven BUILD SUCCESS（3:06），应用镜像已封装并完成Java17.0.21/Wren及包内容静态预检；未启动业务。镜像引用及完整SHA以66本次jobs/*/build-result.txt为准。
- 用户最新文字回传66“演练库核对通过”：37表/两账号验证完成；无MySQL宿主端口。应用尚未启动，助手未连接66。

## 已交付构建能力（9d378d8b）及本轮应用控制
- [spec099](../../specs/099-source-build-deployment.md)、[ADR0118](../../adr/0118-source-build-on-deployment-host.md)，只构建阶段完成，完整部署未完成。
- docker/update-source.sh：指定分支/干净工作区，3次fetch/单次90秒/退避，仅快进，不reset/clean/切分支/启停服务。
- docker/check-builder.sh：无生产挂载的断网512MiB临时容器，检查Java/javac/Maven；本地脚本实际运行通过。
- docker/build-from-source.sh：要求干净明确HEAD及已提交控制文件，git archive固定源和构建控制文件；默认~/dataagent-build专属jobs/cache与flock，2CPU/6GiB/3600秒，无宿主Maven/生产data/socket挂载到编译容器。
- docker/build-in-container.sh和build-maven-settings.xml：两个BOM/harness/所需扩展及core从源码安装（框架测试跳过），应用verify/前端ci，固定Node20.19.2/npm10.9.2，公共依赖用项目范围国内源。
- docker/application.Dockerfile/application-entrypoint.sh：基于原runtime镜像，白名单JAR在/opt/dataagent/app，生产conf与data/logs外置；image-check不启动业务，拒绝私有prod/dev profile。兼容经典Docker builder，不依赖COPY --chmod。
- pom新增frontend.npm.install.arguments可覆盖属性，默认install不变，仅源码发布使用ci；正式Java/前端无改动。
- src/test/python/test_source_build.py：10个Linux合成Git保护用例+4个真实应用镜像契约用例，外层5测试全部通过。
- 新增rehearsal-app.sh/compose.rehearsal-app.yml：独立rehearsal-app目录/项目、外置密钥、当前UID/socket组、只读离线缓存及同绝对路径；check/start/stop/logs/status/upgrade/rollback，候选检查失败不动旧服务，启动失败恢复原镜像；已随用户122d2b7e提交。
- 修正后续首次MySQL初始化：02-users.sh可执行子进程，防止set -u污染官方入口；健康改TCP，不误认临时socket。66已有成功库不清理/重放/重建，原私有Compose不会被prepare覆盖。

## 本轮真实验证
- 测试根output/source-build-20261029（Git忽略）；冻结当前6262文件约108MB，在独立Linux/var/lib/dataagent-source-build-20261029/source新建测试Git提交，不触碰原仓库HEAD。
- 初次空Maven/npm缓存：源码框架安装成功，应用Java946/0失败/0错误/28跳过，前端48通过并构建；未借开发机SNAPSHOT。
- 初次封装经典builder不支持COPY --chmod，已修为COPY+RUN chmod；保留失败日志。修正后全流程再次成功，测试镜像dataagent/app:1b22b798a58d-20261029091351-MCFKCY（7bf671aa9288），断网/只读根静态预检通过。
- 测试标签与提交仅隔离副本，不是正式生产版本。builder index摘要f58d59b6…、本地JDK17.0.15/Maven3.9.9；runtime仍17.0.21/Python/Wren。
- [构建报告](../../test-reports/20261029-source-build-validation.md)、[66指引](../../guides/66-source-build.md)已更新；guard测试包含更新失败3次且HEAD不变、快进成功、编译失败不进镜像/部署、缺配置/非法端口/私有profile拒绝。
- 未启停业务服务，测试容器正常自动退出，镜像/作业文件保留；本地原18766/18860及其他Docker项目保留。
- 本轮真实应用集成在独立Linux根/var/lib/dataagent-app-test-9dd088fb，UID1000/2CPU/6GiB，同MySQL8.0.46及先前构建应用镜像；197.469秒通过，覆盖37表validate启动、离线向量、兄弟沙箱、BI首登普通用户、JWT/账号/文件停启保留、预检失败不停旧服务、坏JVM候选恢复旧镜像、成功更新及显式回退。测试应用/库已停，目录/网络/镜像保留；未调真实LLM或65/BI浏览器。

## 下一步
1. 66源码构建和镜像静态预检已通过，不需重复构建；保留本次作业目录、镜像和日志。工作区修复的逐文件哈希输出未单独回传，不能把构建成功等同于已获得该项完整证据。
2. 应用控制已由用户随122d2b7e提交。最新66截图确认prepare/start、两库、离线向量(2,384)和兄弟沙箱读写通过，容器Healthy，18080健康UP；不需再次重启/构建，未调用模型。
3. 演练库/data/dataagent/rehearsal-db与dataagent66-rehearsal-db_default网络已在66成功核对，保留。应用将用/data/dataagent/rehearsal-app、18080/当前UID、默认~/dataagent-env/hf-cache-modelscope；prod关闭密码、开启BI账号入口，不自动创建管理员或复制模型Key。
4. 下一步用户登录65只读检查到66:18080健康连通、Nginx宿主/容器及目标配置；确定承接BI原origin的server后，再增量配置、nginx -t和reload。只剥/dify/dataagent/一次，保留SSE；不能以UP代替BI/管理员/模型/问答验收。
5. 正式并发修复单独落地并回归；本次只构建测试不等于10–15并发、66业务或上线验收。

## 历史源码构建提交收口
- 更新主入口为66-source-build，旧安装/tar文档注明备选，复用已验环境，不删除有用的历史安装材料。固定摘要拉Maven避免标签漂移。
- 本次提交父版本为b82bb7f4；提交号以git log为准。用户自行push后按指引更新源码→check-builder→build-from-source，不直接启动服务。

## 66现场旧Git二进制换行判定（构建阻塞已解除）
- 用户截图确认66已成功clone 9d378d8b，固定builder镜像拉取及Java17.0.15/javac/Maven3.9.9基础检查在内核3.10上通过。实际构建尚未进入编译，被干净工作区检查拦截。
- git status显示字体、wav/mp4/pdf/pb等二进制资源被标修改；根.gitattributes仅自动text/eol与部分binary，遗漏这些类型。截图仍在分页器，原始哈希未完整回传，不能断言磁盘字节已被改。
- 根.gitattributes对应binary声明已随用户47e431df提交，git check-attr确认text/diff/merge unset，未改资源字节。早先66局部修复要求先逐一验证所有dirty文件原始哈希等于HEAD，无暂存/模式变化；真实差异不覆盖。
- 最新截图已越过工作区保护并完成镜像构建，原阻塞解除；66业务启动/数据库/离线模型运行及BI尚未验。MySQL配套本地已验，说明见66-source-build与构建报告；正式并发问题仍独立待修。

## 2026-10-30现场新增事实
- 本地HEAD122d2b7e，当前截图显示dataagent66-rehearsal-app-app-1 Healthy，curl10.194.2.66:18080/actuator/health返回groups及status UP；助手未操作65/66。历史未启动/未提交段落保留为当时记录，以本段及下一步为准。
- 65若仅承接其他Dify域名，还需BI入口到65的路由；浏览器必须保持BI原协议/域名/端口。仅借不同Dify域名不能共享Local Storage。本轮仅校准指引与状态，不要求为文档立即提交push。
- 65新截图：10.194.2.65到66:18080健康UP；账号UID1012/仅本组1011，Docker socket明确permission denied且用户不知道sudo密码。两条Nginx master命令均为nginx -g daemon off（9338/9992），不能据此确认容器/挂载。/data/dify、dify_1.2.0、dify_0.15.3及其docker-legacy下存在候选Compose/Nginx文件；先只读核对路径别名及listen/server_name/location，运行时挂载和nginx -t/reload需运维有权限执行，不尝试绕过。

- 最新候选文件头/路由回传：/data/dify、dify_1.2.0、dify_0.15.3均为独立目录，读取到的各default.conf含Dify路由但无旧/dify/dataAnalyse/；原grep受路径/15秒/隐藏错误限制，不能证明全机无此路由。不要任选一份改。已在忽略output/nginx-65-20261030准备增量location及运维说明，本地隔离nginx -t通过，现网未改；下一步核对/data/nginx路径可见性或由运维指出BI原origin生效配置及实际前缀。

- 65最新ls明确/data/nginx、/data/nginx/conf、/etc/nginx均ENOENT（换到/也相同），不是这些路径存在但当前账号读不到；绝对路径不受cwd影响。不能因此断言Nginx不存在或旧截图必属其他服务器，旧alias只是静态目录。停止猜宿主路径；请有权限运维核对实际运行Nginx加载配置和挂载来源，候选增量文件已备好，现网未改。

- 用户询问是否只差入口及如何验证：需明确演练服务已启动，但还要管理员/模型和业务验收，正式两库仍等待。已在忽略output/service-smoke-20261030准备run-on-66.sh与check-service.py，验证健康/options、普通测试账号首登、me/知识库列表、匿名401/普通用户模型管理403；令牌仅内存不输出。已用独立本地fixture实际通过，原停机状态恢复，仅该测试库新增/复用deployment_probe_20261030，未操作66。脚本须在66执行，不在无Docker权限的65。

- 用户新聊天截图线索：同事明确称入口在65 Docker的ng里，并要求参考“数据资产”路由；不是要求必须找到旧dataAnalyse。已有进程证明Nginx存在。先暂停后端smoke，读取/data/dify/docker/nginx/conf.d/default.conf中已看到的/dify/lineage段及对应Compose nginx/ng服务挂载来核对候选，不能直接认定lineage就是数据资产或文件已生效；Docker权限不足仍只读，不猜路径/不绕过。

- 最新截图明确/data/dify/docker/docker-compose.yaml的nginx服务（image nginx:latest）声明./nginx/conf.d:/etc/nginx/conf.d，候选宿主文件对应容器/etc/nginx/conf.d/default.conf；lineage后端172.17.0.1:8006，前端3100，与同事数据资产线索吻合。此为Compose声明非运行时inspect，不能把服务名nginx当容器名或假称已生效。下一步可检查宿主文件写权限，准备同server并列location；实际挂载复核、nginx -t/reload仍需有Docker权限的人，不包含现有proxy.conf以避免同级指令重复。

- 用户已确认65账号对/data/dify/docker/nginx/conf.d/default.conf无写权限，加上Docker socket拒绝与sudo密码未知，当前账号不能改动/加载Nginx。路径/Compose映射已明确，实际容器挂载仍需有权限者核对。既有output/nginx-65-20261030运维说明已更新，可按规范授权或代执行备份/插入location/配置检查/reload；不再让当前账号反复试写/exec。等待期间可回66执行已备后端smoke，尚未收到其66实测输出。
