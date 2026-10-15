> Historical source record: data-agent-multiAgent-Mdl-lyf @ 6a75b6cf (2026-10-14). Branch-specific validation and release instructions below describe that source only. Current configuration, build and release: [platform-concurrency-and-release.md](platform-concurrency-and-release.md); integration: [ADR 0078](adr/0078-platform-capacity-followup-integration.md).

# 云服务器部署演练记录

开始日期：2026-10-11。本文记录每次实际操作及结果，供公司单机部署参考。尚未执行的步骤明确标为待执行。

## 环境

用户已购买阿里云轻量应用服务器，截图显示实例运行中：

| 项目 | 购买页/实例页显示 |
| --- | --- |
| 地域 | 成都 |
| 系统 | Ubuntu 24.04 |
| CPU / 内存 | 4 vCPU / 16 GiB |
| 磁盘 | 80 GiB ESSD 系统盘 |
| 演练方式 | 本地打包，上传服务器，通过脚本启停 |

2026-10-11 用户通过 Xshell 登录成功。登录截图显示 Ubuntu 24.04.2 LTS、x86_64、内核 6.8.0-63-generic，当前用户 root。随后用户提供命令输出截图：`nproc` 为 4；`free -h` 显示内存总量 14 GiB、已用 518 MiB、可用约 14 GiB，Swap 为 0；`df -h /` 显示根文件系统 79 G、已用 3.0 G、可用 73 G、使用率 4%。`command -v java docker python3 mysql nginx` 仅返回 `/usr/bin/python3`，其他命令未在当前 PATH 中找到，Python 版本待确认。

## 第一步：远程登录和基础资源检查（已完成）

实际使用方式：Xshell 通过 SSH 连接服务器公网 IP 的 22 端口，以 root 登录。终端已出现 `root@…:~#` 提示符，可以执行命令。下面保留 Workbench 作为备用方式。

在实例卡片选择「远程连接 → Workbench 一键连接 → 立即登录」。该方式使用 admin 账号免密登录。管理安装操作需要权限时再使用 sudo；首次检查无需切换 root。

在服务器终端逐行执行：

```bash
whoami
cat /etc/os-release
uname -m
nproc
free -h
df -h /
date
command -v java docker python3 mysql nginx
```

命令用途依次为：登录用户、系统版本、处理器架构、CPU 数量、内存、系统盘、时间和现有软件路径。最后一行只输出已找到的软件路径；有些软件尚未安装时返回非零状态，不影响前面的检查。

预期核对：Ubuntu 24.04、x86_64、4 个 CPU，内存及磁盘接近购买规格；实际可用容量小于套餐标称容量属于正常情况。

如需使用 Xshell 等本地客户端，另外配置 SSH 密钥或本人设置的登录密码。不要把密码、私钥、模型 API Key 写入本记录。

参考：[阿里云远程连接 Linux 轻量服务器](https://help.aliyun.com/zh/simple-application-server/user-guide/connect-to-linux-server-remotely)。

## 后续演练顺序（待执行）

1. 明确管理账户与应用运行账户，设置允许的访问来源。
2. 安装并记录 Java、Docker、MySQL、Python/Wren、Nginx 的版本与路径。
3. 构建或导入项目 Python 分析镜像，验证沙箱能够启动和执行代码。
4. 制作发布包及启停脚本，确定外置配置、日志和持久数据目录。
5. 初始化项目数据库，上传发布包，启动并验证页面与健康状态。
6. 使用测试数据验证上传、外部数据源关联、建模、发布、问答和文件下载。
7. 演练前端单独更新、后端单独更新、整体更新和回退。
8. 演练依赖不可用、重启恢复、备份还原和资源不足时的处理。
9. 验证并发与资源消耗，记录与公司 65 服务器环境之间的差异。

测试库使用测试数据建立；公司内网数据库、BI 登录和内网模型连通性需要在公司环境另行核实。

## 操作结果记录

| 日期 | 操作 | 结果 | 证据/备注 |
| --- | --- | --- | --- |
| 2026-10-11 | 用户购买演练服务器 | 已完成 | 实例控制台截图显示运行中 |
| 2026-10-11 | Xshell 远程登录 | 已完成 | 登录截图显示 Connection established，root 命令提示符 |
| 2026-10-11 | 确认系统、架构与资源 | 已完成 | 欢迎信息及用户命令截图：Ubuntu 24.04.2 LTS、x86_64、4 核、内存 14 GiB、根目录可用 73 G |
| 2026-10-11 | 检查常用软件路径 | 已完成 | 仅找到 /usr/bin/python3；Java、Docker、MySQL、Nginx 命令未在 PATH 中找到 |

## 第二步：更新现有系统软件（进行中）

项目根 POM 与 standalone 模块 POM 的 `java.version` 均为 17，后续安装 Java 17。当前先更新 Ubuntu 24.04 的现有软件并确认 Python 版本，尚未安装项目依赖。

首次以 root 登录，逐条执行：

```bash
apt update
apt upgrade
python3 --version
```

`apt update` 刷新软件索引；确认成功后执行 `apt upgrade` 更新当前系统软件。出现是否继续的确认可输入 `Y`；出现配置文件选择或错误时，记录提示后决定处理方式。更新后是否需要重启，根据实际输出及 `/run/reboot-required` 判断。这些命令尚未有执行结果，不表示已更新完成。

参考：[Ubuntu 软件管理说明](https://ubuntu.com/server/docs/tutorial/managing-software/)。

### 首次执行结果与后续操作

用户截图确认 `apt update` 成功，软件源为阿里云 Ubuntu noble 系列镜像，提示 289 项可更新。`apt upgrade` 提议升级 286 项、安装 2 项、保留 3 项；在 `Do you want to continue? [Y/n]` 后出现 `^HY`，紧接着 `Abort.`，本次更新未执行。无法仅凭截图确定控制字符的输入来源。`python3 --version` 返回 `Python 3.12.3`。

下一步重新执行 `apt upgrade -y`，自动确认本次软件升级；若仍出现配置文件选择或错误，按实际提示处理。完成后执行下列命令确认重启提示：

```bash
if [ -f /run/reboot-required ]; then cat /run/reboot-required; else echo "No reboot required"; fi
```

### 第二次更新结果与重启检查（待完成）

用户随后提供更新过程截图：出现键盘布局配置界面，已指导保留 English (US)；出现 `/etc/ssh/sshd_config` 配置文件选择，已指导选择 `keep the local version currently installed` 保留当前 SSH 登录设置。

最新截图显示软件设置及触发器处理已结束，needrestart 检查后回到 root 命令提示符。运行内核提示已是最新版本，但多个系统服务延期重启，SSH/bash 会话仍使用旧程序。自动重启 `aegis.service` 时报告控制进程收到致命信号；结合阿里云环境及官方 Aegis 客户端说明，推断该服务属于阿里云安全客户端，具体失败原因尚未有服务日志支持。不能将其表述为更新全部无异常或断言重启一定恢复。

下一步在当前无应用负载的演练服务器执行 `reboot`。SSH 暂时断开属预期；等待约 1–2 分钟后重新连接，执行：

```bash
dpkg --audit
systemctl --failed --no-pager
systemctl status aegis.service --no-pager -l
```

`dpkg --audit` 无输出表示未发现软件包未完成配置问题；结合服务状态确认更新后的运行情况。若 Aegis 仍失败，再读取 `journalctl -u aegis.service -n 50 --no-pager` 和服务定义判断原因。重启与检查结果待用户提供。

参考：[阿里云安全客户端说明](https://help.aliyun.com/zh/security-center/install-the-security-center-agent)、[Ubuntu 更新与服务重启说明](https://ubuntu.com/server/docs/how-to/software/automatic-updates/)。

### 重启后实际结果（已完成）

用户截图确认已执行 `reboot`，SSH 断开后重新连接成功。欢迎信息变为 Ubuntu 24.04.5 LTS，内核仍为 6.8.0-63-generic，提示当前 0 项可立即更新。`dpkg --audit` 无输出，`systemctl --failed --no-pager` 返回 `0 loaded units listed`。说明当前未发现软件包配置不完整及失败状态的服务。截图没有包含 `systemctl status aegis.service`，因此未单独确认 Aegis 的 active 状态及其先前失败原因。

## 第三步：安装 Java 17（已完成）

发布包计划在本地构建，服务器运行后端 JAR，因此先安装 Java 17 无图形运行环境，不要求服务器安装 Maven 或编译工具：

```bash
apt install -y openjdk-17-jre-headless
java -version
```

预期版本输出包含 `openjdk version "17..."`。具体补丁版本与安装结果以实际输出记录。Docker、MySQL、Nginx 和 Wren 环境后续分别安装验证。

参考：[Ubuntu 24.04 OpenJDK 17 运行环境软件包](https://packages.ubuntu.com/noble/openjdk-17-jre-headless)。

用户截图确认 `java -version` 输出 OpenJDK 17.0.20.1，Runtime/VM build 为 `17.0.20.1+1-1~24.04-Ubuntu`。安装后的检查提示没有服务、容器或会话需要重启。

## 第四步：安装 Docker Engine（已完成，沙箱验证待执行）

使用阿里云提供的 Docker CE 软件包镜像源，匹配本机已确认的 amd64 架构和 Ubuntu 24.04（noble）。保留软件源签名校验，当前 root 会话逐行执行，每条成功后继续：

```bash
apt install -y ca-certificates curl
install -m 0755 -d /etc/apt/keyrings
curl -fsSL --connect-timeout 15 --max-time 60 https://mirrors.aliyun.com/docker-ce/linux/ubuntu/gpg -o /etc/apt/keyrings/docker.asc
chmod a+r /etc/apt/keyrings/docker.asc
echo "deb [arch=amd64 signed-by=/etc/apt/keyrings/docker.asc] https://mirrors.aliyun.com/docker-ce/linux/ubuntu noble stable" > /etc/apt/sources.list.d/docker.list
apt update
apt install -y docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin
systemctl enable --now docker
docker version
docker compose version
```

下载公钥、刷新源或安装报错时暂停，不继续执行依赖该步骤的命令。当前未检查到 Docker 命令，没有执行卸载操作；如实际安装出现冲突再检查已装软件包。安装结果、版本和服务状态尚待用户提供。

`docker version` 同时返回 Client/Server 信息表示客户端可连接本机 Docker 服务；这只确认 Docker 基础服务，不表示项目分析镜像、沙箱或镜像拉取已验证。后续使用项目沙箱镜像进行运行验证。

参考：[Docker 官方 Ubuntu 安装说明](https://docs.docker.com/engine/install/ubuntu/)、[阿里云 Docker CE 镜像说明](https://developer.aliyun.com/mirror/docker-ce/)。

用户截图确认已安装并执行 `systemctl enable --now docker`：Docker Client/Server 版本均为 29.9.0，API 为 1.56（服务端最低 API 1.40），linux/amd64；containerd 2.4.1，runc 1.5.2；Docker Compose 为 5.6.0。客户端连接服务端成功。项目沙箱实际运行、资源限制及镜像导入/构建尚待验证。

## 第五步：安装 MySQL（已完成）

云演练在同一服务器安装 MySQL 服务，作为项目元数据和上传数据存储。公司部署时使用已有 MySQL 服务，需替换连接配置并核实权限。当前阶段只安装和检查服务，数据库及专用账户后续创建。

```bash
apt install -y mysql-server
systemctl enable --now mysql
systemctl is-active mysql
mysql -e "SELECT VERSION() AS mysql_version;"
```

逐行执行。预期服务状态为 `active`，SQL 返回实际服务端版本；安装、服务状态和查询结果尚待用户截图。数据库网络访问、专用账户及备份配置仍待处理。

参考：[Ubuntu 官方 MySQL 安装说明](https://ubuntu.com/server/docs/how-to/databases/install-mysql/)。

用户截图确认 `systemctl enable --now mysql` 执行完成，`systemctl is-active mysql` 输出 `active`，以 root 本机执行版本查询成功，实际服务端版本为 `8.0.46-0ubuntu0.24.04.4`。

## 第六步：创建项目数据库（已完成）

核对当前 `src/main/resources/application.yml`：平台数据库默认名称为 `dataagent_platform`，数据集数据库默认名称为 `data_agent`。前者保存用户、知识库、会话及模型元数据，后者保存上传文件生成的数据表。按当前项目名称创建两个空库，使用 utf8mb4 字符集。

在服务器 Linux 提示符执行 `mysql`，进入 `mysql>` 后逐条执行：

```sql
CREATE DATABASE IF NOT EXISTS dataagent_platform CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE DATABASE IF NOT EXISTS data_agent CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
SHOW DATABASES;
EXIT;
```

仅创建空库，不导入本地真实数据。专用数据库账户、密码、访问范围和项目连接配置下一阶段设置；不要将密码或带密码的命令截图发送到会话。数据库创建结果仍待用户截图确认。

参考：[MySQL CREATE DATABASE 说明](https://dev.mysql.com/doc/refman/8.0/en/create-database.html)。

用户截图确认两条建库语句均返回 `Query OK`，`SHOW DATABASES` 包含 `dataagent_platform` 和 `data_agent`，随后已退出 MySQL。

## 第七步：创建项目数据库账户（登录及库访问已验证）

演练后端及 Wren 均在本机运行，使用 `dataagent_app@localhost` 账户访问两个项目库。密码由用户自行设置保存，本记录仅保留占位符。进入 `mysql` 后执行下列语句，创建用户时替换密码占位符，保留字符串单引号；密码不包含单引号或反斜杠，以便当前手工 SQL 输入。

```sql
CREATE USER 'dataagent_app'@'localhost' IDENTIFIED BY '替换为自己保存的强密码';
GRANT ALL PRIVILEGES ON `dataagent\_platform`.* TO 'dataagent_app'@'localhost';
GRANT ALL PRIVILEGES ON `data\_agent`.* TO 'dataagent_app'@'localhost';
EXIT;
```

库名中的下划线在 MySQL 8 数据库级 GRANT 中有通配符含义，所以用反斜杠转义。该操作授予两个项目库的权限，不授予全局权限及转授权权限。当前新装实例采用默认的权限解释方式；公司现有实例需核实 `partial_revokes` 等设置。使用 CREATE USER/GRANT 后权限即生效，无需执行 FLUSH PRIVILEGES。

退出后在 Linux 提示符验证 TCP 登录与两个库的访问，`-p` 要求交互输入密码，不将密码写入 shell 命令：

```bash
mysql -h 127.0.0.1 -u dataagent_app -p -e "USE dataagent_platform; SELECT DATABASE(); USE data_agent; SELECT DATABASE();"
```

预期分别返回两个库名。截图仅保留验证结果，不包含创建账户时的密码。账户及授权执行结果仍待确认，后续连接配置使用本机地址与该账户。

参考：[MySQL CREATE USER](https://dev.mysql.com/doc/refman/8.0/en/create-user.html)、[MySQL GRANT](https://dev.mysql.com/doc/refman/8.0/en/grant.html)。

用户要求生成演练密码，已使用加密随机数生成器生成并在当前会话提供。本记录不保存实际密码，正式上线使用公司数据库管理页面提供的账密。

最新用户截图确认通过 `127.0.0.1`、`dataagent_app` 和交互密码登录成功，依次 `USE` 两个库并查询 `DATABASE()`，返回 `dataagent_platform` 和 `data_agent`。账户的 TCP 登录及两个库访问已验证；尚未通过该账户实际执行建表、写入、删表，完整 DDL/DML 权限在后续项目初始化与上传删除演练中验证。

## 第八步：安装 Nginx（已完成）

前端使用 Nginx 提供静态页面，API 请求转发到本机后端。本阶段先安装并确认默认站点的本机 HTTP 响应，项目站点、API/SSE 代理及公网访问规则在上传发布包时配置。

```bash
apt install -y nginx
systemctl enable --now nginx
nginx -v
curl -I --connect-timeout 5 --max-time 15 http://127.0.0.1
```

验收标准为输出 Nginx 版本，HTTP 默认站点返回 `200 OK`。结果见下方截图记录。Wren venv、项目分析镜像和发布包尚未部署。

参考：[Ubuntu 官方 Nginx 安装说明](https://ubuntu.com/server/docs/how-to/web-services/install-nginx/)。

用户截图确认安装完成，`systemctl enable --now nginx` 执行成功，`nginx -v` 输出 `nginx/1.24.0 (Ubuntu)`，本机 `curl -I` 返回 `HTTP/1.1 200 OK`。已验证默认站点，本项目前端及 API 代理尚未配置。

## 第九步：应用运行账户与固定目录（已完成）

由当前 root 管理会话创建 Linux 系统账户 `dataagent`，用于后端、Wren 和沙箱管理。该账户与 MySQL 的 `dataagent_app`、页面登录的 admin 分属不同系统。无需设置 Linux 登录密码；root 后续使用 `runuser` 执行应用命令。Docker 组权限授予这个受信应用账户，具有接近 root 的主机控制能力，不授予页面用户。

在 Xshell 的 root 提示符逐条执行（本演练此前尚未创建该账户）：

```bash
useradd --system --user-group --create-home --home-dir /home/dataagent --shell /bin/bash dataagent
usermod -aG docker dataagent
install -d -m 0755 /opt/dataagent
install -d -o dataagent -g dataagent -m 0755 /opt/dataagent/releases /opt/dataagent/venvs
install -d -o dataagent -g dataagent -m 0750 /opt/dataagent/conf /var/lib/dataagent /var/log/dataagent
id dataagent
runuser -u dataagent -- docker info --format '{{.ServerVersion}}'
```

目录用途：`releases` 保存版本发布包；`venvs` 保存服务器本机创建的 Python 环境；`conf` 保存固定配置；`/var/lib/dataagent` 保存上传、模型、会话工作区、分析产物等持久数据；`/var/log/dataagent` 保存日志。具体数据子目录及应用配置在发布阶段确定。固定数据不放在版本发布包中，更新不覆盖。

2026-10-12 用户截图确认创建账户、授权及目录创建命令均执行完毕，未显示错误。`id dataagent` 返回 `uid=999(dataagent) gid=987(dataagent) groups=987(dataagent),988(docker)`；以 `dataagent` 运行 `docker info` 返回服务端版本 `29.9.0`。已验证应用账户访问 Docker；目录创建命令成功，尚未分别检查目录权限及实际文件写入。

当前项目 `requirements-wren.txt` 声明 `wrenai[memory-onnx]==0.15.0`。后续按该依赖文件准备 Wren 环境并检查实际 CLI 和依赖兼容性，不直接沿用旧文档中的无版本安装命令。本步骤尚未安装 Wren。

参考：[Docker 官方非 root 账户配置](https://docs.docker.com/engine/install/linux-postinstall/)、[Python 官方 venv 说明](https://docs.python.org/3/library/venv.html)。

### 2026-10-12 阶段安排

最新发布方式：用户要求按已有项目使用 Maven package 直接生成 target/*.tar.gz，包内 conf/bin/lib；dev/prod YAML 从 resources 原样带入 conf，日常只需 start/stop。Python、example、prepare/check 和本轮 Flyway 原型已撤掉。下次演练按 [简单发布说明](manual-release-guide.md) 执行，不再沿用此前原型命令。

后续项目阶段已实施发布基础，用户改为 `/data/app/dataagent/dataagent-<版本>/{bin,lib,conf,log}`，公共持久数据为父目录 data/，Wren环境为父目录venvs/。此前创建的 /opt、/var 目录仍留在服务器，本轮未操作或删除；下一次云演练由 start 自动准备新布局。参见 [简单发布指南](manual-release-guide.md)。

用户决定先完成与项目迭代关系较小的基础环境，再完善项目核心流程，最后执行完整云部署演练。当前基础准备告一段落，服务器配置先停在此处。已准备 Java、Docker、MySQL、两个空数据库、数据库账户、Nginx、Linux 应用账户及固定目录。Python 已存在，但项目专用 venv/Wren 依赖、分析镜像、发布包、生产配置和项目 Nginx 站点均尚未部署，不能视为项目已经可运行。

下一阶段回到项目梳理上传与数据库关联、语义建模与发布、问答、用户及知识库权限、删除清理，以及打包、生产配置、启停脚本和持久路径。形成稳定可部署版本后，继续云服务器环境安装及上传启动演练。

### 首次上线：自动建库与自动建表的区别

按当前代码，平台连接 URL 默认带 `createDatabaseIfNotExist=true`，因此在使用该 URL 且数据库账户具备创建目标库权限时，Connector/J 可以创建缺失的 `dataagent_platform`。外置生产 URL 覆盖后是否保留这一参数必须核实。数据集连接默认未带该参数，`TableProvisioner` 直接连接已配置的 `data_agent` 并创建表，没有创建数据库逻辑，故不能依赖项目自动创建两个库。

首次上线采用明确的准备步骤：提前创建平台库及数据集库，配置专用账户权限，再启动应用。当前平台 `ddl-auto` 默认 `update`，管理表由 Hibernate 在启动时创建或更新；上传 Excel/CSV 时由 `TableProvisioner` 在数据集库创建导入表。上述自动建表都要求相应 DDL 权限。若生产切换为 `validate`，需另行先执行迁移创建管理表，不再由启动自动建表。

数据库创建仅需首次执行；正常更新、重启和新增知识库不重新创建或清空数据库。两个名称由外置连接配置决定，可以由运维采用其他名称并同步配置。导入表的当前 DDL 显式使用 `utf8mb4_0900_ai_ci`，因此不依赖数据库的默认排序规则；正式准备库时一并核实字符集及表排序规则。

### 首次登录提示

- `/usr/bin/xauth: file /root/.Xauthority does not exist`：X11 图形认证文件提示。结合登录场景，可能由 Xshell 启用 X11 转发触发；当前命令行已经登录成功。部署不需要图形转发，若反复出现可在会话属性「连接 → SSH → 隧道」中关闭 X11 转发。
- 288 项可更新、233 项标准安全更新：登录时报告的软件更新提示，后续在安装环境前处理当前 Ubuntu 24.04 的软件更新。
- Ubuntu 26.04.1 新版本提示：当前演练继续使用 Ubuntu 24.04，不执行 `do-release-upgrade`。
- ESM Apps 未启用：Ubuntu 可选的扩展维护服务提示，当前演练不需要订阅。

后续每个阶段记录执行命令、脱敏后的配置、实际结果、故障原因和恢复方式；仅经实际验证的步骤写入最终上线手册。

## 2026-10-13：云 MySQL 作为模拟业务数据源（本轮数据源闭环通过）

用户确认首发单库单来源、分库建模、多库问答，并开始云端外部源验收。项目仍在本地运行；本阶段先用云 MySQL 模拟业务库，之后再考虑 Doris。

- 本地已只读转换四个电商 Excel，生成 `output/cloud-mysql-verification/mysql-ecommerce-1013.sql`，约 300 KB。源 SHA-256 未变。
- 用户截图确认已导入独立库 `dataagent_verify_ecommerce_1013`，四表 customers/products/orders/order_items，实际 200/100/1500/4339 行。
- 用户截图确认新账号 `da_verify_ro_1013@localhost` 已创建，SHOW GRANTS 仅含 USAGE 和 SELECT 测试库。测试凭证、完整操作说明与预期结果放在 Git 忽略的夹具目录，不写入此文。
- 脚本采用创建新库方式，没有 DROP/TRUNCATE；若库已存在则停止执行，避免反复导入。项目现有两库及 dataagent_app 不调整。
- 独立源数据核对：仅排除已取消订单，明细 amount 汇总 66,728,198.85，订单数 1,399，客单价 47,697.07。金额不再乘折扣系数。
- 连接计划为 SSH 本地转发 127.0.0.1:13306 → 云端 127.0.0.1:3306，供本地应用和 Wren 使用；不需改变云 MySQL 的公网监听设置。
- 上传期间发现两个问题：本地 `.ssh/config` 权限校验失败，通过本次命令 `-F NUL` 绕过；root 仅提供公钥认证，本地生成演练专用密钥，由用户在已登录的 Xshell 登记公钥，再显式指定私钥完成 scp。专用密钥位于 Git 忽略目录，未修改现有 SSH 配置或其他密钥。
- 用户截图确认 scp 上传 100%，云端执行 SQL 成功，四表行数、总额、订单数、客单价及月度结果与独立预期一致。数据准备完成，尚未建立本地隧道或连接应用；建模/共享问答仍待验证。
- 后续用户在本地开启 SSH 转发；已确认 127.0.0.1:13306 监听，使用当前项目的真实 test-connection 接口验证云 MySQL 登录。页面默认 DISABLED 失败（Public Key Retrieval is not allowed），PREFERRED 成功；后续添加源选择“优先加密”。这是 MySQL 连接认证设置，不修改 SSH 或应用源码。接口检查未保存数据源，关联/建模/发布/共享问答仍待验证。
- 用户页面保存数据源成功并显示已连通；保存的实际加密模式为 REQUIRED，该配置下接口读取元数据成功。四张表及 27 个字段均与源夹具一致，字段中文备注完整。当前通过到保存和字段探查，创建知识库、关联、建模、正式问答尚待后续步骤。
- 用户随后创建独立知识库“云端MySQL电商验收1013”（265612e7-0976-4d74-bdfa-74d9c32919eb），四张关联表行数/字段数正确，基础模型 v1 发布成功；普通问答四表计数正确。读取实际会话工具记录确认 wren_run_sql 执行 COUNT 子查询并返回 200/100/1500/4339，使用该库已发布 v1；不是仅以回答文案作为查询证明。关联、基础编译发布和云 MySQL 查询通过，复杂业务关系/视图、共享用户、撤权、跨来源问答及附件下载仍待验收。
- 对话新增三组多对一关系和 sales_detail 后，工程校验报 1049 Unknown database。已复现是试跑使用默认本地连接，而正式问数使用云 profile：同一编译草稿默认 dry-run 失败，显式云连接 dry-run 成功；云源草稿视图实际销售额/订单数/客单价匹配 66,728,198.85/1,399/47,697.07。模型及发布文件哈希未变，云数据和全局 profile 未改。外部源业务发布被原有建模连接路由缺陷阻断，按约定先记录，不自动修复。证据见 Git 忽略目录的 external-profile-diagnosis.json。

2026-10-13 后续经用户确认已修复该连接路由缺陷。校验和发布的 SQL 试跑显式使用当前知识库连接，以临时 JSON 文件传递并在结束后清理；单来源限制及原工程门禁保留。全量测试 533 项，0 失败、0 错误、12 跳过。使用现有草稿副本、真实云 MySQL、Wren CLI 和 JDBC 探查器运行校验及隔离发布成功，sales_detail 三项指标与预期一致。原知识库文件哈希不变，原应用发布版本仍 v1；后端由用户启动，未替用户停止或重启。待重启后执行页面检查与发布，再继续共享用户/多库问答验收。隔离证据：output/cloud-mysql-verification/repair-acceptance-evidence.json（Git 忽略目录）。

随后用户复测反馈改动已经正常，本项连接阻断已确认修复。上段 v1/待重启是助手隔离测试时的状态；用户未提供复测后的最终发布版本及业务指标证据，后续继续确认业务发布、指标、共享与撤权、附件及多库问答，不重复要求重启。

后续用户授权助手完成 MySQL 验收，原库页面正式发布 **v2** 成功、无待发布差异。实际 Wren 查询及 CSV 核对三项合计、九个月结果一致；共享 QUERY/MANAGE、降级/撤权/恢复、本人历史及附件、跨来源独立查询、混合/多来源拒绝、取消关联保护和错误密码探测均通过，共 110 项具名检查。取消关联仅操作新建独立验收库，随后重新关联 products 并保留；主库和原四张物理表保持完整。验收后云端直接 SQL 与源 Excel SHA-256 再次核对一致。详细资产、证据和结果见 [体验结果](experience-results-20261013.md#后续云-mysql-实际应用完整验收)。

发现一次普通问答最终内容仅“两个库已分”，SQL 已查出两库各 100，页面标完成且历史同样不完整；两次接口和一次页面重试正常。原因待定位，本轮不改代码。该观察独立于已经通过的 MySQL 查询链路，作为上线前 P1 保留。

## Doris 下一阶段具体操作步骤（准备完成，未安装）

本阶段继续“本地项目 → SSH 隧道 → 云端测试业务库”，在云服务器安装单 FE + 单 BE，用于验证兼容性。用户暂时无法获取公司版本，已授权选一个版本先测试，本轮固定 **Doris 3.0.8**（官方 3.x 快速入门示例版本）；得到公司版本和表模型后再回归。单机小数据结果不能代替公司集群或生产容量验证。当前产品只有 MySQL/PostgreSQL 数据源类型，先通过 MySQL 协议验证 Doris 连接、元数据、类型和 Wren 执行，成功之前不标为支持 Doris。

**当前执行路径改为官方二进制包安装。** 后续网络预检发现云端和本地 Docker Hub manifest 请求均超时；VeloDB 镜像下载地址返回 403，官网指向的官方 OSS 地址返回 200，SHA-512 文件可读取。二进制包大小 4,278,678,794 字节（约 4 GB）；云端 `/usr/lib/jvm/java-17-openjdk-amd64` 可用，CPU 支持 AVX2。没有执行下载安装。以下 Docker 步骤保留作备选，当前先执行文末“官方安装包第一阶段”，不要同时启动两种部署。

### 第一步：确定版本和服务器准备

当前直接使用准备好的 3.0.8 配置开展测试。以后确认公司版本时，优先取 `SHOW FRONTENDS` / `SHOW BACKENDS` 的 Version 列；`SELECT VERSION()` 部分实例只返回 MySQL 协议版本，需与 Doris 实际版本核对。

助手已只读检查云服务器：4 核、约 13 GiB available、71 GiB 可用磁盘、Docker 暂无运行容器，9030 等 Doris 端口未占用，`vm.max_map_count=1048576`。安装前按官方要求提高到 2000000；云防火墙保持 Doris 端口未向公网开放，本地用隧道。

以下命令在 **Xshell 云服务器终端**执行：

```bash
install -d -m 0755 /data/app/doris-verification
printf 'vm.max_map_count=2000000\n' > /etc/sysctl.d/99-doris-verification.conf
sysctl -p /etc/sysctl.d/99-doris-verification.conf
cd /data/app/doris-verification
```

### 第二步：上传准备好的文件

本地已准备四个文件，均在 Git 忽略目录 `output/cloud-mysql-verification/`：

- `docker-compose-doris.yaml`：按官方 FE/BE 环境参数配置，在当前目录持久保存元数据、存储及日志，镜像固定为 fe-3.0.8 / be-3.0.8。
- `doris-ecommerce-1013.sql`：新库 `dataagent_verify_doris_1013`、四张 OLAP 表及与 MySQL 完全相同的 6,139 条源记录；单副本、一桶，使用 DUPLICATE KEY，不添加 MySQL 的 InnoDB/FK 定义。未在 Doris 执行。
- `doris-user-private.sql`：独立测试账号和该测试库的 SELECT_PRIV 授权，含密码，不进入 Git。
- `doris-connection-private.json`：用于项目页面填写的测试连接参数，含密码，不进入 Git。

下面在 **本地 Windows PowerShell**执行，把前三个文件上传云端：

```powershell
$fixtureDir = 'D:\code\agentscope\agentscope-java\output\cloud-mysql-verification'
scp -F NUL -o IdentitiesOnly=yes -i "$fixtureDir\cloud-verify-ed25519" "$fixtureDir\docker-compose-doris.yaml" "$fixtureDir\doris-ecommerce-1013.sql" "$fixtureDir\doris-user-private.sql" root@8.137.156.84:/data/app/doris-verification/
```

### 第三步：启动 Doris，确认 FE/BE 健康

回到 **Xshell**。先用固定 3.0.8 镜像启动，再检查 FE/BE 状态；本地文件仅完成准备和配置解析，实际下载/启动结果以云端输出为准。

```bash
cd /data/app/doris-verification
docker compose -f docker-compose-doris.yaml config --quiet
docker compose -f docker-compose-doris.yaml pull
docker compose -f docker-compose-doris.yaml up -d
docker compose -f docker-compose-doris.yaml ps
mysql --ssl-mode=DISABLED -h127.0.0.1 -P9030 -uroot -e 'SHOW FRONTENDS; SHOW BACKENDS;'
```

FE 的 Join/Alive 和 BE 的 Alive 应为 true。只看到容器 Running 还不够；未健康先看 `docker compose -f docker-compose-doris.yaml logs --tail=100`。FE 的 9030 为 MySQL 协议查询端口，与已有 MySQL 3306 分开。持久目录在此验证目录内，暂时停用可执行 `docker compose -f docker-compose-doris.yaml stop`，保留测试数据。

### 第四步：导入同一批数据，创建测试账号

仍在 **Xshell**执行一次：

```bash
cd /data/app/doris-verification
mysql --ssl-mode=DISABLED --default-character-set=utf8mb4 -h127.0.0.1 -P9030 -uroot < doris-ecommerce-1013.sql
mysql --ssl-mode=DISABLED --default-character-set=utf8mb4 -h127.0.0.1 -P9030 -uroot < doris-user-private.sql
```

脚本创建独立新库；库已存在时先核对先前导入，不反复执行 INSERT，以免 DUPLICATE KEY 重复累计。期望四表 200/100/1500/4339；合计 66,728,198.85 / 1,399 / 47,697.07。DDL 和授权写法参照当前官方文档；实际执行结果仍待对应版本验证。

### 第五步：建立本地隧道，添加项目数据源

保持原 MySQL 隧道。另开 **本地 PowerShell**，运行并保持该窗口：

```powershell
ssh -F NUL -o IdentitiesOnly=yes -i 'D:\code\agentscope\agentscope-java\output\cloud-mysql-verification\cloud-verify-ed25519' -N -L 127.0.0.1:19030:127.0.0.1:9030 -o ExitOnForwardFailure=yes -o ServerAliveInterval=30 root@8.137.156.84
```

项目页面添加「云端Doris电商验收1013」，暂按 MySQL 协议路径填写：主机 127.0.0.1、端口 19030、库名 dataagent_verify_doris_1013、测试账号/密码取私有 JSON、加密方式 DISABLED。这里的传输由 SSH 隧道提供。若本地 external-datasource.allowed-endpoints 非空，要先追加 `127.0.0.1:19030` 并重启后端，否则被地址策略拒绝属于配置前置条件。

### 第六步：交给助手完成项目兼容性验收

测试连接 → 四表元数据/中文注释/DATE/DECIMAL/BIGINT → 新知识库关联 → 基础模型发布 → 三条关系和销售视图 → 工程校验/发布 → admin 与共享用户问答 → 撤权/恢复/附件 → 与 MySQL 和上传库多选对照 → 独立取消关联核对物理表保留。

特别核对 MySQL JDBC 在 Doris 上的只读会话设置、information_schema 探查、Wren 编译后的函数/物理 SQL、聚合金额精度和权限语法。遇到失败先收集 Doris/Wren/JDBC 原始错误，区分配置、协议、元数据与语义执行问题，再讨论最小适配；通过前不会直接添加“Doris 已支持”说明。

参考：[官方 3.x 快速入门](https://doris.apache.org/docs/3.x/gettingStarted/quick-start/)、[CREATE TABLE](https://doris.apache.org/docs/3.x/sql-manual/sql-statements/table-and-view/table/CREATE-TABLE/)、[GRANT TO](https://doris.apache.org/docs/3.x/sql-manual/sql-statements/account-management/GRANT-TO/)。本节为待执行指南，助手目前只读取云环境并准备本地夹具，没有安装 Doris、修改云内核参数或启动容器。

### 当前执行：官方安装包第一阶段

在 Xshell 云服务器终端执行下载和校验，继续保留现有 MySQL 服务及隧道：

```bash
mkdir -p /data/app/doris-verification
cd /data/app/doris-verification
curl -fL --retry 3 --connect-timeout 15 -C - -o apache-doris-3.0.8-bin-x64.tar.gz https://apache-doris-releases.oss-accelerate.aliyuncs.com/apache-doris-3.0.8-bin-x64.tar.gz
curl -fL --retry 3 --connect-timeout 15 -o apache-doris-3.0.8-bin-x64.tar.gz.sha512 https://apache-doris-releases.oss-accelerate.aliyuncs.com/apache-doris-3.0.8-bin-x64.tar.gz.sha512
sha512sum -c apache-doris-3.0.8-bin-x64.tar.gz.sha512
```

期望最后显示 `apache-doris-3.0.8-bin-x64.tar.gz: OK`。下载中断时同一条 `curl -C -` 可续传，校验通过后进入解压和 FE/BE 配置；若校验失败先保留输出，不执行解压或启动。

官网源码中的 `ORIGIN` 和 3.0.8 x64 文件名已核对：[官方发布文件定义](https://github.com/apache/doris-website/blob/master/src/constant/download.data.ts)。访问和环境检查证据在 Git 忽略目录的 `doris-fe-manifest-check.json`、`doris-local-fe-manifest-check.json`、`doris-oss-download-check.txt`、`doris-binary-download-check.txt`。本阶段后续顺序为解压及配置 → 启动 FE/BE 并检查 Alive → 上传并导入已经准备的 SQL → 建立 19030 隧道 → 助手验证项目完整链路。具体启动参数在读取实际安装包配置后确定，避免假设某个镜像配置适用于原生包。

### 官方安装包第二阶段：解压、配置及 FE 启动

2026-10-14 用户确认 SHA-512 校验显示 OK。助手只读核对 tar 目录，实际顶层为 `apache-doris-3.0.8-bin-x64/`，尚未解压；读取安装包的 FE/BE 默认配置保存到忽略目录 `doris-3.0.8-default-configs.txt`。实际 FE 的 Java 17 参数为 Xms/Xmx 8192m，BE 的 Java 17 参数 Xmx 2048m；本测试机调整为 FE Xms 512m / Xmx 2048m、BE JVM Xmx 512m，BE 原生内存上限设置 40%。这是小数据兼容测试参数，不作为生产容量建议。

以下在 Xshell 执行。先解压，命令结束后再执行后续块：

```bash
cd /data/app/doris-verification
tar -xzf apache-doris-3.0.8-bin-x64.tar.gz
cd apache-doris-3.0.8-bin-x64
mkdir -p fe/doris-meta be/storage
```

设置内核参数及当前终端句柄上限：

```bash
printf 'vm.max_map_count=2000000\n' > /etc/sysctl.d/99-doris-verification.conf
sysctl -p /etc/sysctl.d/99-doris-verification.conf
ulimit -n 1000000
```

按包内实际默认值缩减 JVM，并添加 Java/单机地址配置。下列配置块执行一次；原默认 JAVA_HOME、priority_networks 和 mem_limit 均未显式配置，sed 保留所有其他 JVM 参数：

```bash
sed -i 's/-Xmx8192m/-Xmx2048m/g; s/-Xms8192m/-Xms512m/g' fe/conf/fe.conf
sed -i 's/-Xmx2048m/-Xmx512m/g' be/conf/be.conf

cat >> fe/conf/fe.conf <<'EOF'

JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
priority_networks=127.0.0.1/32
EOF

cat >> be/conf/be.conf <<'EOF'

JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
priority_networks=127.0.0.1/32
mem_limit=40%
EOF
```

先启动 FE（负责 SQL 接入与协调），随后手动等待约 10—20 秒再执行健康查询：

```bash
bash fe/bin/start_fe.sh --daemon
```

```bash
mysql --ssl-mode=DISABLED -h127.0.0.1 -P9030 -uroot -e 'SHOW FRONTENDS\G'
```

期望 Version 为 3.0.8 对应版本，Join=true、Alive=true、IsMaster=true。若连接失败，读取 `tail -n 80 fe/log/fe.out` 和 `tail -n 80 fe/log/fe.log`，先定位错误再启动 BE。此阶段配置与启动命令已交付，尚未由助手执行，实际结果待用户反馈。

### 官方安装包第三阶段：FE 已健康，启动并注册 BE

2026-10-14 用户提供 SHOW FRONTENDS 输出：Host=127.0.0.1、QueryPort=9030、Join/Alive/IsMaster 均 true、Version=`doris-3.0.8-rc01-09b0cc49a6`。FE 阶段通过。助手只读核对当前 BACKENDS 为空；带 Java 17 lib/server 路径执行 ldd，BE 二进制依赖均可找到。用户配置的 Java 路径在启动时由脚本加载。

以下在 Xshell 执行，BE 负责存储和实际计算，FE 需要登记其心跳地址 9050：

```bash
cd /data/app/doris-verification/apache-doris-3.0.8-bin-x64
ulimit -n 1000000
bash be/bin/start_be.sh --daemon
```

随后注册一次：

```bash
mysql --ssl-mode=DISABLED -h127.0.0.1 -P9030 -uroot -e "ALTER SYSTEM ADD BACKEND '127.0.0.1:9050';"
```

手动等待约 10—20 秒，再查：

```bash
mysql --ssl-mode=DISABLED -h127.0.0.1 -P9030 -uroot -e 'SHOW BACKENDS\G'
```

期望 Host=127.0.0.1、Alive=true、Version 对应 3.0.8，ErrMsg 为空。若启动失败或 Alive 持续 false，先读取 `tail -n 80 be/log/be.out` 与 `tail -n 80 be/log/be.INFO`；保留实际输出供定位。BE 启动/注册尚未由助手执行，成功后才导入测试数据。

### 官方安装包第四阶段：BE 已健康，上传及导入测试数据

2026-10-14 用户提供 SHOW BACKENDS：Host=127.0.0.1、Alive=true、ErrMsg 为空、HeartbeatFailureCounter=0，Version 与 FE 一致。FE/BE 启动和注册阶段通过，尚未完成业务数据或项目连接验收。

使用已准备好的 `doris-ecommerce-1013.sql` 和 `doris-user-private.sql`，账号文件与私有连接 JSON 一致，后者只赋测试库 SELECT_PRIV。上传命令在本地 PowerShell，导入命令在 Xshell；数据库创建/INSERT 只执行一次，若任一步失败先保留输出，不重新运行整批数据脚本。

本地 PowerShell：

```powershell
$fixtureDir = 'D:\code\agentscope\agentscope-java\output\cloud-mysql-verification'
scp -F NUL -o IdentitiesOnly=yes -i "$fixtureDir\cloud-verify-ed25519" "$fixtureDir\doris-ecommerce-1013.sql" "$fixtureDir\doris-user-private.sql" root@8.137.156.84:/data/app/doris-verification/
```

上传成功后，在 Xshell 先导入数据：

```bash
cd /data/app/doris-verification
mysql --ssl-mode=DISABLED --default-character-set=utf8mb4 -h127.0.0.1 -P9030 -uroot < doris-ecommerce-1013.sql
```

预期四表行数 customers=200、products=100、orders=1500、order_items=4339，销售额/订单数/客单价为 66728198.85 / 1399 / 47697.07。数据导入成功后创建测试账号：

```bash
mysql --ssl-mode=DISABLED --default-character-set=utf8mb4 -h127.0.0.1 -P9030 -uroot < doris-user-private.sql
```

完成后再建立 19030 隧道、添加项目数据源。本阶段尚未由助手导入数据或创建 Doris 账号，待用户执行反馈。

### 官方安装包第五阶段：数据和账号已导入，建立本地隧道

2026-10-14 用户截图确认数据脚本完成：order_items=4339、orders=1500、products=100、customers=200；销售额 66728198.85、订单数 1399、客单价 47697.07。账号脚本执行后无报错，返回命令提示符。Doris 原生数据准备通过，项目 JDBC/Wren 兼容性仍待隧道建立后验收。

本地 Windows PowerShell 另开一个窗口，保留 MySQL 的 13306 隧道，执行：

```powershell
ssh -F NUL -o IdentitiesOnly=yes -i 'D:\code\agentscope\agentscope-java\output\cloud-mysql-verification\cloud-verify-ed25519' -N -L 127.0.0.1:19030:127.0.0.1:9030 -o ExitOnForwardFailure=yes -o ServerAliveInterval=30 root@8.137.156.84
```

成功后没有新提示符或输出是正常现象，保持该窗口运行。另一个 PowerShell 窗口可执行 `Test-NetConnection 127.0.0.1 -Port 19030` 检查 TcpTestSucceeded。助手随后接手连接、元数据、新库关联、基础与业务发布、共享问答及多库对照；连接账号/密码从 Git 忽略的 doris-connection-private.json 读取，不写入版本化文档。项目目前采用 MySQL 协议测试路径，不能仅因原生 SQL 成功认定项目已支持 Doris。

### 官方安装包第六阶段：项目完整验收通过

2026-10-14 用户确认 TcpTestSucceeded=True，助手在当前运行的本地应用完成 150 项具名检查。数据源「云端Doris电商验收1014」通过 MySQL 协议连接，四表/27 字段探查及关联成功；自动基础 v1 → 对话提案/HITL 采用三关系与 sales_detail → 工程校验 → admin 页面发布业务 v2。最终模型与草稿一致，QUERY 和 MANAGE 授权保留。

真实 Wren、共享用户及下载 CSV 的销售额/去重订单/客单价均为 66728198.85 / 1399 / 47697.07；九个月结果逐项一致。撤权/全选最新范围、私有历史附件、同会话切库/清空、多库工具路由通过。一次三选问答独立查询 Doris/MySQL/上传库，各自返回 101/100/200；取消关联测试后 products 源表仍在且重新关联行数 100。最终云端原生 SQL 复核源数据未变、FE/BE 健康、测试账号无业务写权限。具体检查与保留资产见 [Doris 体验结果](experience-results-20261013.md#后续doris-308-实际应用完整验收)。

继续体验时保持 19030 隧道；同时询问云 MySQL 时还需保留 13306 隧道。项目数据源类型仍选 MySQL，当前未新增 Doris 独立适配，所测服务版本为 doris-3.0.8-rc01-09b0cc49a6。此次未修改应用代码、未启停本地后端、未重新导入源数据，全部测试资产保留。该结果不代替公司版本/表模型/网络及大数据容量回归，也不代表云服务器项目 Linux 部署已完成；原回答完成性 P1 和暂缓建模问题仍在总账。
