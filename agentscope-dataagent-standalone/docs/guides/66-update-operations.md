# 66服务器：日常代码更新、构建与重启命令

适用服务器：10.194.2.66；部署主线：`develop-2.0`。个人分支`liyufan_dev`/`wangchao_dev`的修改须先合并并push到主线，再登录66执行本文命令。适用于现有Docker运行环境、数据库和应用都已就绪的日常更新，不是首次安装。

流程：**更新源码 → 构建新镜像 → 按版本核对增量字段 → upgrade替换并重启 → 查看状态和本次改动**。更新源码、构建镜像不会停止旧应用；upgrade时有短暂中断。

## 一、登录66并进入源码目录

使用Xshell/SSH登录10.194.2.66，执行：

```bash
hostname -I
cd "$HOME/dataagent-source/agentscope-java/agentscope-dataagent-standalone"
pwd
```

不同SSH账号的HOME可能不同。如果目录不存在，改用该账号实际获授权的源码目录；不要因此重新安装或修改其他人的目录权限。后面命令均在项目模块目录执行。

### 仅首次：将66源码目录从旧整合分支转到主线

本机开发分支切换不会改变66的源码分支或运行容器。下列操作由运维在66手动执行，当前助手未执行；不重新克隆、不重建数据库、不启停服务。若源码目录有任何修改，立即停止并先保留处理：

```bash
(
  set -e
  cd "$HOME/dataagent-source/agentscope-java"
  test -z "$(git status --porcelain)" || { echo '源码有未提交修改，停止切换'; exit 1; }
  unset HTTP_PROXY HTTPS_PROXY ALL_PROXY http_proxy https_proxy all_proxy
  export NO_PROXY='*' no_proxy='*'
  timeout 1800s git fetch origin refs/heads/develop-2.0:refs/remotes/origin/develop-2.0
  if git show-ref --verify --quiet refs/heads/develop-2.0; then
    git checkout develop-2.0
  else
    git checkout --no-track -b develop-2.0 origin/develop-2.0
  fi
  git config branch.develop-2.0.remote origin
  git config branch.develop-2.0.merge refs/heads/develop-2.0
  git merge --ff-only origin/develop-2.0
  git rev-parse --abbrev-ref HEAD
  git rev-parse HEAD
)
```

显式fetch目标引用并单独配置主线跟踪，兼容此前`--single-branch`克隆（其原fetch规则可能只认识旧分支）。必须看到`develop-2.0`及预期主线提交后再构建；任一步失败即停止，不强制切换或reset。后续更新脚本默认`develop-2.0`，`DATAAGENT_SOURCE_BRANCH`仅用于明确指定其他部署分支；普通开发不从个人分支部署。

## 二、拉取已push的代码

当前66曾出现“代理CONNECT返回503、直连GitHub返回200”，因此使用以下临时直连方式。括号内的环境变量只作用于本次更新，不改变永久代理配置，也不改变应用容器环境。

```bash
(
  set -e
  cd "$HOME/dataagent-source/agentscope-java/agentscope-dataagent-standalone"

  unset HTTP_PROXY HTTPS_PROXY ALL_PROXY
  unset http_proxy https_proxy all_proxy
  export NO_PROXY='*'
  export no_proxy='*'

  bash docker/update-source.sh
  git log -1 --oneline
)
```

核对最后显示的是本次要发布的提交。脚本只更新指定分支，工作区有修改或分支不能快进时会停止，不覆盖现有源码。报错时停在这一步处理，不能用旧源码继续构建后误认为已更新。

以后若运维调整了网络，直连不再可用，可按现场网络改回`bash docker/update-source.sh`。旧Git1.8.3.1曾拒绝`-c remote.origin.proxy=`空值参数，不套用该方式。

## 三、构建新应用镜像

```bash
bash docker/build-from-source.sh "$(git rev-parse HEAD)"
```

脚本在专属构建容器完成前后端构建、当前配置的构建检查、应用镜像封装及静态预检。宿主机不需要另装Maven/Node/Python。构建脚本自身可能执行测试，这是现有发布流程，不代表还需要人工再做一次全方面验收。

必须看到末尾：

```text
镜像构建及静态预检完成：dataagent/app:……
```

复制这一行完整的`dataagent/app:……`标签。每次构建标签不同，不复用上一次截图里的旧标签，也不猜测latest。只看到Maven BUILD SUCCESS或Docker镜像导出完成，还不能代替最后的静态预检通过。

构建记录保存在本次输出的`$HOME/dataagent-build/jobs/...`目录，包含build.log、image-build.log、image-check.log和build-result.txt。构建失败时原应用继续运行，保留错误输出再处理。

### 本次版本额外步骤：两个用户表字段（2026-10-31）

本次整合版本为 `b6105aa1d08f32ff37626ba2c69fe17b8efa4fff`；2026-10-31已核对GitHub主线包含此提交。以66实际fetch结果为准，不用旧个人分支构建。构建完成后、upgrade之前执行以下检查。现有66保持 `ddl-auto=validate`，不改成update，不执行初始化SQL，不重新导用户。

在Linux的 `$` 提示符执行下面整段，进入已有平台库（容器内部读取现有凭据，不在命令里填密码）：

```bash
sudo docker compose -p dataagent66-rehearsal-db \
  --project-directory /data/dataagent/rehearsal-db \
  --env-file /data/dataagent/rehearsal-db/compose.env \
  -f /data/dataagent/rehearsal-db/compose.yml \
  exec mysql sh -ec '
    export MYSQL_PWD="$MYSQL_ROOT_PASSWORD"
    exec mysql --protocol=socket --default-character-set=utf8mb4 --table -uroot dataagent_platform
  '
```

看到 `mysql>` 后执行：

```sql
SELECT DATABASE();
SELECT COLUMN_NAME, COLUMN_TYPE, IS_NULLABLE
FROM information_schema.COLUMNS
WHERE TABLE_SCHEMA = DATABASE()
  AND TABLE_NAME = 'dataagent_user'
  AND COLUMN_NAME IN ('onboarding_tour', 'onboarding_reset_at');
```

确认当前库为 `dataagent_platform`。结果为0行时，执行下面两句；若已有其中一列，只执行缺失那列对应的一句；若两列都已有且类型分别为varchar(200)、datetime(6)，跳过ALTER。已有列类型不同或报错时先停止核对，不删除重建字段：

```sql
ALTER TABLE dataagent_user ADD COLUMN onboarding_tour VARCHAR(200) NULL;
ALTER TABLE dataagent_user ADD COLUMN onboarding_reset_at DATETIME(6) NULL;
```

再次执行上面的列查询，确认两列存在且允许NULL，然后退出：

```sql
exit;
```

对应仓库增量脚本为 `docs/migrations/0105-guided-tour-mysql.sql`，它没有重复执行保护，不能不检查就反复执行。这两列只用于指引进度与重置时间，不重置账号、知识库或已有引导记录。

`0106-demo-data-visit-time-mysql.sql` 仅适用于此前已启用并导入过文本时间的 `demo_data.report_visits`。没有启用内置示例时本次跳过，不新建demo_data。已有示例库的维护由对应环境单独核对字段类型后按该脚本执行，不对真实业务表套用。开启内置示例还需部署两份Excel、挂载内可读路径及只读账号配置，见项目 `docs/guides/manual-release-guide.md` 的“内置示例数据”一节；本次不默认开启。

### 如本次同时启用新手内置Excel与示例数据源

用户于2026-10-31确认66只能通过GitHub接收文件，因此两份指定Excel改为本次临时随仓库 `deploy/demo-samples/` 分发。应用仍从持久目录读取，不打入JAR。先由用户提交并push本次文件到develop-2.0，66再拉取；b6105aa1本身不含本目录，不能在旧版本上直接复制。

在66拉取包含本目录的新提交后执行（不需要Xftp）：

```bash
(
  set -e
  cd "$HOME/dataagent-source/agentscope-java/agentscope-dataagent-standalone"
  demo_source="$PWD/deploy/demo-samples"
  (cd "$demo_source" && sha256sum -c SHA256SUMS)
  mkdir -p /data/dataagent/rehearsal-app/data/demo-samples
  cp -- "$demo_source/报表访问明细.xlsx" "$demo_source/用户列表.xlsx" "$demo_source/SHA256SUMS" \
    /data/dataagent/rehearsal-app/data/demo-samples/
  (cd /data/dataagent/rehearsal-app/data/demo-samples && sha256sum -c "$demo_source/SHA256SUMS")
)
```

两次校验均应显示两个文件OK；应用运行账号需要有目录访问和文件读取权限。该目录位于已有应用持久目录挂载内，不修改Compose挂载。

旧Git兼容：011d06b版本发布后，66曾出现Excel校验OK，但Git提示CRLF转换且将两文件标为M。仓库文件原字节正确；这是旧Git1.8与通用text=auto/eol规则的兼容问题。本目录已补.gitattributes将xlsx声明为binary。尚未拉到该修复时，可以在仓库根目录添加仅针对本示例目录的本地属性，保留已有属性，不重置或删除文件：

```bash
(
  set -e
  cd "$HOME/dataagent-source/agentscope-java"
  printf '\n# Preserve onboarding Excel bytes on old Git.\nagentscope-dataagent-standalone/deploy/demo-samples/*.xlsx binary\n' \
    >> "$(git rev-parse --git-dir)/info/attributes"
  git check-attr text diff merge -- \
    'agentscope-dataagent-standalone/deploy/demo-samples/报表访问明细.xlsx' \
    'agentscope-dataagent-standalone/deploy/demo-samples/用户列表.xlsx'
  git status --short
)
```

预期text/diff/merge均显示unset，git status无输出。该规则不使用assume-unchanged，也不屏蔽真实文件内容变化；若仍有修改输出，停止核对。再执行文件复制/校验。不要把Excel作为文本重新保存，不执行reset/clean。


这是本次首次传输步骤。用户确认66持久目录的文件复制/校验/应用读取成功后，下次提交可删除仓库内的两份Excel并push，README和SHA256SUMS可保留。正常git拉取只删除受版本管理的源码副本，现有upgrade保留上述持久目录；以后无需每次重新复制，也不能把样例配置改指向源码目录。删除后Git历史仍包含原文件，不代表历史内容清除。

编辑服务器私有 `/data/dataagent/rehearsal-app/runtime.env`，在现有内容中逐项新增或修改，不覆盖整个文件、不保留重复键。示意值如下（密码由管理员设置，不将占位值直接用于生产）：

```dotenv
DATAAGENT_DEMO_ENABLED=true
DATAAGENT_DEMO_SAMPLES_DIR=/data/dataagent/rehearsal-app/data/demo-samples
DATAAGENT_DEMO_JDBC_URL=jdbc:mysql://mysql:3306/demo_data
DATAAGENT_DEMO_USERNAME=demo_reader
DATAAGENT_DEMO_PASSWORD=替换为示例只读账号的实际密码
```

`mysql` 是66已有Docker网络里的数据库服务名，不使用容器自己的localhost。已有数据源地址白名单的环境也应按原规则允许该地址。只修改私有配置和部署文件，不需要为此重新编译镜像；随本次upgrade重建应用容器后读取新配置。

两种用途分别验收：
- 内置Excel上传：文件及enabled配置生效后，用户可在新手上传入口取用文件，后续沿原上传、建模、发布流程。此项不依赖外部示例库准备成功。
- 内置外部数据源：还要准备 `demo_data.report_visits`、`demo_data.platform_users` 的表和Excel数据，并配置只拥有该示例库SELECT的 `demo_reader`。

**66权限前提**：当前初始化脚本只给 `dataagent_dataset` 对 `data_agent.*` 的权限，不能凭空创建另一个demo_data库或创建数据库用户。`DemoDataService` 的自动准备先做建库/表，再导入，最后创建只读账号；受限账号会在这些步骤失败。不能只在env启用就假称初始化完成，也不应把常驻应用账号改为root。应由管理员在升级前另行完成示例库首次建表/导入/只读账号准备，并确认行数、时间类型及只读连接；本指引没有远程执行这些操作。

首次示例初始化时使用当前代码的DATETIME建表和时间归一化逻辑，原始Excel不改。0106只处理已存在的旧文本时间列，不负责首次建库/导入Excel。外部示例源成功后日志应有 `Demo data source ready: demo_data`，且页面可选“示例数据源”；只有上传文件成功或应用Healthy不能替代此项验收。

## 四、升级并重启应用

把下面占位内容替换为本次实际输出的完整标签，保留引号：

```bash
bash docker/rehearsal-app.sh upgrade 'REPLACE_WITH_THIS_BUILD_IMAGE_TAG'
```

例如标签以`dataagent/app:`开头，后面包含提交前缀、构建时间和随机后缀。upgrade会先验证候选镜像与依赖，通过后替换应用容器并等待就绪；不要提前执行stop，也不用升级后再执行一次start。

预期看到容器Healthy以及：

```text
应用镜像更新通过；数据库/配置/文件没有回退
```

普通更新沿用当前MySQL、用户、知识库、模型配置和持久文件，不重建库、不重复导入用户、不重装运行环境，也不需要每次改Nginx。若本次发布明确涉及数据库字段、运行依赖或配置变化，先按该版本的迁移/环境说明完成额外步骤，不能仅凭本文通用流程代替。

## 五、确认重启与当前运行版本

```bash
bash docker/rehearsal-app.sh status

curl --noproxy '*' --max-time 10 \
  http://10.194.2.66:18080/actuator/health
```

预期状态为`healthy`，健康接口为`"status":"UP"`。它证明基础服务已启动，不代表所有业务场景均已验收。

需要确认“运行的是否本次代码”时，再执行以下只读命令：

```bash
git rev-parse HEAD

sudo docker compose -p dataagent66-rehearsal-app \
  --project-directory /data/dataagent/rehearsal-app \
  --env-file /data/dataagent/rehearsal-app/compose.env \
  -f /data/dataagent/rehearsal-app/compose.yml \
  exec -T app cat /opt/dataagent/app/source-revision
```

第一行是源码目录提交号，第二个命令输出运行中应用的构建提交号。日常刚完成同一提交的构建升级时，两者应一致；如果后来又拉取了新代码，应与本次构建时记录的提交比较，不能仅因源码目录更新就认定运行应用也更新了。

从BI图标重新进入，前端变更按`Ctrl+F5`刷新，检查本次改动的页面/功能即可。只有明确安排全面验收时，再展开全流程、正确率或并发测试。

## 六、异常时查看日志

```bash
bash docker/rehearsal-app.sh logs
```

此命令显示最近100行应用容器日志。upgrade候选启动失败时，脚本会尝试恢复原镜像，并明确报告是否恢复成功；不要看到健康接口UP就忽略此前的“候选失败、恢复旧版”提示。失败部署日志保存在`/data/dataagent/rehearsal-app/logs/failed-deploy-*.log`。

## 七、确需回退应用版本时

以下不是每次更新必做。新版业务出现问题并已决定回退时执行：

```bash
bash docker/rehearsal-app.sh rollback
bash docker/rehearsal-app.sh status

curl --noproxy '*' --max-time 10 \
  http://10.194.2.66:18080/actuator/health
```

rollback使用已有previous-image.txt记录选择应用镜像，不回退数据库、文件、配置或业务数据。没有回退记录时会停止；涉及不兼容数据库迁移的发布，须先核对版本的回退方案。

## 八、已完成的现场示例（2026-10-30）

用户截图显示：4af226b6版本应用镜像构建与静态预检通过，执行upgrade后容器Healthy，脚本报告应用镜像更新通过，状态检查healthy，66直连健康接口UP。本次构建、应用替换和重启已完成；截图不代表助手远程执行，也不把它扩大为全业务验收。

本文是可重复使用的操作指引。以后每次使用自己的新提交与新镜像标签，不固定使用该历史版本。
