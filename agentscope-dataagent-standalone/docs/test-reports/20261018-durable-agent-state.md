# 会话上下文MySQL持久化：实现与验收

提交状态更新（2026-10-19）：已纳入本轮主题提交，尚未部署。以下保留原验收日期、测试结果及当时的提交状态；当前上线安排见[总账](../go-live-gap-review-20261012.md)。

日期：2026-10-18；boundary-lyf / 270aff22加既有未提交改动。用户明确批准接入框架持久存储；准确性规则/模型治理已要求跳过，本轮没有修改业务模型或官方Wren技能。对应spec073、ADR0084。

## 一、实现

1. `AgentStateConfig#durableAgentStateStore`提供框架`MysqlAgentStateStore`，共用平台DataSource当前数据库、新表`dataagent_agent_state`。自定义AgentStateStore Bean优先；默认装配不再回落内存。H2开发复用`JsonFileAgentStateStore`，在持久user.home下按数据库URL哈希分目录。
2. `DataAgentConfig#builderBootstrap`把同一存储交给问数、建模等已配置Agent，以及`DataAgentBootstrap.Builder#agentStateStore`的生命周期清理。核心状态`agent_state`按用户/运行会话ID保存最新JSON快照，正常调用加载/结束保存，复用框架序列化和CAS版本校验；没有另写恢复消息或业务路由。
3. 表联合主键为`session_id/state_key/item_index`，单状态item_index=0。session_id由框架组合内部userId和sessionId；binary排序规则避免MySQL忽略大小写混淆命名空间。初始化检查必需列和binary身份列，缺失明确失败。开发update/create/create-drop初始化该表，独立状态表不随create-drop自动删除；生产validate/none须先迁移。
4. `SessionAgentManager#resetSession/removeSession`删除该用户旧sessionId的所有状态键，再变更登记；删除失败保留登记供重试。启动保留策略也拿到同一存储，API和自动维护共用清理，不改原忙碌会话门锁。
5. 展示历史继续独立保存；不在登录时全量加载所有会话、不把前端展示记录拼入提示词。之前已丢失的内存状态不会自动重建。当前选库和授权仍逐轮检查，恢复上下文不授予历史权限。

框架依赖`agentscope-extensions-mysql`已加入standalone POM；上层安装该模块及所需依赖成功。没有修改core/harness源码。遗留Redis配置块实际未被当前装配读取，相关误导注释/README同步纠正，未新增Redis接入。

## 二、自动化验证

| 验证 | 实际结果 |
|---|---|
| AgentStateConfigTest + SessionStateLifecycleTest | 10项通过；H2快照重建、真正ReActAgent重新构造后收到前文、用户/会话/数据库隔离、自定义Bean、未知后端/缺库/生产缺表失败、重置/删除/维护和失败重试 |
| AgentStateMysqlIntegrationTest，显式传私有连接文件 | 真实MySQL 3项全部通过；新存储对象重读最新快照、旧版本拒写、大小写不同用户分别保存、同一slot只有1行、10线程各20轮读写不串、主键点查及不同大小快照计时 |
| 全量Java测试 | 698项，675通过、23跳过、0失败/错误；本轮执行，不引用10.17成绩 |
| Maven package -DskipTests | 通过；包含实际TypeScript/Vite前端构建及exec.jar/tar.gz生成，无前端业务源码改动 |

全量23跳过含12项既有Disabled、8项环境/opt-in条件、3项本轮MySQL专项；这3项已另行显式运行并通过，其他20项没有因此变成已验证。没有声称全套旧前端node测试通过。

复现命令（项目根目录，PowerShell参数需引号）：

```powershell
mvn '-Dtest=AgentStateConfigTest,SessionStateLifecycleTest' '-Dskip.npm=true' '-Dskip.installnodenpm=true' test
mvn '-Dtest=AgentStateMysqlIntegrationTest' '-Ddataagent.test.state.config=<私有连接文件绝对路径>' '-Dskip.npm=true' '-Dskip.installnodenpm=true' test
mvn '-Dskip.npm=true' '-Dskip.installnodenpm=true' test
mvn package -DskipTests
```

MySQL专项只接受`dataagent_state_verify_`前缀的专用库；私有properties含url/username/password，不提交、不打印，原始URL/凭据不放报告。试验数据保留在独立库`dataagent_state_verify_20261018`，不指向用户默认业务库。

## 三、真实模型与HTTP重启验证

隔离18083，复用10.17验收元数据库/用户/上传资产，使用本次新JAR与活动配置模型deepseek-flash。真实停止自建JVM及子进程，再启动新进程；没有依赖旧JVM内存。

- **普通会话恢复**：前一轮约定随机暗号及2025年1月有效订单/排除取消条件，MySQL实际写入1条agent_state、version=1、1641字节。重启后的问题没有提供暗号/条件，模型正确说出前文；回答4.646秒。更新仍1条，version=2、2632字节，证明不是逐轮新增完整快照。
- **隔离**：新会话不知道该暗号；其他用户使用相同前端conversationId不会获得原用户上下文。读取原用户内部会话key的历史接口403，原用户展示历史仍可读取。
- **重置/删除**：重置删除旧运行sessionId快照，新轮不再记得暗号；删除新会话后其快照消失。再次重启后旧已删快照仍不存在，没有恢复成幽灵会话。仅清理本轮专门创建的重置/删除夹具。
- **实际查询续问**：重启前经Wren查2025年1月有效订单158单（14.349秒）；重启后只问“那2月份呢？保持上一问的统计口径，请实际执行查询”，得到147单（11.038秒）。实际查询回执保留2025年及排除取消/有效订单条件，不仅核对文字。
- **10用户并发恢复**：10个既有合成load账号同时约定各自随机代号，重启后同时询问；10/10正确恢复自身代号，无其他人的代号。设置轮p50 4.808秒/最大5.361秒，恢复轮p50 5.421秒/最大7.057秒。它是短会话恢复验证，不能替代10人复杂SQL/Python混合负载或目标65容量验收。
- **范围边界**：恢复后明确零选库，仍不能执行数据查询；另在有真实选库查询历史的订单会话中，将本轮groupIds设空重新检查，旧上下文不能扩大当前范围。保存工具回执，无成功数据查询回执。

已测正常完成回合的重启续问；没有证明重启中断的Python/SQL能自动续跑，也没有完成全部建模HITL恢复、备份恢复或多副本迁移验收。

## 四、MySQL开销与规模

真实本机MySQL、Hikari连接池12，表约10012行，其中1万条背景快照。框架完整序列化/数据库读写/反序列化计时；每档读取30次、版本更新10次。负载文本为ASCII测试正文，实际JSON多约646字节，非生产对话分布。

| 正文规模 | 读取p50 / p95 | 更新p50 / p95 |
|---|---|---|
| 约20KB | 0.537 / 1.334毫秒 | 4.794 / 8.791毫秒 |
| 约200KB | 1.645 / 5.136毫秒 | 7.593 / 9.413毫秒 |
| 约2MB | 6.042 / 11.130毫秒 | 34.286 / 85.556毫秒 |

EXPLAIN：`type=const`、`key=PRIMARY`、估计1行。每次读取当前会话，不扫描其他会话。十线程20轮更新另行验证隔离，未将上述顺序点查当并发延迟数据；无修改前受控LLM耗时A/B，不能宣称整体问答恰好增加这些毫秒。

状态正文约等于保留会话数×平均最新快照大小，但整体JSON每轮更新仍产生数据库日志/备份和传输成本。现有展示历史另占空间。长上下文沿用框架已有结果驱逐与80消息触发/保留20的压缩配置，本轮不自加裁剪规则；单个巨大快照仍可能增加开销。本机小样本不外推目标机SLA、百万会话性能或24小时稳定性。

## 五、部署与边界

- MySQL使用平台元数据库，先运行[0084迁移SQL](../migrations/0084-agent-state-mysql.sql)；运行期脚本在`src/main/resources/db/agent-state-mysql.sql`，定义相同。生产使用既有validate策略，先建表，默认不自动建新业务库或回落内存。已有0083图表迁移仍须按发布手册执行。
- 平台备份应同时包含状态表、session_registry、展示历史及相关持久文件。只恢复状态表不等于恢复全部会话/附件；H2文件放在稳定user.home下，不放版本临时目录。
- H2开发JSON路径遵循宿主文件系统；Windows大小写规则由文件系统决定。当前平台身份使用既有稳定内部ID，不切换成用户名；本轮MySQL身份列的大小写隔离已独立实测，不把它泛化为所有后端均同等语义。
- 原15库/56数据集/15账号及本轮非删除夹具会话保留。46份源XLSX和863个发布/草稿文件核对不变。本轮没有根据准确性测试调整规则、示例、Cube或官方技能。
- 收尾只停止本轮18083的JVM及子进程，无该端口监听、验收sandbox标签容器0；用户5173前端、Docker、MySQL保持。未自动提交、未部署生产；旧MCP连接恢复P1仍待后续授权。

原始证据：仓库上层`.runtime/agent-state-20261018/`，包括`restart.json`、`orders.json`、`wave-before/after.json`、`restored-scope.json`、events、MySQL计时、测试/打包日志及audit；私有配置独立忽略。重跑会生成新测试会话，不能用覆盖旧结果的方式取代历史事实。
