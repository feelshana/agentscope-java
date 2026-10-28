# 平台数据库初始化与升级

平台元数据库和上传数据集库应分别配置。上传库先建空库，由程序按上传文件动态创建 `ds_` 表；这里的 SQL 只针对平台元数据库。

## 首次安装：空平台库

1. DBA 在目标 MySQL 建立两个逻辑库和应用账号，分别授予平台表读写、上传库建表及数据读写权限；连接变量见[单机发布手册](../guides/single-host-release.md)。
2. 在**空平台库**执行 [0000-initial-platform-mysql.sql](0000-initial-platform-mysql.sql)。该文件为 2026-10-26 合并候选在隔离空库生成的 37 张表结构，不包含用户、业务数据或凭据；不是增量升级脚本，不重复执行。
3. 配置 `DATAAGENT_SQL_INIT_MODE=never`、`DATAAGENT_JPA_DDL_AUTO=validate`，启动同一版本程序校验。已在独立 MySQL 空库应用此 SQL 并以 `validate` 完整启动验证；生产数据库版本、权限与备份仍需现场核对。
4. BI 首次免登按真实用户名创建用户；管理员角色按 [BI 接入说明](../integration/bi-login.md) 配置，重新进入取得新角色。没有预置生产管理员或演示账号。

首次安装完成后**不要再执行本目录旧增量 ALTER**，完整结构已包含这些字段和表。不要将开发库数据、示例账号或模型凭据导入生产。

## 已有环境升级

先备份并对照当前实际结构逐项选择缺失迁移，不依据日期或分支名盲目执行全部 SQL。本次整合涉及的增量包括：

| 文件 | 内容 |
| --- | --- |
| [0081](0081-bi-login-mysql.sql) | BI 一次性票据 |
| [0083](0083-chart-owner-mysql.sql) | 图表所属用户 |
| [0084](0084-agent-state-mysql.sql) | 框架 Agent 状态快照 |
| [0088](0088-model-services-mysql.sql) | 加密模型配置与激活选择 |
| [0093](0093-modeling-revision-mysql.sql) | 草稿与已发布修订号 |
| [0094](0094-fact-query-window-mysql.sql) | 外部事实表时间配置 |
| [0095](0095-field-screening-mysql.sql) | 字段整理确认状态 |

每份 SQL 的适用条件优先。`dataagent_agent_state` 的身份列必须采用 binary 排序规则。数据库、发布工程、附件、HOME 下的模型加密主密钥须匹配备份；只有数据库备份不能恢复完整服务。

## BI目录与公共库（ADR0116）

已有平台库在启动新版本前一次执行[0116迁移](0116-bi-directory-public-knowledge-mysql.sql)，增加用户资料及public_query。空库执行更新后的[0000完整DDL](0000-initial-platform-mysql.sql)，不再叠加0116。上传库无需执行平台DDL。管理员首登后通过SQL赋权并重新进入，再按[导入说明](../integration/bi-account-production.md)运行预览/导入。此流程不会导入BI密码。
