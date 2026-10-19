# 当前任务与接手入口

更新：2026-10-19；boundary-lyf；本页随本轮第4次提交交付，最终HEAD为待部署基线。

## 当前交付：4次中文提交收口

- `f782bf3b`：feat(frontend): 优化工具详情并保留例行任务占位入口
- `02bc8e60`：fix(web): 隔离图表归属并修复SQL问答对编辑
- `4b0f96e3`：fix(runtime): 持久化会话状态并恢复Wren断连
- 第4次提交收录剩余架构/README/部署/唯一总账/验收记录/共享记忆及构建静态资源。版本仍2.0.3-SNAPSHOT，未推送、未打标签、未部署；不再将前述功能标记为未提交。
- 本次只整理提交和文档状态，未新增业务实现或重跑测试。引用10.18最终730项707通过23跳过及完整Maven构建；现有JAR哈希与验证记录相符。生产validate注意0083/0084迁移；当前产物根路径，/dataagent/部署须设置前缀重建。
- 下一步：已讨论模拟BI首页图标、同源iframe与真实免登后端；先本地再云端部署全链路。该模拟入口尚未实施、未获得本轮新增实现指令；真实BI和65验收继续保留。准确性治理仍按用户要求跳过。
- 本页下面保留功能实施阶段及原验收日期的历史说明；其中“未提交”等仅表示当时状态，当前统一以上述交付及上线总账为准。私有配置和.runtime材料保持忽略。

## 当前已完成：Wren数据源断连恢复（spec074 / ADR0085）

- 用户批准最小恢复方案后，WrenInstanceRegistry增加明确MySQL兼容连接诊断识别、只读run_sql/dry_run/query_cube一次重建/重放、重试前及重建后授权、取消检查、共享执行等待预算、同库30秒冷却。工具发现也遵守冷却，避免额外创建；传输/启动失败保留冷却，旧实例按身份移除，关闭失败仍保留名额。
- 安装版官方Engine缓存connector及MySQLdb连接，无主动重连；本轮不改官方库/技能/MDL/业务规则/发布版本，无前端、配置或迁移变更。代码2类（Registry/ConnectionFailure），测试2类（30定向+2opt-in真实连接）。
- 全量730项707通过23既有跳过，含真实MySQL/Doris：旧进程失败而新JDBC100→自动重建100；持续拒绝连接只重试一次→发现/10次调用冷却拒绝→真实等30.1秒恢复；dry_run与Cube断连后也自动恢复、Cube结果一致。定向含现有实例/回执共46项通过。最终Maven package含TypeScript/Vite前端构建、exec.jar/tar.gz成功；未部署。
- 证据上层.runtime/wren-recovery-20261018：fixture-private/prepare、两源result、integration-final/full-tests-final/package日志、source-before/audit。源发布工程56文件哈希不变，自建SSH已停止、无遗留Wren/专用监听；用户Docker/MySQL/前端保留。未启动长期后端。
- spec074、ADR0085、专项报告、架构、发布排障及唯一总账更新；既有图表/SQL/UI/任务/会话持久化未提交差异完整保留，无自动Git提交/推送。下一步为模拟BI及云端演练，再真实BI、Linux发布回退及65容量；准确性治理按用户决定跳过，同事Docker现场WARN待补充。

## 当前已完成：MySQL会话上下文持久化（spec073 / ADR0084）

- 用户明确跳过准确性规则/模型治理，批准把框架会话状态接入MySQL。默认AgentStateConfig复用MysqlAgentStateStore/平台DataSource，核心agent_state最新JSON快照；H2开发框架JSON文件，自定义Bean优先，不静默回落内存。生产validate先0084迁移。
- SessionAgentManager重置/删除/维护清理旧用户sessionId的全部状态键，失败保留登记供重试；不修改ReAct/官方Wren/前端问答流程，不自动重建已丢失的旧状态。
- 10定向全过；真实MySQL3项全过（版本、单slot一行、大小写命名空间、10线程各20轮、1万背景行点查/20KB～2MB计时）；全量698项675通过23跳过，3个MySQL条件跳过另行显式跑过，其他跳过仍未覆盖。Maven package含实际前端构建成功。
- 实际18083/新JAR：暗号和订单条件跨重启保持，展示历史仍在、新会话/其他账号隔离、他人内部key历史403；重置/删除后状态消失且后续重启未复活。真实Wren订单1月158→重启追问2月147；10load用户同时恢复10/10，p50/max5.421/7.057秒。已选库历史恢复后本轮零选仍无成功数据查询。
- 核心改动：POM、AgentStateConfig、DataAgentConfig、DataAgentBootstrap、SessionAgentManager、2定向测试类+1MySQL集成类、运行期建表SQL及0084迁移。报告[20261018-durable-agent-state](../test-reports/20261018-durable-agent-state.md)，架构/README/发布手册/唯一总账同步；既有图表/SQL/UI/任务差异保留，未自动提交/部署。
- 证据上层.runtime/agent-state-20261018：live.py、restart/orders/wave/restored-scope、events、mysql-metrics、audit、测试/打包日志；私有连接文件不打印/提交。原15库56数据集15账号及非删除测试会话保留，专用dataagent_state_verify_20261018探针库保留；46源/863发布草稿文件未变。
- 自建18083 JVM/子进程已停、验收标签容器0；用户5173前端及Docker/MySQL保留。旧MCP连接恢复随后已按spec074完成；BI联调/Linux部署与目标65容量后续验收。准确性问题只留记录，同事Docker现场WARN仍待补充。

## 最新已完成：问数准确性专项实测（2026-10-18）

- [215次实测报告](../test-reports/20261018-answer-accuracy-full-validation.md)及逐题CSV已完成；先前answer-accuracy-analysis仅是历史复盘。真实HTTP/SSE，198主评估+17诊断，含重复/回归，不称215独立盲题。活动配置模型deepseek-flash，没有多模型A/B。
- 192次数值题179核心指标/口径通过：含源/模型故障首次93.23%，可用条件179/184=97.28%。4次同族复购数值99.5%相同但过滤未遵从，1次月度去重未答492；1道员工数金标歧义排除。另有附加最值/数学/因果/折扣解释错误，完整答案准确率未知。
- 7次云源不可达由本轮SSH断开，首次2次模型连接超时；恢复8/9，Doris商品数补题模型PT5M超时。6次Cube日期诊断、2次澄清通过，不能覆盖首次失败或标记历史日期/旧MCP P1已根治。5次行为题首次4通过/1连接失败，恢复坏金额合理拒答。
- 独立46XLSX/202293行参考SQL，不向模型提供金标；使用10.17已纠正的冻结发布资产。源46文件、13工程published/workspace共863文件哈希/集合均未变，无业务实现/模型/官方技能修改。旧图表/SQL/UI等未提交差异保留。
- 证据上层.runtime/accuracy-review-20261018：cases/results/events、manual-review、grading、summary-final、integrity和cleanup。不要重跑cases.py覆盖追加题或删除失败后重问。测试会话和15库56数据集15账号保留。
- 已核对身份停止本轮JVM及子进程7164/21592/29556、SSH39244；18081/13306/19030无监听，本轮label容器0，Docker/MySQL/用户其他服务保留。收尾检查见cleanup.json。
- 用户随后明确跳过准确性治理，以下结果仅留记录，不作为当前修复任务；BI/目标Linux/65长稳仍待验收，同事Docker WARN另待补充。未自动提交推送。

## 最新诊断已完成：Docker/MEMORY.md故障模拟（2026-10-18）

- 仅新增测试/报告，未改沙箱生产代码。8类进程边界故障均复现相同MEMORY.md ERROR，底层差异在orphan cleanup WARN，中文异常不带cause。
- 定向17项全过（15新增+2容量），含真实30秒挂起子进程超时；独立子JVM空PATH/不可达DOCKER_HOST两组真实复现。未停Docker/改宿主环境/删除真实容器，未重跑业务全链/容量。
- 已验证恢复可重试无需重启；检查成功后再故障不走同一中文异常；MEMORY.md缺失正常file_not_found。不能唯一确定同事12:32原因，已请求完整orphan cleanup WARN，等待现场证据再归类，不擅修。
- [模拟报告](../test-reports/20261018-docker-readiness-simulation.md)；证据上层.runtime/docker-readiness-20261018及target/docker-readiness-tests.log。未提交，前轮全部差异保留。

## 当前续作已完成：图表归属与SQL编辑修复（spec072/ADR0083）

- 已实现RuntimeContext归属写入、owner查询及精确匹配/私有缓存、NULL旧图保留拒读；结构化Markdown/YAML编辑、精确SQL往返校验/原子替换、点赞库级锁与索引失败源/派生恢复、坏文件可见/禁编辑。此前任务/UI差异保留，其余P1未改。
- 定向33项全过；最终全量670项652通过18既有跳过，包含实际Wren/LanceDB在新索引落盘后失败并恢复旧召回的测试。真实隔离MySQL接口36/36；实际浏览器保存刷新、多行特殊字符、坏文件禁编辑与图表显示通过；真实重启归属保持；旧表迁移通过。
- 图表GET各30次p50旧17.952ms/新17.577ms；草稿20次p50 18.603ms；点赞3次含官方索引7.7～9.6秒。没有重跑10用户问答波次/65/Linux/长稳，不将小样本外推SLA。
- 最终Maven package -DskipTests含前端TypeScript/Vite构建通过；工具解析16/16。图表只读调查/候选审查完成，无确认的剩余绕过。未自动提交/推送。
- 交付：[专项报告](../test-reports/20261017-chart-owner-sql-edit.md)、spec072、ADR0083及docs/migrations/0083-chart-owner-mysql.sql；架构/README/唯一上线总账同步。生产validate先迁移owner_id，旧图不回填，需要重新生成。

## 本轮三项任务已完成

1. 上线前代码/流程/性能核对，文档收敛；go-live-gap-review-20261012 是唯一当前总账，TODO/旧发布手册仅导航，架构合并过时模型流程与重复描述。
2. 用户恢复Docker后完成新一轮隔离验收；不是所有测试均通过。例行任务只占位，SecurityConfig关闭全部旧任务API，ADR0082/spec070。既有后端/建模问题只记录，未越界修复。
3. 只改工具右侧栏：结果/输入/原始内容、SQL表格、Python输出及本次文件、ECharts、放大/窄屏/键盘。ChatPage仅容器className，聊天工具块/ChatPanel未改。spec071与真实浏览器截图已完成；后续SQL修复只补问答对面板坏文件禁编辑提示。

## 实测摘要与限制

- 15测试库、56数据集、15账号保留。46 XLSX/202293行，额外边界CSV3行；8外部表分别来自云MySQL/Doris只读库。原用户库/账号/历史不动，源文件哈希不变，无孤儿上传表。
- 45文件首次200、1次IMPORTED_MODEL_PENDING/500数据仍保存；云MySQL也遇Windows快照切换失败，后续发布成功不覆盖首次失败。
- 11场景及两云源经正常建模对话/HITL纠正测试资产后显式发布；77固定题71直接/6人工等价，关键结果与证据均通过，非任意问题正确率100%。
- 10用户两轮20/20，简单p50/最大9.292/11.324秒，混合18.927/27.771秒。验收Wren2/沙箱12×512MiB/JVM768MiB，预热另记；68.627分钟资源观察，不是65生产SLA/24小时soak。
- 初轮Java642项624通过18跳过（12既有Disabled+6条件）；工具解析16/16、npm build和mvn package -DskipTests通过。既有前端2入口加载失败，11断言未执行，需后续维护夹具。
- 浏览器实际SQL/Python/图表/下载及390窄屏通过，临时夹具检查错误/空/无回执/长文本等，原型对照记录在报告。

## 仍未解决与下一步

初轮4项既有运行P1中，图表归属/SQL编辑和10.18上下文持久化均已修复验收，未提交部署；外联恢复后旧MCP失败也已按spec074/ADR0085修复验收，尚未提交部署。历史月末/复购风险保留，用户10.18明确跳过准确性规则/模型治理；下一步安排模拟BI及云端演练，不自动扩大修改。

后续真实BI/公司数据源联调、Linux安装升级回退及备份、65混合容量/向量长稳和业务金标验收。当前不作无阻断上线承诺，不自动提交或推送。

## 证据与环境

详细结果：[本轮报告](../test-reports/20261017-launch-review.md)；当前待办：[上线总账](../go-live-gap-review-20261012.md)。历史检查点查看SESSION_LOG和Git历史，不再把历史暂停状态堆在当前任务页。

私有配置/账号/脚本/原始SSE/采样/截图：仓库上层 `.runtime/launch-review-20261017/`，不提交凭据。隔离库名 dataagent_verify_20261017 / dataagent_data_verify_20261017。主测试资产全部保留，删除测试的临时库/代理源及UI夹具已清理；本次隔离18081已停止，本轮label容器0，Docker/原MySQL及用户前端5173保留；未主动停止用户8080进程，收尾时该端口无监听。不要套用旧阶段所有端口均无监听的历史记录。需要恢复隔离体验时读取STATE.md、instance.json，不要运行历史默认库清理脚本。
