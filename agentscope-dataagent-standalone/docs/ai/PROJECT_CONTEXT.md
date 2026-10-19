# 项目长期背景

更新：2026-10-19。本轮4次中文提交收口，最终HEAD为待部署基线；未推送/部署。当前事实以代码和ARCHITECTURE_zh.md为准；历史取舍在ADR、来源快照及SESSION_LOG，不再在本页叠加互相矛盾的旧摘要。

## 产品及主流程

多用户数据分析平台：BI免登或开发密码登录→创建知识库→上传Excel/CSV或关联同一外部连接的表→基础模型→可选业务文档/预设问题→单库对话建模/HITL→官方检查与编译→显式发布→授权库普通问答/SQL/Python/图表→点赞成功业务查询保存官方memory。

每轮普通问答可零/单/多/全选；零选仅聊天，全选使用最新授权，切库保留历史但本轮新查询只限本轮范围。建模一次一个知识库；库内纯上传或单外部来源，混合及多外部源不支持，按分库建模、多库问答使用。

首发例行任务按用户10.17指令只保留占位入口，不开放实际任务API；后续须补owner/调度/取消/投递，不能将关闭接口描述为已实现归属隔离。整体HTML仍为原型，本轮只允许工具右侧详情栏采用其设计，聊天工具块/建模页/其余布局保持原实现。

## 架构与权限

- Java17、Spring Boot4.0.4 WebFlux/JPA、React18TSVite，standalone Maven依赖同仓库core/harness/OpenAI扩展；框架改动先安装配套依赖，阻塞JDBC/CLI放boundedElastic。
- 框架负责ReAct/Agent状态、会话门锁、工具执行和沙箱，业务层不复制。Agent对象可复用，状态按userId/sessionId隔离；Web按userId/agentId/conversationId映射。ADR0084已接框架MySQL最新状态快照，H2开发JSON文件；显示历史继续独立保存，已丢失旧状态不自动重建。重置/删除/维护清理对应状态，生产validate先0084迁移。
- 只用官方Wren0.15.0 skills/CLI/MCP，wren-core-py0.8.0、mcp1.30.0、ONNX/LanceDB；问数挂WrenMcpToolkit通用发现/调用和Python/render_chart，建模原生文件+受限CLI/HITL。模型/ref_sql/关系/视图/Cube遵官方契约，无自研SQL/召回业务路由、KG或物理SQL回退。
- 工程YAML事实源，草稿与发布快照分开，build不替代publish。QUERY仅问已发布库，MANAGE可维护/建模/发布/成员；创建者和全局管理员天然管理。运行身份不能替换为库owner，历史/沙箱/附件/反馈仍私有。
- WrenQueryAccess本轮范围及排队后复核，WrenPublishedProject部署发布工程并合并库内点赞SQL示例，指纹变化更新索引。错误业务例子也会被召回，技术成功不是口径正确。
- Python沙箱按(userId,agentId)复用，租约/空闲驱逐/进程组取消。私有附件与原生ECharts是不同权限路径：前者已有隔离证据，后者已按ADR0083修复请求用户归属，NULL历史图保留拒读（2026-10-19已提交，尚未部署）。

## 身份与发布

BI由前端直接持共享密钥AES-GCM加密用户名，POST ticket换固定默认30分钟JWT，不接BI后端/IM SDK。密钥可提取并伪造任意用户名包括管理员，用户明确接受，不能称可信SSO。未知用户自动建普通用户、旧userId/roles保留，管理员roles_csv由SQL维护；不关联BI退出，关页不撤销JWT。

prod强制禁密码登录/Java种子并开启BI，SQL演示初始化在prod配置关闭；开发保留密码。前端options先行、sessionStorage、同源iframe握手和新入口整页重载，刷新不续期。DATAAGENT_BASE_PATH与代理前缀一致；参见ADR0081/spec069及docs/integration/bi-login.md。

平台元数据与上传MySQL分库；默认application.yml用MySQL，h2 profile仅切元数据。上传ds_表同库支持JOIN。默认ddl-auto=update便于开发，生产发布样例validate，先准备schema/迁移。私有dev/prod配置Git忽略、不入JAR/分发，必须外置加载，不记录密钥/测试账号密码。

Maven默认重建前端，产出exec.jar与tar.gz/conf/bin/lib，版本内log、父级data持久状态。single-host-release.md是唯一当前操作手册；manual-release-guide只导航。单机单实例，扩副本需共享状态和协调，不把本地门锁/预算当分布式保证。

## 容量及当前证据

问数单JVM默认16执行/16等待、每用户2执行/1等待，30秒排队/600秒执行；上传全局8，50MB/文件、100MB/批、10文件、10万行/100列/200万单元格。Wren MCP16实例/每库公平门锁，建模/外部通道及普通CLI不全经过同预算，数字不是性能承诺。

10.17新隔离验收：15库56数据集，46XLSX/202293行加3行边界CSV，8外部表；原库未动。11场景及MySQL/Doris正常对话纠正测试资产后显式发布，77固定题关键结果/证据通过（71直接、6人工等价），不是任意问法正确率100%。10用户两轮20/20，简单p50/最大9.292/11.324秒，混合18.927/27.771秒；Wren2/沙箱12×512MiB/JVM768MiB，预热另记，非生产SLO。642项Java624通过18跳过（12Disabled+6条件），工具解析16/16、前端及Maven打包通过；旧前端两个入口加载失败，11断言未执行。原始证据上层.runtime/launch-review-20261017，详情以本轮报告为准。

工具右侧栏已按spec071实施并实测SQL/Python/ECharts、复制/预览/下载、空/错/缺失/截断、390窄屏/键盘；ChatPage只加容器className，ChatPanel/ToolCallBlock/建模页未改，不补造payload缺失信息。

图表owner与SQL编辑结构已按用户授权修复并通过10.17专项验收，validate升级须0083迁移。10.18会话持久化已按ADR0084修复：全量698项675通过23跳过，MySQL另3项全过；实际重启暗号/查询续问与10用户恢复通过，2026-10-19已提交，尚未部署。10.18外联旧MCP恢复已按ADR0085/spec074完成：官方明确断连只读重试一次、同库30秒冷却，工具发现同样受限；真实MySQL/Doris的SQL/校验/Cube恢复通过，全量730项707通过23跳过，2026-10-19已提交，尚未部署；准确性/日期/复购风险按用户10.18要求跳过，只保留报告，不调测试资产。65实际配额/长期向量资源/生产容量/BI及Linux发布恢复仍需验收。

## 导航

10.18准确性专项补测实际215次（198主评估/17诊断，含重复）：192次数值题179核心指标/口径通过，首次含故障93.23%，可用条件179/184=97.28%；非完整答案准确率。复购过滤4次碰巧同值、月度去重1次答偏，附加解释也有错误；故障及金标歧义单列。46源/863资产不变，未擅改建模/问答实现；测试环境已停止、会话保留。详见[实测报告](../test-reports/20261018-answer-accuracy-full-validation.md)；历史77题通过不能外推任意问法。

- docs/go-live-gap-review-20261012.md：唯一当前上线总账与下一步；docs/TODO.md只导航。
- CURRENT_TASK.md：当前执行和交接；DECISIONS.md：决策导航；SESSION_LOG.md：历史检查点。
- docs/test-reports：按版本/日期验收事实；docs/imported：来源快照；docs/validation：历史证据，均不能替代当前状态。
- 根AGENTS.md、ARCHITECTURE_zh.md、README中英文、docs/agent-definition.md：规则/代码事实；ADR追加不改写，有0080/068历史编号碰撞时按完整文件名区分，不擅自重编号破坏链接。


## 最新交付安排（2026-10-19）

工具侧栏/任务占位、图表/SQL编辑、持久化/Wren恢复、文档/前端构建按4次提交收口，最终HEAD为完整待部署基线。引用10.18既有实测，不在提交整理中新增测试。模拟BI首页/图标/iframe接真实后端及云部署演练已讨论尚未实施；子路径构建前缀必须与Nginx一致。生产schema状态先核对0080/0081，本轮新增0083/0084迁移。
