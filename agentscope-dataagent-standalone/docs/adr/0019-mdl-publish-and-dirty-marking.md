# ADR 0019: MDL 拼装、验证与发布 + 脏标记（specs/010 M2）

- 状态：已采纳（实施规格 specs/010 M2）
- 日期：2026-09-26
- 来源：M2 实施（MdlPublishService / WrenCli / 脏标记 / MDL 三端点 / MdlPublishPanel）

## 背景

ADR 0018 确定「数据集组 == wren project」与「MDL 在语义建模页显式发布时生成」（D8/D9）；
M1 已落地组内人审产物（CONFIRMED 关系、cube 配置、列 schema 与 AI 描述）。M2 要把这些产物
编译为 WrenAI v5 工程并发布：拼装（assemble）→ 验证（`context validate --strict`）→
构建（`context build`）→ 快照 + 版本号 +1，同时把「组内容/建模变更 → 旧版本继续服务」的
脏标记闭环落地。

约束（延续 ADR 0018 D10）：不影响现有上传/选表/问数链路与 `DataAgentToolkit` 四工具；
「发布」是引擎切换开关，不是问数许可证。

## 决策

1. **D1 拼装 = 服务端确定性渲染，单一事实源在 DB**：`MdlPublishService#assemble` 只读 DB
   （`DatasetRepository` 列 schema / `DatasetRelationRepository` 的 CONFIRMED 关系 /
   `SemanticCubeRepository` 的 cube），按固定字段序渲染四类文件：`wren_project.yml`、
   `models/<name>/metadata.yml`、`relationships.yml`、`cubes/<name>/metadata.yml`。
   渲染直出字符串模板（字段序稳定 → diff 可读），不经 LLM；逻辑模型名经
   `sanitizeIdentifier`（保留中英文/数字/下划线，其余 → `_`，去重后缀 `_2`，数字开头补
   `m_`）；数据集顺序按 `createdAt nullsLast, id`。关系 + 计算列渲染在边的多端（黄金范式）。
2. **D2 类型归一化经 `wren utils parse-types`，禁手写映射**（延续 ADR 0018 D2 实证教训）：
   全工程 raw type 集合一次批量送插件（types_in.json → normalized），结果进进程级
   raw→normalized 缓存（类型是数据源全局语义，跨组共享，不落 DB）。任一列类型无法归一化
   → 本地 error，不发子进程。
3. **D3 YAML 安全渲染走自实现最小转义（`yamlScalar`）**：MDL 里中文列名/描述/CASE 表达式
   （含 `:`、`#`、引号）必须安全出引号。规则：非「中文/字母/数字/下划线」开头、YAML 指示符
   （`-?:,[]{}#&*!|>'"%@``）开头、布尔样（true/false/yes/no/on/off/null/~）、数字样（含日期形）
   或含 `": "`/`" #"`/tab 的值 → 单引号包裹 + `''` 转义。
4. **D4 本地校验短路 wren**：assemble 同时产出本地 issue（无数据集、列结构 JSON 无法解析、
   列缺类型、类型无法归一化、CONFIRMED 关系引用组外数据集、cube 引用缺失列/空 cube 等）——
   有 error 时禁用子进程（坏引用不交给 wren，得到的是可读中文错误而非晦涩英文栈）；否则落
   staging 跑 `wren context validate --strict`，输出经正则解析为结构化 issue
   （`severity`=error|warning，`source`=local|wren）。
5. **D5 发布流水线 = validate → build → 快照 → mdl.json → 状态翻转（版本 +1）**：`publish`
   重复 validate；`wren context build` 成功后把 `project/`（staging）整树复制为 `published/`
   快照（覆盖式重建），写 `mdl.json` 平台清单（version/发布时刻/dataSource/models 含类型与
   计算列表达式/relations/cubes），最后组置 `PUBLISHED` + `mdlVersion+1` +
   `mdlPublishedAt`。**validate/build 失败 → 返回 failure，不触碰快照、不 bump 版本、组状态
   不变**（测试锁定）。闸门理由：坏 cube/坏引用会毒害后续所有会话（官方警告）。
6. **D6 发布成功 = cube 状态归一**：`markPublished` 把组内所有 cube 置 `PUBLISHED`
   （已有 PUBLISHED 不重复写）；`updateCube` 改配置即回 `DRAFT`（M1 已定）——未发布的配置
   变更不会静默进 MDL。
7. **D7 脏标记单向幂等，收口单点**：`DatasetService#markMdlDirty`——仅 `PUBLISHED` →
   `DIRTY`（刷新 `updatedAt`）；`NONE`/`DIRTY`/孤儿组为 no-op 不写库；不动版本号（旧快照
   继续服务问数）。触发点：组内容变更 5 处（`ingest`、`associateTables`、`deleteEntity`、
   `updateColumnDescriptions`、`saveKnowledge`）+ 建模变更 5 处（`SemanticModelingController`
   在关系确认/拒绝/手工添加、cube 创建/更新/删除后调 `DatasetGroupService#markMdlDirty`，
   先做 ownerId 多租户校验）。
8. **D8 三端点，预览不落盘**：`GET /mdl`（纯内存 assemble + 读 `published/` 快照做
   changed/file diff，不写盘）、`POST /mdl/validate`（落 staging + 跑 CLI，返回
   issues/output/files）、`POST /mdl/publish`。均先经 `DatasetGroupService#getGroup`
   多租户 404 校验（与 M1 端点一致），阻塞调用落 `boundedElastic`。
9. **D9 产物布局 = `mdl-home/<groupId>/{project, published, mdl.json}`**：`project/` 每次
   assemble 前删除重建（staging）；`published/` 只由 publish 写入（运行时唯一服务面）；
   `mdl.json` 是平台清单（M3 预注入读它渲染逻辑模型 + cube 成员）。根目录默认
   `~/.agentscope/dataagent/mdl`（`dataagent.wren.mdl-home` 可覆盖）。删除知识库时
   `deleteArtifacts` 级联清理（best-effort，失败仅告警——文件系统态不阻塞 DB 删除）。
10. **D10 前端 = YAML diff 发布卡（`MdlPublishPanel`）**：文件列表 + 行级 LCS diff（对比
    published 快照，「仅显示变更」开关）、验证按钮（结构化 issue 内联 badging）、发布按钮；
    发布成功刷新组状态，失败横幅明示「已发布版本保持不变」。请求经
    `api/semanticModeling.ts`，与既有端点同风格。

## 理由与权衡

- 拼装归服务端（D1）的后果：MDL 永远是 DB 现状的派生视图（重渲染即同步，杜绝「编辑器写坏
  MDL」的失配类）；代价是每次预览/发布一次全量渲染（组规模小，可忽略）。
- 本地校验优先（D4）的理由：wren 对缺失引用/坏类型的报错是英文且上下文弱；本地错误带中文
  数据集名/列名，且失败路径零子进程开销。
- 版本语义（D5）：版本号只在发布成功时 +1；`changed` 由内容逐文件字符串比较得出而非版本号，
  预览即反映「发布后是否有未发布变更」。
- 脏标记单向（D7）：`DIRTY` 是「已发布但落后」的标记，`NONE`（从未发布）没有落后语义；
  幂等 no-op 保证重复置脏零写放大；发布成功自然清脏（置 `PUBLISHED`）。
- 代价：`mdl-home` 是本地盘状态（与每用户沙箱同源的部署约束）——多副本需按 groupId
  sticky LB 或共享盘（与 M3 实例池一并处理）；快照覆盖式重建在磁盘故障下可能留半写快照，
  但 DB 版本号未 bump，重新发布即收敛。
- 明确不决策项：per-group wren 实例池/spawn/空闲回收（M3）、实例热加载、脏组混合态路由
  （预注入分流）、MdlPublishService 的异步化（当前同步阻塞接口）。

## 影响面

- 新增：`dataset/MdlPublishService`、`dataset/{WrenCli,WrenProperties}`、
  `SemanticModelingController` 三个 MDL 端点、前端 `components/MdlPublishPanel`、
  配置 `dataagent.wren.*`（README 配置表 + ARCHITECTURE 12.1）。
- 修改：`DatasetService` 5 处置脏、`DatasetGroupService#markMdlDirty/deleteArtifacts`
  （删除知识库级联清理 MDL 产物）、`application.yml`。
- 测试：`MdlPublishServiceTest`（10 用例：黄金工程形状、YAML 转义、脏/发布语义、失败保持
  旧版本）、`DatasetServiceMdlDirtyTest`（6 用例：幂等/单向/各触发点）、
  `SemanticModelingControllerTest`（MDL 端点委托 + 多租户 404）。
- 文档：README/README_zh 配置表、ARCHITECTURE_zh.md 5.6 / 12.1 / 13。
- 后续（M3）：`WrenToolkit` 查询工具 + per-group 实例池 + 预注入按组分流读 `mdl.json`。
