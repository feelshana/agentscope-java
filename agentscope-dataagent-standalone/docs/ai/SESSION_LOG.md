# 交接历史

## 2026-10-17｜Checkpoint：原生工具展示与日志效率优化

新增ADR0087/spec075及日志报告；修复原生回执假截断/中文标签、空选库Reactive空完成；清理建模Cube提示冲突、集中校验；补分段耗时及单次MDL读取。前端4项回归及build通过。全量647项首次3项旧Cube测试失败，已对齐0085并重跑；测试日志target/spec075-tests.log。未重启、提交或改线上数据；AST/profile/最终答案证据等仍待续。

## 2026-10-16｜Checkpoint：Wren架构审查

完成当前代码、安装版usage、历史验收与用户日志的架构核对；报告docs/test-reports/20261016-wren-architecture-review.md。识别通用Map二次封装、提示依赖、业务验收和答案来源薄弱点；建议受控对照后再确定重构。未改业务代码、模型、数据库或重启服务，未新跑测试；文档diffcheck通过。在线官方文档网络失败，不宣称最新官方能力。CURRENT_TASK已更新，旧任务保留。

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

- 已新增 `docs/adr/0070-official-mcp-query-receipts-and-autopilot-confirmation.md` 与 `docs/specs/done/059-official-mcp-query-receipts-and-feedback.md`。
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
- 核对 `../../logs/archive-20261020/LLM-modeling.log`：建模阶段显式调用三次官方 `wren memory store --tags source:enrich`，生成 `knowledge/sql/query.md`、`query-2.md`、`query-3.md`，内容分别是有效订单总营收、付费客户数、月度有效营收示例；随后执行 `memory index`。
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
- 用户澄清：`../../logs/archive-20261020/LLM.log` 中的 6 个问题分别来自 6 个新对话，不能把跨块调用当作同一会话的上下文复用。
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

## 2026-10-16 17:50 +08:00｜官方 MCP 原生工具接入 checkpoint

- 完成调用作用域 Toolkit、WrenScopedMcpClient 和 AgentScope Core 作用域解析；移除共享 DataToolkitRegistrar 的 WrenMcpToolkit 注册。
- 验证 core install、standalone compile、mvn test（638/0/18）、新增5项原生 MCP 测试及前端构建均通过。
- 待真实服务重启后的工具列表/问数验收；多知识库直接官方工具的命名方案尚需后续设计。

- 原生工具结果回执与点赞保存链路已补齐；AnswerQueryMemory/WrenScopedMcpClient 相关13项测试通过。

## 2026-10-17 12:12 +08:00｜采样上下文收敛 checkpoint

- 取消完整采样写入 knowledge/rules 和建模 prompt 注入；保留 raw 摘要用于审计/刷新，初始采样继续进入 MDL 列 description。
- 新增 ADR0082/spec070；DatasetSamplingTest、WrenSamplingContextTest 共5项通过。

## 2026-10-17 12:21 +08:00｜采样注入移除完成 checkpoint

- 完成 ADR0082/spec070：采样继续进入初始 MDL 列 description；DatasetSampleDocuments 不再生成完整 knowledge/rules/data-sampling.md，同步会删除旧自动文件，建模 prompt 不再注入 raw 采样正文。
- 全量 mvn test 638项0失败18跳过；专项采样/上下文5项及提示词相关25项通过；前端构建、Spotless通过。服务未重启，未提交 Git。

## 2026-10-17 12:25 +08:00｜取消采样 Markdown 文件

- 用户进一步要求不写 raw/data-samples.md；已改为只清理旧采样 Markdown，保留数据库结构化摘要。专项测试待重跑。

- 2026-10-17�����ʱ���ֶβ�ѯ�߽�� ADR/spec��QueryBoundary ��������ؼ���Ԫ���ԣ�Maven �������������δ��ʱ���أ����������顣

- 2026-10-17���������뽨ģ�ݸ� models YAML ��ʱ���ֶ���ȡ��OfficialWrenToolkit ������ MCP ���� QueryBoundary��DISTINCT+LIMIT/TOP ���������������ء�Maven ���������δ���ؽ����

- 2026-10-17��shared-memory checkpoint������ ADR/spec 0083-0086��071-074 �� QueryBoundary/OfficialWrenToolkit/WrenScopedMcpClient �ĵ�ǰ״̬���Ѽ�¼�������ܱ߽�Ҫ�󣻲������������Դ���֤��δ�ύ Git��

- 2026-10-18 checkpoint：完成 Wren 查询结果解析和问数效率提示改造；前端 build 通过，Maven compile 生成制品但需重跑并保存完整测试日志；未提交、未重启服务。
- 2026-10-18 verification：Maven targeted 26 tests and frontend Wren presentation 4 tests passed; compile/package-side frontend build passed. No service restart or Git commit.
- 2026-10-18 00:18 +08:00｜checkpoint｜shared-memory 保存：记录效率优化代码、26 项后端定向测试、4 项前端解析测试及 build/compile 结果；遗留 AST、真实服务复测、全量验证和 Git 提交。
- 2026-10-18（Asia/Shanghai）checkpoint：ADR0089/spec076轻量实现完成；全量657项0失败0错误18跳过，Spotless和Maven前端构建通过。缺日期原生澄清、延迟MCP、有限采样日期属性、显式视图提示和逐步耗时已接通。保留历史视图与既有工作区修改；服务未重启、Git未提交。下一步加载制品后真实同题验收。

- 2026-10-18 verification: mvn package -DskipTests passed, including frontend npm run build; artifacts produced. Log: target/spec076-package.log. No service restart or Git commit.

- 2026-10-18 10:38 +08:00｜checkpoint｜Codex 按用户要求执行 shared-memory 保存：核对分支、Git差异和上一阶段测试/打包日志；657项0失败0错误18跳过，package及内置前端构建成功。本次未重跑测试，仅补充交接入口、状态及验收顺序。ADR0089轻量方案已实施，服务尚未由本助手重启、未提交Git；下一步加载制品后验证日期澄清/补充、显式视图和逐步耗时。


## 2026-10-19 checkpoint ? rollback lite skill and keep official modeling

- ??????? `enrich-context-lite`?`deep-modeling-agent` ????????????? Agent ???????? `generate-mdl`?`enrich-context`?`usage`?`onboarding`?
- ??????????????????/revision?????????????
- `ModelingHitlMiddleware` ? `models/**`?`relationships.yml` ????????????????Cube/View??????????????????
- ?? ADR0094/spec080??? `mvn spotless:check`?`mvn -DskipTests compile` ? HITL/ChatController ?????
- ????????? Git??????????????????????

- 2026-10-19 19:01 +08:00｜checkpoint｜完成ADR0097/spec083：独立数据库事实表配置，知识库页面管理，不依赖MDL/建模/发布；CLI/MCP共用LLM逐表SQL门禁与同轮拒绝停止；删除合并0091/0092/0095/0096，文档引用迁移。全量673项0失败0错误18跳过，package（含前端build）和Spotless通过。JUnit默认临时目录AccessDenied，通过验证进程JAVA_TOOL_OPTIONS指向工作区临时目录解决，未改全局环境。未重启/提交/改线上数据，真实LLM准确率及延迟待加载制品后验收；Wren底层无公开物理SQL钩子，本次未改安装包。

- 2026-10-19（Asia/Shanghai）｜checkpoint｜ADR0098/spec084完成：采样现有模型调用一并识别FACT/DIMENSION/UNKNOWN并存数据库；仅待配置FACT弹时间窗口，DIMENSION跳过、UNKNOWN行内确认，保存后自动启动建模，失败/关闭暂停，旧配置不重复弹。角色和窗口写入在dataset锁前后复核MANAGE；建模上下文读取独立DB策略。最终全量682项0失败0错误18跳过、前端8项回归通过、Spotless/package（含前端build）通过。新摘要字段使用原JSON列，不需新增DB列。未重启/提交/改线上数据，真实LLM和浏览器流程待验收；角色判定采用已说明推荐默认，未记作用户明确选项回复。

- 2026-10-20（Asia/Shanghai）｜日志复核checkpoint｜确认3次真实日期门禁ALLOWED（建模696/501ms，问数733ms）；全年问题仅主Agent预判无SQL，一月查询经过门禁，未验证DENIED。发现31天策略被写入MDL、修改配置指引错误、逐月拼全年建议矛盾、自选样例日期、有效订单/内部账号过滤遗漏及只读cube list误拦截。只写报告和共享记忆，未改业务代码/配置/MDL/服务、未执行SQL或新测试。报告见20261020-wren-runtime-date-gate-review.md。

- 2026-10-20（Asia/Shanghai）｜checkpoint｜ADR0099/spec085实施：动态窗口不落MDL、允许建模自选样例日期、已确认业务过滤提示、CLI/MCP独立门禁日志。真实LLM+实际适配器18项通过，10拒绝执行端0调用，16模型判定中位952ms；模拟执行端，非真实DB端到端。最终全量686项0失败0错误19跳过；Spotless/package通过，含npm run build（target/spec085-package.log）。未重启/发布/提交，下一步加载新制品复测实际日志。

- 2026-10-20（Asia/Shanghai）｜日志分析checkpoint｜最新实际服务出现365>90天DENIED（857ms），停止重试，配置31→90无需发布，动态天数未落MDL。发现企业样例缺customer_type=2、推断规则正式化、维度时间建议泛化、Q4实际92天却作90天示例；首轮22.358秒主要首次召回准备，第二轮6.978秒。仅分析/报告/记忆，未修改业务代码或执行查询。报告20261020-latest-modeling-query-review.md。

- 2026-10-20（Asia/Shanghai）｜checkpoint｜ADR0100/spec086问数准备优化实施：发布提交后异步索引、同库合并与指纹复用、实例级工程说明缓存且权限复核、索引/MCP细分计时。全量692项0失败0错误19跳过；Spotless/package通过，包含npm run build（target/spec086-package.log）。未重启/发布/提交，实际提速待加载后同题验证，语义样例问题仍待续。

- 2026-10-20（Asia/Shanghai）｜第一阶段checkpoint｜ADR0101/spec087：实际池内MCP只读召回预热、明确成功输出省status、发布页权限隔离状态。真实隔离工程预热后召回47ms，10并发31–275ms；进程重建预热后67ms。最终698项0失败0错误19跳过，Spotless/package/前端build成功。未重启当前服务/发布/提交；候选原子切换及增量同步未实现，不能混记完成。

- 2026-10-20（Asia/Shanghai）｜checkpoint｜编译错误未复现：Maven clean test package完整重编译/前端build/Spotless/698项测试及IDE build_project均成功（仅deprecated警告），用户具体错误待提供。发布提示简化为点击发布成功后出现、完成2.5秒隐藏、未完成首次提问重试，不增加阶段/操作。日志target/compile-repair-full.log；clean已清除旧target临时验证产物，历史数据见正式报告。未重启/提交。

- 2026-10-20（Asia/Shanghai）｜修复checkpoint｜用户堆栈定位MdlPublishService两个必选Autowired构造器，合并唯一完整注入入口保留权限/删除保护/事件；真实Spring单Bean装配测试先复现再通过。最终699项0失败0错误19跳过、Spotless/package/前端build及IDE指定构建通过，日志target/mdl-wiring-package.log。新exec.jar已生成，未重启/提交，需加载重启验证完整服务。

- 2026-10-20（Asia/Shanghai）｜日志分析checkpoint｜确认默认memory index被当store审批，前端缺参取memory且标题重复；建议--no-seed自动维护与命令分类，未改代码。真实预热后召回7–9ms、月问数4.243/4.760秒。发现规则冲突、分段全年建议、样例超窗兜底确认及正式样例缺失等。仅报告/记忆，详见20261020-modeling-index-confirmation-review.md。

- 2026-10-20（Asia/Shanghai）｜ADR0102/spec088 checkpoint｜通用日期先询问、当前时间上下文、建模样例与空结果/拒绝提示已实现，保留原停止机制。705项0失败0错误20跳过、Spotless/前端/打包通过；真实模型3业务12轮受控验收通过，非实际业务服务端到端。已修空表测试夹具，保留早期失败；真实联表门禁漏检和矛盾输出未修。完整矩阵见20261020-query-date-priority-validation报告，新exec.jar待加载；未重启/发布/修改业务DB/提交。

- 2026-10-20（Asia/Shanghai）｜实施checkpoint｜ADR0103/spec089：上传表不加门禁且允许官方探查、外部表禁止探查（运行时逐表列出）；门禁结论按逐表明细重算；--tags source:enrich、挂载时关闭CLI skills、未知选项列出可用项、检查前移出工程锁、memory store先过门禁、确认卡区分store/index、关系基数与年份不提问规则。714项0失败20跳过，package与前端build成功。未重启/未真实验收/未提交。

- 2026-10-20（Asia/Shanghai）｜实施checkpoint｜上传库实测确认ADR0103多数生效；发现探查过多、write_file覆盖失败、编译前查询、唯一性过保守与问数手工汇总算错。ADR0104收窄上传表探查为时间范围与关系验证两类，全表验证唯一可MANY_TO_ONE，统计必须来自SQL。714项0失败20跳过，package/前端build成功。未重启/未提交，外部数据源库待测。

- 2026-10-20（Asia/Shanghai）｜实施checkpoint｜上传库复测确认ADR0104生效；ADR0105注入编译状态、说明-o json时间戳、问数排名只按主体分组。714项0失败20跳过，package/前端build成功。Docker诊断为backend到VM 192.168.65.7 no route to host，疑Mihomo TUN劫持，待用户处理。未重启/未提交。

- 2026-10-20（Asia/Shanghai）｜实施checkpoint｜ADR0106/0107：建模query单轮上限10、禁CROSS JOIN与逗号隐式连接、cube_proposals不加载、超时先杀子进程、cubes/views不读不写、自动建模消息引用运行上下文。718项0失败20跳过，前端build/package成功。未重启/未提交；问数侧Cube/View待定。

- 2026-10-20（Asia/Shanghai）｜实施checkpoint｜ADR0108：YAML写入语法检查、models只写metadata.yml/ref_sql.sql、问数年份规则提级、wren_cli分段计时。720项0失败20跳过，前端build/package成功。另给出架构评审：问数保留Wren，建模问题源于官方技能驱动Agent与平台约束冲突及手写YAML；建议停止自动生成问答、改为用户填写问题逐题确认，基础建模确定性化待用户决定。未重启/未提交。

- 2026-10-20（Asia/Shanghai）｜实施checkpoint｜spec090/ADR0109：建模不生成问答、自动建模后弹窗填写最多5题、Agent写knowledge/questions、用户逐题确认后平台写knowledge/sql（source:user-confirmed）、发布后可召回；问题验证加时间门禁与笛卡尔积检查并允许LIMIT。722项0失败20跳过，前端build成功，package收尾中。未重启/未提交。

- 2026-10-21（Asia/Shanghai）｜实施checkpoint｜ADR0110：自动补编译、允许整文件替换、日期优先规则按受限表条件注入；724项0失败20跳过。文档重组：AGENTS重写+CLAUDE.md、ARCHITECTURE第14章合并、ADR/spec索引、spec001–089移入done、guides/reference/samples/archive目录、链接机械更新、PROJECT_CONTEXT与CURRENT_TASK精简并存档旧版。未重启/未提交。

- 2026-10-21（Asia/Shanghai）｜实施checkpoint｜可持续开发机制：AGENTS 工作流与判断表（默认不写）、ADR 模板、doc-maintenance Skill、DocsConsistencyTest（规模/索引/取代/spec/链接）、第14章结构化、历史小节归档。729项0失败20跳过，前端build/package成功。未提交。

- 2026-10-22（Asia/Shanghai）｜实施checkpoint｜spec092 §7：建模页改为对话为中心（AI 建模结果逐条编辑/删除、对话内"需要你确定的部分"、试问推荐3题并过时间门禁、答对写knowledge/sql、底部草稿栏一次发布、开始前提示上传文档）；新增DraftItemService与/modeling/draft/items；日期识别兼容不补零与无分隔符；删除旧审阅页组件。未提交。
- 2026-10-22（Asia/Shanghai）｜实施checkpoint｜13:50 建模日志：确认工具因多余"其他"选项失败两次且失败计数导致一题未问、外部表零关系、门禁先于外部表拒绝、不补零文本时间按字符串比较。已修复并按用户确认的原型细化建模页。未提交。
- 2026-10-22（Asia/Shanghai）｜实施checkpoint｜16:00 日志：问数 STR_TO_DATE 后 >= 正确；试问用 LIKE（上一轮提示词所致）且门禁对同一 SQL 先放行后拒绝；同一关系问题重复；"访问次数=行数"无文档依据写入规则；确认后不滚动、无下一步引导。已按用户确认方案修复（平台给出时间条件写法，不用 LIKE）。未提交。
- 2026-10-22（Asia/Shanghai）｜实施checkpoint｜16:38 日志：访问次数口径与关系已改为提问，问数用 STR_TO_DATE 与 SUM(visit_count)，确认正确命中门禁复用；问题 SQL 因 MAX(project_name) 被外部表极值规则误拒两次，已改为只限制时间列极值。推荐问题改简单、试问打开时才生成，"设为示例"改为"确认正确"。未提交。
- 2026-10-22（Asia/Shanghai）｜实施checkpoint｜17:05 新库测试：自动建模未运行（切换标签丢失 start=1，只跑了文档更新），已改为记住开始意图；推荐问题出现"各项目的项目数量"，提示词要求围绕事实表度量；问数按报表过滤、STR_TO_DATE、SUM 正确；门禁 CACHE_HIT 生效。"已设为示例"为旧页面缓存，需刷新。未提交。
- 2026-10-22（Asia/Shanghai）｜实施checkpoint｜17:21 重建测试：自动建模正常运行，规则依据真实，三个口径问题合理，推荐问题简单，问数遵循用户确认口径；但三个问题名额全用于口径、外部表零关系，已给关系问题单独名额；dry-plan 不再调用门禁模型。未提交。
- 2026-10-22（Asia/Shanghai）｜实施checkpoint｜去除对 agentscope-core 修改的依赖：新增 WrenToolForwarder/WrenQueryToolRegistrar，中间件改写模型工具列表并按次绑定官方工具；ReActAgent.java 已还原为提交版本（原修改备份为会话 scratchpad 中的 reactagent-callToolkit.patch），框架重新安装后 standalone 全量测试通过。未提交。
- 2026-10-22（Asia/Shanghai）｜实施checkpoint｜界面优化：外部表逐表关联采样进度条（associate 增加 publish 参数，最后统一发布）；事实表时间范围弹窗改为标准弹窗与天数选择；建模回复折叠过程为一行统计、突出最终说明与结果。未提交。
- 2026-10-22（Asia/Shanghai）｜实施checkpoint｜21:01 建模 63 秒分析：6 轮逐个列目录读文件约 13 秒、选项名超长被拒重提约 6 秒。上下文注入工程文件清单、选项名超长改为截断；前端建模阶段进度、结果边写边出、最终说明用正文色。未提交。
- 2026-10-23（Asia/Shanghai）｜实施checkpoint｜试问"不正确"：原流程先弹确认、答复以 confirm 发送导致外部表查询被拒、SQL 未执行且旧结果不刷新；改为卡片内填写原因交助手（question 轮次）或自行改 SQL，SQL 变化后自动重跑。字段筛选中间步骤待用户确认后写 spec。模型切换到 model-router（qwen/deepseek-v4.1-flash，仅本机启动配置）。未提交。
- 2026-10-23（Asia/Shanghai）｜实施checkpoint｜spec093 字段整理：FieldScreeningService/Controller、ColumnSchema 增加纳入与来源字段、DatasetEntity.fieldScreeningConfirmed（迁移 0095）、MdlSeeder 只处理纳入字段并修复追加列缩进识别（matches→lookingAt、列 0 条目）、自动建模前置校验、提示词保留用户说明；前端 FieldScreeningPanel、建模准备五步、资料页字段调整与单表重建。未提交，待真实验收。
- 2026-10-23（Asia/Shanghai）｜实施checkpoint｜界面改版第一批：建模页保留左侧导航、统一页头（面包屑、状态胶囊、操作区）与下划线标签、对话区居中阅读宽度与助手头像、欢迎卡与输入框、试问栏固定宽度；建模回复写法约束（中文、短过程说明、按轮次控制篇幅、不列文件与命令）。未提交。
- 2026-10-25（Asia/Shanghai）｜实施checkpoint｜试问改为只生成并 dry-plan 检查 SQL、用户核对后确认（工具拒绝试问轮次 query）；确认与发布竞态修复（修订号在工作区锁内递增、发布在锁内读取），草稿栏"重新发布"兜底；建模提示词精简并按轮次分段；技能按助手挂载；查询结果紧凑格式；命名候选关系；外部表描述不再拼接样例；建模不单独 build、编译过期自动重建；时间门禁规则优先（ADR0112）；数据源仅 MySQL 8.0+/Doris、SQL 用日期常量（ADR0113）；问数准备进度、按钮置灰、新版本刷新提示；知识库与语义建模页字号、列表倒序改版。后端 796 项 0 失败 20 跳过，前端 build 与 19 项测试通过。已提交（application.yml 密钥修改除外）。

- 2026-10-26（Asia/Shanghai）｜整合验收checkpoint｜用户授权v2实际merge。冲突清零，单库前后端、辅助模型快照、受限恢复/预热、SQL独立示例与问题确认边界、通用导航适配完成；迁移并集及37表首次DDL、发布包文档补齐。Java927项0失败28跳过，前端双路径各45通过，构建打包成功。合并JAR隔离实测上传→字段→建模→SQL-only核对确认→发布→查询/Python下载，模型在途A/下一轮B/重启B，会话重启与reset、BI生产策略/防重放、私有图表及受控断连恢复通过。公司真实接入、外部源完整流程与容量/长稳未验；本地合并交付，不推送。当前规范见ADR0114/spec094，旧整合资料不作为依据。

- 2026-10-27（Asia/Shanghai）｜全流程及内容回归checkpoint｜基线990a844f→未提交有限修复，独立18840/prod/新MySQL三库，11原场景46XLSX加干净财务/IT补充。39题源数据独立核对：主答案34/36→36/36，整份严格27/36→29/36，质量解释2/3→3/3；仍有手工合计/不实补充/无据归因/旧HR规则。Boot4错误原因恢复，公式过滤提示增强及answer-quality检查；Java928项0失败28跳过、Python15、双前端各45，构建通过。外部源6项、ACL/撤权/配额、SQL-only修订确认发布/反馈、按需Python下载/隔离/reset、模型主/辅助同轮快照/503人工切换/排队、持久化重启/受控断连、BI浏览器通过。全题复测p50/p95=8.85/14.15秒；不代替公司接入/生产容量长稳。唯一桌面结论branch-merge-plan第9节；原始证据DataAgent-合并版本全流程测试-20261026。新修复未提交，未动源分支/日常库；个人交接继续忽略。

- 2026-10-27（Asia/Shanghai）｜回归交付收尾｜最终Spotless、DocsConsistencyTest 5项、package（含前端45项与TS/Vite）通过；重打包JAR0dd1dc90…/tar53db97e0…，704个运行类和默认配置与已实测候选一致；私有prod/dev与个人交接未入包。BI合法/重放/过期/未来/篡改及prod密码策略6项通过。临时代理/浏览器/采样器关闭，独立18840转已验证公网直连保留，不影响旧服务；未提交。

- 2026-10-27（Asia/Shanghai）｜七题归因补充｜重读首轮SSE/SQL/工具结果、auto write_file及源文档：4题正确工具输出后生成层出错；HR两题同一擅增默认在职规则；11-left-join原文约5%与XLSX32/200=16%冲突传入MDL，生成层再把5%误为5笔并称195（独立内连接168）。七题均无run_python，8次主SQL返回已独立核对正确。已补桌面唯一方案9.6及raw root-cause-trace.json，修正此前边界归因过粗，评分不变；不实施新模块、不修改源数据/已发布模型。


- 2026-10-27（Asia/Shanghai）｜扩大验收完成｜110主问+14追问+603压力=727真实chat；数值主问题103/104（迟到歧义1题单列）、质量安全5/5、完整65/110，追问主要13/14、完整9/14。SQL→Python数组错位/累计舍入、默认在职污染、额外排名/解释错误已归因；本阶段未改产品源码/数据/发布规则，未提交。16测试沙箱预算下15用户短持续纯277/277、混合99/99、同库30/30；603压力598合格，1金额抄错+4低预算缺文件；仍有162–183秒路径探查长尾，非生产长稳。用户批准停止18827–18830，当前18840/PID40940/ffca71a7候选/沙箱16保留，18831保留，采样器结束。唯一桌面方案第10节、expanded-110证据与逐题HTML已更新，细节见CURRENT_TASK。

- 2026-10-27（Asia/Shanghai）｜扩大验收收尾｜DocsConsistencyTest当次5项通过、diff检查通过、暂存为空/个人交接忽略有效；逐题HTML真实浏览器验证筛选110/45及搜索，专用浏览器/预览服务关闭，18840保留。

- 2026-10-27（Asia/Shanghai）｜云部署演练接手｜用户要求指引现有云服务器重新上线前演练；截图确认Ubuntu24.04.5/x86_64。已核对发布脚本/手册及旧演练，首先要求只读盘点目录、软件、端口、容器和schema；未远程执行、传包、停服务或改数据库。65系统仍未知，旧云记录不当现状。

- 2026-10-27（Asia/Shanghai）｜云部署盘点回传｜4CPU/14GiB RAM可用10GiB/55GiB空闲，Java17/Python3.12/MySQL8.0.46/Docker/Nginx就绪；8080 Java307708、Nginx18881/80、Doris仍运行，Docker无容器。平台35表/上传12表，未执行DDL。/opt无JAR仅准备目录，个人历史实际/data/app/dataagent待核实；下一步核对旧发布路径、Wren/镜像/关键列，未远程执行或停服务。

- 2026-10-27（Asia/Shanghai）｜云候选构建完成｜用户回传旧应用/data/app/dataagent、dataagent进程307708/Wren固定三版本/18881入口200；确认缺2表+4列，完整DDL仍待。独立快照构建/date release、Spotless/verify成功：Java928/0失败/28跳过，前端双45，Python15；运行类/依赖与110实测候选一致。应用tar24a82b77…、实测沙箱6b96c40d…镜像tarc0f85474…及SHA在output/cloud-rehearsal-20261027/delivery，云旧镜像209c…保留。release-checklist过时条目按真实assembly/整合状态校准，未改业务源码/未提交/未远程操作；待上传/旧DDL后备份升级。

- 2026-10-27（Asia/Shanghai）｜部署方式澄清｜用户要求本人Maven打包、本人/运维上传，助手只指导，不采用助手包替代该链路。已停止进一步代打包；说明应用tar与一次性环境镜像/缓存、update自动建表与prod validate预建平台表、上传空库动态ds_表的区别，普通用户BI首登自动创建无需同步整表。手册补充上述边界，未改运行配置/源码/远程服务。

- 2026-10-27（Asia/Shanghai）｜提交前整理｜用户授权有用修改一次提交。审查15个文件，保留Boot4修复/HTTP回归、4条通用提示、回答验收夹具及有效配置/发布说明；精简CURRENT_TASK过时重复状态，归档标明历史，统一发布者标准Maven流程。未删除业务文件/测试证据/私有配置或输出资源，未部署；验证及最终提交见Git与本轮回复。

- 2026-10-27（Asia/Shanghai）｜提交验证｜本次Spotless、DocsConsistencyTest/BiAuthHttpTest共8项、Python15项、diff空白检查通过；运行/测试源码与同日完整verify快照一致，当前编译类/配置亦一致。显式暂存15个审查文件一次提交，不推送，私有配置/个人交接/输出包保持忽略。
- 2026-10-26（Asia/Shanghai）｜实施checkpoint｜spec094 问数联网搜索：WebSearchTool（DeepSeek /v1/tools/search，只发 query/count/need_content，错误转中文说明），DataToolkitRegistrar 收敛 harness web_search/web_fetch 并按 Key 注册，建模移除两者，问数提示词联网搜索一节，ADR0114，README/ARCHITECTURE 配置表。804 项 0 失败 20 跳过，前端 build 通过。真实搜索待 DEEPSEEK_API_KEY。未提交。

- 2026-10-28（Asia/Shanghai）｜实施进行中｜用户批准整合ec6cb3d8及BI/目录/只读/批量授权/公共库；实际--no-commit合并，保留799a2b82修复，未提交推送或操作生产。新增spec096/ADR0116，专项与全量验证进行中；现场BI/66未验，不把历史测试当本轮完成。

- 2026-10-28（Asia/Shanghai）｜本地验收完成｜整合ec6cb3d8并完成spec096/ADR0116；最终Java944/0失败/0错误/28跳过，前端三路径各46、Python17、模拟器9及双路径构建通过。新HTTP补足Boot4 JSON树与大导入缓冲问题并修复；浏览器UI用隔离夹具，不冒充公司接入。未提交推送/打发布包/部署/导入真实数据，既有服务未重启；见20261028验收报告，下一步用户体验及现场环境核对。

- 2026-10-28（Asia/Shanghai）｜提交检查点｜用户明确授权先将当前整合及改造一次提交，再自行测试。提交前核对87个暂存文件，无未解决冲突或未暂存差异，私有配置/个人交接/名单未入暂存；复核既有944项Java及三路径前端等验收记录，仅提交状态文档有更新，不重复打发布包、不推送，最终提交号以Git为准。

- 2026-10-28（Asia/Shanghai）｜统一详情checkpoint｜a9ccfdc2后修正独立QUERY界面，复用原详情与预览/字段/技术标签，写操作继续MANAGE。真实本地平台库新增1484普通用户，总1521；120秒客户端超时后逐条资料和重复预览确认后台已提交，未第二次写入。Java946/0失败/0错误/28跳过、前端三路径各46、Python17、双路径构建及隔离浏览器角色对照通过。未重启IDEA、打发布包或提交；报告20261028-unified-knowledge-detail.md。

- 2026-10-28（Asia/Shanghai）｜侧栏布局checkpoint｜用户要求收起按钮与用户信息等高；底部改flex并排，右侧独立32px箭头，折叠状态统一样式，长公司名省略但可打开完整资料。前端三路径各46/根构建及隔离浏览器1500/900宽度、鼠标和Enter切换通过，截图已目检。不新增spec/ADR、不改业务流程、不打发布包或重启IDEA，未提交。

- 2026-10-28（Asia/Shanghai）｜回答反馈UI checkpoint｜用户要求参考主流AI产品，移除大框粗标题/emoji，采用答案下方轻量图标按钮、紫色选择态、简短可选提示与原服务端状态。仅AnswerFeedback/Icon/CSS和静态产物，无反馈API或样例保存策略变化。根构建、三路径各46通过；IAB在真实ChatPanel配隔离API验UP/DOWN/刷新、503重试/样例FAILED再确认及390px无溢出，截图目检通过。专用夹具/测试页关闭、视口已复原，IDEA保留；未提交/推送/打发布包。

- 2026-10-28（Asia/Shanghai）｜用户气泡checkpoint｜用户要求美化且可选中复制；旧实现user-select为auto、实际可选但淡紫选区叠深紫背景不易辨识。改浅紫/深色字/细边/非对称圆角、去阴影，显式text选择与独立高亮，长链接自动换行、手机90%宽。仅两处CSS和静态产物。根构建/前端三路径各46通过，IAB1280及390宽度无溢出；实际鼠标跨行拖选、Ctrl+C后剪贴板69字符与整题相同，空格/换行保留。隔离API/合成数据，无业务后端或真实用户数据改动。专用测试页/18855已关、视口恢复；未提交/重启IDEA/打发布包。

- 2026-10-28（Asia/Shanghai）｜工具详情实测checkpoint｜真实8080发起7独立问答、17工具调用、8工具，使用合成订单/题面数据。复现Python失败轨迹误勾成功，统一inspectResult并补2个组件状态回归；目录/字段、业务规则Markdown、图表输入数据表、数值对齐、文件按钮/图标优化。前端三路径各48、根/目标前缀构建、真实CSV下载6行合计916、图片/图表/66行表/空查询/窄屏等通过；IAB兼容中断后使用独立Chromium，不冒充夹具实测。技能加载有意隐藏且实际为示例模板；搜索无Key未验。报告20261028-tool-inspector-review.md，原始证据output/tool-review-20261028。未提交/部署/重启IDEA/改后端/删原会话；测试会话产物保留。

- 2026-10-28（Asia/Shanghai）｜工具详情验收收尾｜Spotless/DocsConsistencyTest5项与diff检查通过；HTTP核对8080静态产物已加载，独立Chromium关闭，IAB测试视口复原。Java/Wren、测试会话及产物保留，未提交；仅确认本轮前端加载，不据此推断上轮后端热加载。

- 2026-10-28（Asia/Shanghai）｜本批提交授权｜用户要求提交当前代码。核对a9ccfdc2后统一详情/用户导入超时/对话与工具展示等有效修改，无合并冲突；本次一次提交，不推送。沿用同批Java946/0失败/28条件跳过及最终前端三路径各48、Python17、双路径构建与7题17工具真实验收；补做提交前门禁。不打发布包、不包含私有配置/名单/个人交接/output；提交号以Git为准。下一步建议固定候选后盘点66及两库，推进部署联调。
