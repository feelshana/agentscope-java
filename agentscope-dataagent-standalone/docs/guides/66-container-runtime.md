# 66服务器容器运行环境与发布操作

当前66主交付方式已选择GitHub源码构建，后续操作以[源码构建指引](66-source-build.md)为准。本文保留运行环境准备或外置tar备选说明，不要求在66重复安装或重复部署。

2026-10-29本次环境部署目标已完成，今天不继续数据库、打包上传或业务启动。给同事解释选择原因、性能边界和后续更新方式见[沟通说明](66-deployment-explanation.md)；下方后续发布命令作为以后参考，不是今天必须执行的任务。

适用已盘点的10.194.2.66：Anolis7.9、x86_64、glibc2.17、宿主内核3.10、Docker26.1.4；2026-10-28用户已确认采用本方案。实施见[spec098](../specs/098-single-host-container-runtime.md)，取舍见[ADR0117](../adr/0117-single-host-container-runtime.md)。2026-10-29用户截图已确认66运行环境安装检查全部通过；本机验证与该实机结果分别记录，业务服务尚未启动。

## 1. 已有环境与本次安装内容

**66最新实测（2026-10-29用户截图）**：安装脚本最后出现“环境镜像构建和检查全部完成；此脚本未启动业务服务”。OpenJDK17.0.21、wrenai0.15.0、pip check、Docker客户端26.1.4连接宿主服务端26.1.4（linux/amd64）、Java/Wren同环境断网向量、兄弟沙箱同路径共享、只读根/tmpfs/1CPU/1GiB断网分析、CSV/SciPy/中文字体/PNG/setsid均通过。截图未给出总耗时和两镜像ID，不推算。用户同时明确尚未拿到生产两库信息，当前仅可继续正式目录/空闲端口等独立准备，不尝试生产SQL或业务启动。


已在66通过：Python基础镜像、Wren依赖镜像dataagent/wren-deps-check:20261028、ModelScope校验下载后的默认ONNX模型断网计算。模型当前保存在登录账号的~/dataagent-env/hf-cache-modelscope。

本次准备dataagent/runtime:20261028（Java17运行环境、Python/Wren、固定Docker26.1.4客户端）和agentscope/dataagent-sandbox:20261028（分析库、中文字体、setsid）。不安装Maven，不编译业务JAR；应用运行镜像不含业务包、数据库口令或模型Key。只使用现有宿主daemon，不启动Docker-in-Docker。

源码安装材料在docker/：runtime.Dockerfile、runtime-entrypoint.sh、sandbox-anolis79.Dockerfile、install-66-environment.sh、check-environment.sh、dataagent-container.sh、runtime.env.example及.dockerignore。镜像构建上下文采用白名单，只允许Dockerfile和前台入口，防止误打包配置。Dockerfile需要此前已验收Wren基础镜像，不能跳过前置步骤在新机器独立构建。

这些是部署控制文件，业务JAR不会自动读取源码docker目录。今日复制安装已把材料写到66当前账号的~/dataagent-env/runtime-20261028；以后用当前源码打包，还会按assembly白名单把同一套文本附到发布包ops/container。旧包不会自动增加此目录，助手没有代用户重新打包。

| 文件 | 何时使用 | 执行位置 |
| --- | --- | --- |
| install-66-environment.sh | 首次安装或明确升级运行依赖；今天已完成，不为普通JAR更新重复运行 | 宿主机 |
| check-environment.sh | 安装后/环境变更后验证模型、Docker协作与分析栈 | 宿主机，创建临时检查容器 |
| dataagent-container.sh | 日常prepare/check/start/stop/replace/status/logs | 宿主机 |
| runtime-entrypoint.sh | 应用容器启动时自动预检并exec Java，操作者不用手动调用 | 已COPY入运行镜像，在容器内 |
| runtime.Dockerfile / sandbox-anolis79.Dockerfile | 构建对应环境镜像的配方；不会被业务JAR读取 | docker build时 |
| runtime.env.example | 首次配置参考，另建私有runtime.env；模板不含正式凭据 | 宿主机外置配置 |

正式业务启动链路是：

```text
宿主执行 dataagent-container.sh start
  → 使用已安装的dataagent/runtime镜像创建常驻应用容器
  → 挂载宿主业务版本目录、持久data和模型缓存
  → 容器自动执行runtime-entrypoint.sh
  → 容器内Java17执行挂载目录lib中的业务JAR，读取conf生产配置
  → Java直接调用同一容器内的Wren CLI/MCP
  → 需要Python分析时，通过宿主Docker创建独立兄弟沙箱
```

因此使用容器环境发生在Java启动之前，由宿主控制脚本决定；不是JAR启动后再自动寻找某个Java环境。业务JAR和配置通过bind mount可见，不需要复制进镜像。原bin/dataagent.sh仍属于原生启动方式，66本次容器方式不直接调用它。

若能上传文本材料，将这些文件放同一目录后执行：

```bash
bash ~/dataagent-env/runtime-20261028/install-66-environment.sh
```

无需上传文件的方式：助手另外生成“66环境部署-复制执行.txt”，内容以cat创建透明脚本和材料，再bash执行；打开文件全选复制到SSH终端即可，不要把Windows本地路径当服务器路径。该文本由当前源码逐字组装，不编码命令、无外部脚本下载；可先交运维审阅。包含脚本内临时docker run自动移除行为，不清理业务目录。

安装完成应出现“环境镜像构建和检查全部完成；此脚本未启动业务服务”。此时两个镜像及模型缓存均留在66，临时检查容器退出移除；构建材料目录保留runtime/sandbox的解析依赖清单和镜像编号。首次拉取Docker CLI镜像走已验证的DaoCloud，Debian/PyPI用国内源；网络或旧内核失败时返回首处错误，不重启宿主Docker或更换依赖版本。

## 2. 正式持久目录

2026-10-29用户截图已确认prepare成功，/data/dataagent归当前登录账号wangchao_yy1、组operation，权限0750，data子目录已建立；ss过滤18080仅显示表头，无当前监听记录。端口不是永久保留，正式启动前仍需现场复核；尚未收到业务包上传或生产配置完成的证据。

```text
/data/dataagent/
  runtime.env                    私有运行参数（不入Git）
  dataagent-2.0.3-SNAPSHOT/       用户本人打包上传后解压的原发布目录
    lib/                        *-exec.jar
    conf/application-prod.yml   私有生产配置，原样例使用conf目录名
    bin/                        原生启停脚本和依赖预检
    logs/                       应用分文件日志
  data/
    home/                       应用用户目录、配置及模型加密主密钥文件
    state/                      Agent状态
    workspace/                  工作区及shared
    artifacts/                  用户附件
    mdl/                        Wren工程与发布快照
```

正式应用以同一路径挂载整个/data/dataagent，宿主Docker解析兄弟沙箱shared路径时才能找到同一目录。默认模型缓存另从登录账号的既有缓存只读挂载到data/home/.cache/huggingface，不重复下载。当前登录账号UID/GID必须与目录所有者匹配；脚本只为不存在的项目根创建目录，不递归chown既有文件。

此步骤可在业务包上传前执行：

```bash
bash ~/dataagent-env/runtime-20261028/dataagent-container.sh prepare
```

不把以上目录放NFS；现有持久文件不随运行容器替换而清理。若变更运行账号或模型缓存位置，应先确认读取权限与备份位置，不直接移动/覆盖缓存。

## 3. 用户本人打包、上传和配置

用户在本机按目标前缀/dify/dataagent/自行Maven构建并上传tar，解压到上面的版本目录。运行环境脚本不构建、修改或上传业务包。生产数据库先按[数据库指引](production-database-setup.md)创建两库、平台表并导入人员；上传库先空库，ds_表由上传流程创建。

数据库地址不影响外置配置方式，可在等信息期间由用户本人先构建（不是在66执行）：

```powershell
Set-Location 'D:\code\agentscope\agentscope-java\agentscope-dataagent-standalone'
$env:DATAAGENT_BASE_PATH='/dify/dataagent/'
mvn package -DskipTests
```

tar在target目录。首次使用当前源码打包后，核对包内ops/container和conf/application-prod.yml.example等材料；助手仅核对assembly白名单，未代用户生成此业务归档。最终访问前缀若改变，需重新构建前端；数据库参数在外置生产配置填写，不因拿到数据库地址而重新编译。

把docker/runtime.env.example复制为/data/dataagent/runtime.env，填入实际两库地址/账号/口令、JWT密钥、模型配置加密主密钥和真实BI Origin。Docker env文件写原始KEY=value，不写export，不给值增加shell引号；不要把填好的文件或含密钥的终端输出发到聊天/Git。DATAAGENT_MODEL_ENCRYPTION_KEY必须是32随机字节的Base64，独立于JWT密钥，长期保留，与业务数据一起备份。

业务包conf/application-prod.yml按现有样例配置，生产ddl-auto仍validate；不靠启动自动建表。模型服务可在私有配置中配置初始默认值，或启动后由管理员页面维护；无有效模型时不能宣布问答验收通过。模型、联网搜索、数据库均保留外部真实地址，容器的localhost指容器自身。

如果需要在服务器本地生成密钥，可用已准备的运行镜像生成并写入仅当前账号可读的私有文件，具体操作按正式配置阶段执行；不要使用模板占位值启动。

## 4. 发布预检和启动（配置齐全后执行）

宿主脚本默认sudo docker；不使用sudo bash。运行容器按当前操作者UID/GID及Docker socket附加组启动。挂载socket意味着应用可管理宿主Docker，应按公司运维授权管理，不加privileged或开放网络Docker端口。

默认宿主端口18080是待确认空闲端口，不代表已检查占用；启动前现场核对。默认8CPU/16GiB是初始应用容器上限，不是15并发承诺；应用内MCP和沙箱还有各自预算，需根据混合负载调整，不能直接沿用基础检查2CPU/4GiB作为正式容量结论。

```bash
export DATAAGENT_RELEASE_DIR=/data/dataagent/dataagent-2.0.3-SNAPSHOT
export DATAAGENT_PORT=18080
export DATAAGENT_BIND_IP=10.194.2.66
bash ~/dataagent-env/runtime-20261028/dataagent-container.sh check
bash ~/dataagent-env/runtime-20261028/dataagent-container.sh start
bash ~/dataagent-env/runtime-20261028/dataagent-container.sh status
```

check只验证发布包、配置文件、Java/Wren依赖、Docker访问和沙箱镜像，不连接MySQL或验证业务权限。start返回容器编号不是应用就绪；继续查看状态、启动日志与健康接口：

```bash
bash ~/dataagent-env/runtime-20261028/dataagent-container.sh logs
```

logs中的Ctrl+C只结束跟随显示，不停止服务。启动控制台信息通过Docker logs查看；应用原有分文件日志仍在版本目录logs。healthcheck检查容器内/actuator/health，unhealthy并不触发Docker自动重启；unless-stopped针对主进程退出。Java以exec前台运行，Docker --init转发信号并回收已退出的子进程；不能使用nohup加tail来伪装容器主服务。

停止仅针对同名且项目/路径标签匹配的应用容器，给Java90秒退出：

```bash
bash ~/dataagent-env/runtime-20261028/dataagent-container.sh stop
```

原生bin/dataagent.sh不用于宿主直接启动此容器方案；它保留给具备原生依赖的机器。不要宿主和容器各启动一份应用。

## 5. 更新、回退和验收

隔离演练可通过宿主环境变量`DATAAGENT_SANDBOX_INSTANCE_ID`、`DATAAGENT_SANDBOX_IMAGE`覆盖控制脚本的沙箱实例和镜像；默认仍为66生产值。每个并存实例必须使用自己的持久根、数据库、端口、容器名称和沙箱实例标识，避免资源回收互相影响。私有runtime.env中的同名值会被控制脚本显式参数覆盖，因此在调用控制脚本的宿主环境中设置。

持久根应位于容量充足的本机磁盘。`df -h <项目根>`需核对实际挂载类型和可用空间，不应把`/tmp`、小容量tmpfs当作业务持久根；附件默认要求至少保留2GiB可用磁盘，模型缓存和JAR也会占用空间。2026-10-29本机Docker虚拟机的`/tmp`仅为约1.6GiB内存盘，触发附件保护，迁到磁盘后恢复；这不是66已准备的`/data`挂载。

更新仍由用户自行打包上传新版本目录；准备好该版本conf和私有环境文件，先stop，再设置新的DATAAGENT_RELEASE_DIR后执行replace。replace只移除本项目带匹配标签、已经停止的运行容器，再按当前镜像和参数创建；不会删除宿主data、模型缓存或版本目录。需要保留旧启动控制台时先导出本项目Docker日志；应用分文件日志保留在旧版本。回退以相同步骤切回旧发布目录；涉及schema变更须另按迁移/回退计划，不能只回退JAR。

```bash
export DATAAGENT_RELEASE_DIR=/data/dataagent/dataagent-REPLACE_VERSION
bash ~/dataagent-env/runtime-20261028/dataagent-container.sh replace
```

正式验收尚需：生产两库联通/表校验；公司大模型与备选配置；真实上传与建模、官方Wren索引/召回/SQL；Python分析与附件下载；停止/重启/恢复；Nginx子路径、SSE/上传与真实BI；目标并发和长稳。环境分项检查通过不代替这些结论。
