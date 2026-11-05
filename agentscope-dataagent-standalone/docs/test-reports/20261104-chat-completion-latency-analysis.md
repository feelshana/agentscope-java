# 2026-11-04 问数回答结束与按钮延迟：分段计时和真实复测

## 范围与当前状态

用户授权补计时、自测后再给方案。本轮仅增加可选诊断，不改变保存顺序、SSE事件语义、模型选择或存储后端。liyufan_dev@2948807f，原有文档和未跟踪文件保留，未提交/push。

本机独立Java诊断进程127.0.0.1:18082，复用dev配置及用户当前可用模型，通过官方Wren查询用户截图中的“测试本地数据源”，独立诊断会话3轮、每轮真实查询和图表。前端为现有5173，通过独立Playwright浏览器将其请求转发到诊断后端。原IDEA 8080/PID39708、Vite5173/PID20736保持运行；未连接66或正式数据库，不修改知识库模型/数据源或点赞。

## 计时实现

- core的CallTiming为RuntimeContext中的可选Observer，仅传阶段、单调起点及状态，不传消息/SQL/凭据；未装Observer不写诊断，Observer异常不改变操作结果/异常。
- ReActAgent#saveStateToSession记录实际状态持久化；SessionTranscriptWriter#appendMessages记录本地load、远端syncFromRemote、本地flush、索引；TranscriptMiddleware记录追加总耗时，包含关系不能相加。
- SandboxLifecycleMiddleware记录persist/release；网关直接记录history.projection（其上下文与框架内部调用上下文不同，使用requestId关联）；ChatController记录done组帧时间，此点不是TCP已送达。
- ExecutionTimingMiddleware记录每次模型最后正文到模型流结束的间隔；正文最终轮次取最后一个有文本的模型调用。保留原model.end和turn.end，避免误用为端到端结束。
- 浏览器测试临时脚本旁路读取SSE副本，记录最后token/done；MutationObserver记录反馈工具栏DOM出现及按钮启用。计时不跨机器相减，没有新增遥测HTTP端点/生产前端代码。

## 实测（毫秒）

| 环节 | 第1轮 | 第2轮 | 第3轮 |
| --- | ---: | ---: | ---: |
| 最后正文至模型流结束（后端） | 15 | 20 | <1 |
| 核心状态持久化 | 35 | 30 | 14 |
| 会话本地load | <1 | 7 | 1 |
| 会话远端分段回读 | **1836** | **1866** | **481** |
| 本地会话追加 | 7 | 11 | 2 |
| 本地会话索引 | 3 | 5 | <1 |
| 会话追加总计（包含上列子项） | 1853 | 1895 | 486 |
| 沙箱persist/release | 均<1 | 均<1 | 均<1 |
| 展示历史投影 | 未单独捕获 | 87 | 30 |
| 浏览器最后token至done | 未捕获 | 2085.6 | 538.2 |
| 浏览器最后token至工具栏DOM出现 | 未捕获 | **2098.0** | **551.5** |
| 浏览器done至工具栏DOM出现 | 未捕获 | 12.4 | 13.3 |
| 工具栏DOM出现至评价按钮启用 | 未捕获 | 33.4 | 19.2 |

第2/3轮远端回读占浏览器最后token至工具栏DOM出现间隔约89%/87%，是本机复现的主要等待；核心状态保存和前端渲染占比较小。本地3轮小样本，不外推66或所有长会话的耗时/分位数。

第1轮浏览器临时计时脚本最初漏了/chat路径，因此只有后端数据；发现后修正，在后2轮完整捕获，未把缺失值当0。第1轮网关调用上下文未带框架内部Observer，修正为模块已有logger直接记录后，后2轮完整捕获history。测试服务首次因沿用standalone实例标识被实例锁拒绝，改为专属latency-probe-20261104，不解除用户实例锁；一次Spotless遇Windows映射文件锁，重试通过。命令阶段的路径/格式匹配错误均已修正，最终门禁以下述结果为准。

## 根因解释与方案（优化未实施）

TranscriptMiddleware通过concatWith等待会话追加完成，SessionTranscriptWriter每轮新建SessionTree并调用syncFromRemote；默认ObjectStoreTranscriptStore依赖文件系统枚举、读取全部远端分段。问数助手的默认后端为Docker沙箱，现有本机路由只覆盖agents/与.agentscope/bus/，分段键default/data-agent/<session>/events不匹配，仍走Docker访问。异步上传已经存在，真正阻塞完成的是远端回读。历史投影在done前等待但本次只占30–87ms。

推荐优先通过框架已有TranscriptStore扩展，将会话记录分段明确落到宿主持久目录，保留用户/会话隔离、恢复和备份范围，使历史读取无需Docker；复用现有FilesystemTranscriptStore或宿主文件系统路由，不重写会话系统，不粗暴删syncFromRemote。

核心Agent状态保存与本地会话追加仍在完成前等待：实际只有十几到几十ms，承担下一轮上下文、版本一致性和恢复保障。展示历史索引可在后续复用既有后台队列，但属于次要收益；若后台化必须验即时刷新/新会话/立即评价/下一轮/失败重试/重启恢复。当前不提前发送done，也不增加答案结束事件来掩盖等待。

上述存储方案尚未实施；若用户选择实施，先按AGENTS明确相应ADR及验收范围，不迁移或清理既有会话资源。目标是去掉本机观测到的0.48–1.87秒Docker回读等待，不能把该目标写为已取得的优化效果。

## 验证及产物

- core CallTimingTest：2项通过，覆盖Observer异常不改变成功/失败、原异常身份、无Observer和上下文隔离。
- harness SessionTranscriptWriterTest4项、SandboxLifecycleMiddlewareCallbackTest4项、SandboxLifecycleConcurrencyReproTest1项通过，0跳过；追加幂等与消息链保持，回读/追加/索引Observer已断言。
- 模块ExecutionTimingMiddlewareTest1项及DocsConsistencyTest5项通过；补断言最后正文尾部计时、请求关联和核心回调。共17项，0失败/错误/跳过。
- 框架core/harness/OpenAI依赖已按项目要求install（该构建跳过测试，相关测试另单独执行）；限定修改文件Spotless通过，git diff --check通过。未跑无关全量、生产压测或前端构建（未改前端源码）。
- 原始阶段日志和安全数值摘要：忽略的target/latency-probe-20261104/；浏览器数值browser-timings.txt，截图output/playwright/chat-completion-latency-20261104.png已视觉检查。
- 诊断服务及专属浏览器已停止；Docker容器6b75523bb400经managed=true/instance=latency-probe-20261104两标签核对后仅stop、数据保留；原IDEA/Vite仍监听8080/5173。未清理用户资源或删除诊断会话。

## 66 上线后录屏与下一次部署的诊断口径

当前正式版本 `a422041f`。两段录屏（北京时间 14:57、14:59）浏览器最后 token 到 done 分别为 1,786 和 2,557 ms，按钮与 done 同帧或相邻采样帧。对应 66 同 trace 最后 model.end 到 turn.end 为 435 和 992 ms。图表早于正文最后一段出现；第二段图表到按钮约 6 秒。上述窗口起止不同，不能相减后归给代理或模型，也不能用它们单独认定回读占比。

本地补充了同请求诊断。保存顺序和回读流程沿用现状：

- `completion.model.tail` 是 Java 收到最后正文增量到该模型流结束，按最后一次有正文的模型调用取值。
- `completion.sse.done` 的 `durationMs` 是 Java 生成最后一个 token 事件到生成 done 的单调时钟间隔；`NO_TOKEN` 跳过该耗时样本。此处度量 Java 组帧，浏览器侧单独读取 EventStream 的间隔，不跨机器直接相减墙钟时间。
- `completion.transcript.segment_sync` 包含枚举、读取、解析、去重和合并；同时输出 `segmentsListed`、`segmentReadFailures`、`uniqueEntriesRead`、`entriesMerged`。后者只计新增到本地会话树的事件。分段同步是 `completion.transcript.remote_read` 的子区间，不重复相加。存储实现返回空列表时，零列出仅代表调用方没有收到可用分段。
- 普通流式问答的 done 增加同一个服务器生成的 `requestId`，对应计时日志中的字段。计时不记录正文、SQL 或凭据。

下次合入并部署后，同一个知识库用原问题新开对话跑一次，记 EventStream 最后 token 与 done 时间，并从 done 的数据复制 requestId。在 66 执行：

```bash
cd /data/dataagent/formal-app/logs
request_id='替换成 done 中的 requestId'
grep -hF '"requestId":"'"$request_id"'"' execution-timing*.log |
  grep -E '"phase":"completion\.'
```

如果后端尾段主要落在回读，继续比较新会话和旧会话的分段规模及实际合并条数，再定普通请求是否需要回读；若模型尾段占主导，优先定位模型响应流何时完成；若 Java 组帧间隔明显短于浏览器侧间隔，再测 BI 转发链路。

本次补充计时的聚焦验证共 30 项通过（core 2、harness 5、Web 18、DocsConsistency 5），包含实际分段去重、请求 ID 透传和计数日志。未运行新一轮真实模型或生产发布。
