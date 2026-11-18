# 看板任务恢复与体验缺陷验证

日期：2026-11-18 Asia/Shanghai（任务始于11月17日）。分支codex/architecture-phase1，沿用工作区全部未提交的spec143/144/145修改；本轮为spec146/ADR0151。不提交、推送或部署，不启停用户IDEA。

## 已确认原因与修改

1. 原工作台浏览器持有SSE，组件卸载会abort。现在任务API由服务端持有既有ChatController执行订阅，复用原生会话、选库、RUN/资产权限、容量与超时；浏览器只提交和轮询。请求ID/期望修订防重复，同看板单活跃任务，显式停止才取消。当前记录和各用户最近终态原子保存在看板目录，回复有界；另一管理者不读取他人消息。服务重启遗留任务标INTERRUPTED，不自动重跑；删除/恢复/发布/检查点阻止活跃任务。自动修复须引用原任务，只一次，继承原撤销检查点。
2. 截图的英文并非用户发送，是ConversationCompactor生成的USER角色内部摘要；保留名/ID为__compaction_summary__。SessionTurnParser、既有历史索引及看板展示排除该身份；每进程首读重新检查未变转录，清理已保存的内部投影，重算显示数量/标题/预览。原生文件不改，真实用户发送相同英文措辞仍显示。
3. 卡片将知识库、创建归属、更新时间、创建时间并成紧凑信息带，长名省略、窄屏换行；旧日期不捏造。当前API仅有owned，继续显示“我创建的/共享给我”，未伪造其他创建人姓名。
4. 当前IDEA旧服务8080实际只读预检：同Origin=http://localhost:5173，GET返回200，PATCH返回403，allow-methods无PATCH。SecurityConfig补PATCH，保留原来源及JWT/Agent/资产授权；需重启IDEA加载。

## 本轮实测

| 验证 | 结果 |
| --- | --- |
| python scripts/check.py backend --unit | 1118项，失败/错误0，跳过1（Windows符号链接条件） |
| 任务服务/控制器、CORS、Parser、DashboardService、AdminHistoryHttpTest、JpaPersistenceIntegrationTest、架构选测 | 47项，失败/错误0，同一跳过1 |
| 前端相关5文件，根路径及两个嵌套部署前缀 | 各24项全部通过，无跳过 |
| TypeScript/Vite生产构建 | 通过；静态资源同步。既有大chunk提醒仍存在 |
| Chrome隔离任务交互 | 刷新、离开重进保持任务ID；完成/失败/停止/重启中断恢复；完成后重进不调用模型；内部摘要隐藏、用户消息无重复；描述保存、桌面元数据单排、390px无页面横溢 |
| Chrome自动修复回归 | 主任务+一次子任务，parentRunId正确，只有一个原检查点；完成刷新不再次修复；列表/窄屏正常，无意外API或JS异常 |

任务服务测试使用真实资产文件服务及可控Flux，覆盖幂等、状态只读、其他用户隔离/终态保留、并发拒绝、显式取消、活动资产保护、错误/不完整流失败、旧进程中断、自动修复预算。控制器测试确认复用ChatController固定看板/单库参数，并验证RUN拒绝；CORS测试通过真实CorsWebFilter验证允许来源PATCH预检/请求和未允许来源拒绝。H2集成测试先写入已缓存的内部摘要及未变转录，验证移除摘要、保留真实消息、修复统计投影且原生源文件字节不变。

验证期间修正了测试自身的两个问题：MockWebTestClient预检需绝对URI以构成合法源；旧标题缓存用例须反映新的一次重索引，追加第二次读取不再写入的断言。首次最终检查还发现新增Java测试未被选择性格式化匹配，完整Spotless应用后全unit及选测均通过。

隔离夹具/日志位于.runtime/dashboard-generation-*；截图位于output/playwright/dashboard-generation-library.png、dashboard-generation-restored-running.png、dashboard-generation-interrupted-mobile.png、dashboard-generation-list-mobile.png，四张均已实际查看。

## 限制与体验步骤

浏览器任务、SQL响应及描述PATCH为隔离模拟；不是实际IDEA模型/Wren/知识库完整链路。未运行本轮clean verify/发布部署门禁，不使用本次结果保证统计口径、所有筛选或线上可用性。任务执行仍为单进程，重启不接续模型连接，另一用户的活跃任务不会暴露其内容；冲突以409提示。完整对话继续保存在原生会话，并非新聊天数据库。

用户重启IDEA后刷新前端：发送需求，刷新或离开再进入，检查运行/终态恢复；失败后重试，显式停止，修改描述并保存。无需SQL迁移；旧服务未加载新generation接口及PATCH跨域修复，直接刷新前端不能替代后端重启。文档门禁及最终空白检查在共享记忆checkpoint后补记。

最终补记：共享记忆后DocsConsistencyTest 5项通过，失败/错误/跳过0；git diff --check通过。临时QA浏览器已关闭，用户服务保留。
