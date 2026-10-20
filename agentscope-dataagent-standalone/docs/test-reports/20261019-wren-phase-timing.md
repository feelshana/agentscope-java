# 2026-10-19 Wren 分段计时与冷、热进程对照

## 范围和交付状态

用户授权先补计时、依据日志解释耗时。本轮增加Java分段日志及按需Python诊断脚本；未实施进程常驻、增量索引、异步保存、业务模型调整或UI修复。Java代码本地编译和定向测试通过，尚未打包替换云端JAR。云端仅以dataagent账号在独立临时目录复制专用验收库的已发布工程并运行诊断，结束自动清理，前后源文件哈希一致；未重启应用、改原用户工程或官方Python源码。

## 新增可观测性

- `ProcessWrenCli#run`：随机trace、固定操作分类、库ID、准入、准备、spawn、process_wait、read_output、cleanup、total；超时/非零退出/启动失败分别标记。`process_wait`包含Python解释器、依赖导入和实际CLI工作，不能直接称纯计算时间。
- `WrenPublishedProject#indexed`：锁等待、prepare_project、fingerprint、rebuild_index、check_backend或reuse_index，以及总耗时。保持原指纹、索引及权限行为。
- `AnswerQueryMemory#feedback`：owner锁等待、组锁等待、工程初始化、store_example、update_published_index、总耗时。原失败回执仍保留；不新增SQL和问题内容到计时日志。
- `OfficialWrenToolkit#execute`补workspace_lock_wait；`WrenInstanceRegistry#withGate`/`doCall`/`invoke`补门锁等待、受锁执行、实例获取、RPC耗时。取消/超时/授权/断连恢复契约不变，源码原有ClassLoader修复保持。
- `bin/profile-wren.py`：只在显式运行时启用标准库调用边界跟踪；同环境调用官方`wren.cli:app`，报告只含阶段、调用次数和毫秒，无参数、SQL、问题、向量、源码路径或原始调用栈。JSON用独占创建、0600模式，拒绝覆盖已有文件。原CLI退出码/异常保留；退出恢复此前的profile回调。

报告中`inclusiveMs`可能重复计算嵌套调用；`outermostMs`去除同阶段递归重叠，但不同阶段仍可能包含彼此，不能相加。Python解释器/sitecustomize启动不在内部跟踪范围，须看外部进程计时。深度跟踪明显放大导入开销，只按需诊断，不挂到生产默认请求。

## 云端真实对照

同一服务器、同一Python3.12/Wren0.15/ONNX模型缓存，HF离线模式；六表工程，索引报告67项schema资料，不是明细全量嵌入。

### 不启用深度跟踪的独立CLI

| 命令 | 外部总耗时 | 结果 |
|---|---:|---|
| memory store | 4.672秒 | 退出0 |
| memory index --no-seed | 7.091秒 | 退出0、67项 |
| memory recall | 4.606秒 | 退出0 |

此前一轮独立运行store4.690秒、index7.157秒、recall4.556秒；属于单次对照，不是稳定性或负载SLO。

### 同进程、不启用全局跟踪的独立API对照

| 阶段 | 耗时 |
|---|---:|
| 导入MemoryStore、ONNX Runtime、tokenizers | 366.631毫秒 |
| 首次创建MemoryStore，包含延迟导入LanceDB | 1563.761毫秒 |
| 加载模型运行环境，包含分词器、配置与ONNX会话 | 2348.806毫秒 |
| 已加载模型对单条文字向量化 | 9.717毫秒 |
| 已加载模型后，连续保存两条示例 | 17.580 / 16.067毫秒 |
| 同进程连续召回两次 | 18.664 / 16.616毫秒 |
| 同进程对整个67项schema重新计算向量并写入 | 2698.857毫秒 |

首次MemoryStore构造不是一次“向量数据库连接耗时1.56秒”的证据：官方构造函数内部首次import lancedb包含在该阶段。独立API探针使用固定测试文字/SELECT常量，不执行物理SQL；不能将其十几毫秒当作页面最终保存时延承诺。

### 按需细分跟踪

跟踪模式store9.146秒、recall9.271秒，明显高于上表普通CLI；不能把跟踪后的导入时间当作生产原始耗时。该模式用于辨别内部阶段：单条推理9.243毫秒，实际LanceDB add7.536毫秒、召回to_list12.152毫秒，ONNX会话创建1284.734毫秒。全schema推理约2.4秒，模型加载与依赖导入仍另有成本。参数和业务输出未进入汇总JSON。

最初cProfile试验中嵌套/native调用的累计项不适合直接解释本次墙钟时延，已弃用；最终脚本使用`sys.setprofile`调用边界及单调时钟，并增加不启用全局跟踪的对照。原始试验保留，仅最终cached-filter日志和无跟踪对照用于结论。

## 本地Java实际集成日志

使用真实`ProcessWrenCli`及`WrenPublishedProject`，复制相同六表工程到JUnit临时目录，默认HF缓存已准备；无模拟CLI结果，不执行源数据库查询。

| 阶段 | 第一次准备 | 示例变化后 |
|---|---:|---:|
| 工程准备 | 103.539毫秒 | 19.582毫秒 |
| 指纹计算 | 42.831毫秒 | 18.571毫秒 |
| 官方memory index进程 | 5083.060毫秒 | 4880.576毫秒 |
| 官方memory status新进程 | 2234.333毫秒 | 2493.827毫秒 |
| 索引准备总计 | 7480.293毫秒 | 7414.198毫秒 |

源未变时索引准备20.225毫秒，未启动index/status；后续独立CLI召回3826.424毫秒。Java spawn约几毫秒、读取输出约零点几毫秒；本次本地无锁竞争，不能据此断言真实并发不会排队。

## 结论与后续方向

1. 数据库保存/检索本身不是当前单条示例等待几秒的主要部分；云端同进程加载后实际保存/召回约16–19毫秒。反复冷启动和依赖/模型初始化是明确成本。
2. 示例变化触发`WrenPublishedProject#indexed`，现有指纹将MDL与knowledge一起判变化；官方index重新计算整个schema并同步示例。初始化之外，整批计算也有约2.7秒实际成本。
3. 平台索引后另开CLI执行memory status，本地实际又耗2.2–2.5秒；不能认为只是一次常数时间状态读取。
4. 因此“store新进程→index新进程→status新进程→后续召回/模型回答”会叠加为十几秒甚至更久。此前云端20秒是业务链路总体回执，本轮没有在原云JAR上逐段解释那一次请求全部时间；不把本轮数值拼接为它的精确分解。
5. 下一步应优先评估减少重复冷启动、仅示例变化时避免全schema重算、避免重复状态进程；保留源文件与索引一致性、失败恢复、权限及显式确认。不以延长超时或隐藏等待替代优化。本轮尚未实施这些优化。

## 验证与限制

- Java定向68项通过：ProcessWrenCli4、WrenPublishedProject2、AnswerQueryMemory8、SharedOfficialWrenAccess1、Registry12、ConnectionRecovery30、OfficialWrenToolkit10，以及显式启用的真实WrenTimingIntegration1。计时改动前后的既有权限/隔离/取消/连接恢复测试保留通过。
- Python `python -m unittest discover -s src/test/python -v`：11项通过，其中新增诊断6项，覆盖安全阶段白名单、递归时间、失败退出/异常、参数不泄漏、不覆盖报告。
- Spotless只格式化本轮目标文件，`git diff --check`通过；命令行参数转义错误已修正后重跑，不计产品失败。
- 未重跑完整Maven/UI/并发/长稳；无新JAR部署、无生产优化承诺。应用配置中其他来源的max-rows/max-cells改动保留，不属于本轮计时代码。
- 证据：上层`.runtime/cloud-perf-20261019/remote-phase-profile-cached-filter.log`、`remote-cold-warm.log`、`java-real-timing.log`、`java-timing-regression.log`、`official-timing-tests.log`、`python-timing-tests.log`；本地工程副本在`.runtime/wren-phase-timing-20261019`。临时云端工程已清理、发布源哈希不变。

## 后续完整问答链路复现与根因方案

用户要求以用户等待为准继续分析。已在真实云服务将独立库一个现有示例的问题增加诊断标记、SQL不变，经真实编辑与显式发布v9；发布1.380秒。发布后第一问召回20.557秒、SQL0.112秒、整轮25.522秒；不同新会话立即问同题，召回0.046秒、SQL0.059秒、整轮5.498秒，两轮均得162862。第一问只有一个召回调用，其20秒为真实后台等待。

主请求只读进程观察见index约10.136秒、status约2.190秒，MCP并存。另用真实官方stdio MCP在临时副本按平台顺序复现：初始化0.874秒、发现0.007秒、index7.306秒、status2.300秒、首次召回3.741秒、再次0.022秒，总14.252秒，源哈希未变，临时进程关闭/副本清理。这不是前述20.557秒的逐毫秒分解，但确认了整条链路中的重复准备。

源码链路确认：发布仅编译/切换快照并invalidate实例；召回适配器同步indexed（全schema+额外status），实际只读MCP随后仍首次加载自己的向量模型。问答MCP是常驻并能复用模型，不能说每次问答都新开CLI。点赞/保存持有同库锁并重建，可能把等待传播给其他用户。框架collectList后统一发结果结束事件会拖延同批快工具展示，属于附加问题。

详见待确认[spec077根因及修复方案](../specs/077-query-memory-readiness.md)：准备前移到发布/就绪阶段，拆分schema与示例同步，实际查询实例按资源预算预热，精简status及有界等待，再处理框架逐工具完成通知；要求以真实端到端及10人场景验收。本轮只分析/测试和文档，无进一步产品代码修改、服务重启、Git提交；原用户库未动，专用测试库v9及历史保留。证据e2e-cold/warm事件、observe-e2e.log、remote-mcp-chain-final.log；受控MCP首次脚本cwd遗漏已修正重跑，不列产品失败。
