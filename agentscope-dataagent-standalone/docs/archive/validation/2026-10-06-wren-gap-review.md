# 问数与语义建模评审及验证记录

日期：2026-10-06。范围：项目文档、核心源码、已安装WrenAI 0.15.0、独立测试数据。
本轮新增分析文档及测试夹具，没有修改业务实现。

## 判断

项目已经沿着正确的产品边界实现：建模助手负责对话和确认，Wren负责语义编译与执行，
问数助手负责问题理解及SQL生成，Python负责进一步分析。
下一阶段应从“能建模、能查数”转向“口径可对账、分析可复现、发布可解释”。

Wren在本项目中不是另一个独立聊天agent。实际宿主是AgentScope。
官方CLI的wren ask只生成提示词，也不会独立执行Text2SQL。
因此“与WrenAI差距”应区分：
- 与同版本官方语义引擎的功能和兼容性差距；
- 与官方技能/上下文工具工作流的接入差距；
- 与使用同一模型的官方宿主agent的端到端效果差距。

前两项已做源码核对和实验；第三项本轮没有可用平台HTTP服务，不能给出准确率差值。
最新官方网页不一定对应本机0.15.0，运行结论以安装包和实测为准。
参考：[官方CLI](https://docs.getwren.ai/oss/reference/cli)、
[官方MDL参考](https://github.com/Canner/WrenAI/blob/main/docs/core/reference/mdl.md)、
[官方架构](https://github.com/Canner/WrenAI/blob/main/docs/core/reference/architecture.md)。

## 文档与实现依据

重点阅读ARCHITECTURE_zh第2/3/4/5/7/11/12章；
specs/019、022、023、025、026、029、030、034、035、036、037；
ADR 0024、0033、0035、0043、0044、0045、0046；
已有014零售验证指南与019黄金E2E记录，并检索docs中的规格/ADR目录。
这是主流程相关文档的重点评审，不声称每份历史文档都已逐字审核。

源码锚点：
- DataAgentConfig#builderBootstrap、DataToolkitRegistrar：宿主模型、技能与工具装配。
- WrenToolkit#wrenRunSql、wrenQueryCube、effectiveScope、resolveGroup、
  renderTable、dataFileNote、renderCsv：问数边界和数据交接。
- MdlPublishService#validate、doPublish、dryRunCubes：发布校验与快照。
- MdlSuggestionService#refreshRelations、suggestRelationsLlm、probeJoinTypes、
  probeJoinType、confirmRelation：候选与基数探查。
- MdlWorkspaceService#withWorkspaceLock：工作区写入互斥。
- WrenInstanceRegistry#call、invalidate：实例串行执行和发布后刷新。
- MdlGraphView、SemanticModelingPage、ModelingHitlCard：读面、工作台与确认界面。
- 安装包wren.engine.WrenEngine、mcp_server、config、connector.duckdb：
  官方规划、执行、行数上限及严格模式。

重要历史证据：019黄金记录中，首次建模营收3099/客户3，真值3089/客户2；
直到将内部客户过滤固化到投影列及Cube度量，才修正。
这属于2026-10-04历史记录，不能冒充本轮重新执行的结果。

## 实际链路与官方差距

| 能力 | 当前项目 | 对照与评价 |
|---|---|---|
| 建模宿主 | modeling-agent+官方skills+HITL | 与官方宿主agent+技能模式同向，Web确认是平台能力 |
| 语义事实源 | YAML工作区→官方CLI构建→发布快照 | 已消除主要自建编译器差距；REST镜像和DB展示仍需持续防漂移 |
| 问数生成 | AgentScope模型生成逻辑SQL，Wren展开/执行 | 符合官方能力拆分；无需再实现一套Text2SQL循环 |
| 问数上下文 | 轻量目录+按需describe+应用知识检索 | 已降低上下文成本；没有完整接入官方get_context/recall_queries等上下文闭环 |
| 查询示例记忆 | 会话/长期记忆，非已确认NL→SQL资产闭环 | 需要按知识库与发布版本组织可审阅示例，避免把错误答案存为事实 |
| 查询诊断 | 问数侧3个Wren工具；建模侧有dry-plan/dry-run | 问数缺显式分阶段诊断入口，修复较依赖执行后的文字错误 |
| 分析交接 | Wren结果CSV→Docker run_python | 方向正确；缺类型及完整性契约，CSV本身不等于完整原始数据 |
| MDL可视化 | 只读ERD、资产列表、YAML及diff | 已有可用底座；关系图需要延伸到指标口径、来源及发布版本的解释 |
| 治理 | ownerId/scope、ACL、HITL、发布快照、审计 | 属平台产品价值，不宜为了“完全官方化”撤掉 |

官方上下文工具与示例存储能力可参照CLI工具清单；具体启用范围应以本机安装包为准。
平台只应包装租户与发布上下文，不另写检索/规划/ReAct框架。

## 本轮测试与发现

测试包：[wren-gap/README.md](wren-gap/README.md)。
7张CSV、10042行，12道问题及独立预期结果，额外含异常数据、10005行事件表。
标准答案：有效营收1050元、净营收950元、有效订单6笔、付费客户2个；
月度营收300/700/0/50元，客单价175元。

| 实验 | 本轮结果 | 能证明什么 |
|---|---|---|
| 独立SQLite基准 | 12/12断言通过 | 测试题与业务口径有确定的对账基准 |
| 官方CLI正常/故障注入 | 14个探针均符合最终记录的预期 | 正常模型可编译；故障不一定被同一阶段发现 |
| 官方SDK+隔离DuckDB固定SQL | 初始11/12通过 | 验证语义执行，不代表Text2SQL正确率 |
| 月份补齐CTE | 初始失败；内层客户别名c改cust后通过 | 存在查询写法兼容性问题；别名作用域是重点怀疑点 |
| 错误Cube维度 | validate/build/仅度量sql-only通过；选择坏维度时拒绝 | 当前仅度量发布探针漏掉维度问题 |
| 不存在字段的普通SQL | dry-plan通过；连接数据源dry_run拒绝 | 规划输出不等于数据库校验通过 |
| 官方Cube真实执行 | 总额105000分；客户分组含内部客户/未知客户的0值组；月度无3月行 | CASE固化度量不会自动筛掉分组，也不会补空月份 |
| 官方MCP默认行数上限 | 1000行，truncated=true，总额10元 | 不能拿默认明细样本回答全量100.05元 |
| 官方MCP最大行数上限 | 10000行，truncated=true，总额100元 | 单纯调大limit也不能解决全量分析 |
| pandas默认CSV读取 | 编码000123变为数值123；空串和NULL均为缺失 | 文件交接需要类型/空值协议 |
| 明细JOIN反例 | 错误订单金额1950元，真值1050元 | SQL能执行仍会因粒度不一致得到错误业务值 |
| 未建模表读取对照 | 默认和strict两种模式均拒绝；strict在策略阶段拒绝 | 本轮没有发现成功的物理表越界绕过，不能宣称存在越权漏洞 |

别名修复是可复现的A/B观察；不能仅凭一次实验断言覆盖了所有CTE/别名问题。
错误维度的最终故障注入同时将维度名称和expression改为不存在列；
只选择度量时放行，选择该维度时返回Unknown dimension。
“仅度量通过”是现有MdlPublishService#dryRunCubes的确切覆盖缺口。

数据与输出：
- [questions.json](wren-gap/questions.json)：问题、独立SQL、预期结果、NOT_RUN状态。
- [oracle-results.json](wren-gap/oracle-results.json)：12个独立基准。
- [official-results.json](wren-gap/official-results.json)：CLI原始输出与pandas探针。
- [official-execution-results.json](wren-gap/official-execution-results.json)：SDK执行、别名修复、截断及策略探针。
- [diagnostics.json](wren-gap/diagnostics.json)：放大和样本反例。
- [semantic-source.md](wren-gap/semantic-source.md)：建模业务口径。
- [fixture.mysql.sql](wren-gap/fixture.mysql.sql)：专用测试数据库导入脚本。
- [build-results.json](wren-gap/build-results.json)：检查退出码。

## 优先优化：问数

### P0：把数据完整性和类型作为分析的必需输入

保留CSV路径交接，避免模型搬运数据行；新增受作用域保护的查询产物元数据：
query_id、group_id、mdl_version/内容hash、columns/types、row_count、
truncated、SQL、筛选区间、文件hash、生成时间。
CSV无法区分NULL/空串时必须声明损失；编码、金额、时间需给出明确读取类型。
有条件时以Arrow/Parquet保存类型，CSV保留作为兼容导出。

如果truncated=true，run_python应知道这是样本，不能默默输出总体统计。
总体聚合优先交给Wren；需要明细分析时设计受控分批导出，并保持每批同一快照、
稳定排序和一致的数据读取范围，不能提供绕过Wren的物理直查通道。
render_chart也可接受受控data_ref，减少模型重新抄写columns/rows。

验收：10005行事件表总体100.05元，样本结论显式标注；
编码000123保真，NULL与空串按契约区分。

### P1：补齐官方诊断和上下文工作流

问数工具面可增加作用域包装的dry_plan/dry_run、describe_cube和get_context/recall_queries，
复用WrenQueryGateway及已发布项目。
先区分语义规划、SQL策略、数据源执行和数据完整性错误，再让模型一次修一个问题。
不要恢复specs/025已经撤掉的硬视图路由门。

小模型可一次获取完整schema，较大模型按问题召回并扩展相邻关系；
召回必须携带同一owner/group/version上下文。
少量确认过的NL→SQL示例比不断加长提示词更易审阅和复用。
存储必须经过业务确认或确定的对账验收，错误查询不可自动成为黄金示例。
知识库外的全局记忆只能承载偏好，不应提供当前库的业务事实。

验收：同义词、跨表口径、重复问法、模型升级后示例失效均有测试；
不同用户/不同库不能互相召回示例。
工具包装不要暴露物理连接或无范围的官方全局索引。

### P1：优化SQL边界与修复提示，避免误拦

WrenToolkit#wrenRunSql使用EXPLICIT_LIMIT全字符串正则；
合法的SELECT 'offset' AS label或注释中的limit也会匹配。
这是静态实现证据，未在平台HTTP端点动态复现。
保留禁止显式LIMIT/OFFSET契约，但改为识别实际SQL语法节点或复用官方解析能力；
SELECT/WITH前缀检测也不能替代完整语句验证。

本机WrenConfig默认strict_mode=false；平台WrenProfileHome目前生成profiles，
没有找到业务源码设置strict_mode，本机默认平台home也未发现config.json。
建议显式配置官方strict策略并启动时校验，不依赖部署者私有配置。
本轮未建模表在默认模式也被规划器拒绝，所以这是边界确定性增强，
不是已证实的跨租户漏洞。不得恢复checkCrossTable或自建物理SQL回退。

### P2：答案带可追溯证据

最终回答展示业务指标名、过滤口径、时间/时区、发布版本、
完整性和查询产物引用。Python的图和报告应回链到同一query_id。
多轮问题保留用户的分析意图，但MDL更新后重新确认当前可用成员。
重试须有次数上限，成功条件为结果对账或正确澄清，而非仅无报错。

## 优先优化：语义建模

### P0：发布前增加业务对账与Cube组合验证

MdlPublishService#dryRunCubes只选全部度量，不覆盖维度、时间维度、筛选和排序组合，
且--sql-only不能证明数据库执行或业务口径正确。

建议复用官方命令完成：
全度量编译 → 每个分组维度 → 每个时间维度/常用粒度 → 代表性筛选/排序
→ 数据源dry_run → 少量黄金问题真实查询对账。
组合按覆盖策略挑选，避免指数级穷举。
分清语法错误、无法连接数据源与业务值不符，不能把数据源不可用标为验证通过。

把“有效订单、排内部账号、币种、软删、退款归属”落实到模型/Cube/View，
knowledge规则作为解释和来源，不能期待确定性Cube执行自动理解规则文本。
复杂多退款净额先按订单聚合退款，再连接订单，避免fan-out。

官方Cube实际执行另有两个产品语义细节：CASE只让无效记录贡献0，不移除这些记录所在的分组，
所以客户维度仍出现“内部测试”和NULL两组；按时间分组只返回存在数据的月份，不自动补3月。
如果问题要求只展示有效客户，应在受治理的基础View或明确过滤中落实行集合，
不能把“度量排除”和“结果行排除”当作同一件事；月份补齐需日期骨架或明确的后处理。

验收：坏维度禁止发布；内部订单不计入；净营收950元；
旧快照在失败时继续可用；黄金对账结果随发布版本保存。

### P1：关系由LLM提议，证据由数据验证

保留ADR 0046的LLM关系判定，不复活同名/后缀自动规则。
但“只允许事实到维表”的提示词过窄：退款→订单、订单明细→订单、
角色维度、自关联、桥接表都可能有合法关系。
应让LLM基于业务粒度提出关系，再用测量证据确认安全用途。

当前probeJoinTypes使用单列distinct与行数比较，
且已有joinType会跳过后续探测；
复合键、重复维表、数据增长后的基数变化需覆盖。
展示测量时间、键唯一性、NULL率、孤儿率、匹配率和JOIN放大率。
这些是校验关系质量，不是用规则代替AI判断关系。

本次product_code=P2重复，应报告不可直接当作唯一维键；
订单→明细连接后的订单金额1950元必须识别为放大风险。
不要用SUM(DISTINCT amount)修复，金额相同的不同订单会被误合并。

### P1：将多文件提案作为一次可审阅变更

单文件HITL和全工程三重闸已是优势。
但对象列、relationship、Cube相互依赖，逐文件验证可能产生
“每个中间状态都不合法”的阻塞，并增加确认次数。

可设计多文件change set：对组的scratch副本统一修改→校验→展示整体diff与影响资产
→一次确认→版本比较后原子落盘。
继续走平台认可的文件写面与ModelingHitlMiddleware，不增加DB结构化工具。
变更基于workspace revision/hash，确认时检查是否过期；
关系/视图/指标变化应能定位受影响黄金问题。
所有会话、沙箱、工具调度机制继续复用harness/core。

### P2：把语义完善度升级为可验证覆盖度

现有“基线/完善中/已完善”有助于引导，但资产数量不代表正确性。
建议显示：关键指标对账覆盖、关系数据验证覆盖、描述/单位/枚举覆盖、
业务文档出处、冲突待确认、异常数据处理、缺失时间补齐。
“已完善”应有可解释证据和未完成项，避免用户将徽章理解为正确性认证。

## MDL可视化优化

保持只读ERD与YAML唯一事实源，复用现有G6/ECharts及组件。

1. 增加工作区/已发布版本切换，明确当前查询使用哪版；
   图上标出未发布资产、删除/新增关系，点击可看现有diff。
2. 从表关系图延伸到指标依赖：业务问题→Cube度量→表达式/过滤→模型字段→文档出处。
   不把这项做成新的知识图谱构建链路。
3. 关系边展示完整复合键、基数和测量状态；发现重复业务键或孤儿时显示诊断证据。
4. 指标侧栏展示粒度、单位、默认过滤、时间归属、NULL语义、黄金预期与最近验证。
5. 在影响分析中列出某次变更影响的Cube/View和问数样例，
   让用户确认的是业务影响，而不仅是YAML字面内容。

## 建议实施顺序

| 顺序 | 交付 | 可量化验收 |
|---|---|---|
| 1 | 查询产物完整性与类型契约 | 截断数据不冒充总体；编码保真 |
| 2 | Cube组合验证+业务黄金对账 | 1050/950元、6笔、2客户、空月份0正确 |
| 3 | 关系质量与粒度测量 | 重复P2可见，订单JOIN不放大 |
| 4 | 官方上下文与确认查询示例 | 同义问题稳定复用；租户/库/版本隔离 |
| 5 | 多文件变更与指标依赖展示 | 减少确认次数；确认前可看完整影响 |

以上是优化建议，不是已实现承诺。
按仓库规范，中大功能实施前分别编写一页spec并确认方案，
主流程改动再同步ARCHITECTURE_zh，新的架构取舍追加ADR。

## 检查结果与限制

本仓库pom是standalone，不是AGENTS所写的原多模块路径，实际使用：
- mvn test：BUILD SUCCESS，407 tests，0 failures，0 errors，12 skipped。
- mvn package -DskipTests：BUILD SUCCESS。
- frontend目录npm run build：成功。

跳过测试不可当作动态验证通过。
这些检查使用原有测试；没有改业务代码，也没有新增业务JUnit。

本机127.0.0.1:8080及8082未监听，3306有监听。
本轮没有启动平台或往运行中的MySQL导入新数据。
官方实际执行使用隔离DuckDB，不能据此声称MySQL方言兼容性全部通过。
未运行平台登录→上传→建模HITL→发布→问数→Docker Python完整链路；
也未调用LLM做官方宿主agent对照，因此没有“平台准确率比官方低X%”的结论。
测试包明确保留这些NOT_RUN状态，并提供复跑方法。

文档存在历史状态漂移：README仍描述部分旧的自建agent/分布式行为，
ARCHITECTURE已描述不同当前实现；AGENTS的Java21/多模块命令与standalone pom
的java.version=17和无reactor也不一致。
建议增加当前行为索引和历史spec取代关系，避免agent加载旧流程。
ADR按仓库约定只追加，不重写历史决策。
