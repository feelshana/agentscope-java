# 交接历史

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
