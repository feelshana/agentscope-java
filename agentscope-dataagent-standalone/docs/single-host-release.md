# Linux 单机发布

## BI 同域免登部署（ADR0081）

完整协议、可直接交付BI的JavaScript、生产配置与Nginx示例见[BI对接](integration/bi-login.md)，迁移见[0081 SQL](migrations/0081-bi-login-mysql.sql)。生产profile强制禁用密码接口/表单、启用BI；配置独立32字节Base64免登密钥，JWT默认1800秒，并关闭SQL演示初始化。前端共享密钥可被提取并用于伪造任意用户名，限制已获用户接受，不能当作可信SSO。

若部署到/dataagent/，构建前设置DATAAGENT_BASE_PATH=/dataagent/（Windows PowerShell：`$env:DATAAGENT_BASE_PATH='/dataagent/'`），再正常Maven打包。Nginx剥离前缀、关闭SSE缓冲并只允许同源iframe。私有dev/prod配置已Git忽略，既有凭据仍外置，不提交、不打包；BI模块通过参数接收密钥，仓库不保存正式值。此配置不代表已完成真实BI/生产代理验收。

本包运行当前分支的 Boot exec JAR 与官方 Wren。版本目录为 `dataagent-<version>/`，包含 `bin/`、`conf/`、`lib/` 与 `requirements-wren.txt`；相邻的 `data/` 保存状态，不随版本目录替换。适用于单机单实例，不提供分布式锁或全局预算。

## 构建

框架变更后先安装配套 harness/OpenAI 依赖；随后在 standalone 模块运行：

```bash
mvn test
mvn package -DskipTests
```

默认自动构建当前前端，产出原有 `agentscope-dataagent-<version>-exec.jar` 及 `dataagent-<version>.tar.gz`。公共 application.yml 和 h2 profile 保留在 JAR；私有 application-dev/prod 的 yml/yaml/properties 不进入资源/JAR/发布包。即使旧 target/classes 有这些文件，JAR 也会排除。分发包只携带无凭据 prod 样例。

本机仍可使用原来的私有配置文件，但通过外置目录显式加载，不依赖打入JAR：

```bash
java -jar target/agentscope-dataagent-2.0.3-SNAPSHOT-exec.jar \
  --spring.profiles.active=dev --spring.config.additional-location=file:src/main/resources/
```

IDE启动也可加入同样的Spring参数；本次不修改已有dev/prod文件。全新构建不会带私有配置，升级前遗留的target/classes文件建议通过clean清除。

## 首次部署

要求 Linux、Bash、flock/util-linux、Java 17+、Python 3.11+；沙箱使用同版本 Docker 镜像及 setsid。Wren Python 环境由部署者准备，启动脚本不执行 pip install。

Debian/Ubuntu首次安装Wren前，以root准备系统编译依赖（[mysqlclient官方安装说明](https://github.com/PyMySQL/mysqlclient/blob/main/README.md)）。`wrenai[mysql]`包含mysqlclient，本机可能从源码构建，需要Python/MySQL开发头文件、编译器及pkg-config；仅有运行中的MySQL服务不足以提供这些构建依赖。

```bash
apt-get update
apt-get install -y python3-venv python3-dev default-libmysqlclient-dev build-essential pkg-config
```

若pip报`pkg-config: not found`或`Can not find valid pkg-config name`，补齐上述依赖后，以原部署账号重新执行同一条pip安装命令；无需删除虚拟环境、改固定版本或手动伪造MYSQLCLIENT_CFLAGS/LDFLAGS。之后运行发布包`bin/check-wren.py dependencies <Wren可执行文件>`检查。系统包安装与venv/pip安装分开执行，后者保持应用运行账号。


```bash
# 在部署父目录解包，例如 /opt/dataagent。
tar -xzf dataagent-2.0.3-SNAPSHOT.tar.gz
cd dataagent-2.0.3-SNAPSHOT
python3 -m venv ../wren-venv
../wren-venv/bin/python -m pip install -r requirements-wren.txt
cp conf/application-prod.yml.example conf/application-prod.yml

export DATAAGENT_WREN_EXECUTABLE="$(cd ../wren-venv/bin && pwd)/wren"
export DATAAGENT_WREN_PYTHON="$(cd ../wren-venv/bin && pwd)/python"
# 设置目标环境变量或编辑 conf/application-prod.yml 后启动。
./bin/dataagent.sh start
./bin/dataagent.sh stop
```

图表归属升级（ADR0083）：已有平台表使用validate时，启动新JAR前检查并执行[0083迁移SQL](migrations/0083-chart-owner-mysql.sql)，补可空owner_id列；脚本只执行一次。开发默认update可补列。历史无主图不删除、不自动回填，旧对话中的此类图需重新生成；新的共享库问数图仍仅提问者可读。

必须配置平台数据库 DATAAGENT_DB_URL/USER/PASSWORD、DATAAGENT_JWT_SECRET（至少32字节）、DATAAGENT_CORS_ALLOWED_ORIGINS，以及实际模型供应商密钥、独立数据集 MySQL 连接。生产样例关闭演示 SQL 初始化并使用 ddl-auto=validate；首次部署须先在目标库准备兼容 schema 和管理员，不能靠样例自动创建。不要复制开发数据库或本机配置到发布包。

会话上下文升级（ADR0084）：在平台元数据库提前执行[0084迁移SQL](migrations/0084-agent-state-mysql.sql)，创建`dataagent_agent_state`。它保存每用户/会话的最新状态JSON及版本，身份列需binary排序规则；生产缺表/列会拒绝启动，不回落内存。备份/恢复应同时包含状态表、session_registry、展示历史及持久文件。接入后新保存状态可跨重启继续追问，之前已丢失的Agent状态不会自动重建；开发默认update自动建表。

Wren 依赖固定在 requirements-wren.txt；脚本验证 CLI、对应 Python 的包版本及 MCP/MySQL/ONNX/LanceDB 导入。可执行文件优先使用 DATAAGENT_WREN_EXECUTABLE，Python 优先使用 DATAAGENT_WREN_PYTHON，其次取 CLI 同目录 python，再回退 python3。首次向量召回仍可能下载多语言模型；缓存需对部署账号可读，可通过 HF_HOME 指定持久缓存。预检不下载模型、不验证业务数据库或大模型答案。安装及启动应使用同一部署账号。依赖导入通过不代表嵌入模型可用；上线前须在应用实际HOME/HF_HOME下做一次离线向量加载检查。模型未缓存且下载网络不可达会使memory store/index/recall等待到CLI超时，文件可能已写入而索引未完成。2026-10-19云端实测及已准备的离线缓存材料见[建模与问答耗时专项](test-reports/20261019-cloud-modeling-latency.md)；用户已完成云端模型安装及离线预检，真实保存/召回/发布后问答/点赞回归通过；首次或发布后索引仍约20秒，不据此承诺并发性能。详见该专项的修复后记录。

2026-10-19云端演练修正：Wren0.15.0的mysql extra使用mysqlclient，其实际模块名为MySQLdb。依赖预检应导入MySQLdb，不要求无关PyMySQL。旧包若报`No module named pymysql`，可将`bin/check-wren.py`模块列表中的`"pymysql"`替换为`"MySQLdb"`后重新预检；不要为通过错误检查额外安装驱动。新分发包已修正，JAR/固定依赖版本不变。定向回归：`python -m unittest discover -s src/test/python -p test_check_wren.py -v`，测试使用临时发布目录；不要求在源码目录直接运行面向发布目录布局的预检脚本。


## 沙箱镜像与离线导入

先用本模块docker/sandbox.Dockerfile构建agentscope/dataagent-sandbox:latest；镜像需要分析库、中文字体及util-linux提供的setsid。若DockerHub manifest请求超时，可在可访问仓库且已验证的同架构环境构建/保存镜像，上传后用docker load导入；不依赖在应用容器运行时下载包。Docker官方支持直接读取gzip压缩归档：[image load](https://docs.docker.com/reference/cli/docker/image/load/)。

```bash
# 目标主机：归档由可信构建环境提供，先核对平台与SHA256。
docker load -i /root/dataagent-sandbox-linux-amd64.tar.gz
runuser -u dataagent -- docker run --rm --network none agentscope/dataagent-sandbox:latest python -c "import pandas, numpy, scipy, matplotlib, shutil; assert shutil.which('setsid'); print('沙箱依赖和setsid检查通过')"
```

离线镜像与应用配置使用同一tag；保存构建环境、平台、imageId及依赖版本，后续正式部署应使用已验证的同一制品。导入通过仍需验证应用沙箱租约、Python执行和取消等业务链路，不替代完整验收。

## 启停与升级

脚本使用共享 `data/run/control.lock` 串行启停，PID记录包含启动时间及实际 JAR 路径；拒绝进程身份不符及PID复用。健康检查通过后才报告就绪，默认等待120秒；失败会结束本次进程并保留日志。停止无需 Wren/Python 可用。

| 环境变量 | 默认值 | 用途 |
| --- | --- | --- |
| DATAAGENT_PROFILE | prod | 要求 conf/application-<profile>.yml |
| DATAAGENT_PORT | 8080 | 实际 server.port 与默认健康检查端口 |
| DATAAGENT_START_TIMEOUT | 120 | 等待健康检查秒数 |
| DATAAGENT_HEALTH_URL | http://127.0.0.1:<port>/actuator/health | 自定义管理端口或上下文路径时覆盖 |
| DATAAGENT_JAVA / JAVA_HOME | java | Java可执行文件 |
| JAVA_OPTS | -Xms256m -Xmx1024m | JVM参数，按空白分隔，不执行shell表达式 |

持久目录为父目录 `data/home`（含Agent配置和默认模型缓存）、`state`、`workspace`、`artifacts`、`mdl`、`run`；日志在各版本 `logs/`。root执行且存在dataagent用户时，会创建运行目录并切换到该账号；版本文件和Wren环境须允许该账号读取/执行。

升级前暂停新任务，等待或取消执行，备份平台元数据库、共享授权、上述持久目录。先stop旧版本，再将新版本解包为同一父目录下的相邻目录，配置目标环境并start。回滚同样先stop再切旧版本；持久数据与schema兼容性需要单独核验。源码Maven SNAPSHOT版本不变时，请先在其他位置备份旧版本，勿直接覆盖正在运行的目录。多版本共用PID记录，禁止同时启动。

生产首次验收：应用健康、登录与共享权限、上传/发布/问数、问答对保存召回、Docker取消；只有包版本正确和HTTP健康不能代替这些业务检查。


## Wren分段耗时诊断

包含spec076的新版本使用`[wren-timing]`输出锁等待、进程执行、索引重建、状态检查及MCP调用阶段；新Java代码需随JAR升级后生效，旧云端JAR不会自动出现这些日志。按trace关联单次操作，同库有并发时勿直接按相邻行相减。示例：`grep '\[wren-timing\]' logs/launcher.log | tail -n 100`。

按需诊断官方Python内部阶段时，在独立工程副本、同运行账号及同HOME/HF_HOME下执行以下命令；不得在用户正在使用的工程上反复执行store/index。`profile-wren.py`只生成无业务内容的汇总JSON，官方CLI自身输出仍按原命令显示。`--report`必须是尚不存在的文件。若需与应用完全一致的启动开销，还需沿用应用的兼容PYTHONPATH；脚本内部耗时不含解释器/sitecustomize启动。

```bash
cd /path/to/isolated-test-project
/path/to/wren-venv/bin/python /path/to/release/bin/profile-wren.py \
  --report /path/to/private-diagnostics/recall-01.json \
  -- memory recall -q '验收问题' -o json
```

这是有开销的调用跟踪，不能常驻到默认请求或把跟踪总时长当生产基线；另运行普通CLI作对照。`inclusiveMs`包含嵌套调用，`outermostMs`仅消除同阶段递归重叠，不同阶段仍不可直接相加。当前六表云端冷CLI保存/召回约4.7/4.6秒，而同进程模型已就绪后保存/召回约16–19毫秒；这不是任意问答SLO。详见[分段实测](test-reports/20261019-wren-phase-timing.md)。本轮仅诊断，尚未改变索引更新策略或复用模型进程。

## 数据源中断恢复

ADR0085：明确的MySQL/Doris兼容断连错误会在当前知识库自动重建一次Wren实例，并重试一次只读查询；无需重新发布或重启整个应用。恢复仍失败时同库冷却30秒，随后由下一次请求重新连接。正常请求不增加数据库心跳。冷却期间先检查数据库和网络，勿反复发布；SQL/字段/账号权限/缺库缺表错误不走该恢复流程，按原始错误处理。更换Wren版本后重验错误形态；本轮不新增配置、SQL迁移或后台恢复任务。


## 当前交付与升级边界

版本仍为`2.0.3-SNAPSHOT`，不能只凭版本号判断源码、制品和已部署内容是否一致。工具详情、图表/SQL编辑、持久会话与Wren恢复已提交；模拟BI及MCP类加载器修复随后用于云端演练，依赖预检和logs目录已现场修正；新增Java分段计时尚未部署。本次整理不覆盖云服务。提交状态、既有制品哈希和后续验收统一见[上线总账](go-live-gap-review-20261012.md)。

正式发布从确认的提交重新打包，记录提交号、JAR/tar/镜像校验值、部署前缀及外置配置版本。按`/dataagent/`嵌入时，构建前设置`DATAAGENT_BASE_PATH=/dataagent/`，不能混用旧根路径包。平台schema先核对0080/0081，validate升级提前准备0083/0084，不对已存在列重复执行ALTER。

默认上传解析上限为50万行、100列、1000万单元格，大小及并发预算不变。云端已验六个真实文件，最大168893行；50万边界和目标容量仍需验收，可按目标机配额用外置prod配置降低上限。
