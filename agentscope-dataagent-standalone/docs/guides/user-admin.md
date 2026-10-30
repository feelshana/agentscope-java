# 66数据库常用命令：用户查询、权限与资料维护

适用环境：10.194.2.66；当前Docker MySQL 8.0；平台库`dataagent_platform`、上传库`data_agent`。数据库根目录`/data/dataagent/rehearsal-db`，Compose项目`dataagent66-rehearsal-db`。目录中的rehearsal是现有名称，日常维护继续使用它，不因名称重建数据库。以后切换外部MySQL时，须重新核对本文的连接命令。

本文可独立保存或交给同事。常用角色操作使用项目自带`docker/user-admin.sh`，无需宿主MySQL客户端、数据库密码或修改配置文件；姓名/公司资料补充另有明确SQL模板。不会要求重构镜像或重启应用。

## 一、每次登录66后的准备

1. 用Xshell/SSH登录10.194.2.66，使用已获授权的账号。
2. 查看当前机器、进入项目源码模块目录：

```bash
hostname -I
whoami
cd "$HOME/dataagent-source/agentscope-java/agentscope-dataagent-standalone"
pwd
ls -l docker/user-admin.sh
```

应能看到服务器地址10.194.2.66和user-admin.sh。若目录不存在，同事需改用本机实际获授权的源码目录；不同SSH账号的`$HOME`不同，不代表程序没部署。不要直接在别人的目录修改权限。

首次使用需要该源码目录已拉取含管理员脚本的版本。日常查询、改角色不需要每次拉代码。以下命令都从上述模块目录执行；报目录、项目标记或权限错误时，先核对环境，不跳过脚本校验。

## 二、常用命令速查

将`REPLACE_WITH_BI_ACCOUNT`替换成准确的BI英文账号，保留引号；它不是中文姓名，也不一定是SSH账号。

| 场景 | 命令 |
| --- | --- |
| 按中文名、英文账号、公司或部门查找 | `bash docker/user-admin.sh search '关键词'` |
| 查看一个准确账号的资料与角色 | `bash docker/user-admin.sh show 'REPLACE_WITH_BI_ACCOUNT'` |
| 列出所有全局管理员 | `bash docker/user-admin.sh admins` |
| 普通用户改为全局管理员 | `bash docker/user-admin.sh grant 'REPLACE_WITH_BI_ACCOUNT'` |
| 全局管理员改为普通用户 | `bash docker/user-admin.sh revoke 'REPLACE_WITH_BI_ACCOUNT'` |
| 查看脚本帮助 | `bash docker/user-admin.sh help` |

角色`user`是普通用户，`user,admin`是全局管理员。全局管理员可管理所有知识库和大模型配置。普通用户仍可能具有某个知识库的管理权限；单库的“使用/管理”请在该库“成员与权限”页面操作，不使用本文全局角色命令。

## 三、查询某个用户

不知道准确账号时先搜索，以下关键词仅为例子，按需要替换：

```bash
bash docker/user-admin.sh search '张三'
bash docker/user-admin.sh search '示例公司'
bash docker/user-admin.sh search '数据部'
```

每次搜索最多100条；中文重名时，结合英文账号、公司和部门确定具体人员。多个搜索条件不会自动组合，换更明确的关键词或用show准确查询。

查到账号后：

```bash
bash docker/user-admin.sh show 'REPLACE_WITH_BI_ACCOUNT'
```

输出列顺序：**用户ID、BI英文账号、中文姓名、公司、部门、角色**；空白列表示资料未填写。只查询这些用户资料，不展示密码哈希或用户业务问答内容。

也可一次查看几个已知账号：

```bash
(
  set -e
  accounts=('REPLACE_WITH_ACCOUNT_1' 'REPLACE_WITH_ACCOUNT_2')
  for account in "${accounts[@]}"; do
    echo "账号：$account"
    bash docker/user-admin.sh show "$account"
  done
)
```

## 四、把一个普通用户设为全局管理员

先查看账号，再授权；命令用`&&`连接，查询失败时不继续：

```bash
bash docker/user-admin.sh show 'REPLACE_WITH_BI_ACCOUNT' &&
bash docker/user-admin.sh grant 'REPLACE_WITH_BI_ACCOUNT' &&
bash docker/user-admin.sh show 'REPLACE_WITH_BI_ACCOUNT'
```

最终角色应为`user,admin`。`UPDATED`表示已修改；`NO_CHANGE`表示已经是该角色，不必重复操作。

## 五、把一个全局管理员改为普通用户

```bash
bash docker/user-admin.sh show 'REPLACE_WITH_BI_ACCOUNT' &&
bash docker/user-admin.sh revoke 'REPLACE_WITH_BI_ACCOUNT' &&
bash docker/user-admin.sh show 'REPLACE_WITH_BI_ACCOUNT'
```

最终角色应为`user`。脚本拒绝撤销最后一个管理员；需要替换最后一个管理员时，先给新账号grant，再对旧账号revoke。撤销全局角色不会删除人员、知识库或单库成员授权。

## 六、一次授权几个用户

先把数组里的示例账号全部替换成实际账号，需要几个就写几个。代码整段复制执行；第一轮先确认所有账号存在，再逐人修改：

```bash
(
  set -e
  accounts=('REPLACE_WITH_ACCOUNT_1' 'REPLACE_WITH_ACCOUNT_2')

  for account in "${accounts[@]}"; do
    bash docker/user-admin.sh show "$account"
  done

  for account in "${accounts[@]}"; do
    bash docker/user-admin.sh grant "$account"
    bash docker/user-admin.sh show "$account"
  done
)
```

## 七、一次把几个管理员改为普通用户

```bash
(
  set -e
  accounts=('REPLACE_WITH_ACCOUNT_1' 'REPLACE_WITH_ACCOUNT_2')

  for account in "${accounts[@]}"; do
    bash docker/user-admin.sh show "$account"
  done

  for account in "${accounts[@]}"; do
    bash docker/user-admin.sh revoke "$account"
    bash docker/user-admin.sh show "$account"
  done
)
```

多人操作是**逐人提交，失败即停**，不是一个整体事务；前面成功的账号不会自动回滚。脚本会拒绝撤销最后一个管理员；核对输出、show或审计后，可以重新执行，已达到目标角色的操作为NO_CHANGE。

## 八、角色修改后怎样生效

告诉用户关闭当前DataAgent页面，再从BI图标重新进入，获取新登录令牌。普通刷新可能仍使用旧令牌。

现有JWT中的角色不会立即改变：升权后需要重新登录；撤权后的旧令牌在过期前仍可能拥有原权限。当前生产示例有效期为1800秒，以现场配置为准。数据库命令不需要重启应用；重启也不能代替令牌失效机制。

## 九、补充或修改中文姓名、公司、部门

先用show核对准确账号。以下操作只改资料，不改角色、ID或密码。

### 9.1 进入平台数据库终端

在66执行整段命令，容器内部读取现有凭据，不需要在命令里写密码：

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

看到`mysql>`后，执行下面的SQL，**不要把SQL直接贴在Linux的`$`提示符下**。每条SQL以分号结尾。用完输入`exit;`退出数据库终端。

### 9.2 只修改中文名

替换账号和姓名后执行，保留WHERE条件：

```sql
UPDATE dataagent_user
SET display_name = '填写中文姓名'
WHERE username = 'REPLACE_WITH_BI_ACCOUNT';
```

### 9.3 修改公司或部门

只改公司：

```sql
UPDATE dataagent_user
SET company = '填写公司全称'
WHERE username = 'REPLACE_WITH_BI_ACCOUNT';
```

只改部门：

```sql
UPDATE dataagent_user
SET department = '填写部门名称'
WHERE username = 'REPLACE_WITH_BI_ACCOUNT';
```

需要同时改三项时：

```sql
UPDATE dataagent_user
SET display_name = '填写中文姓名',
    company = '填写公司全称',
    department = '填写部门名称'
WHERE username = 'REPLACE_WITH_BI_ACCOUNT';
```

没有确认的字段不要填写空字符串覆盖，可只执行对应的单字段SQL。如果真实文字含英文单引号，SQL中用两个连续单引号表示，例如`O''Brien`。

修改后准确查询核对：

```sql
SELECT user_id, username, display_name, company, department, roles_csv
FROM dataagent_user
WHERE username = 'REPLACE_WITH_BI_ACCOUNT';
```

SQL显示`Rows matched: 0`表示没匹配到账号，应核对账号；`Changed: 0`也可能是原值已经相同，不能只据此判断账号不存在。修改后刷新页面读取资料，若仍保留旧显示则重新从BI进入。现有账号的资料不会被BI账号登录覆盖。

```sql
exit;
```

## 十、数据库只读检查

使用第九节的数据库终端。这些是日常查看，不是重新初始化：

```sql
-- Current database, server version and user count.
SELECT DATABASE() AS current_database, VERSION() AS mysql_version;
SELECT COUNT(*) AS user_count FROM dataagent_user;

-- Current global administrators, without password fields.
SELECT username, display_name, company, department, roles_csv
FROM dataagent_user
WHERE FIND_IN_SET('admin', REPLACE(COALESCE(roles_csv, ''), ' ', '')) > 0
ORDER BY username;

-- Table names in the two application databases.
SHOW TABLES FROM dataagent_platform;
SHOW TABLES FROM data_agent;
```

`data_agent`的上传表按实际上传情况生成；没有上传时可能为空。日常维护不重复执行首次建库、建表或1484人导入SQL，首次部署另见项目`docs/guides/production-database-setup.md`。

## 十一、查看角色操作记录

退出mysql回到Linux后执行：

```bash
tail -n 50 /data/dataagent/rehearsal-db/ops/user-admin.log
```

审计记录时间、执行账号/机器、操作、目标账号、结果与修改前后角色，不写密码或令牌。它是user-admin.sh的角色操作记录，**不包含第九节手工资料SQL**。文件为0600，只有相应获授权的文件所有者或运维可读取；其他同事权限不足时按运维流程处理。

## 十二、常见提示怎么处理

| 提示/现象 | 处理 |
| --- | --- |
| 找不到源码目录/脚本 | 核对实际源码路径；不同SSH账号的HOME不同；首次需要拉取脚本版本 |
| Docker permission denied / sudo要求密码 | 使用获授权的SSH账号，或请运维处理，不能靠修改数据库密码解决 |
| 账号不存在 | 先search核对BI英文账号；新用户可先从BI进入一次自动建普通账号，也可走正式人员导入流程 |
| 最后一个管理员不能撤销 | 先给其他确认账号授权，再撤销旧账号 |
| 含user/admin以外角色 | 脚本拒绝覆盖，保留现场人工核对 |
| 项目标记/容器不匹配 | 核对服务器、目录、Compose项目；不删除标记或绕过校验 |
| 改角色后界面没变化 | 关闭原页面，从BI图标重新进入获取新令牌 |
| 多人操作中途停止 | 前面成功者已生效，用show/admins核对后处理失败项；不要假定整体回滚 |

脚本只连接当前专属MySQL中的`dataagent_platform`，不启停业务、不删除容器或数据；密码由容器已有环境读取，不在本文或Git存放。角色修改使用事务行锁并保护最后一个管理员；保护仅覆盖脚本，不能用手工角色SQL或旧管理接口推断存在相同保护。
