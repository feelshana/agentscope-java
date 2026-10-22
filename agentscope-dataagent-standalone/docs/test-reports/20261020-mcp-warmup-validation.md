# 2026-10-20 实际MCP预热与准备状态验收

关联ADR0101/spec087。本轮实现截图建议4、5及发布页状态展示、部分真实场景验收。建议2/6的候选版本就绪后原子切换尚未实施，建议3只核对官方能力，未启用自定义增量更新。没有重启当前应用服务、发布用户工程或修改生产数据。

## 官方安装版证据

本机D:/workspace/wren-probe/.venv安装wrenai 0.15.0，核对memory/cli.py、memory/store.py、memory/index_backend.py、memory/embeddings.py、mcp_server.py：

- memory index的Indexed N schema items完整行在LanceDB index_schema成功后输出；grep分支输出grep backend并提前返回。平台只对明确成功格式省去status，未知格式仍执行原验证。
- index_schema默认replace并重建schema向量；sync_markdown_queries支持完整当前样例集合的upsert及删除同步，但CLI index没有仅同步样例的开关。直接用store逐条追加不能等价处理删除，故暂未改写增量流程。
- recall_queries在当前MCP进程内调用get_index及search；ONNX运行时有进程级缓存，CLI预热结束即退出不能等同于MCP已预热。

## 实施

- 发布事件先使旧MCP失效，再执行提交后的后台索引与实际MCP只读recall预热；控制器不再重复销毁刚预热的实例。BaselineMdlService原删除/草稿准备失效逻辑保留。
- 预热固定技术文本、limit=1，丢弃结果，不产生问答素材/查询回执，不执行SQL；后续问数按实际问题召回。使用原实例预算、门锁、执行前及排队后权限/版本复核，回收后清除预热标记。
- 准备状态包含QUEUED/INDEXING/WARMING/READY/FAILED，版本不符或实例回收时不返回旧READY。新只读端点同时执行Agent RUN与KB QUERY；失败不能读取别人的库状态。
- 发布页显示实际后台准备状态，活跃时1.5秒轮询、结束后30秒更新，换库/卸载取消更新。未增加问数工具内部阶段的SSE帧，不能宣称全链路实时进度已完成。

## 真实安装版隔离测试

命令：`mvn surefire:test -Dtest=WrenQueryMemoryIntegrationTest -Ddataagent.test.wren=D:/workspace/wren-probe/.venv/Scripts/wren.exe`。进程级JAVA_TOOL_OPTIONS将JUnit临时目录设为工作区target/test-temp；HF_HUB_OFFLINE=1使用本地模型缓存。测试使用隔离DuckDB/ref_sql合成数据、实际官方CLI和stdio MCP，不连接生产数据库。此测试不使用主Agent LLM，不是浏览器/HTTP端到端。

| 阶段 | 实测 |
| --- | ---: |
| 隔离工程索引准备 | 6906ms |
| 已启动MCP内首次召回预热 | 5334ms |
| 预热后首次用户问题召回（含平台按需指纹检查） | 47ms |
| 10个同库并发召回，含串行排队 | 31/57/87/113/139/164/190/216/245/275ms |
| 关闭并重建MCP后的预热（含进程启动） | 7322ms |
| 重建预热后的下一次召回 | 67ms |

测试成功，0跳过。真实日志两次index.status均为VERIFIED_BY_INDEX_OUTPUT，未另起status进程。建模样例、点赞样例及回收后召回正确；未发布草稿与其他库请求未泄漏。进程重建等价于测试该MCP的冷启动，不等同于完整应用/JVM重启；10并发共享单库/单测试身份，不宣称10真实用户多库负载验收。

结果target/spec087-recall-timings.json、target/spec087-real-wren-retry.log。第一次spec087-real-wren.log失败是旧测试假定空采样自动生成规则；修正为在隔离工程显式准备规则后通过，没有为通过测试改业务规则。

## 回归与限制

初轮全量695项通过；补充状态端点权限后697项通过；最后增加提交后准备准入失败不传播到发布请求的防御及用例，最终mvn spotless:apply spotless:check package成功，698项0失败0错误19跳过，含前端npm run build及打包（target/spec087-package-tests-final.log）。早期spec087-targeted.log因构建期间源码格式变动失败，保留记录，不算成功。

真实数据证明预热转移了本机小工程的召回冷启动，不证明所有知识库/模型/硬件均有同样表现，也不证明完整问数5–8秒已达成。未实测完整服务重启、大模型多库10用户负载、浏览器交互、候选发布失败与旧实例并行切换。

下一步使用新制品在实际发布后观察recall.warmup及准备状态，再按同模型同题比较问数总耗时。候选目录、索引和MCP就绪后原子切换需独立实现并处理实例双占用/失败回滚；此前仅提示词和业务样例准确性问题仍待续。
