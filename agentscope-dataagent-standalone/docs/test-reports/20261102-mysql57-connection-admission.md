# MySQL 5.7 接入放开与真实链路验收

日期：2026-11-02 Asia/Shanghai。起点 develop-2.0@0e9f25ee。未提交、push或部署66。

## 结论

仅修改接入门禁后，真实MySQL 5.7.44的连接、保存、更新、元数据浏览、创建知识库、关联采样、基础建模及发布通过；实际普通问数失败。**不能把接入成功宣传为5.7建模问数全流程兼容。** 本轮没有追加Wren/SQL/提示词改造。

## 环境与隔离

- 官方 mysql:5.7.44 镜像；本地Docker此前未运行，本轮启动Desktop后建立唯一测试容器 dataagent-mysql57-acceptance-20261102，仅127.0.0.1端口映射，无现有数据库挂载。
- 本轮构建的真实Java应用，独立随机端口、内存H2平台库、user.home、MDL、workspace、artifacts、sandbox instance-id；没有启动IDEA或连接现有平台库。
- MySQL acceptance57内仅合成customers/orders：3个客户、6笔订单，原生参考SQL COUNT=6、SUM=599.00；外部连接账号reader57仅SELECT权限。
- 本机固定wrenai 0.15.0，真实配置模型参与采样/建模/问答；通过真实HTTP/SSE/API调用，没有浏览器页面验收、Mock数据库或Mock模型。
- 私有状态/凭据/原始日志保留在本地忽略的target/mysql57-acceptance-20261102，不纳入报告。脱敏证据见[JSON](assets/mysql57-20261102/evidence.json)。

## 实际阶段

| 阶段 | 结果 | 说明/耗时 |
|---|---|---|
| 连接检测 | 通过 | 5.7.44真实JDBC；错误密码仍返回connected=false |
| 创建、修改数据源 | 通过 | 修改时空密码复用已保存密码 |
| schema/table/column浏览 | 通过 | 两张表及字段来自真实5.7 |
| 创建知识库 | 通过 | 独立知识库，未复用现有用户业务资源 |
| 关联与采样/预览 | 通过 | customers约1.515秒、orders约1.367秒；预览分别3/6行 |
| 字段确认/事实表范围 | 通过 | 正常API确认全部字段；合成orders明确设置UNLIMITED |
| 草稿初始化 | 通过 | 两张物理映射模型，约5.736秒 |
| 基础建模对话 | 通过结构检查 | 真实模型约18.34秒；字段与模型草稿生成，关系请求确认后未擅自采纳，最终2模型/0关系 |
| validate | 通过 | 约1.615秒；结构验证，不等于数据库SQL执行 |
| publish | 通过 | 约1.579秒，PUBLISHED v1 |
| 实际问答 | 失败 | 约8.89秒；官方MCP run_sql两次均MySQL错误1064，无结果数字 |

原始测试脚本先误用一次表浏览路径（404），再因知识库名含空格/点触发名称校验（400），均已按实际前端API/名称规则修正后继续；这些是验收脚本问题，不列为产品缺陷或版本问题。

## 失败定位

问数输入为最普通的聚合：

```sql
SELECT COUNT(*) AS order_count, SUM(amount) AS total_amount FROM orders
```

官方Wren在已发布模型上实际展开为：

```sql
WITH orders AS (
  SELECT `wren_src_orders`.amount AS amount
  FROM (
    SELECT orders.amount
    FROM (SELECT __source.amount AS amount FROM acceptance57.orders AS __source) AS orders
  ) AS `wren_src_orders`
)
SELECT COUNT(*) AS order_count, SUM(amount) AS total_amount FROM orders
```

不经过模型的真实数据库对照结果：

1. reader57直接执行物理参考聚合成功，结果6/599.00，证明权限、表和数据正常。此操作只作为验收参考，不是产品回退路径。
2. 同账号执行不引用任何业务表的 `WITH sample AS (SELECT 1 AS n) SELECT n FROM sample`，错误1064。
3. 对实际发布manifest使用官方Wren strict_mode生成上面的计划，再交5.7执行，同样1064。

因此根因为5.7不支持WITH，不能通过给orders加反引号或更换模型名称解决。实际回答没有编造数字，但把根因解释为orders名称/关键字错误，属于额外发现的错误归因问题；本次未改提示词。用户可据此决定后续是否另做执行兼容。

## 修改与验证

- 业务行为仅DataSourceIntrospector版本准入；ExternalDataSourceController只更新注释。
- 允许5.7、8.0+和Doris，5.6/5.5、未知版本保持拒绝；网络、认证、isValid及其他用户凭据隔离逻辑保持。
- DataSourceEngineTest、ExternalDataSourceConnectionTest、ExternalDataSourcePolicyTest及DocsConsistencyTest共32项通过；Spotless通过，package成功。
- 本轮Maven使用 `-Dskip.npm=true -Dskip.installnodenpm=true`，前端源码/资源与原包保持。无schema、配置、Wren兼容资源或Agent提示变更。
- 实际5.7.28未测，5.7.44是本次实库；8.0+/Doris版本判定有回归，未另起真实Doris或执行8.0业务全链路。
- 本轮应用与专属测试MySQL的停止状态见SESSION_LOG收尾记录；不清理已有用户资源。

## 后续决定：撤销接入放开（2026-11-02）

用户和同事决定上线暂不支持5.7，已将DataSourceIntrospector门禁及Controller注释恢复到HEAD原有的MySQL8.0+/Doris范围，恢复5.7拒绝的回归。上文为历史试验结果，保留失败SQL、6/599参考数据及耗时供未来评估，不代表当前5.7可接入。没有操作生产、删除本地测试容器或已有数据源/知识库；此次回退不会自动清理已经保存的测试资源。IDEA运行实例须重启才加载原门禁。局部验证结果见SESSION_LOG。
