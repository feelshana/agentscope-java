# 66拉取GitHub源码并构建镜像：分步指引

2026-10-29：用户选择本方案。用户已反馈源码拉取完成；源码更新、只构建和应用镜像入口已实现并通过本地验证；部署文件经本次提交统一交付；用户push后，66更新至新提交再调用这些脚本。原环境镜像已经准备，生产库信息仍未确认，不启动业务。

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
GIT_TERMINAL_PROMPT=0 timeout 30s git ls-remote https://github.com/feelshana/agentscope-java.git HEAD refs/heads/codex/integrate-boundary-lyf-v2
```

最后一条返回提交哈希及分支才证明Git读取成功；HTTP成功不等于Git成功。私有仓库报认证错误时需配置只读凭据，不能关闭TLS验证。不要把Token放URL或聊天中。Compose缺失不阻碍本次网络/源码检查，后续再确定是否安装。

## 二、Git读取成功后，可隔离拉取现有分支

新目录与生产data隔离；若目录已存在，不覆盖，不reset/clean，不自动切换既有checkout：

```bash
mkdir -p "$HOME/dataagent-source"
if [ -e "$HOME/dataagent-source/agentscope-java" ]; then
  echo '目录已存在，先核对现有内容；本次不覆盖'
else
  timeout 180s git clone --depth 1 --single-branch --branch codex/integrate-boundary-lyf-v2 \
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
timeout 90s git pull --ff-only origin codex/integrate-boundary-lyf-v2
cd agentscope-dataagent-standalone
bash docker/check-builder.sh
bash docker/build-from-source.sh "$(git rev-parse HEAD)"
```

check-builder.sh仅新建一个断网512MiB临时容器检查Java/javac/Maven，自动移除本轮检查容器，不挂载生产目录/socket，不启停服务。宿主Java8不改。

build-from-source.sh拒绝脏源码、未提交控制文件或不同于HEAD的指定提交；归档明确源码，构建控制文件也来自归档。默认~/dataagent-build下jobs保留每次源码、日志、JAR摘要与镜像信息，cache为项目独立Maven/npm缓存，不借用宿主~/.m2。默认2CPU/6GiB、3600秒上限，可显式配置DATAAGENT_BUILD_CPUS/MEMORY/TIMEOUT。

完整core/harness/BOM及直接使用扩展从同一提交构建；应用执行verify，前端固定Node20.19.2/npm10.9.2并执行npm ci，前缀默认/dify/dataagent/。仅构建容器使用阿里云Maven公共源与npmmirror的Node/npm源；不改全局源。公司代理或仓库如需定制，DATAAGENT_MAVEN_SETTINGS可指向私有设置文件，不把凭据提交或放入最终镜像。

成功后输出dataagent/app:<提交前缀>-<时间>-<本次标识>及本次build-result.txt。应用JAR在镜像/opt/dataagent/app；镜像仅包含白名单运行文件，没有源码/Maven/Node/私有生产配置。断网静态预检不等于数据库或业务启动通过。

后续更新可以执行bash docker/update-source.sh：仅fetch指定分支并快进，三次重试、单次90秒，有本地修改/错误分支/历史分叉就停止，不自动merge、reset、清理或启停服务。然后独立执行只构建脚本。

## 五、正式启动仍待后续实施

需要生产两库及初始化、外置runtime.env与/data/dataagent/conf/application-prod.yml、持久data/logs和离线模型缓存。新应用入口已支持外置路径；Compose启停、健康检查和回退仍待实现及实测，不自行套用旧tar入口或Dify脚本。

正式方案：[spec099](../specs/099-source-build-deployment.md)、[ADR0118](../adr/0118-source-build-on-deployment-host.md)。验证见[构建报告](../test-reports/20261029-source-build-validation.md)。助手未连接66执行构建；用户自行push。
