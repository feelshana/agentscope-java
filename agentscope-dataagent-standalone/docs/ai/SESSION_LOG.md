# 交接历史

## 2026-10-18｜checkpoint｜MySQL会话持久化完成

- 用户要求跳过准确性治理，批准上下文持久化；ADR0084/spec073。框架MysqlAgentStateStore共用平台库最新快照，H2开发文件，自定义Bean优先；重置/删除/维护清理状态，生产validate先迁移。不改官方技能/业务模型，不自动重建已丢失状态。
- 定向10项、真实MySQL3项通过；全量698项675通过23跳过（MySQL3另显式通过），Maven package含实际前端构建通过。1万背景行主键点查，200KB读/写p50约1.645/7.593ms，2MB约6.042/34.286ms，非生产SLA。
- 新JVM重启暗号/条件保持，实际订单1月158→追问2月147；10用户重启恢复10/10，p50/max5.421/7.057秒。新会话/他人隔离、历史403、重置删除/旧状态不复活、恢复后本轮零选守卫通过。
- 新报告20261018-durable-agent-state.md，证据上层.runtime/agent-state-20261018。46源/863工程文件不变、原资产及会话保留；仅删除专门的清理测试夹具。18083及验收标签容器已停清，5173/Docker/MySQL保留；未自动提交。下一步讨论旧MCP恢复，后续BI/Linux/目标容量。

## 2026-10-18 16:06 +08:00｜checkpoint｜准确性215次真实问答完成并收尾

- 198主评估+17诊断，不是215独立盲题；192数值179核心通过，含故障首次93.23%、可用条件97.28%。4次复购忽略显式过滤但数字碰巧一致、1次月度去重答偏；附加最值/数学/因果/资产假设错误另记，1金标歧义排除。
- 7次SSH源不可达、首次2次模型连接失败，恢复8/9，另1模型5分钟超时；6次Cube日期/2澄清通过不覆盖首次失败。活动deepseek-flash，未做模型A/B或10用户负载。
- 新专项报告+215用例CSV、历史复盘导航、唯一总账和共享记忆更新。46源XLSX/863工程文件哈希不变，未改业务实现/模型；测试会话及原15库56数据集15账号保留。
- 本轮JVM/子进程和SSH已停，18081/13306/19030无监听、label容器0；用户Docker/MySQL/其他服务保留。未自动提交；下步讨论关键指标金标与最小修复范围，同事Docker WARN仍待提供。

## 2026-10-18｜checkpoint｜Docker未就绪故障模拟完成

- 用户只授权模拟定位；新增测试/报告，不改生产代码。8类启动查询/清理故障均产生同一MEMORY.md错误；17项定向全过，含真实30秒超时，子JVM空PATH/不可达DOCKER_HOST两组真实复现。
- 恢复可重试无需重启，MEMORY.md缺失不触发此错误；首次检查成功后的Docker故障不是同一readiness异常。不能凭统一异常反推同事原因，需orphan cleanup WARN，已请求。
- 报告20261018-docker-readiness-simulation.md，证据上层.runtime/docker-readiness-20261018；原Docker/容器未改，未自动提交，前轮差异保留。

## 2026-10-17 21:37 +08:00｜checkpoint｜两项P1修复验收完成

- 图表当前调用者归属/精确检查/私有缓存及legacy-null拒读；SQL结构化编辑/原子保存/索引失败恢复/坏文件禁编辑完成，spec072和ADR0083。
- 670项652通过18跳过，33定向全过，真实接口36/36；实际Wren索引落盘后失败恢复旧召回、浏览器刷新/图表、真实重启归属、旧表迁移通过；最终Maven含前端构建通过。
- 图表GET p50旧17.952ms/新17.577ms，草稿18.603ms，点赞含官方索引7.7～9.6秒。未重跑生产容量/长稳。
- 专项报告/架构/README/发布手册/总账更新，原失败报告保留。隔离服务停止、临时SQL夹具清理，15库56数据集15账号及新会话图表保留。未触碰用户业务库，未自动提交推送。下步讨论剩余上下文/旧MCP恢复P1；业务模型问题仍仅记录。

## 2026-10-17｜三项上线核对工作完成

- 用户恢复Docker后完成隔离复测：15库56数据集15账号保留，46XLSX/202293行加3行边界CSV，无孤儿表/源哈希改变；11场景和云MySQL/Doris经正常建模对话纠正资产后发布。
- 77固定题关键结果/证据通过（71直接、6人工等价）；10用户两轮20/20，简单p50/最大9.292/11.324秒、混合18.927/27.771秒。Wren2/沙箱12×512MiB/JVM768MiB，预热另记，非生产SLA；资源观察68.627分钟。
- Java642项624通过18跳过（12Disabled+6条件），新增工具解析16项全过，npm build/Maven package通过；旧前端2入口加载失败、11断言未执行，未越界改认证。
- 既有4项运行P1真实复现：图表归属、SQL编辑、重启上下文、外联旧MCP恢复；历史月末/复购风险仍记录。仅关闭任务API与占位页（ADR0082/spec070），不擅修已提交建模逻辑。
- 工具右栏按spec071实施：三页签、结果表格、Python本次产物/失败部分文件警告、图表、复制下载/放大/键盘/390窄屏。ChatPage只加容器className，聊天块/建模页不改；实际截图与原型对照、临时夹具边界检查完成。
- 总账原地更新、TODO/旧手册导航化、架构合并旧模型与UI重复描述，CURRENT_TASK只保留当前交接。私有证据上层.runtime/launch-review-20261017，不自动提交推送。下一步先经用户确认处理P1，再真实BI/65/Linux/长稳验收。

## 2026-10-14 22:56 +08:00｜checkpoint｜官方 SQL 契约对齐

- 日志分析确认 Docker 导致的 run_python 恢复错误与 Wren 无关；建模流程除一次已正确修复的 write_file 预检外均成功。
- Wren 官方文档允许 `wren --sql` 使用 LIMIT，Cube 查询使用结构化 limit；已移除平台对 run_sql LIMIT/OFFSET 的额外拒绝，回执记录 SQL 末尾 limit，并补充 View/Model/Cube 入口提示。
- 定向测试 14 项通过。服务仍需重启后做真实问数验收；未提交 Git。

## 2026-10-14 17:57 +08:00｜checkpoint｜点赞回执、中文与 Limit

- 当前 Codex 会话修复持久化工具结果双层 JSON 解包，并补充回归测试；收紧问数及建模中文提示，补充 query_cube 排序与 arguments.limit 指导，修正新增提示的乱码。
- 本次 22 项定向测试、Spotless 和 Java 编译通过；前端构建在本轮较早阶段通过。全量测试、package、服务重启和真实点赞/召回尚未在最终改动后验证。未提交 Git，工作区既有改动保留。
- 下一步按 CURRENT_TASK.md 重启后验收中文、limit=1、candidates>0/store/recall。

只追加简短阶段记录；详细进度以 CURRENT_TASK.md 为准。不推断账号身份，不复制完整聊天或秘密配置。

## 2026-10-10（Asia/Shanghai）｜初始化 checkpoint

- 执行者：当前 Codex 会话，账号标签未提供。
- 用户要求：创建项目级 shared-memory Skill、四份共享记忆，并保留 AGENTS.md 规则追加协作规范。
- 已完成：读取 skill-creator、本项目规则、Git 状态、spec 055 和 ADR 0063；建立 Resume / Checkpoint / Handoff 流程并填入真实项目背景与待续任务。
- 保留：既有大量业务及 static 资源未提交修改，没有提交、清理或回滚。
- 业务交接：Cube 退役等已有实现；最新库示例为空、运行规则装配和诊断误选问题已定位；四条后续 ADR 仍待讨论。
- 验证：官方 quick_validate.py 返回 Skill is valid!；四个相对链接全部有效；本次范围 git diff --check 通过。AGENTS.md 原文作为完整前缀保留，仅追加协作规范。排除 AGENTS.md 后 Git 已跟踪差异的 SHA-256 与创建前相同，既有业务改动未被修改。Skill 和共享记忆没有被 Git 忽略。
- 未运行：Maven、前端构建、两个账号的实际客户端发现测试；本次为文档/Skill 创建，不宣称已经完成双账号端到端验证。
- 下一步：按 CURRENT_TASK.md 继续 ADR 讨论；不要将未确认建议当作已授权开发。

## 2026-10-10（Asia/Shanghai）｜Resume checkpoint

- 执行者：当前 Codex 会话，账号标签未提供。
- 已完成：读取共享记忆、规则、Skill，核对分支、既有差异、相关代码及日志；恢复此前未完成的 ADR 讨论和点赞保存/召回目标。
- 新事实：Top10 成功查询缺少声明而漏存，用户明确要求保存中文 question 与成功业务 SQL；修复未实施。
- 验证：仅文件、Git、代码和日志检查；未运行业务测试、构建或确认服务版本。exec_command 本次可用。
- 保留全部既有修改；本次仅追加共享记忆，没有业务代码修改或 Git 提交。下一步整理漏存修复与诊断排除方案，其他建议继续标为待讨论。


## 2026-10-10（Asia/Shanghai）｜ADR/spec 开发 checkpoint

- 已生成 ADR 0064—0067、spec 056 并完成首轮实现。
- 新行为：query_type 必填，点赞保存成功 BUSINESS 中文 SQL；官方 Wren CLI 按 owner/group/path 隔离；不处理历史、不撤回。
- Spotless 和 compile 通过；定向 AnswerQueryMemory/SqlExampleRecall 测试通过。全量测试最终汇总、真实 CLI、前端构建未确认。
- 下一步：检查全量测试、架构/README 同步、实际 agent id 装配和 CLI/前端验证。


## 2026-10-11 Asia/Shanghai — 开发 checkpoint

实现官方 enrich-context Auto-pilot 会话锁定：新增 ModelingToolkit 模式工具，按 sessionId 保存一次性模式选择并拒绝中途切换；建模系统提示要求遵守官方 Step 0 及三类升级条件。定向 Maven 测试与 Spotless 通过。

## 2026-10-11（Asia/Shanghai）｜shared-memory Resume

- 执行者：当前 Codex 会话，账号标签未提供。
- 恢复：分支 design/wren-official-capability-boundary，最新提交 9f8b0462；核对 Git 状态、代码及 ADR 0064—0067、spec 056/057，确认后续工作已推进到官方能力边界收敛。
- 发现：记忆顶部仍含旧分支、待讨论决策和点踩撤回描述；已追加当前事实及取代关系，保留历史记录。
- 当前待办：建模工具收敛、真实官方 CLI 对照、Auto-pilot 高影响变更升级验证及全量测试/端到端。
- 本次只修改共享记忆，无业务代码修改、Git 提交或服务重启；没有重跑历史测试。

## 2026-10-11（Asia/Shanghai）｜通用建模 Agent 文档 checkpoint

- 用户明确建模全部委托 WrenAI，本项目 Agent 仅为通用 Agent；核对官方 CLI/MCP 与 enrich-context/generate-mdl 能力。
- 已新增 ADR 0068、修订 spec 057，明确退出平台领域工具/模式工具和流程剧本，保留通用文件、权限/HITL 适配及平台版本发布。
- 业务代码未修改，实施方案待确认；文档差异检查通过，未运行构建/测试或服务验证。

## 2026-10-11（Asia/Shanghai）｜通用建模 Agent 实施完成 checkpoint

- 用户确认后完成 ADR 0068/spec 057：官方 CLI + 原生文件替代领域工具注册，保留租户/HITL、raw 映射、旧 HTTP 与显式发布；前端确认卡/任务行适配完成。
- 修复官方 build 到页面工程状态的衔接，查询连接绑定后恢复配置；官方标准 NL/SQL 发布后可召回，memory 派生索引在知识库 runtime。
- 最终验证：484 测试、0 失败/错误、12 既有跳过；真实安装版 wrenai 0.15.0 同工程对照通过，视图查询结果 3089；Spotless、前端 tsc/Vite、打包、diff check 通过。
- 保留既有修改，无 Git 提交/历史迁移/服务重启。向量与真实模型对话/浏览器未验证；下一步重载、新会话核对实际 schema/官方技能，再验收发布与问数。记忆辅助脚本审批超时后采用直接补丁保存。

## 2026-10-11（Asia/Shanghai）｜EOF 排查 checkpoint

- 首次建模推理约 9.5 秒后响应读取 EOF，尚未见 Wren 工具调用；框架读取失败与超时分支不同，具体上游原因未确定。
- 修复 ADR 0068 遗留：上传文档不自动调用旧 DocEnhanceService；页面区分保存资料、官方对话增强与旧版手动兼容分析。开放 DATAAGENT_OPENAI_STREAM 对比请求方式，默认保持 true；不自动重放 Agent 或写操作。
- 本次最终验证：上传/租户回归 4 项、Spotless、全量 test 486 项（0 失败/错误、12 既有跳过，真实 CLI 对照未跳过）、package（含前端 tsc/Vite build）和 diff check 通过。证据 target/eof-tests.log、eof-package.log、eof-verification.json。未重启服务、未验证真实模型请求；EOF 未宣称修复，保留全部既有改动且不提交 Git。

## 2026-10-11（Asia/Shanghai）｜建模日志与官方 Cube 能力分析 checkpoint

- 新会话已执行官方技能、构建、视图规划与查询；月度视图返回 99 / 2990。发现 Cube 草案和实际指标 Markdown 的总营收、付费客户数漏内部测试客户过滤；结构校验通过不能说明业务正确。
- 平台 Cube 禁用与官方 enrich-context 的指标落点冲突；不支持直接把 Markdown 指标当成已保存样例。memory index 实际为 grep、0 pair(s)，schema fetch 不可用；平台主要注入 rules，metrics 直接消费未接通。
- 已核对官方当前 Cube/MDL/CLI 文档及安装版 cube_proposals，提出完整解除 Cube 禁用、统一明细过滤和查询时粒度的待确认建议。仅更新共享记忆，没有业务改动或 Accepted ADR 变更；本次未运行测试，不借历史结果宣称本次实测。

## 2026-10-11（Asia/Shanghai）｜建模与问数完整方案 checkpoint

- 用户确认两条链路都使用官方 Wren，本项目作通用 Agent，不使用本体；最新要求先完整计划再开发。
- 核实本机 wrenai 0.15.0/core 0.8.0 与实时 PyPI 最新 0.15.0（Beta）；核对原生 MCP 实际 API、官方 MDL 改写和现有集成缺口。
- 新增 ADR 0069、spec 058 和七阶段实施计划；方向明确，详细方案待审阅，尚未开发。保留已有未提交修改与历史 ADR，无 Git 提交/依赖安装/服务重启。
- 文档本地链接检查及 git diff --check 通过；未运行本轮业务测试或声称准确率。下一步方案确认后从 P0 原生能力/版本/基线开始。

## 2026-10-11（Asia/Shanghai）｜ADR 0069 开发 checkpoint

- 用户确认后实现官方 Cube/通用 CLI、批次文件授权、原样发布、主问数官方 skills/MCP、统一 memory 和点赞原生调用关联；页面收敛为资料/对话/检查发布/资产，问题可选。
- 固定 Wren 0.15.0/core 0.8.0/MCP 1.30.0；隔离环境 30 条固定 SQL、年度去重 Cube、中文 ONNX 向量召回和原生 MCP 成功。真实 LLM 30 问未执行，不能报告问答准确率。
- 最终全量测试和 package 重跑中；先前仅两处旧目录保护测试失败，已修正为官方契约。待检查 target/wren-native-final-status.json 和日志；服务未重载，浏览器未验收。保留全部改动，无 Git 提交。

## 2026-10-12（Asia/Shanghai）｜P0–P7 真实验收进行中

- 恢复工具执行并继续开发；30 问实测完成，严格形状 22/30，主要数值审阅 29/30，空值/单位/草稿表达有缺陷。保留原失败与审阅副本，未宣称整体准确率达标。
- 补齐无范围 MCP 配置隔离与通用事实表达约束；原生建模+六问复测进行中。全量 496 测试通过，package 因后续源码格式变更须重跑；7 项浏览器模拟 API 验证通过。真实服务未升级，不提交 Git。


## 2026-10-12 00:24:12 +08:00｜用户请求 shared-memory 保存 checkpoint

- 执行者：当前 Codex会话，账号标签未知。仅更新四份共享记忆，保留全部未提交修改，无Git提交。
- 保存 P0–P7实现范围、实际失败、脚本/证据入口与恢复顺序。30问严格形状22/30；六问复测仍把3月空营收说成0，未宣称效果达标。
- 首轮建模验收因未响应官方业务提案确认而失败；合成测试已调整，重测见两Cube写调用与build/query成功，但无最终summary/status，需接手核对进程与汇总。
- 隔离启动已补driver/独立沙箱标识，最终因MySQL初始化SQL不兼容H2失败；当前服务未重启。最终test/package仍需源码稳定后重跑；最近496测试通过属于此前检查点，最新package失败不能忽略。
- 下一步：先查建模重测结果，处理空结果叙述与隔离启动，再全量构建、真实应用发布/问数/Python/点赞召回，完成P6/P7审阅。用户此次要求保存，不继续新增业务开发。

## 2026-10-12 00:52 +08:00 — 建模重跑与构建验收

官方 enrich-context 建模重跑成功，状态文件 exit=0；日志证明原生文件工具、HITL 确认、context validate/build、Cube 查询均执行。全量测试阶段返回 0。最终脚本 package 阶段第一次因脚本参数传递导致 Spotless 检查失败，之后显式执行 Spotless apply 和跳过前端安装的 Maven package，均 BUILD SUCCESS。当前仍未重启服务或做浏览器端到端验收，无 Git 提交。

## 2026-10-12 01:02 +08:00 — 隔离服务启动成功

隔离启动探针首次因 H2 未指定 SQL 平台而误跑 MySQL seed，补充 `spring.sql.init.platform=h2` 后成功。报告显示内置 JAR、隔离元数据、Actuator health 和前端入口全部通过；日志确认官方文件工具与 wren_cli 注册并启动 Netty。尚未做真实登录后的业务端到端验收。

## 2026-10-12 07:22 +08:00 — ADR 0070 / spec 059 实施 checkpoint

- 已新增 `docs/adr/0070-official-mcp-query-receipts-and-autopilot-confirmation.md` 与 `docs/specs/059-official-mcp-query-receipts-and-feedback.md`。
- `WrenMcpToolkit` 现在接受平台侧可选 `answer_question/query_type` 元数据；兼容模型把元数据嵌入 `arguments` 的现有调用，并在转发官方 Wren MCP 前剥离；成功 `run_sql/query_cube` 生成 `[WREN_QUERY_RECEIPT]`，记录 group/question/sql/limit/queryType。
- `AnswerQueryMemory` 反馈候选支持嵌套 question，且仅接受 receipt 标记为 BUSINESS 的中文业务问题；继续通过官方 Wren CLI memory store，未做历史迁移，也未实现撤回处理。反馈、候选、CLI store 均有日志。
- 新增嵌套元数据与诊断类型排除回归测试。验证：`mvn.cmd '-Dskip.npm=true' '-Dskip.installnodenpm=true' spotless:apply` 成功；`WrenMcpToolkitTest`、`AnswerQueryMemoryTest` 共 6 项测试通过。
- 尚未重启服务或执行真实 HTTP/UI 点赞验收；未提交 Git。下一步应在运行实例中确认 `wren_call_tool` 的工具 schema、receipt 出现在会话回合、点赞后 `memory store` 和后续 `memory recall` 日志。

## 2026-10-12 07:26 +08:00 — 修复验证完成

- 模块全量后端测试：`mvn.cmd '-Dskip.npm=true' '-Dskip.installnodenpm=true' test`，报告汇总 513 tests，failures=0，errors=0。
- 前端构建：`npm.cmd run build` 成功（TypeScript 检查与 Vite build）。
- 仍未提交 Git；运行实例端到端验证留待服务重启后执行。

## 2026-10-12 16:58 +08:00 — 建模节奏优化 checkpoint
- 新增 ADR 0071 与 spec 060，明确建模会话中 skill 单次加载、先 list_files 再 edit/write、files_json 批量写入、逐 YAML validate 后统一 build、Cube 语义查询校验及只读操作不触发 HITL。
- MODELING_SCRIPT 已加入上述执行节奏；ModelingHitlMiddleware 已保留批量确认并记录 confirmation batch size/tools。
- 验证：spotless:apply 成功，git diff --check 成功；本轮未运行测试，未提交 Git。
- 后续：重启服务后用真实建模会话验证 skill 重复注入、文件写入轮次、validate/build 次数与 HITL 批量日志。


## 2026-10-12 17:15:10 +08:00｜shared-memory Resume核对

- 已读规则与四份共享记忆，核对分支、Git差异、最新ADR 0070/0071和实际实现，保留其他会话所有变更。
- 确认后续建模重测/打包/隔离启动已通过；当前513项报告为07:24历史运行，15跳过；最新0071尚未回归，00:52 JAR早于0070/0071，在线生效未验证。
- 下一步优先核对建模提示/原生工具契约与LIMIT约束冲突，再新源码回归打包和真实点赞召回/建模节奏验收。P6/P7未完成。
- 本次仅恢复核对与共享记忆更新，无业务源码修改、测试重跑、服务重启或Git提交。

## 2026-10-12 17:31 +08:00｜新建知识库 409 修复 checkpoint

- 用户临时报告创建知识库无反应；静态核对409是当前用户同名冲突，界面错误显示在弹窗后方且API未解析detail。
- 修改前端API错误解析与创建弹窗反馈，加入重名提示、保留输入/重试、创建中和重复提交保护；后端规则与已有数据未修改。
- TypeScript/Vite构建、IDE指定文件build及diff检查通过；六项模拟API浏览器回归通过，证据target/kb-create-validation/report.json。未运行新的后端回归、未重打包JAR/重启当前服务，不宣称在线已生效。
- 保留P0–P7为待续；无Git提交。此次native自动审批超时后通过可用IDE工具完成浏览器验证。

## 2026-10-12｜知识库999日志与官方召回分析 checkpoint

- 核对最新两问：外层list_tools各1次，call_tool为7/11次；业务工具是官方，外层是项目适配。第二问多出LIMIT错误重试和两次诊断。
- 三条建模示例已发布并部署。外部Wren环境缺语义依赖，官方grep后端对完整中文问句空召回，短词“营收”命中两条；直接只读调用官方GrepIndex复现，非平台自研检索/未发布。
- 两条业务receipt及Python制图已出现在实际日志；点赞存储尚不能凭LLM日志判定。本次只分析与共享记忆，无业务修改/依赖安装/服务重启/Git提交。

## 2026-10-12（Asia/Shanghai）｜LIMIT 修复与调用精简 checkpoint

- 新增ADR0072/spec061，CLI/MCP子进程共享0.15.0 MySQL限行兼容补丁，原SQL最外层行数取较小值，保留排序/分页/嵌套与N+1截断；不改安装目录、skill或MDL。
- 实例级工具schema缓存通过隔离/失效/重试测试；通用提示复用有效上下文、按需诊断。全量回归发现旧建模patch_file提示，进一步核对并纠正不存在的files_json，保留原生批次授权。
- 最终503项测试0失败/错误14跳过，含真实外部Wren环境、知识库999 MySQL只读CLI/MCP；打包、前端构建和diff check通过，exec JAR兼容资源与源码一致。证据target/limit-verification-status.json及limit-*.log。
- 未重启在线服务、未实测LLM调用次数变化；向量召回/P6/P7继续待续。保留其他会话修改，无Git提交。下一步后端重启后新会话验收。

## 2026-10-12 18:xx +08:00 — 相似问题召回分析 checkpoint
- 核对 `logs/LLM-modeling.log`：建模阶段显式调用三次官方 `wren memory store --tags source:enrich`，生成 `knowledge/sql/query.md`、`query-2.md`、`query-3.md`，内容分别是有效订单总营收、付费客户数、月度有效营收示例；随后执行 `memory index`。
- 建模阶段曾验证 `memory recall`，CLI 明确返回 `grep backend: 3 pair(s)`，不是向量后端；当前问数 `LLM.log` 没有调用 `recall_queries`，而是 `list_knowledge` 后逐个 `wren_read_knowledge` 三个 SQL 文件。
- 结论状态：已完成日志分析，未修改业务代码；后续待讨论/实施的是问数每个新问题改为一次官方 `recall_queries`，并按 Wren 实际后端决定是否启用向量索引。历史示例按用户要求暂不处理。
- 验证：本次仅读取日志与共享记忆并检查 Git 状态；未运行测试、未重启服务、未提交 Git。

## 2026-10-12 �����������ٷ� Wren ���� memory
- ������ ADR 0073 �� spec 062����ȷʹ�ùٷ� wrenai[memory-onnx]��LanceDB/ONNX����֪ʶ������Ҳ�Ǩ����ʷ���ݡ�
- WrenProcessEnvironment Ϊ CLI/MCP �ӽ���ע�� WREN_MEMORY_BACKEND=lancedb �� WREN_EMBEDDING_BACKEND=onnx Ĭ��ֵ��
- WrenPublishedProject ��������ǰ��� backend=lancedb���ɱ�ǻᴥ���ٷ� memory index����ɺ�У�� memory status��
- ����ϵͳ��ʾҪ��ÿ���������Ƚ���һ�� recall_queries����ֹ���� knowledge/sql ����ٻء�
- ��֤��spotless:apply ����ɣ�package -DskipTests ����ִ�У�δ�������δ�ύ Git��


## 2026-10-13 向量 memory 运行验证 checkpoint
- 配置环境验证通过：wrenai 0.15.0、lancedb 0.40.0、onnxruntime 1.31.0。
- 官方 CLI 在已发布项目实测：memory status 为 Backend: lancedb / embeddings: onnx；memory index 成功建立 13 条 schema 索引。
- 修正 WrenPublishedProjectTest 以覆盖 index 后 status 校验；定向测试通过。
- 未重启应用、未提交 Git；下一步可重启服务后验证点赞问答的 memory store 与 recall_queries。


## 2026-10-13 建模重复确认分析
- 截图中的重复确认卡对应同一路径 /project/models/orders/metadata.yml。日志显示先错误提交两次 write_file（文件已存在，预检拒绝），随后同一轮对 orders 发起 5 次 edit_file、对 customers 发起 4 次 edit_file。
- ModelingHitlMiddleware 按 tool call 批量确认，前端 ModelingHitlCard 按每个 tool call 展示，因此同一文件出现多张相同标题确认卡；文件内容没有因这些预检失败而重复写入。
- 已加强 MODELING_SCRIPT：同一轮每个文件只提交一次 edit_file，合并全部修改后提交。


## 2026-10-13 Cube 过滤与重复确认分析
- 日志显示初始 valid_order_revenue、paying_customers、monthly_valid_revenue 的 measure expression 未带业务过滤，过滤条件只在 properties.description。
- 用户提醒后，valid_order_revenue 同一文件出现两次 edit_file：一次替换 expression，一次修改 description；截图中的两张同路径确认卡由此产生。其他两个 Cube 也各出现 expression/description 分拆编辑。
- 已加强 MODELING_SCRIPT：Cube 过滤必须使用过滤视图或条件聚合表达式，不能只写描述；同一 Cube 文件的修正必须合并为一次 edit_file。


## 2026-10-13 通用 Cube 约束与确认界面修复
- MODELING_SCRIPT 增加通用规则：固定业务口径必须进入过滤 View 或 measure 条件聚合，description/knowledge/rules 单独记录不算执行过滤；同一 Cube 文件修改合并为一次 edit_file。
- ModelingChatPanel 确认批次增加同路径变更说明、变更序号和顺序执行提示，明确一次授权会全部应用，不会在重复内容中自动择一。
- 待验证：前端构建；未提交 Git。


## 2026-10-13 最新建模日志复核
- 新日志仍生成 valid_revenue/monthly_valid_revenue 的裸 SUM/COUNT，固定过滤只写入 rules 和 description；Agent 明确错误地说 rules 足够。
- cube query 首次在 context build 前执行导致 Cube not found，build 后重新检查虽编译成功，但生成 SQL 仍无 order_status/is_deleted/is_internal 过滤。
- 已进一步加强 MODELING_SCRIPT：必须以 build 后 cube query --sql-only 的实际谓词作为验收，编译通过不代表口径正确；不能以 knowledge/rules 替代固定 Cube 过滤。


## 2026-10-13 点赞 candidates=0 修复
- 日志确认 feedback UP 已到达但 candidates=0；根因是回执存在而历史工具回合 toolCallId 关联缺失时，AnswerQueryMemory 严格丢弃回执。
- candidates 现在在官方回合完全缺失时允许使用服务端 WREN_QUERY_RECEIPT 作为权威来源，仍校验 owner、BUSINESS、question、SQL 和工具类型；存在不匹配的官方调用时继续拒绝，保持安全。
- AnswerQueryMemoryTest 3 项已通过；未重启服务、未提交 Git。


## 2026-10-13 19:05 +08:00 — 问数工具链与多会话日志评估 checkpoint
- 用户澄清：`logs/LLM.log` 中的 6 个问题分别来自 6 个新对话，不能把跨块调用当作同一会话的上下文复用。
- 证据：每个问题块均出现官方 usage 首次查询指引；语义查询实际均通过 `wren_call_tool` 转发至 Wren MCP 的 `recall_queries`、`run_sql`/`query_cube` 等官方工具。
- 现状差距：`DataToolkitRegistrar` 仍同时注册 `DataAgentToolkit`、`WrenMcpToolkit`（官方 MCP 适配）和旧 `WrenToolkit`；`DataDynamicContextMiddleware` 仍注入 `wren_run_sql`/`wren_recall_examples` 等旧工具名，造成工具面和提示词竞争。`retrieve_evidence`、`wren_read_knowledge` 是平台自定义工具；`run_python`/`render_chart` 属于结果分析/展示，不是语义查询。
- 日志现象：新会话的重复发现、技能加载和知识读取部分由官方 usage 的首次查询流程触发；额外冗余来自旧提示词/自定义知识读取。一次 `wren_call_tool` 曾因缺少 `arguments` 参数失败。部分官方 `run_sql` SQL 仍带显式 `LIMIT`，与项目 Wren 0.15.0 连接器兼容约束不一致。
- 未实施改动：本轮仅分析，未移除工具或修改提示词；前端 `WrenToolResult` 已有官方 JSON 结构化展示实现，但旧 `WrenToolkit` 结果仍走旧 Markdown 展示。
- 下一步：按“最新已发布 MDL + 官方 Wren MCP/CLI”为唯一语义查询入口，移除 data-agent 对旧 `WrenToolkit`/`retrieve_evidence` 的注册与旧提示词引用；保留 `wren_call_tool` 作为按知识库动态适配官方 MCP 的边界，保留 Python/图表作为查询结果后处理；补充 `run_sql` 参数约束、工具注册测试和日志验收。

## 2026-10-14｜checkpoint｜官方 Wren 问数工具收敛

- 完成 ADR 0074/spec 064 与实现：移除平台自定义检索/知识读取工具运行时注册，旧 WrenToolkit 不再注册；仅保留官方 Wren MCP 适配器、run_python、render_chart。
- 统一问数上下文、系统提示词、本地技能和测试中的官方工具名；run_sql 拒绝显式 LIMIT/OFFSET，查询绑定最新发布 MDL。
- 验证通过：后端全量 test、Maven package -DskipTests、前端 npm run build、git diff --check。
- 未提交 Git，未重启服务；真实运行验收仍需重启后新建会话确认工具列表与点赞 memory recall。

## 2026-10-15｜SQL 问答对管理与视图 404 修复

- 已新增 ADR 0075 与 spec 065。
- 新增 SqlQaExampleService 与语义建模 API /sql-examples，覆盖建模和点赞来源。
- 修复视图路径回退 metadata.yml。
- 验证 Maven package、前端 build、Spotless 通过；未提交、未重启。

- 修复 SemanticModelingController 多构造器未标注注入导致的 BeanCreationException；Spotless/package 通过。

## 2026-10-15｜平台增量合入分析 checkpoint

- 用户要求保留当前建模/问数并合入共享权限、上传、容量及修复。当前 HEAD 4c96099b；fetch 确认指定 multiAgent-Mdl 为祖先 9f8b0462，无新增提交。
- 定位候选 -lyf 分支 32c6d292，已询问用户确认来源。权限及部分取消/失败/忙状态修复存在，完整上传和问答容量预算在来源记录中仍待开发。
- 新增 spec 066，更新当前任务；仅分析与记忆，无业务修改、测试、Git 合并提交或服务重启。下一步等待来源澄清后适配当前官方 Wren 入口，保持历史/附件身份隔离。

## 2026-10-15（Asia/Shanghai）｜平台增量移植完成 checkpoint

- 用户确认 -lyf 后，移植共享权限、成员/审计、每轮选库、上传取消失败、删除重试、沙箱和界面修复；当前官方 Wren CLI/MCP、中文展示、LIMIT、问答对管理保留。补充共享入口鉴权、撤权及跨用户点赞串行保存，字段注入改为构造器。
- 最终全量570项0失败/错误18跳过；真实CLI/MCP专项5项通过；最终package和前端build通过；SSE/HTML5、解析8、浏览器展示6通过；隔离JAR健康/前端与18次HTTP ACL检查通过。验证脚本修正Java启动器孤儿进程问题，本次验证进程已全部清理。
- ADR0077/spec066/ARCHITECTURE/README与共享记忆已同步。现有服务未重启、无Git提交；完整容量预算来源未实现，仍待独立开发；真实LLM效果和容量压测未执行。恢复入口为CURRENT_TASK顶部。

## 2026-10-15（Asia/Shanghai）｜启动后 401 修复 checkpoint

- 截图显示 API 认证失败，代码确认前端只检查 token 存在。新增 SessionGate 服务端校验、统一 apiFetch 401 处理及中文重新登录提示；403/网络异常和迟到旧请求不清除有效新会话。未推断实际 token 失效原因或数据状态。
- 本轮认证/SSE/HTML 共 11 测试、模拟 API 浏览器四场景、前端 build、Maven package -DskipTests、diff check 通过；后端全量测试未重复执行。新增测试脚本及架构说明，保留原平台移植未提交修改。
- 未重启用户服务、未提交 Git；下一步加载新制品并重新登录验收真实服务。共享权限与真实问数效果验收仍按既有任务继续。

## 2026-10-15 15:42 +08:00｜用户请求保存 checkpoint

- 当前 Codex 会话按 shared-memory 保存。核对 HEAD 已为 70a80f6d，平台移植与登录修复已在该提交中；保存前 standalone 项目无未提交修改，上层未跟踪文件保持原样。本助手未执行提交。
- 复核既有浏览器四场景报告与 package 成功日志，本次未重跑测试；本次只更新共享记忆。服务加载/重新登录后的真实验收仍待确认，完整容量控制仍为未完成事项。具体恢复步骤见 CURRENT_TASK 顶部保存快照。

## 2026-10-15 19:35 +08:00｜来源两次后续提交集成 checkpoint

- 当前 Codex 会话核对来源 a29cc754/6a75b6cf：上传已提交为 bc11f3a5，保留已有容量及框架未提交移植；补正工具发现门锁、send 准入前鉴权及两项回归，安装配套 harness/OpenAI 依赖解决旧 API 编译问题。
- 新增 ADR0078/spec067/平台并发发布说明，导入来源10份ADR与7份spec，更新六份历史运维记录及 README/ARCHITECTURE/配置，明确来源编号和历史验收边界。
- 最终全量613项0失败/错误18跳过；框架28项含2项Docker条件跳过；真实官方Wren集成2项；前端11项与浏览器4场景；构建、格式、打包、diff检查通过。隔离JAR健康/前端及18次ACL检查通过且进程已关闭。默认python缺Playwright已通过既有target虚拟环境解决；Docker接口500导致真实取消测试未启用。
- 无Git提交，未重启用户服务。待同版本框架/JAR/前端/镜像发布，再验收Docker取消、真实用户权限/问答召回、多用户负载与恢复。CURRENT_TASK顶部为完整交接入口。

## 2026-10-15 20:01 +08:00｜来源覆盖与文档核对 checkpoint

- 当前 Codex 会话按用户要求核对 10 月 9 日起来源：14 可达提交/11 主线，3 公共历史已继承，其余功能移植；明确未采用的打包/旧建模/静态预览范围。新增 ADR0079、逐提交核对表和可复现审计脚本。
- 补齐来源布局 ADR/spec 及历史设计/审查记录，来源档案11 ADR/8 spec。更新中英 README、架构、AGENTS、Agent 定义与集群约束，根 AGENTS 增加单文件跟踪例外。
- 本轮审计201源码文件（139相同/62有差异/无整文件缺失）、34 Markdown相对链接0失效、源码锚点与diff检查通过；未重跑业务测试。所有既有代码改动保留，未暂存/提交、未重启服务；后续实际发布与Docker/负载验收按上一检查点继续。


## 2026-10-15 23:27 +08:00｜MANAGE 修复与仅报告遗漏 checkpoint

- 当前 Codex 会话在93ceaee5修复共享管理员草稿连接绑定：复用KnowledgeBaseAccessService#requireManage，保留当前操作者身份；新增12项权限/排队撤权/连接恢复测试并同步架构。
- 固定隔离检出修复后全量625项0失败/错误16跳过，真实Wren集成、格式/package通过；新JAR隔离启动18次ACL、上传413、有限问答排队通过。进程与本轮2容器已清理，未重启用户服务；前端本阶段未改未重跑。
- iridescentpeo来源覆盖仍有5测试、原发布工具/配置及忽略规则差异，详细报告在上层.runtime/merge-review-93ceaee5/lyf-author-coverage-and-manage-fix.md。按用户最新要求只报告不补合；误恢复内容已撤回。根.gitignore并发新增规则保留，不归为本轮成果。未提交或推送；等待用户/同事处理遗漏，服务尚未加载修复。


## 2026-10-15 23:53 +08:00｜来源回归与单机发布实施 checkpoint

- 用户新授权实施选型建议：补回5份LYF测试、忽略规则，保留当前Wren和自动前端构建，新增tar.gz/conf/bin/lib、外置配置样例、带Wren依赖与HTTP就绪检查的Linux启停。用户私有配置哈希不变，已有改动保留；无Git合并/提交/推送。
- 新增ADR0080/spec068/发布指南/脚本验收fixture并同步主文档，当前工作区来源审计139相同/62不同/无缺失。恢复框架11项0跳过（含2Docker）、standalone625项0失败/错误16跳过、前端11项、默认前端构建/package/格式、包排除检查、Linux脚本10场景及真实JAR Linux/Java17启动通过。测试容器已清理，用户服务未重启。
- 证据与验证包在上层.runtime/release-implementation，CURRENT_TASK为恢复入口。下一步用户审阅/提交并按新指南在目标环境准备外置配置和依赖；未验收生产库、真实LLM效果或负载。

## 2026-10-16 01:42 +08:00｜合并后真实全链路验收 checkpoint

- 真实上传/建模/HITL/官方CLI-MCP/显式发布/问数及云MySQL-Doris完成；14库发布，48上传表229809行一致，加云共56数据集242087行，源哈希不变。88固定题中83有效数值81通过，5坏数据识别另计；另2诊断通过。报告在docs/test-reports/20261016-merged-end-to-end-acceptance.md，结论未全部通过。
- 发现P1原生图表跨用户读取、音乐/视频阈值语义及错误memory、断线后旧Wren不能自动恢复；另ONNX多实例内存容量风险与2次Windows快照切换失败。30项核心权限、14项失败边界、准入33用户/同用户20请求、真实Docker取消及闲置回收通过，复杂问数容量与24小时soak未完成，不能当生产全绿。
- 旧19本地库先备份再清理，23账号保留，云只读数据未改；本轮新独立数据/证据保留，测试Java及子进程/标签容器/2浏览器会话已关闭，用户SSH未动。仅测试报告与共享记录变更，业务源码本轮未修复、既有改动保留，无Git提交。建议修复顺序与复现证据见报告/CURRENT_TASK。


## 2026-10-16 10:07 +08:00｜有界采样完成 checkpoint

- 完成外部LIMIT1000内存采样、持久摘要、未知总行数、MANAGE刷新、官方raw/rules资料与建模限长上下文；ADR0080/spec068、README/架构/迁移和验收脚本已同步。
- 本轮后端621项0失败/错误18跳过，真实CLI/MCP2项通过，前端build、浏览器采样3场景、Spotless、diffcheck、package通过。报告和代码入口见CURRENT_TASK。
- 未提交、未重启服务、未对业务库执行迁移或大表压测。按DDL模式补列并加载制品后，旧外部表可手动刷新与发布；真实LLM少探查效果及此前Docker/并发验收待续。

## 2026-10-16 11:14 +08:00｜boundary-lyf 缺类诊断

- HEAD1c90543c起点干净；确认adb49ec2提交遗漏DatasetSampling/DatasetSampleDocuments新增源码及采样配套迁移/脚本，调用方已提交。默认Maven编译22错，匹配隔离框架重验21错；默认SNAPSHOT另有tryAcquire旧依赖，非IDEA缓存。
- 只诊断并记录，未重构替代原实现、未合并提交/覆盖依赖/重启；待原开发者补齐新增文件后重装配套框架、Reload Maven并回归。日志在上层.runtime/dataset-sampling*-compile.log，完整交接见CURRENT_TASK顶部。


## 2026-10-16｜合并版 UI 原型阶段 checkpoint

- 基于 boundary-lyf / 1c90543c 阅读当前官方 Wren、原生 HITL、发布、ACL、双来源 SQL 问答对和采样调用方，原地更新既有原型及说明。保留例行任务，仅静态设计，业务代码不变。
- 初步浏览器主线授权/草稿/检查/发布/问答/点赞/SQL 问答对编辑可交互；响应式、错误状态与最终截图待收尾。8080 未运行、采样缺类与历史验收失败项继续保留；既有记忆修改不覆盖，未提交。


## 2026-10-16｜合并版 UI 原型交付 checkpoint

- 已原地完成 10.16 静态原型与当前设计说明：单主对话、原生文件操作授权/部分失败、显式发布、七类资产、双来源 SQL 问答对、反馈、样本/总量区分、单文档/可选问题，保留例行任务。业务源码未改，无 Git 提交。
- 浏览器 1280×720 的16路由及390×844的15主要路由无页面横向溢出，主线/错误/角色/输入边界等演示检查通过；1536×1024最终截图已对照概念图检查，原型错误日志为空，diff检查通过。仅静态原型检查，后台未启动，采样缺类和历史业务失败项保留。
- 已保存3张设计截图到忽略目录 output/ui-preview-20261016，清理本轮临时脚本/备份/中间截图。下一步用户评审后再决定实施 React UI，具体方案见原 notes；原编译诊断及全链路验收问题仍可从 CURRENT_TASK 恢复。


## 2026-10-16｜工具详情真实体验 checkpoint

- 当前128e953b补齐采样文件后，按AGENTS更新旧框架依赖并编译启动源码，未改业务代码。真实admin体验新建电商库/四表上传6139行/基础发布v2/51秒12工具问答/PythonCSV和PNG/第二轮交互图表/只读建模validate。数据与会话保留。
- 已观察并截图SQL嵌套展示、Python代码重复、图表字符串JSON原文、建模错误仍标完成等UI问题；原HTML增加工具详情设计，正在验证。两次图像草图网络失败，采用既有视觉规范。后端本轮PID38972/8080，收尾处理本轮资源；旧未提交修改与两份私有配置不动。


## 2026-10-16｜工具详情原型完成并转完整验收

- 原HTML及notes原地更新，真实问数/Python/图表/建模原生授权和validate/build已体验，响应式/键盘/下载/边界检查通过；只改设计和说明，不改业务源码。
- 新用户任务：备份并清理本地KB/建模/历史后以最新test_data重新全验收。备份核验及清理已完成，23账号/5连接保留，新四账号和当前源码8080/PID49024启动；正在导入11场景46表202293行，质量缺陷独立记录。详见上层.runtime/acceptance-20261016-refresh/STATE.md。

## 2026-10-16｜原型与当前分支重新验收完成

- 原HTML/notes完成工具详情设计，真实后台体验与IAB响应式/键盘/下载验证；新概念图网络失败沿用原规范。只修改原型、说明、验收报告和共享记录，未改业务Java/React/私有配置，未提交。
- 当前128e953b运行最新11场景46表202293行，14库最终发布、55数据集保留；77固定题72满足数字/查询证据、4错误、1证据不足，另两质量题、4诊断、两云各6题、ACL/生命周期/失败保护/真实10并发两轮/取消/排队撤权/重启检查完成。原始源哈希不变；备份已实际恢复核验，原23账号5连接保留，新测试账号数据保留。
- 未通过上线验收：任务和原生图表隔离、SQL问答对编辑破坏YAML、自然月漏算、复购粒度错误、外联实例恢复、内存会话重启丢上下文；本机内存压力与生产长稳等边界见新报告。没有擅自修复已提交实现。
- 38972/49024/26436及本轮子进程/2沙箱已停，8080关闭，用户前端5173(::1)、数据库、两个SSH隧道及原型18764保留。原型/真实最终截图和完整证据在忽略目录，详细交接在CURRENT_TASK顶部与新测试报告。

## 2026-10-17 00:05 +08:00｜BI前端免登交付 checkpoint

- 用户确认共享密钥由BI前端持有，接受可被提取及冒用包括管理员在内用户名的限制；同域iframe、自动创建普通用户、管理员SQL、JWT30分钟、生产禁密码/开发保留。
- 完成AES-GCM ticket/持久消费/并发建用户、入口配置与会话存储/握手、子路径API/资源、BI交付JS与说明、迁移SQL、ADR0081/spec069及架构/README/部署。私有prod追加随机BI密钥和认证配置，dev/prod加入Git忽略；不输出/提交密钥。
- 最终mvn package含前端/Spotless/641测试（623通过18条件跳过）通过，根路径及/dataagent/构建和包排除检查通过。8新增认证验证、H2并发及真实HTTP；实际前端+模拟BI外壳验证用户切换/刷新不续期/无生产密码框。初次随机密码长度问题已修；报告docs/test-reports/20261017-bi-login.md。
- 夹具18766及两页面已关闭，用户数据库/SSH/前端和此前测试数据未动。未部署/提交，真实BI/Nginx/MySQL迁移待联调；旧P1和向量容量任务保留。既有原型及验收未提交改动完整保留。

## 2026-10-17｜中文规范提交整理

- 用户授权提交当前未提交代码和文档。已读取指定CSDN文章正文，采用中文Conventional Commits、编号正文、按主题分拆、不兼容迁移页脚；AGENTS.md记录后续遵循的格式。
- 提交范围：当前UI原型/说明、全流程验收报告、BI免登与测试/静态资源/ADR/spec/对接部署文档。既有私有配置继续忽略，不上传密钥；此轮不重跑测试，引用上一轮完整Maven package验证。
- 只提交本地Git，不推送、不部署、不改BI正式环境；真实BI联调及原P1继续保留，提交号参照Git历史。


## 2026-10-17 00:57 +08:00｜上线核对完成、复测因Docker暂停

- 三任务顺序及例行任务占位边界已确认，用户休息要求需交互先暂停。当前上线总账原地收敛，TODO/旧发布手册改导航，共享背景去重，ADR0082/spec070及架构/README同步。保留既有P1与历史证据。
- 任务前后端关闭实际功能，10项两角色真实HTTP均403；Maven package -DskipTests通过，新回归已编译未执行。隔离两库/14账号/11库/46文件202293行，45次200+一次IMPORTED_MODEL_PENDING/500；物理和元数据全一致、源哈希不变，文档保存后11库DIRTY。尚未完成建模/发布/问答/性能。
- Docker启动因dockerInference残留套接字系统无法访问失败，可恢复重命名也失败；未删除/重置/改权限或WSL。已询问用户自行恢复或允许进一步排查，待答复暂停；ToolInspector及其他聊天/建模UI未改。
- 本轮JVM12052与SSH38060已停，原用户库/账号/数据未动，隔离资产与私有配置保留.runtime/launch-review-20261017。未提交/推送，详细STATE和阶段报告为续作入口。


## 2026-10-17｜Docker恢复后真实复测进行中

- 用户恢复Docker并明确继续，独立18081/两库与云只读隧道恢复，其他项目容器未动。本轮642项回归通过、18跳过；权限/导入边界/取消/来源通过，图表owner和SQL编辑P1复现，原实现未改。MySQL/Doris各六题真实回执匹配，Doris文件格式通过正常建模对话纠正，保留首失败。11场景建模及问答继续，十人/恢复和工具右栏尚未完成，详细恢复STATE在忽略目录。
- 架构历史说明再收敛，区分当前发布/可选问题/内存会话状态/Harness与Wren记忆，删除重复旧流程正文但保留ADR证据；未提交/推送。


## 2026-10-18 21:50 +08:00｜Wren断连恢复 checkpoint

- 用户批准后完成spec074/ADR0085：明确断连码与官方诊断识别、只读重试一次、授权/取消/预算、同库30秒冷却；发现路径不绕过。无业务模型/官方技能/前端或迁移变更。
- 全量730项707通过23跳过，含两真实云源SQL/校验/Cube恢复、持续断连和真实冷却恢复；46定向通过。最终Maven package含前端实际构建及分发通过；未提交/部署。
- 源发布56文件哈希不变；自建SSH及所有专用Wren/监听已清理，其他服务未动。证据上层.runtime/wren-recovery-20261018，私有fixture不提交。下一步整理提交/BI/Linux/65容量；保留全部既有未提交差异。


## 2026-10-19｜4次中文主题提交 checkpoint

- 用户授权提交并明确分4次。前三次功能提交：f782bf3b, 02bc8e60, 4b0f96e3；本条随第4次文档/静态资源提交收口，最终HEAD为完整待部署基线。主题及迁移见唯一上线总账；本轮没有新增业务代码。
- 检查实际diff/暂存文件/私有配置忽略与新秘密值排除，既有JAR哈希匹配10.18验收记录；引用730项707通过23跳过和Maven构建，本次未重跑测试。生产validate注意0083/0084，子路径需设置DATAAGENT_BASE_PATH重建。
- 不推送/打标签/部署，不改模型与测试资产；模拟BI及云端全流程仍为已讨论未实施。后续为模拟BI本地真实链路、云部署、真实BI及65容量，准确性治理继续跳过。
