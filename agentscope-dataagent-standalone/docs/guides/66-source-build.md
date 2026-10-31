# 66拉取GitHub源码并构建镜像：分步指引

2026-10-31主线调整：后续从`develop-2.0`构建部署，个人分支先合入主线。66此前已部署启动并通过BI进入；下面早期准备状态属于历史记录，不要求重做。现有66源码目录的一次性分支切换及日常操作见[更新手册](66-update-operations.md)。助手此次只切换本机开发分支，未修改66运行环境。

2026-10-29现场进展：用户已将9d378d8b推送并在66克隆，固定Maven镜像基础检查通过；最新截图确认Java946项（0失败、0错误、28条件跳过）、Maven BUILD SUCCESS、应用镜像封装与静态预检通过。作业日志/镜像引用保留在66的~/dataagent-build/jobs下。此结果不代表数据库连接、模型缓存运行或业务启动通过，助手未远程执行。

2026-10-30用户回传66演练库核对通过；MySQL配套已由用户随47e431df提交。应用Compose与更新/恢复控制已在本地真实镜像验证，尚未提交推送或在66启动；生产数据库及65/BI/模型业务验收仍待完成。下文早期检查是可重现步骤，现场已经通过的构建无需重跑。

用户本轮截图已确认：Git1.8.3.1、Docker客户端/服务端26.1.4、Compose2.27.1；runtime/sandbox两镜像均存在。GitHub HTTPS200，实际git ls-remote读取目标分支成功，提交为b82bb7f48220889436e0efd49d515651f900d717。默认HEAD为另一提交，不以默认分支代替目标分支。此结果不代表clone、构建依赖或新应用镜像已验。

## 一、在66当前普通账号检查

逐条执行，不用sudo bash，不安装软件或启停服务：

```bash
whoami
command -v git curl timeout
git --version
sudo docker version
sudo docker compose version
sudo docker image inspect dataagent/runtime:20261028 --format '{{.Id}}'
sudo docker image inspect agentscope/dataagent-sandbox:20261028 --format '{{.Id}}'
curl -I --connect-timeout 10 --max-time 20 https://github.com
GIT_TERMINAL_PROMPT=0 timeout 30s git ls-remote https://github.com/feelshana/agentscope-java.git HEAD refs/heads/develop-2.0
```

最后一条返回提交哈希及分支才证明Git读取成功；HTTP成功不等于Git成功。私有仓库报认证错误时需配置只读凭据，不能关闭TLS验证。不要把Token放URL或聊天中。Compose缺失不阻碍本次网络/源码检查，后续再确定是否安装。

## 二、Git读取成功后，可隔离拉取现有分支

新目录与生产data隔离；若目录已存在，不覆盖，不reset/clean，不自动切换既有checkout：

```bash
mkdir -p "$HOME/dataagent-source"
if [ -e "$HOME/dataagent-source/agentscope-java" ]; then
  echo '目录已存在，先核对现有内容；本次不覆盖'
else
  timeout 180s git clone --depth 1 --single-branch --branch develop-2.0 \
    https://github.com/feelshana/agentscope-java.git \
    "$HOME/dataagent-source/agentscope-java"
fi
```

仅新克隆成功后核对：

```bash
cd "$HOME/dataagent-source/agentscope-java"
git status --short
git rev-parse HEAD
```

本地未提交的部署文件不会出现在GitHub；克隆成功不代表新构建能力已具备。当前分支远程跟踪快照为b82bb7f4，现场以实际Git结果为准。

为兼容66的Git1.8.3.1，使用cd进入目录，不使用git -C、git switch或git worktree。clone超时或失败先保留错误；不对已存在目录执行覆盖、reset或清理。后续自动重试应有单次超时、总次数和退避，并使用独立暂存目录，认证错误明确报告。

## 同事Dify脚本的参考边界

用户提供dify-build-xuanyuan.sh作为参考，已静态阅读，未执行。可借鉴参数化、有限重试、构建失败提前退出和已有镜像复用。

- 原脚本先pull当前分支再checkout目标分支，不能确保目标分支取到最新提交；DataAgent需fetch指定分支并记录明确提交，不自动merge或覆盖脏工作区。
- 原脚本重试20次，但单次pull没有显式超时；DataAgent需要单次上限和有限退避。
- 原脚本要求root；66当前sudo有约束，DataAgent脚本应普通账号执行，仅Docker操作按既有方式sudo。
- 原脚本切分支时重建.env并替换固定密码；DataAgent保持外置生产配置，不按模板自动重写。
- 原脚本固定/data/dify，构建api/web两个镜像；DataAgent前端打入JAR，一个主应用镜像，独立分析沙箱继续复用。
- 原脚本compose down/up后没有业务健康验收和回退，且全局image prune可能影响其他项目；DataAgent限定项目/服务，无全局清理，新镜像检查成功后更新，并保留旧版本。
- 原脚本只解析Dockerfile首个FROM；不能用该逻辑处理DataAgent多阶段构建和ARG镜像。参考脚本的镜像代理地址未在本轮验证，不替换已验收源。

源码更新和只构建保护现已实现；运行容器的Compose启停、健康验收和回退仍待完成。不执行参考脚本，不更改66现有Dify。

## 三、准备构建工具镜像（现在可以执行）

```bash
sudo docker pull --platform linux/amd64 \
  m.daocloud.io/docker.io/library/maven@sha256:f58d59b6273e785ac0a4477f6e9b5ba1d7731c75b906c0f7b34076f1851318cc
```

脚本固定本轮镜像index摘要f58d59b6273e785ac0a4477f6e9b5ba1d7731c75b906c0f7b34076f1851318cc；如标签已变需拉取该摘要或重新验收。本地此镜像实测Maven3.9.9/JDK17.0.15/amd64，运行镜像仍是此前Java17.0.21/Python/Wren；旧内核能否启动新builder需66回传。

## 四、用户push后，在66更新源码并构建

必须先由用户push本次提交，以下为66后续步骤。先核对干净工作区和目标分支，再更新，不覆盖已有改动：

```bash
cd "$HOME/dataagent-source/agentscope-java"
git status --short
git rev-parse --abbrev-ref HEAD
# 确认为目标分支且无本地修改后才执行：
timeout 90s git pull --ff-only origin develop-2.0
cd agentscope-dataagent-standalone
bash docker/check-builder.sh
bash docker/build-from-source.sh "$(git rev-parse HEAD)"
```

check-builder.sh仅新建一个断网512MiB临时容器检查Java/javac/Maven，自动移除本轮检查容器，不挂载生产目录/socket，不启停服务。宿主Java8不改。

build-from-source.sh拒绝脏源码、未提交控制文件或不同于HEAD的指定提交；归档明确源码，构建控制文件也来自归档。默认~/dataagent-build下jobs保留每次源码、日志、JAR摘要与镜像信息，cache为项目独立Maven/npm缓存，不借用宿主~/.m2。默认2CPU/6GiB、3600秒上限，可显式配置DATAAGENT_BUILD_CPUS/MEMORY/TIMEOUT。

完整core/harness/BOM及直接使用扩展从同一提交构建；应用执行verify，前端固定Node20.19.2/npm10.9.2并执行npm ci，前缀默认/dify/dataagent/。仅构建容器使用阿里云Maven公共源与npmmirror的Node/npm源；不改全局源。公司代理或仓库如需定制，DATAAGENT_MAVEN_SETTINGS可指向私有设置文件，不把凭据提交或放入最终镜像。

成功后输出dataagent/app:<提交前缀>-<时间>-<本次标识>及本次build-result.txt。应用JAR在镜像/opt/dataagent/app；镜像仅包含白名单运行文件，没有源码/Maven/Node/私有生产配置。断网静态预检不等于数据库或业务启动通过。

后续更新可以执行bash docker/update-source.sh：仅fetch指定分支并快进，三次重试、单次90秒，有本地修改/错误分支/历史分叉就停止，不自动merge、reset、清理或启停服务。然后独立执行只构建脚本。

## 五、独立MySQL8.0演练

2026-10-29现场截图确认mysql:8.0已下载，linux/amd64，来源摘要7dcddc01f13bab2f15cde676d44d01f61fc9f99fe7785e86196dfc07d358ae2b，本地同一镜像Id为6cd09145362dfe6831b14545de3d5fd6cc75c37cfd6ef8561429c1fc73518b39。镜像下载不代表数据库已启动。

新增docker/rehearsal-mysql.sh目前须先在开发机提交并push，不能直接在66的旧9d378d8b中调用。该脚本不改变应用JAR，无需因这批运维文件重建已经通过预检的应用镜像。用户push后，在66执行：

```bash
cd "$HOME/dataagent-source/agentscope-java/agentscope-dataagent-standalone"
bash docker/update-source.sh
bash docker/rehearsal-mysql.sh prepare
bash docker/rehearsal-mysql.sh start
```

prepare先用已下载镜像断网运行mysql --version，然后只在新/data/dataagent/rehearsal-db目录生成专属Compose、两库DDL和随机密码。已标记的完整配置重复执行不会重置密码；其他已有目录拒绝覆盖。仅创建/自动移除这一个版本检查容器，符合此前人工检查通过脚本执行的约定。

start只启动dataagent66-rehearsal-db项目的mysql服务，等待健康并核对37表/两个账号；不运行应用、不处理BI用户、不停止Dify。MySQL数据在演练目录mysql下，密码保存在mysql.env和app-db.env（600），初始化文件不含密码。Compose固定使用准备时已下载的image ID，启动不自动拉新镜像；2CPU/2GiB预算，不发布宿主端口。

平台账号dataagent_platform仅有平台库DML权限，上传账号dataagent_dataset仅对data_agent授权。后续应用需要加入dataagent66-rehearsal-db_default网络，读取私有app-db.env，使用mysql:3306；该名称不在宿主DNS上，不使用localhost或66:3306。SQL初次建表只由官方MySQL入口对空数据目录执行，之后应用保持ddl-auto=validate及sql.init.mode=never。

只读检查/定位命令：

```bash
bash docker/rehearsal-mysql.sh status
bash docker/rehearsal-mysql.sh verify
bash docker/rehearsal-mysql.sh logs
```

失败时保留数据/密码/日志定位，不删除数据目录、不重放完整DDL，不用compose down -v或全局prune。此入口不包含删除动作。实际初始化及旧内核MySQL兼容仍以66启动结果为准；本地Linux首次初始化、37表、权限隔离、Wren Python MySQLdb通过Docker DNS连接、停启数据保留、无宿主端口及拒绝覆盖已实测通过。

## 六、启动演练应用

用户已回传66的“演练库核对通过”，正式数据库仍在等待。新增rehearsal-app.sh与compose.rehearsal-app.yml须先由开发者本地提交/push；66更新控制文件即可，应用JAR未变时无需重建9d378d8b对应的镜像。

```bash
cd "$HOME/dataagent-source/agentscope-java/agentscope-dataagent-standalone"
bash docker/update-source.sh
bash docker/rehearsal-mysql.sh status
bash docker/rehearsal-app.sh prepare
bash docker/rehearsal-app.sh start
```

prepare默认在~/dataagent-build/jobs中仅存在一份image-reference.txt时读取它；多份时明确停止，使用prepare 'dataagent/app:具体标签'指定，不默取latest。标签必须是本项目linux/amd64应用镜像，实际固定image ID；不重建或拉镜像。仅准备新/data/dataagent/rehearsal-app目录，已有完整配置再次执行保留密钥和数据，其他目录拒绝覆盖。

- 主应用项目dataagent66-rehearsal-app，加入既有dataagent66-rehearsal-db_default网络，读取演练库app-db.env。MySQL无宿主端口不影响Java/Wren同网络访问mysql:3306。
- 默认当前UID/GID及socket附加组，宿主/容器应用目录绝对路径相同，Docker socket供框架创建独立分析沙箱。已下载的~/dataagent-env/hf-cache-modelscope以只读方式挂载；宿主Java8/glibc不修改。
- conf/application-prod.yml使用validate/never，runtime.env生成独立JWT和模型加密密钥，默认关闭密码/旧票据入口、开启BI账号入口，CORS默认https://dc.migu.cn。未自动导入人员或创建管理员，也不复制开发机模型密钥。配置与两库、持久文件需一致保留。
- 默认8CPU/16GiB、JVM沿用镜像默认256MiB—1GiB，主进程在容器中运行，18080绑定10.194.2.66。演练可在prepare前明确覆盖DATAAGENT_BIND_IP、DATAAGENT_PORT、DATAAGENT_RUNTIME_CPUS/MEMORY，不改变既有部署。预算不是并发能力保证。
- start先执行应用依赖/socket检查、两库网络连接、离线向量(2,384)及断网兄弟沙箱同路径读写，再启动真实Java并等健康和BI账号入口。每次检查只移除本次临时检查容器，持久目录和业务容器不删除。

正常输出“服务健康和BI账号入口通过”后，可在66检查：

```bash
curl --noproxy '*' --connect-timeout 5 --max-time 10 http://10.194.2.66:18080/actuator/health
bash docker/rehearsal-app.sh status
bash docker/rehearsal-app.sh logs
```

本阶段后端路径为根路径，前端已经按/dify/dataagent/构建；需要65代理剥离此前缀后转发。不要把66裸端口直接加载前端的资源404误判为服务启动失败，也不以健康UP代替65/BI/模型验收。

后续接通65同源代理与BI图标，用本人的BI英文账号首次进入、按现有管理员SQL赋权并重新进入，再到管理员模型服务页面配置与测试激活服务；未配置模型时应用可以启动，问答/建模尚不可验。真实BI跨层Nginx/iframe/LocalStorage同源联调与用户资料导入另外执行。不得把临时数据库切换成正式库而省略数据/文件/密钥迁移核对。

## 七、日志、停启与镜像更新

已完成首次部署后，日常push后的操作速查见[66更新与重启命令](66-update-operations.md)，包含本次临时直连拉取、构建、upgrade、运行版本核对和回退入口。

人员查询和管理员授权可直接在源码目录执行`bash docker/user-admin.sh`，详见[用户管理指引](user-admin.md)。仅改变已有账号角色，不需要重构或重启应用；修改后重新从BI登录，旧令牌不会立即失效。

启动异常先看rehearsal-app.sh logs的Java控制台；问数/建模转录分别在演练应用logs/LLM.log、LLM-modeling.log，耗时和时间门禁为execution-timing.log、sql-window-gate.log。日志可能含业务数据，外发前需去除敏感内容。数据库问题另用rehearsal-mysql.sh logs；不要输出私有env全文。

```bash
bash docker/rehearsal-app.sh stop
bash docker/rehearsal-app.sh start
# 后续先独立构建并预检新镜像，再显式选择：
bash docker/rehearsal-app.sh upgrade 'dataagent/app:新镜像标签'
bash docker/rehearsal-app.sh rollback
```

stop只停止本项目应用；数据库、配置、文件和镜像保留。upgrade先检查候选镜像，失败不停止旧服务；候选启动或健康验收失败时保留失败日志、恢复旧镜像并再验健康，最终仍返回失败提示。rollback使用previous-image.txt，只回退应用镜像；不会逆向迁移数据库、模型、工作区或人员数据。所有操作核对项目路径标签，不执行全局清理或操作Dify。

本次修正MySQL首次初始化配套：02-users.sh以可执行子进程运行，避免严格模式泄漏到官方入口；健康用TCP127.0.0.1，防止临时初始化socket进程被误判就绪。仅生成新目录时生效，不清空或重放66已经完成的初始化，也不因更新源码自动替换其现有Compose/私有配置。现有库成功核对后继续使用；需要变更已有控制配置时另行审阅，不删库重建。

正式方案：[spec099](../specs/099-source-build-deployment.md)、[ADR0118](../adr/0118-source-build-on-deployment-host.md)。验证见[构建报告](../test-reports/20261029-source-build-validation.md)。助手未连接66执行构建；用户自行push。

## 2026-10-30现场启动通过，转65入口只读盘点
用户最新截图确认66应用prepare/start、两库连接、离线向量(2,384)、宿主Docker和兄弟沙箱同路径读写通过，应用Healthy，18080/actuator/health返回UP。应用控制已由用户随122d2b7e提交；上述未提交/未启动段落是此前状态，以本段为准。无需重复构建或启动，不把基础UP等同模型/BI/正式数据库验收。

下一步在65执行，只读，不安装/启停或改配置：
```bash
hostname -I
command -v nginx docker curl
ps -eo pid,user,comm | grep '[n]ginx'
sudo docker ps --format 'table {{.Names}}\t{{.Image}}\t{{.Ports}}'
curl --noproxy '*' --connect-timeout 5 --max-time 10 http://10.194.2.66:18080/actuator/health
```
没有某个命令或无权限也是定位结果，不据此重装。根据结果确认实际Nginx实例、配置挂载和承接BI访问域名的server，再备份原配置、增量添加location、nginx -t通过后按既有方式reload；不替换整个server，不要求外发全量nginx -T/私有env。

目标为BI原origin下/dify/dataagent/反向代理到66:18080，BI图标使用/dify/dataagent/bi-entry，不能302到66内网IP。若65只接其他Dify域名，需核对BI入口到65路由；多层仅剥前缀一次，逐层保留SSE。确认这些事实后再提供对应实例的修改/reload命令。
