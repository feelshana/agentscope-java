# ADR 0020: Wren 运行期接入——查询工具、实例池与预注入分流（specs/010 M3）

- 状态：已采纳（实施规格 specs/010 M3）
- 日期：2026-09-27
- 来源：M3 实施（WrenInstanceRegistry / WrenProfileHome / WrenQueryGateway / WrenToolkit /
  MdlCatalog / 预注入分流 / TableProvisioner collation）

## 背景

ADR 0018 定了 wren 通道的目标形态但只到演示态（tools.json 静态 MCP + 手工 MDL + 探针）；
ADR 0019 落地 M2 发布流（发布 = 快照 + `mdl.json` + 版本 +1、脏标记单向），并把「per-group
实例池 / spawn / 空闲回收、实例热加载、脏组混合态路由（预注入分流）、WrenToolkit」明确列为
不决策项。M3 的使命：把「已发布组问数走 wren」从探针变成产品运行期能力。

约束（延续 ADR 0018/0019）：

- MCP 动态注册不可用（双重实证），tools.json 静态声明无法覆盖用户动态建组 → 必须有一个
  平台侧的运行期网关来 spawn/管辖子进程；
- harness 不透传 typed attributes 给带外工具调用（sessionId 能透传）→ 租户上下文需三级降级；
- 「发布」是引擎切换开关，不是问数许可证：未发布组必须与现状零差异（回归契约）。

## 决策

1. **D1 per-group 实例池按需 spawn + 空闲回收（`WrenInstanceRegistry`）**：某组首次查询时才
   spawn `wren serve mcp --project <mdlRoot>/<groupId>/published --profile <profile> --quiet`
   （per-group 双检锁防并发重复 spawn；初始化超时 60s、持锁阻塞上限 90s）；实例记录
   `{projectDir, McpClientWrapper, lastUsed}`；reaper 线程每 60s 轮询，关闭空闲超
   `dataagent.wren.instance-idle-seconds`（默认 1800s）的实例，下次查询按需重建。不设实例上限、
   不做预热：建模组可能多但活跃组少，spawn 成本摊在首次查询（秒级，有初始化预算）。
2. **D2 传输失败 = drop 实例引导重试；查询级错误 = 保留实例回传诊断**：
   `WrenInstanceRegistry#call` 传输异常/超时（tool 超时 = wren timeout + 30s slack）→
   `invalidate(groupId)` 关闭实例并抛 503「实例已重置，重试将自动重建」——stdio 管道破裂后
   协议状态不可信，半坏实例不可复用；wren 以 `ok=false` 返回的查询级错误（SQL 坏、cube/成员
   不存在）不是实例故障，原样回传 payload 供模型自愈，**保留实例**避免「模型写错 SQL → 实例
   反复重建」的抖动放大。
3. **D3 发布 = 重建（Publish = rebuild）**：引擎启动期即把 manifest 编译冻结（热加载未验证且
   大概率不可行）→ `SemanticModelingController#mdlPublish` 成功后调
   `WrenQueryGateway#invalidate(groupId)`；下次查询按 D1 重新 spawn 读取新快照。spawn 前校验
   `published/target/mdl.json` 存在，缺失抛 409 引导「请在『语义建模』页重新发布后再查询」。
4. **D4 平台托管 WREN_HOME（`WrenProfileHome`）**：`serve mcp` 需要连接档案，而 operator 的
   `~/.wren` 不可控 → 启动时 `ensure()` 在 `<mdlRoot>/.wren/profiles.yml` 生成托管档案（仅内容
   变化时重写）；连接参数从 `dataagent.dataset.datasource.url` 解析（host/port/database，端口
   缺省 3306；ssl_mode 三态：URL 无显式 SSL 一律 DISABLED，`sslmode=required|verify_ca|
   verify_identity` 或 `usessl=true` → ENABLED）；值经 `$`→`$$` 转义（wren
   expand_profile_secrets 语义）+ `yamlScalar` 出引号。子进程 env 注入 `WREN_HOME` +
   `PYTHONUTF8=1`/`PYTHONIOENCODING=utf-8`（Windows 下中文不乱码）。
5. **D5 `WrenToolkit` 双工具（`wren_run_sql` / `wren_query_cube`）**：Agent 侧 @Tool（描述全
   中文）——`wren_run_sql(group_id, sql, question?, limit?)` 仅 SELECT/WITH 白名单；
   `wren_query_cube(group_id, cube, measures, dimensions?, time_dimension?, filters?, order_by?,
   limit?, offset?)`，MCP 参数键与协议一致（snake_case），筛选/时间维度/排序字符串格式写在
   @Tool 描述里（`dim:op[:value]`、`name:granularity[:start,end]`、`member:direction`）。结果
   渲染为自文档化 markdown 表（与 `JdbcSqlConnector` 报告格式对齐；单元格 200 字符截断；截断
   时提示加聚合/筛选或调 limit）。**引导性错误**——未发布组返回「…尚未发布语义模型，wren 通道
   不可用。请改用 query_structured_data 对物理表直查，或前往『语义建模』页完成建模并发布后再
   试」；多租户失败（scope 不含该组 / 组不属于调用者）统一折叠为不可区分错误「未知或无权访问
   的知识库 'X'」（与 404 语义一致，防探测）。
6. **D6 租户上下文三级降级（与 `DataAgentToolkit#effectiveScope` 镜像）**：typed
   `DatasetScope` → `RuntimeContext.userId` → `ConversationScopeRegistry[sessionId]`（harness
   带外调用丢 typed attributes 但 sessionId 存活；ChatController 在带 groupIds 的请求上同时写
   注册表与 RuntimeContext——ADR 0018 的既有契约）。
7. **D7 装配（`DataToolkitRegistrar`）+ `WrenQueryGateway` 接口隔离**：主 agent Toolkit 注册
   第三个工具集 `WrenToolkit(wrenGateway, datasetGroupService, conversationScopes)`，与
   `DataAgentToolkit`/`RunPythonTool` 同层（Bootstrap 构建完成后 `@PostConstruct` 注册）。
   Agent 工具依赖 `WrenQueryGateway`（call/invalidate）而非 `WrenInstanceRegistry` 实现——
   工具逻辑（scope/引导错误/渲染）可脱离真实子进程测试。
8. **D8 预注入按组分流（`MdlCatalog` + `DataDynamicContextMiddleware`）**：middleware 每次构建
   `[DATA_SOURCES_OVERVIEW]` 时逐数据集判定（同组 manifest 命中缓存）——`MdlCatalog#load` 读
   `<mdlRoot>/<groupId>/mdl.json`（坏文件 warn → empty 降级，不失败 prompt 构建）→
   `coveredDatasetIds().contains(ds.id())` 命中 → 渲染进「知识库『X』—已发布语义模型（group_id、
   版本 vN）」逻辑段（逻辑模型 + 列 + 计算列 + 已建模关联 + Cube 清单）；未命中 → 保留 legacy
   物理 bullet。**回归契约**：无任何已发布组时输出与改动前逐字节一致（快照测试锁定）。逻辑段
   **刻意不泄漏 source_id / 物理表名**——给出物理名会让模型绕开 wren 直查，与「已建模表走语义
   通道」的隔离设计冲突。DIRTY 混合态（ADR 0018 D10）：发布后新上传表不在 covered 集合 → 仍
   物理渲染可直查，已建模表走逻辑段。
9. **D9 系统提示词双通道硬规则（`DataAgentConfig.DEFAULT_AGENT_SYS_PROMPT`）**：常驻层加
   「需要结构化数据时先按 [DATA_SOURCES_OVERVIEW] 判断通道（硬规则，不得混用）——知识库区块
   列出逻辑模型/Cube 清单 → 语义通道（wren_run_sql，指标优先 wren_query_cube）；物理表数据源
   → 直查通道（sql-analysis 技能）」。4.5 分层纪律不变：路由硬规则常驻，「逻辑 SQL 怎么写好」
   留技能/工具描述。
10. **D10 DIRTY 结果附混合态提示（`WrenToolkit#dirtyNote`）**：DIRTY 组的查询结果追加
    「本次结果基于上一次发布的版本；新增或修改的表请改用 query_structured_data 直查」——把
    「快照落后」的边界显式告诉模型，防止对新增表硬走 wren 来回试错。
11. **D11 建表 DDL collation 统一（`TableProvisioner`）**：上传数据集建表 DDL 显式
    `ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci`——修复探针实证的
    「混 collation 跨表 JOIN 报 Illegal mix of collations」（utf8mb4_unicode_ci vs
    utf8mb4_0900_ai_ci），wren 编译的 JOIN 同样受益。

## 理由与权衡

- 实例池按需（D1）vs 预热：预热要为每个已发布组常驻子进程（内存/连接开销），而多数组低活跃；
  按需 spawn 把成本放在真正查询的组上，空闲回收兜底资源泄漏（组数爆炸时的上限/背压列为
  不决策项，出现规模问题再调）。
- 传输失败 drop / 查询错误保留（D2）的对称性：前者防「复用半坏管道」的随机错误，后者防
  「用户/模型错误触发无谓重建」的抖动；503 + 引导重试把恢复责任交给调用方且可预期。
- 发布 = 重建（D3）：引擎冻结 manifest 是实测行为，热加载不承诺——把「发布」翻译为「实例
  失效」是最小可靠语义（与 M2 「发布成功才 bump 版本」呼应：失败发布会 keep 旧实例旧快照）。
- 平台托管 WREN_HOME（D4）：不与 operator 环境耦合；代价是连接信息以明文落在 mdlRoot 下的
  profiles.yml——与 mdl-home 同源的本地盘约束（部署边界见下）。
- 分流在 middleware 而非工具内探测（D8）：TC 式预注入——通道标注随每轮 prompt 到达，模型
  无需先探测「这组发布了吗」；代价是每次 prompt 构建读一遍 mdl.json（文件小；坏文件降级不
  失败）。byte-for-byte 回归契约把「未发布组零差异」从口号变成测试锁。
- 多副本部署约束（与 ADR 0019 同）：mdl-home 是本地盘、实例池是进程内存 → 多副本需按
  groupId sticky LB 或共享盘；本轮不引入分布式协调（单副本演示态足够）。
- 明确不决策项：实例预热/warm pool、实例池上限与背压、wren 引擎热加载、会话级实例隔离
  （实例是组级共享、查询彼此无状态）、tools.json 静态 wren 演示声明的清理（保留向后兼容）。

## 影响面

- 新增：`runtime/wren/{WrenQueryGateway,WrenInstanceRegistry}`、`dataset/WrenProfileHome`、
  `dataset/MdlCatalog`、`tools/data/WrenToolkit`、配置
  `dataagent.wren.instance-idle-seconds`（README 配置表 + ARCHITECTURE 12.1）。
- 修改：`DataToolkitRegistrar`（注册 WrenToolkit）、`DataDynamicContextMiddleware`（3 参构造 +
  按组分流）、`SemanticModelingController#mdlPublish`（发布成功后 invalidate）、
  `dataset/TableProvisioner`（建表 DDL collation）、`DataAgentConfig`（系统提示词双通道硬规则）、
  `application.yml`。
- 测试（46 用例）：`WrenToolkitTest`（15：白名单/引导错误/多租户不可区分/DIRTY 提示/cube 参数与
  渲染）、`DataDynamicContextMiddlewareTest`（7：分流快照/回归逐字节一致/坏 manifest 降级）、
  `MdlCatalogTest`（6）、`WrenProfileHomeTest`（7：档案生成/转义/ssl 三态）、
  `TableProvisionerTest`（2：collation）、`DataAgentConfigTest`（9：含双通道路由断言）。
- 文档：README/README_zh 配置表、ARCHITECTURE_zh.md 2.5 / 2.6 / 3 / 4.1 / 4.6 / 5.6 / 12.1 / 13。
- 后续：集群部署文档补 sticky LB / 共享盘约束（`docs/cluster-deploy.md`）；实例池上限与
  监控（若组数规模化）；tools.json 演示 wren 声明随文档清理。
