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

### 示例文件与数据库：66已完成，后续更新沿用

用户已确认a85ccec6在线可用。66已完成0105两列升级，两个Excel及SHA256SUMS保存在 `/data/dataagent/rehearsal-app/data/demo-samples`；demo_data两表为98819条访问记录、2046条用户记录，visit_time为DATETIME。demo_reader只有该示例库SELECT，初始化时的上传账号临时权限已撤销。数据实际时间范围为2026-09-20 16:35至2026-10-07 16:31。

临时GitHub传输完成后，源码中的两份Excel已删除；这只删除受Git管理的源码副本，现有upgrade保留上述持久目录、runtime.env及已有数据库。后续无需重复导入、重复加列或创建账号。线上配置继续指向上述绝对路径。要检查文件时在66执行：

```bash
(cd /data/dataagent/rehearsal-app/data/demo-samples && sha256sum -c SHA256SUMS)
```

本地开发在模块目录执行一次（Python 3）：

```bash
python deploy/demo-samples/restore-local.py
```

恢复到模块 `data/demo-samples/`（被Git忽略），文件已存在且一致时直接通过，不同则停止而不覆盖。切换/同步个人分支不会删除忽略副本。IDEA Working directory设为模块目录并设置 `DATAAGENT_DEMO_ENABLED=true` 即可测试示例上传；工作目录不同则设置本机绝对 `DATAAGENT_DEMO_SAMPLES_DIR`。外部示例源测试还需本机demo_data及只读账号，凭据放本地忽略配置/IDEA环境变量，不复用或提交66凭据。完整说明见项目deploy/demo-samples/README.md。

新机器或新服务器仍需恢复/提供Excel并配置其自己的示例数据库。恢复脚本依赖原传输提交011d06b的历史，不包含历史的浅克隆要先补齐历史。示例配置格式如下，66现有配置已完成，普通更新不重复修改：

```dotenv
DATAAGENT_DEMO_ENABLED=true
DATAAGENT_DEMO_SAMPLES_DIR=/data/dataagent/rehearsal-app/data/demo-samples
DATAAGENT_DEMO_JDBC_URL=jdbc:mysql://mysql:3306/demo_data
DATAAGENT_DEMO_USERNAME=demo_reader
DATAAGENT_DEMO_PASSWORD=使用现有私有配置中的实际密码
```

旧Git1.8的Excel文本误判已通过66本地info/attributes规则修正；源码目录另保留xlsx binary属性。后续仅删除源码Excel无需清理本地规则，也不要对持久目录执行Git清理。

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
