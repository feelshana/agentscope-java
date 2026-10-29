# 首次上线：数据库准备与BI用户SQL导入

## 数据库名称

沿用当前应用默认名称：平台元数据库 `dataagent_platform`，上传数据集数据库 `data_agent`。这两个是MySQL数据库名，不是登录账号；应用的MySQL账号/密码和权限由DBA另行提供。库名若由DBA另行约定，需要同时替换交付SQL中的库名和两个连接URL，不能只改配置。

平台库预先创建完整结构；上传库先保持空库，后续上传文件才由应用创建 `ds_` 表。平台库当前37张表，已包含用户中文名/公司/部门和公共知识库等本版本字段。

## 本地交付文件与执行顺序

| 顺序 | 本地交付文件 | 操作 |
| --- | --- | --- |
| 00 | `00-创建两个数据库.sql` | 创建两个逻辑库；已有库不重置 |
| 01 | `01-平台库建表.sql` | 仅对空平台库执行完整37表DDL |
| 02 | `02-BI用户导入-1484人.sql` | 一条完整INSERT，导入指定Excel的1484条人员资料 |
| 03 | `03-设置全局管理员.sql` | 将占位符换成你的BI英文账号，查询确认后赋权 |
| 04 | `04-核对初始化结果.sql` | 检查37表、上传库空库、用户数量及管理员 |

通用源文件为[建库SQL](../migrations/0000-create-databases-mysql.sql)、[平台完整DDL](../migrations/0000-initial-platform-mysql.sql)、[管理员SQL](../migrations/0000-set-global-admin-mysql.sql)和[核对SQL](../migrations/0000-verify-platform-mysql.sql)。桌面交付中的建表SQL与其表结构相同，只给每张表增加了明确的 `dataagent_platform` 库限定。

02包含真实人员资料，单独交付，不进入Git或通用Maven发布包。Excel原文件不修改；只采用 `loginname → username`、`name → display_name`、`company`、`dept → department`，不导入BI的ID、密码、手机号或角色。

## 在BI数据开发页面执行

1. 选择同事为本项目提供的真实MySQL连接和平台库，不使用截图里其他业务库。建库需要CREATE权限，建表需要相应DDL权限；没有时把00/01交DBA执行，取得DML权限后自行执行02/03。
2. 首次安装才执行01；已有平台表时先核对适用升级，不能用完整DDL覆盖或清空原表。第一次建表若中途失败，先定位已成功的表，再继续缺失语句，不盲目重跑整份。
3. SQL文件带有显式目标库名，表前缀仍应核对。编辑器如只执行一条语句，00/01/03/04按分号逐条执行，或使用其“执行全部/脚本”功能。02本身只有一条INSERT，须从INSERT到末尾分号完整选中执行。
4. 02采用同一条InnoDB写入，不依赖多条语句共用连接。现有账号按唯一username匹配，只更新中文名、公司、部门；保留原平台UUID、密码、角色、创建时间、绑定和其他表中的授权。不用REPLACE，不删除名单以外人员。新账号由MySQL生成UUID，全部为user。
5. 02中的人员字符串使用UTF-8十六进制表达式，减少中文连接编码、引号和反斜杠造成的解析差异；不是脱敏或加密，仍属于人员资料。密码字段使用与应用相同BCrypt算法生成的独立随机密码哈希，不提供或导入明文密码；BI免登不使用它。
6. 核对空库首次导入后用户数1484，之后允许因BI首登新增而增加。重复导入不重复建人，也不撤销已授予的管理员权限；“影响行数”与实际用户数不等同。SQL编辑器若超时，先查数量和人员资料再决定重试。
7. 将03里的三处 `REPLACE_WITH_BI_ACCOUNT` 全部替换成拟任管理员的BI英文账号，先执行SELECT确认中文名、公司，再执行UPDATE并查询核对。不是填中文姓名；目标账号必须已导入或已首次登录创建。重复赋权不会重复追加admin。随后从BI图标重新进入，取得新角色。

这套SQL流程可在应用启动前完成建表、导入人员和指定管理员，不再需要先取得管理员JWT或在66上运行Python。原来的管理员API导入脚本仍可作为后续资料更新方式，不必两种方式都执行。

## 生产连接配置

把实际MySQL主机/端口/账号替换进外置 `conf/application-prod.yml` 或对应环境变量。平台URL指向 `dataagent_platform`，上传URL指向 `data_agent`；示意：

```text
DATAAGENT_DB_URL=jdbc:mysql://实际主机:3306/dataagent_platform?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai
DATAAGENT_DATASET_DB_URL=jdbc:mysql://实际主机:3306/data_agent?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai
```

两套账号变量分别是 `DATAAGENT_DB_USER/PASSWORD` 和 `DATAAGENT_DATASET_DB_USER/PASSWORD`。平台账号需要运行所需的读写权限，上传库账号需要动态建表、写入及数据集删除所需权限；不要把应用读写账号与全局管理员角色混淆。生产仍使用 `ddl-auto=validate`、`spring.sql.init.mode=never`，不会代执行这份初始化SQL。

当前交付按MySQL语法准备，生产MySQL版本、账号权限、BI编辑器的SQL长度与执行能力仍需现场核对。资料UPSERT使用目前兼容范围较广的 `VALUES(column)`；MySQL 8.0.20起标为弃用，当前仍支持。依据：[MySQL官方说明](https://dev.mysql.com/doc/refman/8.0/en/insert-on-duplicate.html)。
