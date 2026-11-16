# 66：仪表盘与建模界面升级

2026-11-17。整合分支为`liyufan_dev`与`develop-2.0`；由用户在66部署，本文件不是已部署记录。

## 数据库前提

仪表盘本身不新增平台数据库表，也不要求重建知识库、模型、用户或BI同步任务。主线已有共享数据源功能，若66尚未执行该批升级，先按[共享数据源升级](66-shared-datasource-upgrade.md)核对实际平台库：

```sql
SHOW COLUMNS FROM dataagent_external_datasource LIKE 'enabled';
SHOW TABLES LIKE 'dataagent_datasource_grant';
SHOW TABLES LIKE 'dataagent_datasource_audit';
```

三项齐全时不重复迁移；缺项时执行[0146增量SQL](../migrations/0146-shared-datasources-mysql.sql)中对应缺失结构，enabled已存在则跳过ADD COLUMN。不要执行0000初始化脚本，不在数据集库或业务源执行平台SQL。

## 更新服务

保留现有runtime.env、compose.env、实际HOME、数据卷及模型服务配置。在Xshell执行并等待脚本结束：

```bash
cd "$HOME/dataagent-source/agentscope-java/agentscope-dataagent-standalone" &&
bash docker/deploy-formal.sh
```

脚本从develop-2.0更新、完整构建，执行现有运行依赖、数据库连接与用户预检后替换服务；共享数据源结构仍须先按上面的SQL核对。以最终成功和health=healthy为准。`update-source.sh`仅更新源码，不能替代构建/重启。失败时保留本次releases日志，按脚本结果处理；不要手工清空数据卷。此次不重新安装旧七表/新增六表cron。

## 看板试用

强制刷新浏览器，进入仪表盘→新建看板，仅选已发布知识库并取名。进入工作台后再描述需求；生成通过后预览并继续对话修改，由知识库管理者显式发布。权限继承知识库：QUERY只读已发布版，创建者/管理者可编辑及删除，发布需MANAGE；全局管理员治理规则沿用主线。

看板每次模型输出上限默认32768；可在既有runtime.env配置`DATAAGENT_DASHBOARD_MAX_OUTPUT_TOKENS`，须符合所选服务额度。该值不是上下文窗口或最少输出量。当前开发服务已验证32768；66实际配置的模型是否支持须以现场响应为准。达到长度上限会明确停止，不再循环执行空参数。

看板工程、修订与专属会话沿现有MDL/HOME持久目录保存。只更新镜像不会导入本地MySQL中的示例知识库或本地已发布看板。[零售样板数据与复现说明](../samples/retail-showroom/README.md)随源码保留；在线展示前另行上传、建模发布、对话生成和管理者发布，不能直接复制本机asset.json（含本机知识库/用户ID）。

## 部署后核对

- 旧知识库可见、已发布模型可问数，原共享数据源/管理员只读回看/对话内知识库浏览仍可用。
- 新看板生成、修改、发布及重新登录后预览；QUERY成员无法编辑草稿或发布，撤权后不能继续查询。
- 删除仅对测试看板执行，确认原知识库/问数会话保留。
- 七表与六表的status按各自现有脚本检查；本次代码没有修改其cron计划。

完整构建与本机真实Agent证据见[整合验收报告](../test-reports/20261117-dashboard-release-integration.md)。外部模型、66网络及迁移状态未由本机测试代验。
