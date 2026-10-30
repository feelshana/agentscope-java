# Docker MySQL 用户与管理员操作

适用于现有66部署的独立MySQL：`/data/dataagent/rehearsal-db`，项目`dataagent66-rehearsal-db`。脚本在宿主机执行，从运行中的专属MySQL容器读取现有凭据；不需要安装宿主MySQL客户端，不需要填写或展示数据库密码。以后切换外部数据库时不能直接套用本脚本。

代码推送后，在66更新源码，再进入模块目录（脚本不要求重构应用镜像）：

```bash
cd "$HOME/dataagent-source/agentscope-java/agentscope-dataagent-standalone"
bash docker/user-admin.sh search 王超
bash docker/user-admin.sh show wangchao_yy1
bash docker/user-admin.sh admins
```

查询结果列顺序：用户ID、BI英文账号、中文姓名、公司、部门、角色。搜索最多100条，关键词按普通文本匹配；同名时结合英文账号与公司确认身份。没有结果时换关键词；准确账号不存在时`show`返回失败。

核对准确的BI英文账号后执行：

```bash
# Example account only: replace it with the verified BI account.
bash docker/user-admin.sh grant REPLACE_WITH_BI_ACCOUNT
bash docker/user-admin.sh revoke REPLACE_WITH_BI_ACCOUNT
```

`grant`设为`user,admin`，`revoke`设为`user`。仅修改已有账号的`roles_csv`，不修改ID、密码、中文姓名、公司、部门或知识库成员记录。全局管理员权限意味着可以管理所有知识库与大模型服务，应先核对账号再执行。含其他自定义角色时拒绝覆盖；重复操作幂等；撤销最后一个管理员时拒绝。脚本通过事务锁定用户行再核对管理员数量，避免并发脚本同时撤销全部管理员。

修改角色后，请关闭应用页面并重新从BI图标进入，取得新令牌。现有JWT里的角色不会立即失效；撤销后的旧令牌在过期前仍可能具有旧权限，当前生产示例有效期为1800秒，以现场配置为准。脚本的最后管理员保护仅覆盖本脚本，不能保证其他手工SQL或旧管理接口具有相同保护。

操作审计保存在`/data/dataagent/rehearsal-db/ops/user-admin.log`（0600），记录时间、执行账号/机器、操作、目标账号、结果和修改前后角色，不写密码或令牌。脚本需要当前SSH账号可写该私有目录并能执行`sudo docker`；权限不足时由运维处理，不调整Dify或其他项目的权限。

脚本固定连接平台库`dataagent_platform`，校验本地项目标记、Compose项目及容器目录标签，拒绝缺失或多个目标容器。不建库、不导用户、不删除容器或数据，不启停业务服务。它使用现有数据库root凭据执行这组受限操作，因此只应由获授权的运维/开发账号在SSH中运行，不暴露给普通用户。

自定义隔离环境可显式设置`DATAAGENT_REHEARSAL_DB_DIR`、`DATAAGENT_REHEARSAL_DB_PROJECT`；拥有Docker直接访问权限时设`DATAAGENT_DOCKER_SUDO=false`。这些值必须与现有数据库标记/标签一致，不能用来指向无关数据库。
