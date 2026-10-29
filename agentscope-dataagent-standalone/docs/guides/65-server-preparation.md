# 65 生产服务器：运维准备命令单

2026-10-21。入口为[单机发布手册](single-host-release.md)，发布验收顺序见[执行清单](release-checklist.md)。本文供开发交给运维准备环境，不自动执行任何服务器操作。65系统版本尚未知；用户截图仅显示整机32逻辑CPU、252GiB内存，不能推导发行版、架构或本项目可用配额。

## 1. 先采集并回填，暂不安装

以下是只读命令，运维可返回输出；不需要返回配置文件、环境变量全集、密钥或数据库密码。

```bash
cat /etc/os-release
uname -m
getconf _NPROCESSORS_ONLN
free -h
df -h /opt /var/lib
java -version
python3 --version
command -v java python3 docker nginx mysql
docker version
id dataagent
timedatectl status
```

命令不存在/无权限也是有效结果。补充回填：项目可用CPU/内存/磁盘、预计用户数，65能否访问公司大模型和公网备用模型、Python包仓库/镜像仓库是否可达，MySQL服务地址和版本，BI公网/内网访问域名、入口代理的负责方，以及公司是否已有Docker/应用进程托管规范。现有Docker等被其他业务使用时保留，不运行卸载/清空命令。

## 2. 安装对象与责任

| 对象 | 本项目要求 | 部署位置 |
| --- | --- | --- |
| Java | 当前编译基线17，正式候选建议使用并验收JDK17；已有其他版本不全局替换 | 65应用运行环境 |
| Python | Wren要求至少3.11，采用已验证的3.11/3.12环境，不改系统默认解释器 | 65独立venv；容器内Python由镜像提供 |
| Wren | 按随包requirements安装CLI/MCP及ONNX/LanceDB依赖 | 65宿主venv，不需要另部署完整WrenAI网站/一整套Wren容器 |
| Docker Engine | 应用需创建Python沙箱；安装daemon还不够，需要实际镜像和运行账号访问权限 | 65，或经另行适配验收的公司运行环境 |
| MySQL | 两个逻辑库：平台元数据、上传数据；可共用已有MySQL实例 | 65或独立数据库服务器，65可达即可 |
| 入口代理 | BI同源域名的/dataagent/转发到65；Nginx是现有可用方案 | BI入口网关，不要求一定装在65 |
| 其他存储 | 版本外data目录、模型缓存、备份位置 | 持久磁盘，不只备份MySQL |

65仅运行已构建包时不需要Maven、Node、npm或Vite开发服务；Redis和独立向量数据库服务不是当前默认运行的必要项。关联公司的既有MySQL/Doris业务源不需要再复制一份业务库。

## 3. Ubuntu 24.04专用示例：系统包

**只有第1步确认是Ubuntu24.04且运维批准后才采用本节。** 其他系统改用公司批准的软件源/包名；Ubuntu22.04的默认Python也不能直接当作满足3.11要求。命令是参考清单，未在65执行或验收。Java/Python可用版本依据 [Ubuntu Java列表](https://ubuntu.com/developers/docs/reference/availability/java/) 和 [Ubuntu Python说明](https://ubuntu.com/developers/docs/howto/python-setup/)。

```bash
sudo apt-get update
sudo apt-get install -y openjdk-17-jdk-headless python3 python3-venv python3-dev \
  default-libmysqlclient-dev build-essential pkg-config ca-certificates curl util-linux
java -version
python3 --version
```

若java仍指向其他应用的JDK，不改整机alternatives；为DataAgent设置DATAAGENT_JAVA指向核准的JDK17绝对路径。数据库管理需要时另安装客户端：`sudo apt-get install -y default-mysql-client`；已有数据库服务时不需要在65再安装mysql-server。Nginx是否安装由实际入口网关位置决定，不默认给65新增一套。

## 4. Docker：先复用已核准环境，缺失时安装

已有Docker先核对服务/架构/版本与权限，不卸载现有containerd/runc或清理容器。以下仅适用于**无冲突Docker环境的Ubuntu24.04新安装**，按 [Docker官方Ubuntu步骤](https://docs.docker.com/engine/install/ubuntu/) 核对；不使用curl管道执行安装脚本。

```bash
sudo install -m 0755 -d /etc/apt/keyrings
sudo curl -fsSL https://download.docker.com/linux/ubuntu/gpg -o /etc/apt/keyrings/docker.asc
sudo chmod a+r /etc/apt/keyrings/docker.asc
sudo tee /etc/apt/sources.list.d/docker.sources >/dev/null <<EOF
Types: deb
URIs: https://download.docker.com/linux/ubuntu
Suites: noble
Components: stable
Architectures: $(dpkg --print-architecture)
Signed-By: /etc/apt/keyrings/docker.asc
EOF
sudo apt-get update
apt-cache madison docker-ce
```

运维从上条输出选定并记录批准版本，设置DOCKER_VERSION后才执行：

```bash
: "${DOCKER_VERSION:?先设置核准的Docker版本号}"
sudo apt-get install -y docker-ce="$DOCKER_VERSION" docker-ce-cli="$DOCKER_VERSION" containerd.io
sudo systemctl enable --now docker
sudo docker version
```

离线环境由运维准备同系统/架构的安装包和依赖，跳过联网下载，按公司规范安装；不能只上传应用tar.gz就期望Docker/Wren自动可用。

## 5. 应用账号、Wren与沙箱镜像

约定发布父目录/opt/dataagent；已有dataagent账号先核对，**只有不存在时**才创建：

```bash
sudo useradd --system --user-group --home-dir /opt/dataagent --shell /usr/sbin/nologin dataagent
sudo install -d -o dataagent -g dataagent -m 0750 /opt/dataagent
```

运维审批账号访问Docker daemon。若采用docker组授权，`sudo usermod -aG docker dataagent`后以新进程检查 `sudo -u dataagent -H docker version`；docker组具备很高的宿主权限，应遵守公司既有授权方式，不能为方便公开Docker socket。说明见 [Docker运行账号文档](https://docs.docker.com/engine/install/linux-postinstall/)。

上传并核验开发交付的tar/镜像SHA256，以应用账号解包到约定父目录后安装Wren。以下示例必须换成**实际候选包的版本目录**；首次创建venv，不覆盖已有环境：

```bash
sudo -u dataagent python3 -m venv /opt/dataagent/wren-venv
sudo -u dataagent /opt/dataagent/wren-venv/bin/python -m pip install \
  -r /opt/dataagent/dataagent-2.0.3-SNAPSHOT/requirements-wren.txt
sudo -u dataagent /opt/dataagent/wren-venv/bin/python \
  /opt/dataagent/dataagent-2.0.3-SNAPSHOT/bin/check-wren.py dependencies \
  /opt/dataagent/wren-venv/bin/wren
```

固定依赖以合并后随包requirements为准；本次源码为wrenai0.15.0、wren-core-py0.8.0、mcp1.30.0。联网安装与离线wheelhouse由运维按网络条件选择；pip依赖检查失败先修复，不能继续启动。

开发交付与65同CPU架构的沙箱镜像归档，运维导入并核对imageId：

```bash
sudo docker load -i /opt/dataagent/delivery/dataagent-sandbox.tar.gz
sudo docker image inspect agentscope/dataagent-sandbox:latest --format '{{.Id}} {{.Architecture}}'
sudo -u dataagent -H docker run --name dataagent-sandbox-check --network none agentscope/dataagent-sandbox:latest \
  python -c "import pandas,numpy,scipy,matplotlib,shutil; assert shutil.which('setsid'); print('OK')"
```

tag以实际候选配置为准，latest不能代替镜像哈希。另交付向量嵌入模型缓存，放到应用实际HOME/HF_HOME；启动脚本将HOME设为/opt/dataagent/data/home。公司提供的聊天大模型不替代本地ONNX向量嵌入模型。必须用相同账号/HOME做离线模型检查及真实Wren召回，不能只验证import通过。

## 6. DBA：两个库、表结构与管理员

DBA连接核准MySQL实例执行，库名可按公司规范替换，并同步配置。下面**仅创建两个空库，不是完整初始化SQL**：

```sql
CREATE DATABASE IF NOT EXISTS dataagent_platform CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE DATABASE IF NOT EXISTS data_agent CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
```

- 平台库：用户、知识库/授权、数据集元数据、会话索引/Agent状态等。先准备与候选版本匹配的完整schema，prod使用validate；当前完整首次DDL待合并后生成和验收。
- 上传库：应用按上传创建ds_表。运行账号需要相应建表/写入/查询/清理权限；平台库运行时DML与DBA迁移权限按实际代码分开核定，不给全实例root。
- “迁移”指**表结构随版本升级**，不是默认把云端测试库/测试用户搬到生产。0080/0081/0083/0084/0088按实际结构选择执行，不在完整新schema上盲目重复ALTER。0088增加管理员模型服务密文配置表。
- 不运行src/main/resources/data-mysql.sql来初始化生产；它用于开发演示用户，生产SQL初始化关闭。

管理员就是按稳定BI用户名指定角色。建议先让指定人员经BI登录生成普通账号，DBA确认后升级；**账号未创建时UPDATE不会创建用户**。示例中的用户名须替换：

```sql
SELECT user_id, username, roles_csv FROM dataagent_platform.dataagent_user
WHERE username = 'REPLACE_WITH_REAL_BI_USERNAME';

UPDATE dataagent_platform.dataagent_user
SET roles_csv = CASE
  WHEN FIND_IN_SET('admin', COALESCE(roles_csv, '')) > 0 THEN roles_csv
  WHEN COALESCE(roles_csv, '') = '' THEN 'user,admin'
  ELSE CONCAT(roles_csv, ',admin') END
WHERE username = 'REPLACE_WITH_REAL_BI_USERNAME';
```

保留其他角色，不覆盖user_id，不创建共同管理员密码。退出后从BI显式重新进入拿新JWT，检查管理员权限；仅刷新页面可能复用旧JWT。

## 7. 配置、网络与浏览器入口

私有conf/application-prod.yml使用两个库连接、JWT签名密钥、独立BI共享密钥、模型配置加密主密钥和BI同源地址；这些密钥与大模型API Key、数据库密码是不同用途，不能混用。模型连接可由全局管理员在“更多 > 大模型服务”填写并测试/应用，无需重启；原application模型仅作首次启用前的兼容启动源。含`&`/`=`的公司凭据在页面作为完整字符串填写，不拆分、不输出到日志。部署主密钥`DATAAGENT_MODEL_ENCRYPTION_KEY`为独立32字节Base64，未提供时需备份实际应用HOME中自动生成的model-services.key；具体步骤见单机手册的管理员模型服务章节。

部署者要保证变量进入**运行应用的同一账号/进程**；启动脚本不会自动读取任意.env文件。使用公司进程托管/凭据注入方式，或在该运行账号shell显式加载受保护的环境文件后再start。不要仅在root终端export后假设其他账号都能收到。

网络由运维按实际地址核准：

| 发起端 | 目的端 | 用途 |
| --- | --- | --- |
| 用户浏览器 | BI域名HTTPS443 | BI以及同源/dataagent/，浏览器无需直接访问65的8080 |
| BI入口网关 | 65内网应用端口或65内部代理 | 转发DataAgent静态文件/API/SSE/附件 |
| 65应用/Wren | 平台MySQL、上传MySQL、已授权业务MySQL/Doris实际端口 | 平台存储、导入和业务查询 |
| 65应用 | 公司模型HTTPS接口；经允许的公网备用接口 | Chat Completions与流式工具调用 |
| 运维构建/安装环境 | 核准系统仓库、pip仓库、镜像/模型源 | 首次准备；离线包可替代联网安装 |

跨服务器拓扑及Nginx配置见 [BI对接的跨服务器说明](../integration/bi-login.md#bi与dataagent在不同服务器)。65上Nginx可选，关键是**BI访问域名的入口代理**有/dataagent/路由。前端随JAR内static一起提供，不在65另起npm/Vite。

## 8. 最后交付与检查

- 开发：候选应用tar、镜像/模型缓存、校验值、完整首次DDL或适用迁移、正式运维文档与BI模块；不交付本地个人交接、原用户数据或密钥截图。
- DBA：两个库、schema、应用账号权限与管理员；运维：环境、进程托管、配额、网络/HTTPS/代理、日志轮转、备份和重启托管；BI负责人：真实用户名读取、同源入口和新进入/刷新/过期/切账号。
- 应用start/stop命令只按[单机发布手册](single-host-release.md)；云端演练前置大部分业务回归，65仍至少验真实BI、权限、实际数据源/模型、附件、资源限制及重启恢复；容量结论需要在目标配额或等价环境取得证据。
- 同时备份平台库、上传库和版本外持久data（含工程/published、历史、附件等），记录模型缓存/私有配置恢复方法。恢复并验证后才扩大灰度。

**发布前凭据核对**：spec080已移除受Git管理的公共application两项模型Key默认值；正式候选仍需重新构建、检查包内无明文凭据，并由凭据负责人确认历史Key是否需轮换。“私有prod未入包”不等于“包内无凭据”。本文没有复制这些值或执行凭据轮换。
