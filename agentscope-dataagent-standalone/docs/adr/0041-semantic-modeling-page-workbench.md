# ADR 0041 — 语义建模页工作台化：资产独立展示与对话驱动更新发布

日期：2026-10-05 · 状态：Accepted（讨论稿经 docs/prototypes/0041-modeling-workbench.html 可交互原型
多轮迭代定稿；实施明细见 docs/specs/030）

## 背景

语义建模页 `SemanticModelingPage.tsx`（2222 行单文件）当前形态与 Wren-only 语义层
（specs/019「工作区即真相」）之间存在三处断裂：

1. **资产视图不可寻址**：六资产 tab（总览｜表与关系｜指标｜视图｜术语与规则｜MDL）
   状态为纯本地 state，URL 固定 `/configure/datasets/{groupId}/modeling`——刷新回总览、
   无法收藏/分享某个资产视图；「每个部分独立展示」无从谈起。
2. **对话遮挡资产**：`ModelingChatPanel`（877 行）以 1100px 居中 modal 打开（specs/027
   拍板的形态），完全遮住资产视图——用户在对话里确认 HITL 变更时看不到关系/Cube/视图
   列表的实时变化，关闭 modal 才能核对。虽然已有 `modeling:updated` 事件驱动 refresh
   （且 refresh 不重置 tab），但遮挡使「对话 → 资产更新」的因果不可感知。
3. **草稿/发布双态只在 MDL tab 可见**：「草稿 vs 已发布」的差异目前仅 `MdlPublishPanel`
   （全量文件 diff）与 DIRTY 横幅承载，其余五个资产 tab 不标注哪些资产有未发布变更——
   对话建模改了什么、发布前后差在哪，需要用户自行跳转比对。

## 调研

### WrenAI 本身（2026 现状）

- **仓库演进**：`Canner/wren-engine` 已并入 `Canner/WrenAI` 的 `core/`（2026-05，旧仓库
  归档）；带 wren-ui 的经典 GenBI 应用形态归档，官方主推 headless「GenBI for AI agents」
  （`wren serve mcp` / 语义层即代码）——与本平台 ADR 0020/0033 的路线一致，即官方已放弃
  自建建模 UI 作为主形态。
- **经典 wren-ui 建模页**仍是语义建模 UI 的事实标准（官方文档保留）：Modeling 一级入口
  下按资产分页——Modeling Overview（ERD 总览，模型蓝/视图绿）、Models（列表 + 字段/
  计算字段/关系详情）、Relationships（From/To/Type 表单）、Views、Cubes/Metrics——
  每个资产区块独立路由、独立可达；Deploy（草稿 → 部署）为全局两态、状态条常驻。

### 引用/集成 WrenAI 的开源实践

- 生态事实：**深度引用 wrenai 的第三方开源项目稀少**。主流消费形态有三类：独立部署
  （Zeabur/Docker 模板）、语义层与独立 NL2SQL 生成器组合（Vanna 管生成 + WrenAI 管
  语义上下文）、作为同类平台（SuperSonic、Dataherald）的对照系。
- 启示：没有现成的「嵌入 wrenai 资产视图」组件可借鉴；wren-ui 经典页的「分资产组织 +
  全局 Deploy 状态」就是对标形态；官方转向 headless 后，建模工作台 UI 由平台自建，
  恰好是本页面的差异化定位。

## 决策

1. **资产区块独立可寻址 + 资产即 YAML 投影**：资产 tab 状态提升到 URL——复用
   `DatasetGroupPage` 已有的 `?view=` 机制，扩展为 `?view=modeling&asset=<key>`
   （key ∈ overview/schema/cubes/views/glossary/mdl）；单资产选中叠加 `&selected=<name>`，
   刷新、收藏、分享均不丢位。模型/Cube/视图/MDL 四个 tab 统一为
   **「左资产列表 + 右 YAML 内容」双栏范式**：点击资产右侧直接渲染其工程 YAML
   （语法高亮）——UI 是工作区 YAML 的投影，而非另一份数据副本。支撑点为后端
   新增**工作区工程文件只读端点**（见影响面）。
2. **对话与资产并排的工作台布局**：`ModelingChatPanel` 从全屏 modal 改为右侧常驻栏
   （默认 520px、可拖拽 400–760px、可折叠；折叠态仅留悬浮入口），左栏资产视图始终可见。
   对话 HITL 确认后沿用既有 `modeling:updated` → `refresh()` 链路（不重置 asset/selected
   参数），「对话 → 资产变化」全程可感知。
3. **草稿/发布双态标注**：资产行/卡片新增「未发布」小标；对比源 = 工作区 overview
   （草稿）与 `GET /mdl/view` 的 published manifest（基线）。全局状态条
   （版本 + DIRTY 横幅 + 发布按钮）保留为唯一发布出口——闭环为
   「对话建模 HITL 写工作区 → 资产标脏 → MDL 区块看全量 diff → 发布 → 标注清零」。
4. **组件拆分**：`SemanticModelingPage` 拆为页面壳 + 六个 tab 面板组件，其中
   模型/Cube/视图/MDL 四处的「列表 + YAML」双栏由一个可复用 `AssetYamlBrowser`
   组件承载（列表项含草稿橙点，右侧 YAML 语法高亮只读渲染）；表与关系 tab =
   模型双栏 + 关系三要素行（谁关联谁 · 关联字段 · 业务含义）两卡上下排布；
   MDL 区块沿用既有 `MdlPublishPanel` + `MdlGraphView`。每个面板独立数据选择器与
   加载态，可独立刷新——「独立展示」的工程基础。
5. **tab 终稿命名**：总览｜表与关系｜Cube｜视图｜术语与规则｜MDL（原「指标」改名
   「Cube」对齐 wren 术语；总览保留完善度徽章 + 文档语义增强卡不变）。

## 理由（trade-off）

- URL 寻址 + 组件拆分是「独立展示」的最小充分实现：可寻址解决「到达」，拆分解决
  「独立刷新与加载态」，二者都不触碰后端与 SSE 协议。
- 并排工作台消灭 modal 遮挡后，`modeling:updated` 既有机制即可完成「对话后更新」，
  无需新增事件或轮询；对话历史仍在会话内（sessionKey=modeling-<groupId>），
  布局变化零协议影响。
- 发布语义保持全量快照（specs/019 拍板不动）：资产级「未发布」标注只提示差异存在，
  发布动作与 diff 审阅仍收敛在 MDL 区块，避免「单资产发布」的误解。

## 被否方案

- **仅做 tab URL 化**（最小 diff）——不解决 modal 遮挡，「对话后更新」感知仍断裂；
- **单资产独立路由页**（如 `/modeling/cubes/<name>`）——需路由层大改，且与
  `DatasetGroupPage` 的 `?view=` 参数机制重复；查询参数足够承载「独立展示」；
- **per-asset 发布 diff / 单资产发布**——发布语义是全量快照 + 组级串行化
  （`MdlPublishService` ReentrantLock），资产级发布会破坏原子性并误导用户；
- **抄 wren-ui 独立 Deploy 页**——`MdlPublishPanel` 已承载同等职责，双发布入口有害；
- **对话改回抽屉/常驻左栏**——右栏对话 + 左栏资产的读写动线与现有 HITL 卡片宽度
  （`ModelingHitlCard` diff 区）更兼容，左栏会挤压表与关系列宽。

## 影响面

- 前端：`SemanticModelingPage.tsx` 拆分与布局重构（含 `AssetYamlBrowser` 复用组件、
  YAML 高亮只读渲染）、`ModelingChatPanel` 容器形态（modal → 常驻右栏）、
  `DatasetGroupPage` view 参数透传、`api/semanticModeling.ts` 增加工程文件读取与
  草稿对比数据源（published 基线复用 `getMdlView`）；
- 后端：仅一个只读小增量——`GET /api/dataset-groups/{groupId}/modeling/files`
  （按相对路径读取工作区工程文件文本，路径白名单校验限定 wren 工程目录内，
  租户校验走 `groupService.getGroup` 模式；草稿对比复用 overview 与 `/mdl/view`
  既有数据，不新增聚合端点）；
- SSE 协议（`tool_call/tool_result/hitl_request/token/done/error`）：零改动。

## 验证（拍板后落 specs/030 明细）

- `npm run build` 绿；URL 直达各资产 + 刷新不丢位；
- 对话确认 HITL 变更后，左栏资产视图即时刷新且 asset/selected 参数保持；
- 「未发布」标注与 `MdlPublishPanel` 文件 diff 一致，发布成功后标注清零；
- 折叠/展开对话栏不触发资产数据重拉。

## 决策点结论（原型迭代定稿）

1. 布局终态：**右栏常驻对话栏**（默认 520px，可拖拽 400–760px，可折叠）。
2. 「未发布」标注粒度：**资产行级橙点为主 + 状态条草稿计数横幅共存**（原型形态；
   卡片头不做重复标注）。
3. `&selected=` 单资产寻址：**首期实现**（点击资产即写 URL，选中态随刷新保持）。
4. 关系展示形态：**三要素精简行**（谁关联谁 · 关联字段 · 业务含义），待确认/已确认
   分卡；不展示 joinType 术语与冗余状态标。
