# 2026-10-19 云端建模与问答耗时专项

## 状态与范围

用户要求自主复现“建模和问答很慢”。本轮使用真实云端Java/Wren/MySQL、大文件验收库（6个数据集）、专用普通用户cloud_upload_verify_1019；通过实际BI模拟入口/iframe和HTTP SSE完整记录事件。原liyufanwx“测试”知识库仅做文件/日志只读检查，未写入、删除、发布或重试其工程。使用既有SSH凭据只读采集运行日志、临时CLI输出和缓存目录；未自动部署或重启服务、修改官方库、业务规则或共享权限。运行时代码未改。

本轮最小建模任务复用现有模型，包含官方技能/工程读取、校验、编译、实际COUNT查询、原生确认、memory store失败、显式HTTP发布、发布后实际问答；不是全部业务问题/多表关系/Cube的功能或准确率验收。独立库已由v6发布到v7，所有测试历史和样例保留。

## 实测结果

| 测试 | 实际结果 | 耗时 |
|---|---|---:|
| 三个大表实际COUNT问答 | 162862/168893/159284，与源文件及导入计数一致 | 10.835秒 |
| 同会话按项目聚合前5追问 | 官方run_sql完成，含Python辅助计算 | 17.597秒 |
| 最小建模到确认卡 | 官方校验/编译/COUNT162862完成，等待保存授权 | 18.423秒 |
| 确认memory store | 工具179.967秒超时/退出码-1；最终回答结束 | 184.934秒 |
| 独立库工程校验 | HTTP200/ok true/issues0 | 1.439秒 |
| 独立库显式发布 | HTTP200/ok true/PUBLISHED v7 | 1.481秒 |
| 浏览器普通问数（用户列表） | 召回索引报错后继续SQL得2046，页面完整返回 | 浏览器Resource Timing 33.896秒 |
| v7发布后先召回再问数 | 召回180.022秒失败，随后run_sql0.381秒得2046，最终返回 | 191.404秒 |

计时包含实际接口/模型/工具路径；较早HTTP使用localhost及SSH转发，后两项HTTP改127.0.0.1以避免本机地址连接开销，不能拿两者差异作服务优化收益。SQL核对只检查这些计数及调用回执，不扩展为所有业务口径正确。工具输出含JSON字符串包装，时间汇总已解码后识别错误；调试脚本原始FAILED标志不能单独作为最终结论。

## 已确认主因：缺向量模型缓存 + 下载网络不可达

1. 实际Java进程HOME=/data/app/dataagent/data/home，未配置HF_HOME/HF_HUB_CACHE/HF_ENDPOINT或嵌入模型覆盖。
2. 该HOME、/home/dataagent及/root下均无对应Hugging Face缓存；云端新venv只验过包依赖，未验证嵌入模型实际加载。
3. 正在运行的官方CLI临时日志实际报：`[Errno 101] Network is unreachable`，请求为Hugging Face的sentence-transformers/paraphrase-multilingual-MiniLM-L12-v2配置。
4. 该安装版官方wrenai0.15的memory store先写knowledge/sql Markdown，再生成问题向量并写LanceDB；memory index/fetch/recall同样可能需要模型。独立复测memory store真实等满180秒；原用户日志也存在多次180秒/被中断的调用，不将用户离开期间整段时间当成同一条命令执行。
5. 直接SQL回执多数不到1秒，CLI校验/编译/查询约0.6–2秒；源数据十几万行不等于要把明细全量嵌入向量库。故本次长阻断已定位到嵌入模型准备，而非凭猜测归因模型智能或数据库规模。
6. 原用户知识库实际已有2个SQL示例.md，独立测试失败后也有1个。助手“文件也没写入”的叙述不可靠；文件落盘不代表向量索引完成，不能简单标记全成功或全失败。

运行时采样显示约10GB系统内存available，Java RSS约1.1GB、平均CPU约3%；未采集峰值、堆分配或全JVM栈（服务器无jcmd），不据此宣称不存在其他性能问题。

## 已确认界面问题与放大因素

- 建模ModelingChatPanel只用原始toolResult.startsWith('error')标失败；官方CLI返回带“退出码：-1”的普通/JSON包装字符串，因此超时仍可显示绿色“完成”。用户截图与源码相符，建议复用统一结果解码并识别非零退出码。这一修正尚未实施。
- OfficialWrenToolkit在单知识库workspace锁内同步等待CLI；保存耗时期间同库读取示例等操作也会排队。原用户sql-examples读取曾25秒超时；当前一次CLI的180秒限制不包含锁前等待。需在模型依赖修复后再评估锁预算，不改租户/写入隔离。
- ProcessWrenCli在超时时返回退出码及超时文字，最后删除临时文件，未把部分下载错误交给助手。建议后续保留有限且按用户隔离的诊断摘要，帮助区分模型下载/加载/索引；尚未改实现。
- 多个CLI依次运行、助手多轮重试及确认等待会叠加总时长；不承诺只增大CLI超时就能解决网络故障。

## 修复前准备的离线依赖材料（当时尚未部署云端）

- 模型：sentence-transformers/paraphrase-multilingual-MiniLM-L12-v2，revision e8f8c211226b894fcb81acc59f3b34ba3efd5f42。
- 模型包：上层output/cloud-deploy-20261019/wren-onnx-model-cache.tar.gz，434216719字节（约434MB）。SHA256 `414f1a5ae272f01f7dbccd91e820a097026926988a411b90047b67995e2ac330`。
- 包仅有官方运行时所需的Pooling配置、句长配置、tokenizer.json、onnx/model.onnx及refs/main；不含账号、业务数据、密钥或私有配置。源模型未改。
- 从归档提取到全新HF_HOME，逐文件SHA256核对；HF_HUB_OFFLINE=1下实际memory store成功3.968秒。原本机缓存的两次独立CLI为4.777/4.100秒；是Windows本机对照，不能承诺云端同样速度。
- 同目录check-wren-model-cache.py只调用同版官方嵌入函数，离线验证384维；已用导出缓存通过1.63秒。与模型包均加入SHA256SUMS。

下一步按用户部署协作方式上传模型包并以dataagent提取到实际HOME缓存，再HF_HUB_OFFLINE=1预检。缓存路径正确时，CLI后续调用会读取文件，无须改官方库或业务语义；是否需要重新进入/重建特定MCP索引，以实际复测为准。重测独立库memory store/index/recall、发布后问答、点赞保存及跨会话召回。云端此修复尚未执行、未验证通过；UI退出码问题也未修。

## 验证过程中的限制与作废项

- 第一轮query-cold探针误将MCP字典入参当列表切片，脚本抛错并断开；该次不记产品失败，修复探针后新会话重跑得到10.835秒，原空历史保留。
- 第一次尝试校验前刷新BI凭据发生一次25秒HTTP读超时，尚未独立复现，不将其定为服务认证根因；复用有效令牌并改127.0.0.1后校验/发布正常。
- 未测试10人并发/长稳/50万行边界；未完成所有业务模型；缓存故障存在时不将整套云部署记为可上线。
- 真实浏览器只使用独立测试身份，截图无实际用户明细。未停止用户原任务或服务，未清理账号/数据，无Git提交/推送。

## 证据

上层.runtime/cloud-perf-20261019/timing-summary.json、各SSE events/summary、server-readonly-initial.log、cache-environment.log、server-after-reproduction.log、live-index-download-errors.log、validate-result.json、publish-result.json、offline-model-manifest.json、exported-cache-offline-probe.json。私有原始事件/账号/模型输入保持Git忽略，不复制进报告。

浏览器截图：上层output/playwright/cloud-perf-20261019/browser-query-complete.png、recall-index-failure.png。前者34秒完成，后者可查看召回索引错误；不同于API受控先召回用例191秒。

## 云端模型部署后复测（2026-10-19）

用户按指引上传同一离线包，以dataagent解压到实际应用HOME的`.cache/huggingface`，回传`HF_HUB_OFFLINE=1`下384维检查成功，2.22秒。随后通过真实BI认证/API SSE，在同一专用验收库复测；未重启Java、修改官方依赖或业务模型，未操作原用户知识库。以下状态取代前文“尚未部署/复测”的历史状态。

| 测试 | 结果 | 耗时 |
|---|---|---:|
| 部署后首次召回并问用户列表总数 | 召回成功，实际SQL得2046 | 整轮29.669秒；召回调用20.718秒 |
| 新建模轮读取技能/工程、召回、实际COUNT到确认 | 复用现有模型，COUNT162862，等待memory store确认 | 10.091秒 |
| 确认保存问题与SQL | CLI退出码0，query-2.md及向量保存成功 | 保存工具7.810秒 |
| 保存后立即读取文件及召回 | 正确问题和SQL，距离0，整轮正常结束 | 召回工具7.847秒；确认整轮19.433秒 |
| 工程校验/显式发布 | ok true/issues0，独立库v7→v8 | 1.655秒/1.619秒 |
| 发布后新会话召回并实际问数 | 找到新旧两个示例，实际SQL得162862 | 整轮27.678秒；召回19.634秒；SQL0.098秒 |
| 对已实际查得2046的答案点赞 | savedPairs1/memoryStatus SAVED | 20.687秒 |
| 索引已就绪后的另一新会话问数 | 召回命中刚点赞样例，SQL得2046 | 整轮5.553秒；召回0.053秒；SQL0.060秒 |

本轮真实业务路径未再出现180秒索引超时，支持缺模型文件/下载失败为先前主阻断的结论。首次及发布后召回仍约20秒，调用包含索引准备和模型加载，不将其全记为纯向量检索耗时；点赞保存也仍有约21秒等待。上述单次值不代表P95、并发或所有知识库性能，后续可继续评估索引重建和进程加载成本。绿色“完成”误报失败的前端问题依然未修，不能因正常路径恢复就标记关闭。

私有原始证据：同目录`post-cache.py`、`cache-fixed-*-events-private.json`、`cache-fixed-*-summary-private.json`、`cache-fixed-validate.json`、`cache-fixed-publish.json`、`cache-fixed-feedback.json`。本轮为真实API回归，未重新运行浏览器UI验收、并发/长稳或服务重启测试。测试历史/样例保留，运行时代码无新增修改，无Git提交。

后续用户授权补充分段计时，现已增加应用侧计时并完成本地真实Java/CLI、云端临时副本与同进程冷热对照；新Java代码未替换云JAR。云端同进程模型加载约2.35秒，加载后保存/召回约16–19毫秒，全67项schema重算约2.7秒，另有新进程初始化和状态检查成本。详细阶段、跟踪开销及限制见[分段计时报告](20261019-wren-phase-timing.md)；本报告此前“运行时代码无新增修改”指模型文件部署后复测当时状态。
