# 66：共享数据源升级操作

2026-11-13，spec135/ADR0146/0147。由用户操作66；本说明不是已部署记录。本批还包含此前个人分支的资料说明同步和双BI任务观测。

## 平台数据库

在数据开发选择实际平台库，执行[0146增量SQL](../migrations/0146-shared-datasources-mysql.sql)：只增加enabled和授权/审计两张表。不要在上传数据集库或外部业务源执行；不要执行0000初始化脚本。enabled已存在就跳过ADD COLUMN，仅补缺失结构。

```sql
SHOW COLUMNS FROM dataagent_external_datasource LIKE 'enabled';
SHOW CREATE TABLE dataagent_datasource_grant;
SHOW CREATE TABLE dataagent_datasource_audit;
```

本次不转换旧密码，不改密码列，也不要求新建或更换密钥。原数据源按原密码继续连接，默认私有且启用。备份不是此次启动的强制条件，可按公司日常备份流程处理；本轮没有额外的密码迁移备份步骤。模型服务已有加密独立保留，现有runtime.env和实际HOME继续使用，不删除或替换已有配置。

## 更新部署

在66的Xshell执行：

```bash
cd "$HOME/dataagent-source/agentscope-java/agentscope-dataagent-standalone" &&
bash docker/deploy-formal.sh
```

脚本会核对主线、更新源码、构建并重启。等发布成功和health=healthy后强制刷新浏览器。本轮无需重新prepare、修改密钥、重建数据库或安装定时任务。Git网络失败先恢复原代理/隧道后重试，不跳过源码核对。

## 页面核对

知识库→数据源管理：旧连接仍在，配置留空密码后保存仍可检测连接。创建人或全局管理员从“更多→成员与权限”授权另一用户USE，该用户可看到共享连接并向自己可管理的知识库关联表，不能配置。MANAGE可配置；全局管理员能管理所有连接。

撤销源成员不破坏既有知识库绑定；停用会停止依赖库后续供数，仅用测试连接验证。已绑定连接不能删除或切换目标/账号。66真实部署、现场网络及公司数据库权限仍以用户实际结果为准。
