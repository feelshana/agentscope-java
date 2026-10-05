# spec 030: 语义建模页工作台化

> ADR 0041（Accepted）的实施明细。视觉与交互基准 = `docs/prototypes/0041-modeling-workbench.html`。

## 背景与目标

语义建模页当前存在三处断裂（ADR 0041 背景）：资产视图不可寻址、对话 modal 遮挡资产、
草稿/发布双态只在 MDL tab 可见。目标：改造为**工作台布局**——左栏六资产 tab
（总览｜表与关系｜Cube｜视图｜术语与规则｜MDL）+ 右栏常驻对话；资产 tab 状态进 URL；
模型/Cube/视图/MDL 点击资产直接展示其工程 YAML（工作区即真相，UI 是 YAML 的投影）；
对话 HITL 确认后资产行级「未发布」标注即时可见，发布后清零。

## 方案概述

### 后端（一个小增量，其余复用）

- `SemanticModelingController` 新增只读端点
  `GET /api/dataset-groups/{groupId}/modeling/files?path=<相对路径>`：返回工作区工程文件
  文本（`{ path, content }`）。校验链 = 租户校验（`groupService.getGroup(ownerId, groupId)`
  模式）→ 路径安全（normalize 后必须落在该组 wren 工作区根内，拒绝 `..`/绝对路径，
  ADR 0032 guard-escape 教训）→ 扩展名白名单（`.yml`/`.yaml`/`.md`）。
  文件读取走 `MdlPublishService` 已有工作区根定位通路，阻塞 IO
  `subscribeOn(Schedulers.boundedElastic())`。
- 草稿对比不新增端点：行级「未发布」判定数据源 = 既有 overview 响应（工作区现状）与
  `GET /mdl/view`（已发布快照）在前端比对；若 overview 已含 DIRTY 文件列表则直接映射。

### 前端（改造主体）

- `SemanticModelingPage.tsx`（2222 行）拆为页面壳 + `components/modeling/` 下面板组件：
  `OverviewPanel`（完善度 + 文档语义增强，现状迁移）、`SchemaPanel`（模型双栏浏览器 +
  关系三要素行两卡）、`CubesPanel`、`ViewsPanel`、`GlossaryPanel`（现状迁移）、
  MDL 区块沿用 `MdlPublishPanel` + `MdlGraphView`；四处「左列表 + 右 YAML」复用新组件
  `AssetYamlBrowser`（列表项含草稿橙点；右侧 YAML 高亮只读渲染，轻量自实现
  key/注释/字符串着色，不引第三方库）。
- URL 寻址：`useSearchParams` 读写 `asset`/`selected`；tab 切换、资产点击均同步 URL；
  刷新还原位置；`modeling:updated` 刷新不重置参数。
- `ModelingChatPanel`：`variant` 增加 `dock` 形态（右栏常驻，默认 520px、可拖拽
  400–760px、可折叠为悬浮入口），`SemanticModelingPage` 以 dock 形态嵌入；modal 形态
  保留给既有其他调用方（若有）。
- 状态条升级：发布版本徽章 + 草稿变更计数 + 去发布按钮（DIRTY 横幅既有能力收敛进状态条）。
- 关系行改三要素精简形态：`a → b ｜ 字段对徽章 ｜ 业务含义`，待确认/已确认分卡。

数据流：对话 HITL 确认 → `modeling:updated` → 页面 `refresh()`（overview 重拉）→
行级草稿标注与状态条计数更新 → 用户在状态条/MDL 区块发布 → 标注清零。

## 影响面

- 后端：`SemanticModelingController`（+1 端点）、`MdlPublishService`（若工作区根定位为
  private 则提升可见性或加只读门面方法）、对应测试；
- 前端：`pages/configure/SemanticModelingPage.tsx`（拆分重构）、`components/modeling/*`
  （新）、`ModelingChatPanel.tsx`（dock 形态）、`api/semanticModeling.ts`
  （+`getModelingFile`）、`DatasetGroupPage.tsx`（`?view=modeling` 已有，核对透传）；
- 数据：无 JPA 实体/物理表变更；
- 文档：`ARCHITECTURE_zh.md` 3.2（工作台布局一句）与第 11 章（组件清单）同步。

## 验收标准（Given-When-Then）

1. Given 进入建模页，When 点击任意 tab 或资产，Then URL 同步 `asset`/`selected`，
   刷新后位置与选中态保持；
2. Given 模型/Cube/视图/MDL tab，When 点击左侧资产，Then 右侧渲染该工程文件 YAML
   （语法高亮、只读），内容与 MDL tab 同一文件一致；
3. Given 对话 HITL 确认写入成功，When `modeling:updated` 触发刷新，Then 对应资产行出现
   「未发布」标注、状态条草稿计数 +1，且 URL 参数不变；
4. Given 存在未发布变更，When 发布成功，Then 标注清零、版本徽章 +1；
5. Given 对话栏折叠/展开或拖拽调宽，When 操作完成，Then 不触发资产数据重新请求；
6. Given 用户访问他人 groupId，When 调 `modeling/files` 端点，Then 404（看不到别人的数据）；
7. Given `path` 含 `..`、绝对路径或非白名单扩展，When 调 `modeling/files` 端点，Then 400。

## 不做的事（明确排除项）

- 不做单资产发布、不做资产级 diff 审阅页（发布语义保持全量快照 + 组级串行化，specs/019）；
- 不改 SSE 协议与 `ModelingHitlMiddleware` 拦截面；
- 不重绘 ERD（`MdlGraphView` 沿用现状）；
- 术语与规则 tab 不做 YAML 双栏（术语是平台 DB 字典，非工程文件；业务规则 md 经
  MDL tab 查看）；
- 不迁移历史数据、不动 DRAFT/PUBLISHED 遗留状态机（specs/019 已退役）。

## 测试要求

- 后端新增 `SemanticModelingControllerTest`（或对应测试类）用例：租户隔离 404、
  路径穿越/绝对路径 400、非白名单扩展 400、正常读取 200 且内容完整；
- 前端 `npx tsc --noEmit` + `npm run build` 绿；`mvn test` 与 `spotless:check` 全绿。

## 实施记录（2026-10-05 落地，与原方案的差异）

1. **文件读取端点复用而非新增**：specs/019 M5 已交付 `GET .../modeling/workspace/file`，
   本次仅收紧工程文件语义（新增 `isModelingAssetPath`：拒绝 `.platform/`、`target/` 与
   非白名单扩展；测试 `workspaceFileRejectsPlatformRuntimeAndNonAssetPaths`）。
2. **后端 path 透传（原方案未预见）**：前端不做资产名→路径猜测，`MdlWorkspaceReader` 的
   `WorkspaceModel/WorkspaceCube/WorkspaceView` record 增加 `path` 字段
   （`models/<dir>/metadata.yml`、`cubes/<dir>/metadata.yml`、`views/<dir>/sql.yml`），
   经 `MdlPublishService#view` 一路透传到 `/mdl/view` 响应。
3. **行级脏标注数据源改用 `/mdl` preview**：`MdlPreview.files` vs `publishedFiles`
   内容级对比（比原计划的 overview 前端比对更准），dirty 集合驱动行级「未发布」橙点
   与 DIRTY 状态条草稿计数。
4. **未拆面板组件**：2222 行单文件保持单文件，仅拆出 `components/modeling/YamlContent`
   （YAML 高亮）与 `components/modeling/AssetYamlBrowser`（双栏浏览器）两个新组件；
   模型/Cube/视图三个 tab 内改双栏浏览器，编辑/删除经选中行操作区保留。
5. **dock 固定 520px**（拖拽调宽未做，需要时再补）；对话默认状态两轮迭代：嵌套视图时期默认收起（2026-10-05 首轮反馈「太窄」）→ 全屏路由化后恢复**默认展开**（同日复反馈「对话建模没有默认打开」，全屏下 dock 展开仍有 ~1360px 资产区，不再挤）；头部按钮变「收起对话/对话建模」toggle；modal 形态保留（`variant` 默认值不变）。
6. **关系行保留既有 RelationRow**（specs/027 已验收的三要素行式），未再做精简。
7. **全屏路由化（2026-10-05 二次验收反馈：「看不到 tab、内容很窄、占据整个页面」）**：语义建模从 `DatasetGroupPage` 的第五个视图升级为独立全屏路由 `/configure/modeling/:groupId`——`AppShell` 在该路径隐藏全局会话侧栏，页面独占整个视口；顶栏改为「← 返回 + 组名 + 状态徽章 + 对话建模按钮」；tab 行升级为**分段控件**（segmented：容器卡片 + active 项浅紫底紫边实心 pill，复用 `.da-btn` 体系）——此前 underline 形态（灰字+2px 底线）两轮验收均被反馈「看不到 tab」，视觉权重过低是直接原因；`DatasetGroupPage` 的「语义建模」导航项改为路由跳转，旧 `?view=modeling` 链接重定向到新路由；对话 dock 默认展开。宽度账（1920 屏）：内容区 ~1340 → ~1880px（dock 展开时 ~1360）。

注：此前「modeling 视图下父页左栏收窄为纯导航」的过渡方案（rail 260→200 + 隐藏文件列表）已被本条全屏路由化取代，`DatasetGroupPage` 恢复常态。
8. **第三轮验收修复（2026-10-05）**：① tab 容器被下方引导条重叠遮挡——根因是主列 flex column 溢出时默认 flex-shrink 压缩子元素，修复 = tab 容器与引导条均加 `flexShrink: 0`、tab 容器加 `position:relative; zIndex:1`；② 引导条改为**新手引导一次性提示**——新增 `enhanceTouched`（跑过文档增强分析或有任一提案被采纳/忽略）为真时不再显示，解决「已做过文档上传→对话建模→发布仍提示基线语义」的打扰（maturity 未到 refined 时原条件恒真导致常驻）。

验证：`mvn test` 368/0/12 skip、`spotless:check`、`npx tsc --noEmit`、`npm run build` 全绿。

## 关联

- ADR：docs/adr/0041-semantic-modeling-page-workbench.md（Accepted）
- 原型：docs/prototypes/0041-modeling-workbench.html（交互与视觉基准）
