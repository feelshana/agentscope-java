# ADR 0037 — 语义完善度做纯计算信号，建模页按资产分 tab + 对话建模居中大窗

日期：2026-10-04 · 状态：Accepted

## 背景

specs/029 之后，数据源关联/上传即自动播种基线语义（表与列描述），未建模的库可以直接问数；
上传文档再经差异分析（specs/014）增强。但语义建模页的信息架构与此流程不匹配：

- 单页纵向堆 8 张卡，用户看不出「这个库的语义完善到什么程度、下一步做什么能提升」；
- 头部「表单建模 | MDL 视图」二分与卡片无对应关系，资产多了以后滚动成本高；
- 对话建模是右上角小按钮 + 400px 窄抽屉，与「对话建模是主入口」的定位不符；
- 建模对话里确认 HITL 卡后页面数据不更新，用户必须手动刷新才能看到新资产。

另有一条不可越过的边界（ADR 0018 D10）：建模是可选增强层，交互措辞不得暗示
「不建模不能问数」——完善度提示必须是质量信号而非问数门槛。

## 决策

1. **语义完善度 = 三档纯前端计算信号**（`SemanticModelingPage` 内 useMemo，specs/027）：
   对 `ModelingOverview` 一次拉取的数据即可算，无 LLM、无新后端 API——
   基线 = 确认关系 0 且 Cube 0 且已发布视图 0 且术语 0；
   已完善 = (Cube>0 或 已发布视图>0) 且（已采纳业务规则>0 或 术语>0）；
   其余为完善中。业务规则计数取文档增强提案中 `ADOPTED` 的 `BUSINESS_RULE`
   （规则无独立概览接口，复用页面已拉取的 enhance state）。
2. **措辞边界固化**：徽章 tooltip「完善度影响问数的口径准确性，不影响能否问数」；
   非「已完善」时显示引导条「已可正常问数（基线语义）。上传业务文档或使用对话建模，
   可让问数口径更准。」——只描述收益，不设前置。
3. **六资产 tab 纯前端分组**：总览｜表与关系(n)｜指标(n)｜视图(n)｜术语与规则(n)｜MDL，
   带计数；文档增强卡归总览（横向动作），MdlPublishPanel 与 MdlGraphView 归 MDL tab。
   后端零改动。
4. **对话建模居中大窗为默认形态**：`ModelingChatPanel` 增加
   `variant: 'modal' | 'drawer'`（默认 modal），modal 为居中卡片
   （约 min(1100px, 86vw) × min(720px, 84vh)）带遮罩，Esc/遮罩点击关闭；
   顶部提示「发布动作请回『语义建模』页完成」；drawer 保留可选。
5. **建模变更通知用 `window` CustomEvent（`modeling:updated`）**：
   `ModelingChatPanel` 在 HITL 确认（confirmModeling）成功后与面板关闭时各广播一次；
   `SemanticModelingPage` 监听后调用 `refresh()` 全量刷新 overview——所有 tab 数据更新，
   不重置当前 tab。

## 理由（trade-off）

- 完善度做成纯计算而非 LLM 评分：信号本质是资产计数，确定性、零成本、随 overview
  即时更新；三档固定规则避免「几分算完善」的调参泥潭。代价是规则变更要改前端
  （目前只有一处使用方，可接受）。
- 事件总线 vs 层层 callback：变更触发点在面板深层的 HITL 确认回调里，若走 props
  传递需要穿过 ModelingChatPanel 的所有内部状态层并让页面持有 onModelingChanged
  闭包；CustomEvent 一行解耦，代价是全局字符串事件命名空间——当前仅两个使用方，
  事件名集中在两文件内，可控。
- modal 大窗：建模对话需要完整呈现任务行/HITL 变更卡/长 YAML diff，窄抽屉挤压严重；
  居中大窗对齐「对话建模主入口」定位。不做成左对话右建模的常驻分栏，是避免一次性
  侵占浏览空间（specs/027「不做」节明确后置）。
- 刷新不重置 tab：用户可能正停在「指标」tab 看计数，强制跳转会丢位置（验收 G3）。

## 被否方案

- 后端 maturity API + LLM 评估完善度——引入异步与不确定性，且无法随本地乐观更新
  （如确认关系后 `applyRelations`）即时重算；
- 对话确认后 `refresh` 完跳回「总览」tab——打断用户位置，违背「不重置 tab」验收项；
- 完善度拆成独立引导页/onboarding 向导——重交互，引导条 + 徽章已够轻；
- 常驻分栏对话窗——信息密度高但屏幕利用率差，后续可加（本版 modal 默认）。

## 影响面

- 前端 `pages/configure/SemanticModelingPage.tsx`（tab 状态/徽章/引导条/总览卡/
  监听）、`components/ModelingChatPanel.tsx`（variant/通知）；后端与 API 契约零改动。
- `modeling:updated` 成为前端约定事件：任何后续会改建模资产的界面（如未来在对话外
  确认关系）都应广播同一事件，页面自动跟随。

## 验证

`npm run build` 绿；`subView` 旧状态零残留；六 tab 计数与 maturity 三档按验收
G1-G4 手工点检路径定义于 specs/027。
