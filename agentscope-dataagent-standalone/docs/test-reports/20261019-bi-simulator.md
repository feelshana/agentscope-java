# 2026-10-19 模拟BI免登与云端部署验收

## 当前结论（2026-10-20整理）

模拟工作台→同域iframe→真实prod免登及云端前后端已走通，后续红海文件上传、建模保存/发布及问数证据分别见[上传报告](20261019-honghai-upload.md)和[建模耗时报告](20261019-cloud-modeling-latency.md)。云端MJS的MIME、MySQL开发依赖、驱动预检、日志目录和凭据不一致问题已逐步处理；本报告下面保留各阶段失败及当时“待执行”的原始记录，不将其重复当作当前阻断。

本地既有构建/测试、用户回传和云端实测分开记录；真实BI、正式HTTPS、目标机容量、升级回退和备份恢复仍未完成。本轮整理没有重新执行这些测试或部署；旧制品哈希仅对应当时构建，不代表本次提交已重新打包。

## 2026-10-19｜云端模块脚本MIME阻断

用户浏览器工作台静态样式正常但账号选择空、入口禁用。经实际SSH转发localhost18880读取：HTML为text/html，两份mjs均200但Content-Type为application/octet-stream。模块加载在boot前被浏览器拒绝，配置错误提示代码也不会执行；不能将页面200视作入口通过。原因是配套Nginx片段依赖系统默认MIME表，未显式支持mjs。

已仅在模拟入口静态location补HTML/CSS/JSON/JS/MJS完整MIME表，避免只增加mjs却丢失继承的其他类型；不改后端或免登协议。新增静态映射契约，Node8/8通过，本地support归档及校验更新；此测试不等于Linux实际Nginx或云浏览器修正后通过。下一步由用户在服务器幂等补该片段、nginx -t/reload，强刷浏览器后验证选用户及免登。未自动改云端、提交或重启应用。

## 2026-10-19｜云端Nginx入口通过

用户回传独立演练server的/bi-sim与/dataagent均200，反代auth/options保持密码关闭/BI开启。说明静态及API转发已通过用户验证；不等于浏览器加密免登或业务问数通过。下一步通过既有SSH key建立本机18880到云loopback18881转发，用localhost安全上下文体验真实浏览器入口。尚未创建云端管理员或执行完整业务验收；未自动连接服务器、提交或推送。

## 2026-10-19｜云端健康与认证选项通过

用户回传独立curl：actuator/health为UP，auth/options为passwordLoginEnabled=false、biLoginEnabled=true。Nginx仅sites-enabled/default软链且conf.d为空。下一步给用户配置独立演练server监听127.0.0.1:18881，静态/bi-sim和反代/dataagent沿用support片段，SSH localhost18880转发目标改为18881；不修改现有default站点或公开新端口。静态安装、Nginx配置/reload、SSH和浏览器仍待用户执行，本条不计作已部署；未自动提交。

## 2026-10-19｜云端应用首次就绪

用户已同步logs目录修正，随后JDBC与mysql CLI均报1045 Access denied，排除仅应用方言错误。用户确认此前填入密码与预期不一致；密码对齐后回传启动脚本Wren预检通过及应用已就绪PID302435，日志位于版本logs/。这是用户回传的真实云端就绪结果；尚待独立健康/auth配置检查、Nginx/SSH同源入口、浏览器免登与建模问数验证。不记录任何密码或将启动成功当作业务全流程通过；本阶段未新增业务代码、测试或提交。

## 2026-10-19｜云端启动日志目录阻断及修正

用户已安装私有prod配置并填写数据库密码。首次启动通过Wren预检，但Logback无法创建logs/LLM.log及logs/LLM-modeling.log。原因是dataagent.sh只创建并授权log/，而Logback固定写logs/；root拥有的版本目录不允许运行账号自行创建另一个目录。按用户确认统一为logs/：启动脚本的目录准备、launcher重定向、dataagent.log-directory及提示全部同步，保持两份LLM日志独立，不更改运行账号或放宽版本目录权限。

Python定向测试5/5通过（预检3项、日志契约2项）。隔离Linux容器真实复现旧脚本失败，修正后root切换dataagent和dataagent直接启动两条路径均通过启停，三份日志归dataagent；探针使用假Java/Wren/健康适配器，不代替真实Spring Boot云端启动。证据位于.runtime/cloud-deploy-20261019/launcher-logging-probe.log。服务器仅需幂等修正已解压启动脚本再启动，不覆盖已填prod密码，保留旧log/失败日志。云端修正、健康检查、Nginx/BI及业务验收待用户执行；未重跑全量Java或自动提交。

## 2026-10-19｜云端沙箱检查通过，prod配置已准备

用户反馈“沙箱依赖和setsid检查通过”，离线镜像导入阶段完成；未代表应用业务或10人容量通过。output/cloud-deploy-20261019新增私有application-prod.cloud.yml及bi-simulator.cloud.json，Git忽略已确认。模型参数复制既有本地已验证私有配置，密钥不打印/提交；新JWT secret及32字节Base64 BI key独立生成并持久，两文件共享BI key。数据源为dataagent_app@127.0.0.1:3306的dataagent_platform/data_agent，密码两处占位等待用户服务器getpass输入；非空/JSON-YAML特殊字符转义已本地校验。server监听127.0.0.1:8080，JPA validate/init never、prod无密码/无演示用户，沙箱tag为已导入镜像。

模拟入口预设liyufanwx（李雨繁）、demo_owner/demo_reader/demo_guest，角色仍由后台库决定，尚未设置管理员。计划用SSH localhost18880→云Nginx80访问同源/bi-sim与/dataagent，故CORS准备localhost/127.0.0.1:18880；实际Nginx/SSH步骤尚未执行。不将该本地转发当作真实BI/正式HTTPS验收。

下一步用户本地PowerShell上传这两文件，Xshell将prod安装到/data/app/dataagent/dataagent-2.0.3-SNAPSHOT/conf/application-prod.yml（root:dataagent/0640），用系统Python/getpass填数据库密码；export DATAAGENT_WREN_EXECUTABLE/PYTHON后从版本目录执行bin/dataagent.sh start，再curl /actuator/health与/api/auth/options。应用运行目录由现有root启动分支创建并交给dataagent；不手工chown旧资产，不重复建表。云端实际配置填写和启动仍待反馈；没有再构建/测试业务代码或自动提交。私有准备证据.runtime/cloud-deploy-20261019/cloud-config-prepared.json。


## 2026-10-19｜Docker Hub连接超时，沙箱改为离线导入

云端docker build在FROM python:3.12-slim读取DockerHub manifest阶段超时，尚未执行apt/pip构建；不按应用/依赖安装故障处理，不改daemon镜像源或重启Docker。已复用本地既有已验收镜像agentscope/dataagent-sandbox:latest，确认linux/amd64，断网512MiB限制容器中实际导入pandas3.0.5/numpy2.5.3/scipy1.18.1/matplotlib3.11.2、Agg后端、setsid及4个CJK字体文件。没有注入业务配置或挂载宿主数据。

imageId=sha256:6b96c40d12a2c8f4241481694fb0be25cf34fb6361d1ce9c8603f0a66c413ec2。已docker save并gzip导出上层output/cloud-deploy-20261019/dataagent-sandbox-linux-amd64.tar.gz，242247512字节；SHA256=0514ee62108c1e02a4e9265bd58c8c28b5c92121d08fe1634694f3cb6c34d4c1。归档manifest/config digest、OS架构已核对，本地docker load往返确认同一imageId；同目录SHA256SUMS更新。原始证据.runtime/cloud-deploy-20261019/sandbox-export.json。复用镜像不是此次云端从浮动Dockerfile重新构建的结果，具体版本如上。

下一步用户本地PowerShell用原SSH key上传该镜像包到/root；Xshell docker load -i后以dataagent账号运行--rm/--network none分析库+setsid检查。云端导入/容器运行结果尚待反馈，后端和真实BI尚未启动/验收。Wren预检、35表及授权此前已通过。未自动上传/提交或修改业务代码。


## 2026-10-19｜云端Wren预检已通过，准备沙箱镜像

用户最新截图确认以dataagent账号执行已修正的check-wren.py后输出Wren CLI/MCP/MySQL/ONNX-LanceDB依赖检查通过。编译系统包缺失和误查pymysql两项阻断在此云端步骤已闭环；这不代表模型缓存、业务查询或应用启动通过。

下一步指引使用已上传并解压的/root/cloud-support/docker/sandbox.Dockerfile构建agentscope/dataagent-sandbox:latest，随后runuser -u dataagent启动--rm/--network none容器，验证pandas/numpy/scipy/matplotlib导入及setsid存在。已核对support包Dockerfile与当前源码一致。此步尚待用户执行；不提前记录镜像已就绪，不修改Docker网络/镜像源，不停Doris或MySQL。通过后准备外置prod配置与应用启动，再同源BI入口和完整云端验收。未自动提交。


## 2026-10-19｜mysqlclient安装成功，发布预检驱动名称已修正

用户云端截图已出现Successfully built mysqlclient和Successfully installed wrenai0.15.0/wren-core-py0.8.0/mcp1.30.0，但check-wren报No module named pymysql。核对本机同版官方wrenai元数据与connector/mysql.py确认真实连接器依赖mysqlclient、导入MySQLdb；原预检pymysql是错误要求。

源码src/main/bin/check-wren.py仅将导入项pymysql改为MySQLdb；新增src/test/python/test_check_wren.py三项定向回归，覆盖真实模块名、缺MySQLdb仍失败、固定版本仍校验，3/3通过。首轮测试夹具误用源码布局导致3项找不到requirements文件，修正为临时发布目录后全过；不是放宽生产脚本。另用实际已安装Wren/原生驱动及CLI、屏蔽PyMySQL导入验证依赖预检通过，没有连接云数据库。

mvn assembly:single成功，仅重建分发tar、JAR保持aa05b6d1a52386a4300110a000b4c644e6f5f2f2ba1a3bd3f772196ef6a07640及/dataagent/前缀不变；输出目录原tar更新SHA256为76b1120fe0171ff6f68626dcfb02e58f5b03e5124518655f34ec449cae619bd7，SHA256SUMS同步。服务器此前上传的是旧tar；给用户一条sed命令等效修正已解压bin/check-wren.py，随后原账号重新预检，无需重装/重传150MB包。云端修改及通过结果仍待用户执行反馈。部署说明/本报告/共享记忆同步，未自动提交，未重跑全量Java/云端业务。


## 2026-10-19｜云端Wren安装阻断：mysqlclient编译依赖

用户在既有venv安装requirements-wren.txt时，mysqlclient2.3.0源码构建报pkg-config not found（exit127），尚未完成Wren安装。已核对mysqlclient官方Debian/Ubuntu安装要求，前一步指引遗漏系统开发依赖；补充root安装python3-dev/default-libmysqlclient-dev/build-essential/pkg-config，再原dataagent账号重试pip及包内依赖预检。不删venv、不改Wren固定版本、不手工绕过驱动。single-host-release已补安装/排障说明；云端实际恢复结果待用户反馈，本轮未运行安装/测试、未改业务代码或重建发布包。


## 2026-10-19｜用户已完成云端建表

用户最新Xshell截图确认bootstrap-platform.sql导入无报错：dataagent_platform为35表、data_agent为0表；dataagent_app@localhost对两个库均ALL PRIVILEGES（仅库级，USAGE全局）。此前截图确认运行账号dataagent属docker组、暂无镜像。发布材料已由用户上传，尚无解压/依赖安装/应用启动证据。

下一步指引：Xshell解压/root/dataagent-2.0.3-SNAPSHOT.tar.gz到/data/app/dataagent，配套cloud-support解压到/root；安装python3-venv，由dataagent用户在/data/app/dataagent/wren-venv建venv并按发布包requirements-wren.txt安装，调用包内bin/check-wren.py dependencies检查。Wren通过后再构建sandbox.Dockerfile镜像、准备外置prod配置、Nginx/SSH同源入口及实际云端验收。当前只是指引，不能提前记作云端依赖/服务已就绪。无需重复执行增量ALTER或建表。


## 2026-10-19｜空库初始化材料完成，待用户上传/导入

用户最新截图确认dataagent_platform和data_agent均0表；dataagent_app仅localhost；dataagent用户已属docker组；Docker镜像列表为空。目录与Doris保持原状，云端尚未导入/启动应用。

已在上层output/cloud-deploy-20261019生成bootstrap-platform.sql：当前JAR在本地独立空MySQL8.0.41库创建结构，再仅导出35张表DDL，导入第二个新空库后，实际同一JAR以prod/ddl-auto=validate/sql-init=never启动及auth/options检查成功。SQL0业务行、无DROP/ALTER/INSERT，包含会话状态/免登/图表owner等当前列，因此该空库基线导入后不要再盲跑旧增量ALTER。data_agent上传物理表仍由实际上传创建。

生成探针首次因Windows Java launcher子进程未退出占端口而误读旧实例，该次证据作废；改独立18086端口并确认新启动日志及HTTP后通过。收尾脚本曾混用Popen/psutil清理接口，已修正并显式核对停止所有schema探针JVM；原本地18084/18766保留。两个新本地验证库保留，不含导出的用户数据。原始证据.runtime/cloud-deploy-20261019/schema-validation.json及schema-replay/startup-verified.log。

发布包SHA仍448041050ceafa6fbd92cda4e1619b4330508fec1ab8448cdc2f231b7deb657f；完整DDL SHA7710d04e5373a97f0a41276ead15375ae2d7dba901637952a06cd9115143894f。同输出目录另有cloud-support.tar.gz、三文件SHA256SUMS.txt。下一步让用户在本地PowerShell用原cloud-verify-ed25519密钥上传3文件到/root，Xshell导入bootstrap-platform.sql并回传平台35表/上传库0表及SHOW GRANTS结果，再准备Wren、沙箱、外置prod配置和启动。未自动上传/提交；不是新的全流程/并发验收。


## 后续：云端演练准备（11:38，Asia/Shanghai）

用户反馈云服务器环境：root、x86_64；总内存约14GiB/available11GiB、根盘约60GiB剩余；Java17、Python3.12.3、MySQL8.0.46，Docker/MySQL/Nginx active；Doris FE/BE保留运行，8080空闲。平台库dataagent_platform、上传库data_agent存在，但表数量和应用账号授权尚待检查；不能仅凭有库就直接启动validate配置。/data/app已存在，/data/app/dataagent尚未存在，/opt/dataagent为旧准备目录；不移动/覆盖旧目录。

已重新以DATAAGENT_BASE_PATH=/dataagent/完整执行Maven package -DskipTests，含npm install、TypeScript、Vite，BUILD SUCCESS；本轮只构建不重跑732项测试。验证新JAR index包含/dataagent/assets/、包内无私有dev/prod配置、启动脚本LF；构建生成的源码static目录恢复构建前原字节，不覆盖已有源码变更。新产物在根output/cloud-deploy-20261019，tar SHA256 `448041050ceafa6fbd92cda4e1619b4330508fec1ab8448cdc2f231b7deb657f`，JAR SHA256 `aa05b6d1a52386a4300110a000b4c644e6f5f2f2ba1a3bd3f772196ef6a07640`。这是**后续子路径包**，不同于下面前一轮根路径包及其哈希。

同目录cloud-support.tar.gz按白名单收录模拟静态材料、无有效密钥的配置示例、Nginx片段、沙箱Dockerfile及4份增量迁移。没有私有配置和测试数据；增量迁移不能替代空平台库的完整建表，必须先确认表结构。尚未上传/执行任何云端部署。

专用本地后端已重启加载此新JAR，PID53776；本地代理18766保持PID19632、TTL1800，入口及真实prod auth/options检查正常。下一步请用户在Xshell检查两库表、dataagent_app账号Host、部署用户及已有沙箱镜像，再准备适用初始化SQL和Wren环境。


## 结论与范围

已完成独立模拟工作台图标→新页面→同源iframe→真实prod免登→聊天/授权库查询的本地链路。没有mock认证或绕过官方Wren查询。新增静态材料不随JAR自动公开；当前变更未提交、未推送、未部署。此前4次中文提交的最终基线是9183e77f。

本轮是免登入口和发布包验证，不称重新完成全部建模场景、准确性专项或目标服务器容量验收。旧测试数据、知识库与会话保留；新增4账号、1库及本轮会话也保留。

## 环境及真实验证

- Windows本机；prod实际exec.jar，专用端口18084；Node静态/流式代理18766；入口`http://localhost:18766/bi-sim/`，应用前缀`/dataagent/`。
- 平台MySQL复用隔离验收库dataagent_verify_20261017，上传数据在dataagent_data_verify_20261017；不重建/清空。私有配置、随机密钥/JWT和原始材料在根目录忽略区`.runtime/bi-simulator-20261019`。
- 前端实际TypeScript检查、Vite子路径构建通过，输出独立忽略目录；产品前端源码及已提交静态资源未改。当前tar内仍是原根路径静态资源，不能直接当`/dataagent/`云端包上传；云端部署前须设置DATAAGENT_BASE_PATH重新Maven打包。
- auth/options确认密码登录false、BI登录true；密码接口403；错误密钥、过期ticket、同ticket重放401。浏览器首次新用户自动建普通账号，无密码登录页，用户名/ticket/JWT未入URL。
- 四账号：bi_sim_admin_1019（SQL设置user,admin后重新免登）；bi_sim_owner_1019（建库者）；bi_sim_reader_1019（QUERY）；bi_sim_guest_1019（无库授权）。模拟label不会赋予权限。
- 新库“BI免登联调商品分析”，ID b356ca91-a1b7-4f17-898b-ee7fb79cb923。通过真实上传接口导入test_data/01-电商销售/商品.xlsx：100行7字段，基础模型自动发布v1。没有修改业务模型/官方技能/准确性规则。
- 创建者浏览器实际Wren run_sql执行COUNT(*)，回执和工具侧栏返回100，首轮37秒；共享用户实际COUNT(*)及去重商品数返回100/100，11秒。两次成功工具回执保存在owner/reader-success-history.json；不是仅依据回答文本判断成功。
- QUERY用户可选库并问数、不可管理（库管理详情和写操作403）；guest列表无该库，强传groupId时SSE HTTP200后返回error“知识库不存在或未授权”，没有成功查询工具。该流式错误不误记成HTTP403。
- 切换宿主页用户会替换iframe并重新认证，旧用户历史不展示。reader/guest/admin访问owner会话均404，owner200；全局admin不会因此获得他人私有聊天。
- 工作台选择guest打开第二标签页后，原admin标签页刷新仍保持admin。未过期刷新沿用凭据；数据库消耗nonce集合差异为0，没有额外免登/续期。
- 临时将专用实例TTL设20秒完成真实过期实验：auth/me先200后401；宿主页提示过期，刷新不申请新JWT，提示重新进入；点击重新进入成功。随后恢复1800秒并重启，正常账号重新进入成功。此实验不声称实际等待30分钟。

预期校验：带中点的库名被400名称校验拒绝，改用合规名称后创建成功；重复创建409；共享用户查询权限不包含管理详情权限。这些不作为新增缺陷。

## 实际JAR发现的阻断与修复

首次浏览器问数失败，错误为`No default McpJsonMapper implementation found`。真实JAR含mcp-json-jackson2及SPI文件，非缺依赖。隔离Boot启动探针确认：后台AppClassLoader第一次发现失败；主线程LaunchedClassLoader能发现Jackson映射器；主线程初始化后后台因全局缓存又能成功。因此普通类路径测试/启动预热会掩盖问题。

修复仅限WrenInstanceRegistry#createClient：创建及初始化框架MCP客户端期间采用应用ClassLoader，finally恢复原线程上下文类加载器。未修改官方MCP/Wren包、查询权限、编译器或业务模型。新增2个定向测试覆盖受限loader发现失败及异常恢复。新JAR冷启动、未预热映射器时，浏览器发起的真实查询成功100；随后共享用户查询成功。原失败会话f7fcf349-ce41-4b98-a788-ec6704f373ae保留，修复后创建者成功会话3adb1db8-5b0f-41c2-9105-033a78110f18，共享成功会话8d7a6798-48d6-4451-ab56-7dd34d0443d4。

## 自动化及产物

- Maven test：732项，707通过、25跳过、0失败/错误。新增2项通过；25包含此前23项及未在本轮启用的2个真实断连恢复条件测试，不能写成732全部通过。
- Maven package成功（本次跳过npm任务，实际子路径前端另行构建通过）。exec.jar及tar.gz成功；本轮不重跑历史10用户负载/MySQL-Doris断连恢复专项。
- Node测试7/7通过：配置、用户名和标签页身份、重复用户、静态/真实代理边界、401原样透传、路径限制、SSE首事件在上游结束前到达。
- SHA256：exec.jar `dd7124a36cc860de82076dc8ebcd4be55518b667dd78c18bd0492999da2a3eb4`；tar.gz `98cbafaf04d4d10fa0d08962da43f1069cb0f23c4540e2f1913e21c64b05207a`。这两个是本轮含未提交ClassLoader修复的产物，不能使用原基线哈希。
- JAR检查不含私有dev/prod配置或模拟配置，模拟工作台也不嵌入产品JAR。

## 截图与状态

截图在根目录output/playwright/bi-simulator-20261019：portal.jpg、owner-sql.jpg（真实SQL侧栏100）、reader-query.jpg、expired.jpg、entry-final.jpg。以实际浏览器默认宽度观察并修正宿主页账号栏折行；viewport请求390时观测工作台实际clientWidth691，故不把该次截图命名作为“390断点通过”证据，未完整验收所有手机尺寸。

收尾保留专用后端18084（PID 15748）、本地代理18766（PID 19632），供用户直接体验；TTL已恢复30分钟。MySQL/Docker及用户其他服务保持运行。旧数据未清理，源码test_data未改；未自动提交。

## 下一步

参照[模拟入口部署说明](../integration/bi-simulator.md)及既有single-host-release执行云端演练：子路径重新打包、平台库迁移/外置prod配置、Wren/Python/Docker依赖、Nginx同源HTTPS（或SSH localhost映射）、Linux启动升级回退。云端和实际BI尚未执行，65容量/长稳也未验收。前端持钥的既有风险接受不变。
