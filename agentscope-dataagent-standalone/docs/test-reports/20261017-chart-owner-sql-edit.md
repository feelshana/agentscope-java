# 图表归属与SQL问答对编辑专项验收

提交状态更新（2026-10-19）：已纳入本轮主题提交，尚未部署。以下保留原验收日期、测试结果及当时的提交状态；当前上线安排见[总账](../go-live-gap-review-20261012.md)。

日期：2026-10-17；分支 boundary-lyf，基线270aff22加未提交修改。结果：两项已修复并完成本地验收，尚未部署到生产、未提交Git。前一轮核对/任务占位/工具侧栏差异保留。

## 一、原因与实现

### 图表只允许创建者访问

原路径是DataAgentToolkit#renderChart持久保存chartId/option，ChartController#get仅凭chartId取回；已登录的其他用户获得同一ID即可读取。修复前用同一ID证实本人和其他用户均200、返回内容相同。

- ChartOptionEntity增加可空owner_id。DataAgentToolkit#renderChart只从框架RuntimeContext取当前请求userId，模型不提供owner；缺身份拒绝生成且不保存。
- ChartController#get先验证登录身份，再按chartId和ownerId查找，最后用Java精确字符串匹配复核，避免MySQL大小写不敏感排序规则放宽归属。其他用户、管理员、共享知识库创建者均404，匿名401；授权成功响应结构仍为option，增加private/no-store缓存策略。
- 图表属于提问用户，不属于知识库创建者。旧owner_id为空的图表保留但不可读取，不从历史对话推断归属。普通文字历史不删除；无持久仓库的inline模式仍要求调用身份。
- 共享Toolkit实例并发生成时，身份来自各次框架上下文；本次未新增Agent实例、LLM调用或知识库业务查询。

### SQL问答对结构化保存

原SqlQaExampleService使用跨行正则替换YAML的nl/sql，可能吞掉换行/相邻字段；folded SQL和没有tags等格式容易触发。部分情况保存返回成功但文件已不能解析，重复编辑再报500。

- 新SqlExampleDocument分离frontmatter与Markdown正文，用现有安全YAML解析器处理字段，拒绝重复键/不安全类型。只更新nl/sql，保留其他字段值，序列化后再次解析，精确核对问题与SQL。
- 不合并SQL空白；保留中文、引号、冒号、#、反斜杠、行注释和换行。同步正文首个SQL代码块，保留其他正文及代码块。YAML排版/注释可能规范化，不承诺字节级保持未编辑头部。
- SqlQaExampleService#update在现有工作区锁内使用临时文件和原子替换。点赞编辑/删除与官方索引在现有发布锁内串行；索引失败恢复原文件，再恢复发布投影和官方索引；恢复仍失败时返回明确502，并保留失效标记状态供后续重建，不假报保存成功。
- WrenPublishedProject#indexed在改变索引前先移除旧成功标记；restoreIndex负责从恢复源重新部署/重建。成功路径没有增加模型调用或另建向量系统。
- 坏文件在列表标记INVALID、显示原因，前端禁编辑/保存，接口返回400且不覆盖。保留正常确认删除。现有MANAGE、只读SQL、长度及路径约束继续生效。
- 建模问答对仍只修改草稿，显式发布后生效；点赞问答对按原机制刷新官方memory。测试确认发布MDL哈希未改变。

依据：[spec072](../specs/072-chart-ownership-and-sql-example-edit.md)、[ADR0083](../adr/0083-private-chart-and-structured-sql-example-edit.md)。主代码：DataAgentToolkit#renderChart、ChartController#get、SqlExampleDocument#update、SqlQaExampleService#update/delete、WrenPublishedProject#indexed/restoreIndex、SqlQaExamplesPanel。

## 二、执行与结果

| 验证 | 结果与范围 |
|---|---|
| 定向JUnit | 33项全过：图表真实HTTP/JWT/JPA、共享实例10并发生成、大小写边界/编码ID、旧图/缺身份；YAML格式/字符/连续修改/原子恢复/权限/并发编辑 |
| 最终全量JUnit | 670项，652通过、18跳过，0失败/错误；18为既有12 Disabled和6环境/opt-in，不声称全部测试执行 |
| 真实官方索引回滚 | SqlQaExampleMemoryIntegrationTest实际调用安装版Wren/LanceDB；新索引落盘后注入status失败，恢复后召回原问题/SUM，未召回被拒的新问题/COUNT；该项已执行，非跳过 |
| 隔离MySQL与实际接口 | 36/36通过；draft/liked各3次连续编辑、特殊字符、精确文件内容、QUERY用户403、写SQL/越界400、坏文件可见不可写、发布快照不变、真实memory recall新值 |
| 真实Agent与图表 | 共享只读用户执行订单渠道统计并render_chart，475/411/311/303合计1500；本人200，知识库创建者/其他用户/admin404，匿名401；实际浏览器图表正常 |
| 真实进程重启 | 重启隔离新JAR后本人图表内容哈希一致，其余三类仍404；只证明图表持久归属，不代表AgentState上下文问题修复 |
| 旧表迁移 | 新建临时MySQL库复现旧表，执行仓库迁移SQL，原记录完整、owner_id为NULL且列为nullable varchar(128)，测试后仅删除此临时库 |
| 浏览器SQL编辑 | 实际页面编辑特殊字符与多行SQL，保存后刷新回读一致；坏文件提示且字段/保存禁用。临时UI文件已清理 |
| 构建 | Maven package -DskipTests成功，包含前端TypeScript/Vite构建；Spotless通过。已有大chunk警告未当作错误 |
| 工具侧栏回归 | 原有前端tool-inspector测试16/16；既有两个认证/流式测试入口加载问题未在本次修复 |

命令（在standalone模块执行，PowerShell参数引用保持原样）：

```powershell
mvn.cmd test '-Dskip.npm=true' '-Dtest=DataAgentToolkitTest,ChartOwnershipHttpTest,SqlExampleDocumentTest,SqlQaExampleServiceTest,WrenPublishedProjectTest'
mvn.cmd test '-Dskip.npm=true' '-DargLine=-Xmx512m' '-Ddataagent.test.sql-example-wren=E:/Develop/python/Scripts/wren.exe'
mvn.cmd package '-DskipTests'
# frontend目录
node --test tests/tool-inspector.test.mjs
```

全量JUnit中真实Wren回滚测试用临时独立工程，不修改用户已有向量索引。补充真实接口验证使用18081及dataagent_verify_20261017/dataagent_data_verify_20261017隔离库；15知识库/56数据集/15账号保留，原本地业务库未清理。新图表及会话保留；临时SQL/UI文件通过正常接口清理。图表边界另经独立只读调查和候选审查，无确认的剩余绕过或回归发现。

## 三、响应速度观察

| 操作 | 同机观察 | 解释 |
|---|---|---|
| 图表本人读取，各30次顺序请求 | 修复前p50 17.952ms；修复后17.577ms（最大32.089ms） | 此样本未见明显增加；不是统计显著性或生产并发SLA证明 |
| 建模小样例保存，20次 | p50 18.603ms，最大37.272ms | 文件解析/原子保存开销；无LLM调用 |
| 点赞样例保存，3次 | 9.585/7.728/8.443秒 | 含原有官方索引重建；不以“改动小”声称保存无等待，也无可比的旧版成功基线 |
| 实际Agent问数+图表 | 15.728秒 | 含模型/工具执行，不将此当作图表HTTP延迟 |

本次没有重跑前轮完整10用户问答波次，没有做65目标机/Linux/24小时稳定性测试。之前容量结果仍是历史证据。

## 四、升级与剩余事项

已有生产表且使用ddl-auto=validate时，启动新JAR前执行[0083迁移SQL](../migrations/0083-chart-owner-mysql.sql)，先检查列是否已有；脚本按一次执行设计。默认开发update可补列。没有删除旧图或回填；用户需重新生成旧无主图表。

仍未修复：AgentState默认内存导致重启忘记上下文、外联恢复后旧MCP实例仍失败，以及月末/复购业务口径风险。它们保留在[上线总账](../go-live-gap-review-20261012.md)，不因本次通过关闭。未改建模算法、问答主流程或聊天工具块。

最终收尾核对：git diff --check通过（只有既有CRLF归一化提示）；本次文档本地链接无缺失；exec.jar包含新归属/解析类与前端入口，不含dev/prod/verify私有配置，tar.gz包含bin/conf/lib。隔离18081已无监听，本轮label沙箱容器0；未主动停止用户服务，前端5173和MySQL3306仍在，收尾时8080无监听。

构建日志在本地target/chart-sql-focused.log、chart-sql-full-tests.log、chart-sql-package.log，JUnit明细在target/surefire-reports。归属基线、36项接口结果、迁移/重启结果及实际截图保存在Codex Security管理的持久证据目录：

`C:/Users/96309/.codex/state/plugins/codex-security/scans/agentscope-dataagent-standalone/artifacts-59e67dcc2599d149d34624a8958c73851d73d5af6b2c7d99986f9621b250979a/artifacts/`

主要文件：chart-baseline.json、live-fix-verification.json、restart-check.json、migration-and-scan.json、chart-owner-ui.png、sql-edit-ui.png、sql-invalid-ui.png。这些是本机证据，不含登录令牌，不随源代码打包。
