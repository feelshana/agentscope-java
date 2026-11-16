# 2026-11-13：查询记录与失败问题回填验收

## 范围与基线

codex/architecture-phase1@bb02f96d起点；用户授权规划后开始功能优化，范围见[spec134](../specs/134-answer-query-records-and-recovery.md)。保留起点四份共享记忆改动、既有运行对照报告/TSV/截图、独立建模HTML；这些不是本批成果。未提交、push、合并或部署，未启停现有开发服务。

## 本批实现

- 成功查询回执补queryId/queriedAt；缓存保留同一完整回执与下载信息，撤权检查与失败不缓存保持。无需数据库迁移或额外SQL/LLM调用。
- CitationPanel统一解析原生、包装、旧工具记录，展示本轮SQL/行数/样本/请求上限/截断/失败/历史缺失，按执行ID而非相同SQL合并。点击查看复用ToolInspector，不重查。
- 源数据更新时间、实际模型版本未记录时显示未记录；查询时间不充当数据新鲜度，也不宣称逐条结论已经核验。现有CSV/图表/Python/反馈入口保留。
- 失败/中断按钮仅填回对应原问题，保护不同草稿，确认后仍需手动发送，不切换知识库或继承旧权限；聊天侧保留输入聚焦逻辑。

## 自动化验证

- `python scripts/check.py frontend --files answer-query-records.test.mjs tool-inspector.test.mjs wren-presentation.test.mjs`：28项通过，根前缀选测，未重复全部前缀。
- `python scripts/check.py backend --tests WrenScopedMcpClientTest,AnswerQueryMemoryTest,ArchitectureDependencyTest`：35项通过。
- `python scripts/check.py backend --unit`最终1056项、0失败/错误/跳过；排除integration/external通道，不是全量发布验证。
- 首次单元通道1项旧缓存契约断言失败（要求重复调用不带回执）；按spec134更新为完整回执一致，聚焦与最终单元通道均通过。没有通过放松权限测试过关。
- `npm run build`：TypeScript/Vite通过，保留原有大包体积提示；最终收尾状态见共享记忆。
- DocsConsistencyTest：5项通过；无新增依赖例外，无新ADR或DB表。

## 浏览器组件验收

通过Playwright CLI在独立localhost:62481加载真实CitationPanel、QuestionRecovery和ToolInspector，使用合成订单回执（500/0/空结果、失败及历史驱逐），不是接真实LLM或用户数据库的完整ChatPanel端到端测试。初始隔离打包缺BASE_URL定义已在临时验收入口修正；无业务配置改动。

1653×862视口检查展开状态、SQL字面值、详情表格500，回填原问题、取消替换保留草稿、接受替换；390×844检查整页无横向溢出，包含长UUID。最终浏览器断言passed，0 pageerror，操作无/api请求。截图仅证明隔离组件交互，字体/页面容器由测试入口提供，不代表完整线上iframe样式验收。

- [桌面合成回执组件截图](assets/20261113-answer-query-records-desktop.png)
- [手机合成回执组件截图](assets/20261113-answer-query-records-mobile.png)

## 补充：完整应用实际运行与截图（用户要求后追加）

本段为同日后续实测，更新此前“未实测本批真实LLM/SSE持久重开”的限制。未修改生产代码，也未停止用户8080/5173服务；用户现有本地登录未成功，未继续尝试其他密码，改用独立临时MySQL8.4.5、平台库/上传库/MDL/工作区运行应用。只读取得开发库的活动模型连接，在内存中解密并重新加密到临时库；原配置不变，测试数据为新建的4笔合成订单。

运行包由此前验证过的相同依赖归档，加当前target/classes与最新前端静态构建组成；不是重新执行完整发布打包，因此不替代合并前clean verify。临时打包脚本最初遗漏JAR目录条目导致扫描失败，补齐后启动；脚本曾使用不存在的测试库列、重复初始化schema和非法知识库名，均仅修正验收脚本后重跑，未为此改动业务代码。

实际运行地址127.0.0.1:59754，Java17；通过真实登录、上传CSV、Wren校验和发布v1，再在Chrome完整ChatPanel输入问题并发送，真实模型调用官方MCP run_sql，未mock模型或查询返回。

| 本次实测 | 结果 |
| --- | --- |
| 实际问数 | 全部4笔订单SUM(amount)=500.00，COUNT(*)=4，页面答案与真实SQL返回一致；单次约8秒，仅为本次小数据测试 |
| 回执元数据 | 页面显示实际queryId与北京时间完成时间；没有源更新时间/实际模型版本时仍明确“未记录” |
| 查询详情 | 在实际页面打开SQL和结果表格500/4；复用现有ToolInspector，没有发起新问答/新查询 |
| 历史持久重开 | 重新导航同一会话、从服务器加载历史后，仍是相同查询编号和完成时间，结果表格500/4可打开；未对本批另做JVM重启 |
| 中断恢复 | 实际发送第二个问题后点击停止生成，显示“已中断”和“修改问题再问”；回填的是第二题，不是上一题 |
| 草稿保护 | 空输入直接回填；不同草稿取消替换后保留、确认替换后回填原问题，两种操作都不自动发起chat/stream |
| 浏览器异常 | 本次交互监听0 pageerror；不是整个产品所有页面无故障的证明 |
| 本轮重跑自动化 | 相关前端28项及后端查询/记忆/依赖35项通过；1056项单元通道为前次同源码结果，本轮未重复全通道 |

实际完整应用截图（非HTML原型、非组件测试页；数据为合成验收数据）：

- [展开查询记录](assets/20261113-live-query-records.png)
- [查看实际数据与SQL](assets/20261113-live-query-data-sql.png)
- [实际停止生成后回填原问题](assets/20261113-live-question-recovery.png)

收尾：文档一致性5项通过，git diff --check通过；仅关闭本批浏览器/JVM及其子进程、核对任务标签后删除临时MySQL容器。用户原8080/PID41172、5173/PID44676仍监听，当前分支与HEAD不变，未提交/push/部署。

### 后端与数据库范围，分两批理解

本次尚未提交的spec134：仅WrenScopedMcpClient一个生产Java类改动，为既有成功回执增加queryId/queriedAt，成功缓存改为保留完整回执。沿用现有消息历史存储，不增加表、字段、索引、迁移、HTTP端点、SSE事件或额外模型/SQL调用；查询权限和草稿/发布选择没有因本批改造改变。

此前已在本地提交的architecture-phase1三批（46facb45、b63a72de、bb02f96d），相对个人基线5479d152确实有较大的后端重构：只读模型投影、构建校验、发布安装恢复拆分，聊天请求准备/发布HTTP职责分离、会话范围归runtime，清理未装配旧查询实现，补发布中断恢复及删除提交后清理/持久重试。不是只做UI，也没有改成微服务或替换Wren查询。该范围当前相对liyufan_dev无新增迁移/资源schema/实体字段差异；DatasetGroupRepository改的是现有字段的条件更新与恢复锁定读取。恢复意图使用mdl-home内文件journal，不新增数据库表。详见[六阶段报告](20261112-architecture-six-stage-acceptance.md)和[真实分支对照](20261113-branch-runtime-parity-acceptance.md)。

## 未验证与后续（补充实测后）

未重启用户后端、未做本批JVM重启/生产规模或66验证；已完成的真实LLM/SSE历史重开见上段。本批未交付完整逐条证据链、模型版本钉入、源新鲜度、保存分析、反馈处理或调度器。旧分支对照报告仍只支持此前架构重构，不代替本批实际验收。

用户启动本地新后端并刷新前端后：一次普通问数→展开查询记录→查看数据与SQL；查看旧会话缺元数据的兼容显示；失败/停止后回填问题，先留不同草稿检查确认，再自行发送。后续F04/F05另定范围。
