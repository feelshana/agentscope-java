# ADR 0018: WrenAI 语义层集成（问数执行换轨与 cube 发布流）

- 状态：已采纳（实施规格 specs/010）
- 日期：2026-09-25
- 来源：WrenAI 集成调研与探针验证（Phase 0 五探针 + query_cube E2E 20/20）

## 背景

问数链路引入 WrenAI 语义层：LLM 不再手写物理表 SQL，而是面向 wren 逻辑模型（MDL）出查询，
由 wren-core 确定性编译为物理 SQL 执行；指标聚合口径锁死在 MDL 的 cube 声明里。

实证基础：

- Phase 0 五探针 + 内置 data-agent E2E 全部通过（安全边界三形态阻断、MCP stdio 常驻复用、
  LLM 自纠错 run_sql 内建 LIMIT 叠加）。
- query_cube 结构化查询探针 20/20 全绿（proj5：双模型 + relationships + 双 cube；测试矩阵
  T0~T6 + X 交叉对数，探针脚本 probe_query_cube.py，产物在 target\wren-probe\，git 忽略区）。
- 官方资料三条证据：① GenBI Classic（旧 docker 全家桶）已归档 legacy/v1 分支不再维护——排除
  经典版部署路线；② OSS open-core 边界明确不含行列级安全/用户组权限（commercial 清单）——
  多租户过滤必须由平台预注入层承担，非工程选择；③ MCP server 属于 OSS 承诺面（主 README
  "What's included"）——tools.json 挂 MCP server 是官方设计内的接入面。
- 发现并修复 tools.json 沙箱盲点：HarnessAgent 构建期 `ToolsConfigLoader.load` 经沙箱态
  filesystem 读不到 tools.json → allow 白名单与 MCP 声明历史上从未生效
  （`DataAgentConfig#builderBootstrap` 显式 override 加载修复）。
- 用户拍板目标产品形态：选表流程不变（数据集组）；新增「选表 → 可视化建 cube/关系 → 生成并
  发布 MDL」通道；问数查询（上传表+知识库关联表）执行完全走 wren 引擎；历史数据不做兼容
  （无过渡双通道需求）。
- 建模时点与页面形态拍板（D9）：选表/上传只把表送进组内待建模池，MDL 在「语义建模」页
  显式发布时生成；页面挂 DatasetGroupPage 第五 Tab。
- 双通道拍板（D10，用户最终确认「不影响当前流程，当前的工具」）：建模是可选增强，不是
  问数必经闸门——未建模组保持现有 text2sql 直查通道与现有四工具不降级不改；单表/简单
  场景不建模直接问是刻意保留的产品路径；实施规格 specs/010。

## 决策

1. **D1 职责边界**：NL→SQL 归平台 LLM（wren v5 无 LLM，`wren ask` 是空壳）；wren 只做语义
   编译 + 执行引擎（run_sql 时逻辑模型名 → 物理表重写）。
2. **D2 MDL 生成归平台（生成器 = 可视化编辑器 + AI 建议）**：
   - 结构 = columnSchemaJson + 官方类型归一化（`wren utils parse-types`，禁手写映射——实证
     14/20 列丢精度）；列描述 = `SchemaGenerationService` 的 AI 描述 + 维度样例值写入
     description；关系 = `RelationInferenceService` 规则推断（SAME_COLUMN/SUFFIX/DOC）
     + LLM 建议（必需层——规则推断有首列盲区，见 D9）→ joinType 唯一性探测（COUNT
     DISTINCT vs 行数）→ 人审确认 → relationships 段（编辑器落库须防
     `reinferGroup` 全量重建清空人工关系——已知坑）。
   - cubes 经可视化编辑器发布：表单字段与 cube YAML 一一映射（指标 = 聚合函数下拉 + 选列 +
     可选 CASE 条件构造器；维度/时间维度 = 选列），AI 从列语义建议候选（金额列 → SUM 指标、
     类别列 → 维度、时间列 → 时间维度），人审调整后发布；配置面不含查询条件（经典 WrenAI UI
     教训：条件属问数环节，由 LLM 出）。
   - 官方 cube 提议决策树（enrich-context skill references/cube_proposals.md，本地实证）：
     聚合形态（SUM/COUNT/AVG、按月/按状态分组）默认提议 cube；纯行级表达式（amount*1.1 无
     分组）→ 计算列；需跨模型 JOIN/窗口/CTE → VIEW（cube 可坐在 view 上）；同 expression 已有
     cube → 不重复建（去重护栏）；cube 属 high-blast-radius 必须人审（官方：auto-pilot 模式
     也强制 escalate 到人工确认）；发布前双验证 context validate + cube query --sql-only
     （官方警告：坏 cube 毒害后续所有会话）。
   - 事实表/维表判定信号 = 关系方向（MANY_TO_ONE 多端 = 事实表）+ 列形态（金额/数值/时间戳列
     vs 低基数类别列）。官方论断 "cubes are the highest-leverage correctness primitive for
     smaller models"（弱模型手写聚合最易错：JOIN 错/重复计数/日期截断错）。
   - 理由：ds_ 表无外键，wren 官方 generate-mdl 脚手架推不出关系。
3. **D3 schema 上下文不换轨**：预注入 `[DATA_SOURCES_OVERVIEW]` 扩展渲染 wren 逻辑模型 + cube
   成员清单（平台从 mdl.json 生成、带 DatasetScope 过滤）；`prepare_data_context` 保留（维度
   样例值平台独有，MDL 无样例值字段）；产品态工具面经 WrenToolkit 暴露查询类工具（run_sql/
   query_cube 语义）。注：组==project（D8）后 describe_schema「项目级全量」恰为组内可见集，
   租户边界论据弱化，但维度样例值 + TC 预注入宪法（少一次往返）仍立。知识库两类分开：文档类
   RAG（doc 检索）wren 不承载留平台；问数知识（规则/NL→SQL 对）可下沉 wren knowledge/+memory，
   但中文召回弱（grep 后端非连续子串失败，实证）——建议平台自控召回，范围留 specs/010。
4. **D4 拓扑 A（演示/单组态）**：内置 data-agent 经 tools.json 挂 `wren serve mcp`（stdio
   常驻）；bootstrap 期装配层显式 toolsConfig override 加载（修复沙箱盲点）；运行期 API 注册
   MCP 不可用（global 智能体 403 + Reactor block 拒绝，双重实证）——静态 tools.json 绑定单
   project，仅适合单组/演示；多组动态建组的产品态见 D8。
5. **D5 安全边界换轨**：MDL 编译边界（未建模表规划期阻断）+ 只读 DB 账号替代 checkCrossTable
   的表集合检查；注意 MDL 边界只管「可见表集合」、不管「关联路径」（未声明关系的 JOIN 放行，
   实证）。
6. **D6 关系两层次**：Phase 1 最小 = relationships 作语义提示 + LLM 直接写 JOIN（JOIN 不依赖
   relationships，实证）；Phase 2 可选 = 关系列 + 计算列物化维表属性（探针实证：展开为
   RIGHT OUTER JOIN，基表行保留，无匹配维度 NULL 分组）。
7. **D7 问数双模式（query_cube 优先，已实测全绿 20/20）**：
   - 自由模式 run_sql——LLM 写全 SQL，wren 编译重写 + 执行（已 E2E 实证）；
   - 结构化模式 query_cube——LLM 只出 cube + 指标 + 维度 + 过滤（`dim:op:value`，值字符串
     引擎转换）+ 排序，JOIN 与聚合表达式由 wren-core 按 MDL 声明确定性编译——聚合语义锁死在
     MDL，消灭「选错聚合函数」错误类。复杂条件（OR/嵌套 CASE）不可表达 → 下沉到 measure
     声明（探针 T2 的 SUM(CASE WHEN...) 即实例，执行成功）。
   - 双模式路由机制（官方指引 + 实证）：软路由三层信号——① 上下文可见性（预注入/
     prepare_data_context 携带 cube 成员清单与描述）；② 提示词/工具描述规则（官方 workflow
     原文 "For named metrics, prefer query_cube over hand-written aggregate SQL"）；
     ③ 语义匹配：问题聚合语义命中已声明 measure → query_cube，明细/Top-N 投影与临时聚合
     （P95、OR 复杂条件）→ run_sql；失败自愈：query_cube 成员不存在/过滤器不支持时 LLM 依
     结构化错误自动降级 run_sql；平台侧在系统提示词写死路由规则（TC 宪法风格）比仅依赖工具
     描述更可控。
   - 实测五项发现：(a) 过滤器可作用于未选中维度（T3：过滤 is_not_accessed_last_3m 而
     GROUP BY cluster，131,390 行与历史探针一致）；(b) 谓词在物理 SQL 外层 WHERE，MySQL
     优化器下推进 JOIN 内侧——有界 JOIN 0.56s；(c) 无选择性过滤的大扇出 JOIN（162k×162k ≈
     14M 中间行 GROUP BY）为分钟级 workload，非 wren 缺陷——cube 查询应鼓励选择性过滤；
     (d) 关系列计算列展开为 RIGHT OUTER JOIN（基表行保留，无匹配时维度为 NULL 分组）；
     (e) 上传 ds_ 表混合 collation（utf8mb4_unicode_ci vs utf8mb4_0900_ai_ci）使跨表 JOIN
     报 Illegal mix of collations——平台建表 DDL 必须统一 collation（真实平台风险，跨数据集
     JOIN 同样受影响）。
   - 交叉对数 X1/X2/X4：query_cube 编译 SQL 与手写 SQL 结果完全一致（X4 identical）。
8. **D8 数据集组 == wren project（产品态拓扑，方向已定参数留 specs/010）**：选表流程不变 →
   组即 MDL project 边界（组内可见集 = project 全量，OSS 无 RLS 缺陷被 project 划分消化）；
   发布 = 组内 validate --strict + build +（重新）spawn 该组 wren 实例，「发布」成为该组
   问数引擎切到 wren 的前置（未发布组保持现有直查通道，见 D10）；运行期接入 = Java
   WrenToolkit（@Tool 封装查询工具，
   DatasetScope 注入组路由）+ per-group wren 子进程池（按需 spawn + 空闲回收）——MCP 动态
   注册不可用（D4 双重实证）且 tools.json 静态声明无法覆盖用户动态建组，工具面稳定的正道是
   平台 @Tool 包装；实例热加载未验证（引擎 manifest 疑似启动期冻结），大概率发布 = 重建该组
   实例，探针确认。
9. **D9 建模时点与发布状态机（页面形态已拍板）**：选表（`DatasetService#associateTables`）
   与上传（`DatasetService#ingest`）只把表送进组内待建模池，**不自动触发 MDL 生成**；MDL
   的生成时点 = 用户在「语义建模」页完成人审后显式点「发布」（拼 MDL → validate
   --strict → build → 重启该组 wren 实例）。理由：① 关系与 cube 是组级语义，第二张表
   未进组时生成无意义；② 组内容每次变化自动 rebuild 会冲掉人审结果（与
   `reinferGroup` 全量重建清空人工关系同源教训）；③ 状态机自然——组内容变更 → 脏标记
   （旧版本继续服务问数）→ 增量建模 → 发布新版本（问数切新）。
   - 页面形态：`DatasetGroupPage` 第五 Tab「语义建模」（现有四 Tab：文件/知识图谱/树结构
     目录/关系说明文档）——左列模型卡（标注 origin 与待建模状态）、中部关系候选卡
     （规则边 + LLM 建议合并，确认/改向/拒绝/手工添加）、cube 候选卡（官方决策树建议，
     调整聚合/CASE 构造）、底部验证/发布按钮；上传与选表两链路在此汇合，唯一差异是模型
     卡的 tableReference 指向（`ds_` 物理表 vs 外部库 schema.表名）。
   - **SAME_COLUMN 首列盲区（新实证）**：`RelationInferenceService#reinferGroup` 的
     SAME_COLUMN 规则跳过每表首列及 `id`/`<表名>_id` 列——维表主键在首列（真实表普遍
     设计，如「用户表(用户id,用户名,省份)」）时共享列被系统性跳过，规则推断推不出边
     （用户示例实测：订单表.用户id ↔ 用户表.用户id 共享列，SAME_COLUMN/SUFFIX 均不命中
     ——SUFFIX 只剥英文 `_id`/`_key` 后缀，中文列名亦不命中）。推论：LLM 建议层从「增强」
     升级为「必需层」，手工关系入口永久保留；specs/010 须含盲区修复（首列跳过放宽为
     「跳过本表主键列」）与 `A.col = B.col` 双向匹配用例。
   - joinType 唯一性探测具体化：对候选关系的外键列跑 `COUNT(DISTINCT fk) vs COUNT(*)`
     （一条 SQL）——多端即事实表方向，MANY_TO_ONE 由探测结论落定，不靠 LLM 猜。
10. **D10 双通道并存（建模可选，未发布组不降级）**：「执行完全走 wren」适用于选择建模的
   组，不是全平台强制。未发布 MDL 的组维持现有 text2sql 直查通道全量能力
   （`prepare_data_context` 物理表 schema + 维度样例值 → `query_structured_data` 直查，
   checkCrossTable 护栏），与现状零差异；已发布组问数走 wren 通道（WrenToolkit）；脏组
   （发布后新增表）混合态：已建模表走 wren 旧版本、新表走直查，重新发布后收敛。
   - 「发布」语义修正：不是问数许可证，是**引擎切换开关**——用户按组选择；wren 通道
     收益（确定性聚合/JOIN 编译/指标治理）随场景复杂度增长，简单场景边际收益低。
   - 佐证：TCDataAgent 官方对照——Excel 上传/单库表属 text2sql 甜蜜区，轻量语义档
     （AI 列描述 + 词条，即本项目已有 `SchemaGenerationService` + `SemanticTerm`）即可
     达标；跨系统多口径才需重型语义层。强制建模会把「上传即问」轻漏斗变成重漏斗。
   - 双通道路由机制（留 specs/010 细化）：预注入 `[DATA_SOURCES_OVERVIEW]` 按组标注
     引擎形态——已发布组渲染逻辑模型 + cube 成员清单，未发布组维持物理表渲染；两套
     工具（DataAgentToolkit 直查 / WrenToolkit）并存注册，未发布组调 wren 工具时返回
     引导性错误（提示用 `query_structured_data` 或前往语义建模页发布）；混合查询
     （已建模表 JOIN 未建模表）走直查通道。

## 理由与权衡

- TC 宪法延续（预注入 + 确认细节→查询）；租户边界；维度值平台独有；常驻复用性能（冷启动
  2-4s vs 常驻零开销）。
- 代价：wren venv 部署依赖（Windows 强制 PYTHONUTF8=1 + mcp<2 版本锁）、run_sql 内建 1000
  行上限与显式 LIMIT 叠加的语法坑（写入工具描述/提示词）。
- 双模式取舍：query_cube 确定性强但需 cube 预声明（MDL 生成器范围扩大）且复杂条件表达受限；
  run_sql 灵活但聚合语义靠 LLM——定为「命名指标走 query_cube，临时指标走 run_sql」。
- Palantir 对照（定性，公开资料）：Foundry Ontology 中 agent 查询面 = typed object sets +
  functions，指标为预声明治理对象、无自由 SQL 旁路——佐证「预声明指标 + 结构化查询」是
  agent 数据查询的主流治理范式；wren 双通道（cube 优先 + run_sql 兜底）比 Palantir 单通道
  更宽容，本项目取 wren 双通道。
- 保留双通道的动因（D10）：简单场景强制建模得不偿失（text2sql 甜蜜区 + 「上传即问」轻
  漏斗定位）；代价是两套查询工具并存的路由复杂度（预注入按组标注引擎形态消化）。
- 明确不决策项：实例池参数（spawn 策略/TTL/上限）、热加载机制、知识库下沉范围、双通道
  混合态路由细节（脏组查询归属、工具引导文案）留给 specs/010。

## 影响面

- 已落地：`DataAgentConfig#builderBootstrap` 显式加载 tools.json 并注入 toolsConfig override
  （修复沙箱盲点，需补测试）；`~/.agentscope/dataagent/workspace/tools.json` 挂载 wren MCP
  server（stdio、演示 project proj4、enableTools: run_sql + describe_schema）。
- 已完成：query_cube E2E 探针（target\wren-probe\proj5 + probe_query_cube.py，20/20；产物含
  cube YAML 范式 setup_proj5.py、collation 对齐脚本 align_collation.py）。
- 待实施（规格已立项：docs/specs/010-semantic-modeling-and-wren-publish.md，分 M1/M2/M3）：
  语义建模页（`DatasetGroupPage` 第五 Tab：模型卡/关系候选/cube
  候选/验证/发布，D9）+ MDL 发布服务（validate/build/实例管理）、MdlSuggestionService
  （规则边 + LLM 建议 + joinType 探测合并，D9）、Java WrenToolkit + per-group 实例池、
  预注入扩展（渲染 wren 逻辑模型 + cube 成员清单）、`reinferGroup` 人工记录保护与
  SAME_COLUMN 首列盲区修复（D9）、平台建表 DDL 统一 collation。
- 架构文档同步：ARCHITECTURE_zh.md 新增 2.6（tools.json 与 MCP 挂载）、4.6（WrenAI 语义查询
  通道）、5.4 关系去向补段、5.6（数据集组 → wren project 发布流）。
