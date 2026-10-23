# 2026-10-22 当前分支全流程复验

本轮主流程实测通过，但不能据此认定已经满足生产上线条件。发现一次 Windows 发布快照替换失败，重试成功，原因尚未闭环；两份过时浏览器测试夹具已修正。真实公司模型、BI 系统、65 服务器、Doris 断链及目标配额长稳测试仍未验收。

## 候选和环境

- 起点与结束代码：`boundary-lyf@16dac81d8e6f1cabfacd7aab000ca77eb06f609e`。本轮未改业务源码、框架依赖或私有 dev/prod 配置，没有提交、推送或生产部署。
- 起点已有单机发布手册和共享记忆的部署澄清修改，保留；上层 `shared/.seed-manifest.json` 的既有修改保留，SHA256 仍为 `675FB4C9D5482E3C5FC4910595612ED4DD2383738C8CFE75D8556AE3BF6CAC5D`。
- 新打包后复制为不可变测试 JAR，SHA256：`1469bf37db69f5774cb7eee10cad06dbce3e19215c5d7031ef27a9d3c3ecf897`。运行时使用该副本，避免后续 Maven 改写正在加载的 JAR。
- Windows，本机 MySQL；Wren CLI 0.15.0，Python 3.13.3，Node 22.13.1，Docker Engine 29.1.3。构建及首轮测试使用默认 JDK 21.0.6，另用已安装的 JDK 17.0.14 实际启动同一 JAR 验证。编译基线与默认运行 JVM 分别记录。
- 独立 metadata、upload、state、replay 四个数据库；独立 HOME、工作区、MDL、附件和沙箱 instance-id。所有业务数据为本轮合成数据，没有查询原开发库业务表。
- 后端仅监听 `127.0.0.1:59400`。真实问数使用当前本地配置的公网模型；公司模型和截图中的凭据未调用。模型故障专项使用本地合成 OpenAI 协议网关。
- Docker 原先未运行；启动 Docker Desktop 时，其原有容器的自动启动策略也启动了其他项目。未停止或清理这些容器，因此本轮耗时不是专机性能基准。

## 自动回归与打包

| 检查 | 本轮结果 | 范围说明 |
|---|---|---|
| `mvn test` | 759 项，733 通过、26 跳过，失败/错误 0 | 本次重新执行；包含前端门禁、构建与 Spotless |
| 前端 npm 门禁 | 根路径和 `/dataagent/` 各 35 项通过 | 35 个独立用例，70 次执行；未新增依赖 |
| `mvn package -DskipTests` | 通过 | 前端门禁仍执行，Java 测试按参数跳过 |
| 官方 Wren 原生 fixture | 30/30 SQL、中文召回及 MCP 通过 | 新执行 CLI/MCP，覆盖普通模型、View、Cube；不是 LLM 准确率评测 |
| 额外 opt-in Java 集成 | 6 项通过、0 跳过 | OfficialWrenCli、WrenQueryMemory、SqlQaExampleMemory、AgentStateMysql；包含真实 MySQL 状态持久化及十个 owner/session 隔离 |
| Python unittest | 11 项通过 | `python -m unittest discover -s src/test/python -v` |
| BI Node 测试 | 8 项通过 | `bi-login.test.mjs`、`bi-simulator.test.mjs` |
| 登录浏览器夹具 | 5 个场景通过 | 模拟 API；401、网络失败、登录策略加载失败和未登录 |
| 采样浏览器夹具 | 3 个场景通过 | 模拟 API；未知总数、刷新成功、失败保留上次样本，无 JS 异常 |
| 发布 tar 审计 | 通过 | 布局、脚本权限和换行、静态资源、私有 dev/prod 配置排除 |
| Linux 启停脚本 | 10 个场景通过 | 独立 Docker Linux 容器、只读源码、受控 JVM/Wren 桩；不是 Linux 上真实 JAR/Wren/数据库部署 |

可复验命令在 standalone 根目录运行：

```powershell
mvn test
mvn package -DskipTests
python -m unittest discover -s src/test/python -v
node --test docs/integration/bi-login.test.mjs docs/integration/bi-simulator.test.mjs
python docs/fixtures/platform-integration/auth_session_browser.py
python docs/fixtures/platform-integration/sampling_browser.py
python docs/fixtures/platform-integration/release_smoke.py --archive target/dataagent-2.0.3-SNAPSHOT.tar.gz
```

opt-in 命令使用 `-Dtest=OfficialWrenCliIntegrationTest,WrenQueryMemoryIntegrationTest,SqlQaExampleMemoryIntegrationTest,AgentStateMysqlIntegrationTest`，显式传入 `dataagent.test.wren`、`dataagent.test.sql-example-wren` 和指向本轮私有隔离库配置的 `dataagent.test.state.config`。该配置包含本机凭据，不入 Git，不能作为可公开发布的样例。

## 真实业务链路

1. 创建四个测试用户、知识库及 QUERY/MANAGE 授权。CSV 导入八笔订单、XLSX 选择客户工作表导入两行，客户编号 `0001/0002` 保持字符串；重复上传返回 409。无授权预览返回 404，QUERY 写入返回 403。
2. 术语创建、管理成员修改、删除通过；QUERY 写入被拒绝。修改接口会返回新术语 ID，本轮按返回值继续操作，没有把旧 ID 的 404 误报为产品故障。
3. 上传业务口径文档，真实 LLM 使用官方 skills、原生文件工具、HITL 与官方 CLI 建模。83.685 秒、42 次工具调用、3 轮 HITL；最终两个模型、一条关系，验证通过，页面 API 显式发布为 v4。中途四次非零工具结果后由 Agent 继续恢复，不能把 SSE 错误数为零描述成每一次工具调用均成功。
4. 官方 Wren MCP 查询全年有效销售额，结果 **3099、4 单、2 位客户**；同会话追问二月得到 **1599、2 单、2 位客户**，与独立合成数据计算一致。记录的 32.700 秒和 7.166 秒只是本机样本。
5. 月度可视化和 Python 分析实际执行；PNG 中文显示正常，CSV 包含十二个月及合计行，金额与订单数核对通过。实际浏览器打开右侧产物预览、下载 CSV，文件与 API 下载的哈希一致。该真实模型选择了 Python 绘图；ECharts `render_chart` 另由受控模型调用实际 Java 工具，真实浏览器的聊天图和工具详情预览均显示，二者分别记录。
6. 零选库对话没有数据工具调用。三路混合并发（两个账号问数和一个普通问候）结果正确、会话 ID 不混淆；这只是并发冒烟，不是容量和长稳验收。
7. 撤销 QUERY 后，知识库从用户列表消失，直接读取返回 404；恢复授权后可继续。其他用户以及全局管理员读取本人私有历史、附件均返回 404，管理员身份未扩大到用户私有资源。
8. 外部 MySQL 连接测试、创建、不回显密码、空密码编辑保留旧密码、状态/schema/table/columns、关联数据表及在用连接删除保护通过。发布首次失败、重试成功后，直接用官方 CLI 查询该外部发布模型得到八行。
9. 浏览器从真实历史答案点击“正确”，服务执行官方 memory store 并更新发布查询索引，服务记录候选 1、完成约 21.8 秒。记忆位于平台运行目录，本轮不要求把 SQL 示例写回草稿或发布工程的 `knowledge/sql`。

合成口径：按 `order_date` 取 2025 年，`status=paid AND is_internal=0 AND amount>0`；有效金额为 1000、999、600、500。另含内部订单、取消订单、零金额和 2026 年订单，均不计入全年有效销售额。预期数字只用于独立核验，没有在问数提示中直接提供答案。

## 模型管理、认证和恢复

- 真实管理员页面保存连接、测试、确认应用通过；保存和测试时仍显示启动配置，确认后才生效。普通用户更多页无模型管理入口，API 的列表及各写入、测试、激活、删除操作独立返回 403；匿名请求返回 401。
- 使用本地网关验证 HTTP 503、超时、部分 SSE 中断均测试失败且不能激活；有效服务故障后 SSE 明确返回错误，没有静默切换。手动应用备用服务后，新轮次返回备用服务标记。
- 草稿修改、API Key 留空保留、当前有效快照不变、旧 revision 返回 409、当前连接禁止删除通过。真实 MySQL 配置行不包含合成 Key 明文。OpenAI/DeepSeek 经本地兼容协议验证，不宣称已联调真实千问或公司网关。
- 真实公网模型第一次启动了取消用 Python sleep；其进程检查脚本参数有误，该次不作完整取消证据。第二次真实模型拒绝无业务意义的 sleep，也不算取消通过。随后通过受控模型调用实际 `run_python`，断开 SSE，五秒后核查本轮容器中 sleep 已不存在，同一会话可继续。
- 仅中断本轮 Java/Wren 进程树后，以 `prod`、密码登录关闭、BI 开启、`ddl-auto=validate` 重启。MySQL 中模型有效选择、先前历史、附件可读取，跨用户保护继续生效。随后再以 **JDK 17.0.14** 启动同一 JAR，验证原生 ReAct/模型调用、实际 Wren→MySQL 返回 3099 和图表工具。JDK17 专项模型决策为受控网关，不冒充第二次真实模型评测。
- 使用合成共享密钥和真实 Java BI 端点：新用户自动成为普通用户，重复进入保持 ID，已有用户 ID/管理员角色保持；票据重放、过期、未来时间与篡改返回 401，密码登录不可用。
- 浏览器经本地模拟 BI 宿主页、真实前端 `/dataagent/` 构建和代理、真实后端免登；用户切换替换 iframe，成员只见自己的历史及授权库，刷新保持当前成员。桌面与 390px 模型页面、历史 Markdown 图片和右侧预览实测；唯一资源错误为模拟宿主 `/favicon.ico` 404，无应用 JS 异常。
- 从本轮 metadata 导出 36 张表，在独立空 replay 库回放成功；这是测试用 schema 快照，**未作为正式首次部署 SQL 交付，也未完成全量历史迁移演练**。正式 `0088-model-services-mysql.sql` 在本轮专用 state 库执行两次成功。

## 发现、修改及待处理项

| 优先级 | 事实 | 本轮处理/后续 |
|---|---|---|
| P2 | 外部库首次发布 HTTP 500：`published.next → published` 抛 `AccessDeniedException` | 原始失败保留；重试发布成功、官方查询通过。尚未定位目录占用者或形成稳定复现，不宣称已修复，也不能据此推断 Linux 同样故障。继续排查 `MdlPublishService#replacePublishedArtifacts` 的 Windows 目录替换生命周期 |
| P2 测试设施 | 登录/采样浏览器夹具仍写 localStorage 的 claw_token，也未模拟 auth/options | 已改为当前 sessionStorage 与入口策略；登录增加策略加载失败场景；五项/三项复验通过；README 去掉已失效的直接 Node 测试入口 |
| P3 | 登录宣传仍出现“语义图谱”“实体与关系自动构建”，与已停用 KG 的边界不一致 | 本轮只记录，未扩展 UI 改造；可统一改为语义模型/业务口径等实际能力描述 |
| P3 | 术语页文案“供成员查阅”，QUERY 用户实际没有可管理库、不能读术语管理 API | 当前 MANAGE 规则下未发现越权；文案应说明管理范围，或另行确认是否需要只读查阅功能，不能由测试自行扩大权限 |
| 已知待复验 | 建模聊天工具块对非零退出的完成状态、同批工具结束通知仍待与同事交付对齐 | 本轮观察到 Agent 从非零 CLI 结果恢复；未完成该聊天块逐项浏览器故障状态验收，不把右侧详情或整体成功等同于该问题已解决 |

首次尝试曾在 Maven 改写目标 JAR 时启动同一路径，导致类加载异常；改用完成打包后的不可变副本后正常启动。另有成员 ID、术语更新后 ID、管理员同时有 user/admin 两个角色、Docker top 参数、官方 CLI JSON 输出形态、受控图表参数类型等夹具错误，均校正后复验；原日志保留，不归入业务缺陷或隐去失败。

## 生产前仍需的验收

- 同事分支尚未合并到该 HEAD；合并后的代码、框架依赖、镜像和迁移必须重新冻结候选并回归。本轮不能证明未合并代码可用。
- 公司网关完整 URL、签名有效期与 Bearer 合同、真实 SSE/tool_calls；真实 DeepSeek/千问备用连通、公司允许的数据范围。本轮只实际调用本地现用公网模型，不测试截图凭据。
- 65 的 OS/路径/权限、JDK17及约定 Python/Wren/模型缓存、真实 Linux JAR 启停、生产 DB 完整 schema/历史迁移、BI 同源 HTTPS/Nginx 转发及正式用户源。
- MySQL/Doris 真实断链专项、目标并发/上传/沙箱/Wren配额的混合负载及长稳、磁盘满和进程异常、备份与实际恢复演练。本轮没有停止原 MySQL、切断公司网络或人为耗尽共享宿主资源。
- 全量 Java 测试的 26 项跳过必须按环境逐项审计。额外六项 opt-in 已通过，但显式禁用的 JPA 综合测试、Doris 等未覆盖项不能被替代；真实服务启动通过也不等于禁用测试已经重新启用。

## 证据与收尾

本机原始日志、HTTP 状态、SSE、独立 schema、下载文件与专项 JSON 保存在 Git 根 `.runtime/full-review-20261022/`；截图与浏览器材料保存在 Git 根 `output/playwright/full-review-20261022/`。其中有私有测试配置，不入 Git、不公开整个目录。

收尾只停止本轮测试服务、模拟网关、模拟 BI、浏览器会话和本轮 instance-id 的容器；保留四个隔离测试库及证据，不清理用户原有资源。Docker Desktop 和其自动启动的原项目容器保留运行。最终状态以本报告追加的收尾核对为准。

2026-10-22 15:30 +08:00 收尾核对：本轮 Java/Wren、网关、BI、浏览器已停止；59400、51931、52447 均不监听，仅按本轮精确 instance-id 移除剩余一个容器。测试数据库和原始证据保留，CLI 生成目录已移入上述 output 目录。HEAD 和暂存区不变，个人交接仍被本地精确忽略。
