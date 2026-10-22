# 2026-10-20 问数准备优化验证

关联ADR0100/spec086。当前为代码与自动化回归验证，未重启服务、未发布用户工程、未执行实际数据库查询；此前日志22.4秒/7秒是优化前基线，不是优化后性能。

## 实施与可观察行为

- MdlPublishService发布成功发送PublishedQueryProjectChanged；WrenQueryPreparation在事务提交后异步准备，失败不回滚发布。
- 有界后台调度；同库事件合并，复用现有锁和源指纹，查询遇同源就绪索引不再执行memory index/status；点赞同步刷新不变，源变化仍重新校验。
- MCP实例缓存成功的无参数get_instructions，命中仍校验权限和排队撤权，发布/销毁清除，错误不缓存。字段明确时提示减少重复describe，不省略必要证据。
- 记录授权、发现、索引准备与细分步骤、MCP排队/准备/执行，后台READY/FAILED可见于execution-timing.log。未新增前端状态API。

## 自动化结果

- 首轮定向44项通过（target/spec086-targeted.log）；后续补充完整测试。
- 最终mvn spotless:apply test：692项，0失败0错误19条件跳过（target/spec086-tests-retry.log）。首轮spec086-tests.log因新增测试通配符导入触发Spotless失败，已修正，保留失败记录。
- 新增覆盖发布失败不发事件、事务提交/回滚、同库事件合并、不同库身份隔离、失败重试、后台与查询并发同源只构建一次、查询复用准备完成的索引、缓存授权/实例失效/库隔离/错误不缓存。既有排队撤权、日期门禁及多租户用例全量通过。
- mvn spotless:check package -DskipTests通过，包含前端npm run build（target/spec086-package.log）。

## 服务加载后的验收

1. 正常显式发布，观察query_preparation READY与index.build/status在后台会话中出现。
2. 同一发布版本首次问数，确认index.reuse且没有请求内index.build；如准备未完，index.wait反映真实等待，不计作MCP执行。
3. 同题热态复测，核对wren.instructions CACHE_HIT、recall执行及模型往返次数；保留正确答案与日期门禁，不只比较耗时。
4. 源/点赞变化时确认索引重建，重新发布确认说明缓存失效，撤权后不能命中取数。
5. 若首次召回本体仍慢，以wren.execute定位模型冷加载，再决定是否增加官方只读召回预热。目前未实施额外预热调用，避免无证据增加后台MCP实例/模型预算。

当前不宣称达到5–8秒，不把测试替身的索引复用证明当作真实端到端提速。
