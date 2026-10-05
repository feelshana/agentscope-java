# specs/028 — 关系说明文档下线，上传语义文档并入建模页 + tab 导航重构 + 废弃上传自动建图

日期：2026-10-04 ｜ 状态：已实施（mvn test 360 绿 + npm build 绿；G1-G4 代码面达成，运行时点检待用户）

## 背景

1. 「关系说明文档」是知识库左侧独立导航（`DatasetGroupPage` 的 `doc` 视图 = `DocDetailView`），
   与语义建模页的「上传文档增强」按钮割裂：上传在 A 页、分析在 B 页；且文件视图里还有第三处
   上传卡。同一份文档三个入口、两个页面，认知负担高。
2. specs/027 落地后截图实证：六 tab 按钮组挤在状态头中间导致换行（MDL 掉到第二行）；
   「建模总览」卡的计数按钮组与 tab 头计数重复。
3. 上传文档会自动触发知识图谱构建（`uploadKnowledge` 内 `knowledgeGraphService.triggerBuild`），
   用户判定该 LLM 图谱价值不稳定——废弃上传自动建图；知识图谱 tab 展示什么后续再定
   （手动构建入口 `KgBuildConfigModal` 保留，`KnowledgeGraphView` 现状不动）。

## 改动清单

### A. 关系说明文档下线，上传能力并入建模页（改名「上传语义文档」）

1. `DatasetGroupPage`：`View` 类型去掉 `'doc'`；`NAV_ITEMS` 删「关系说明文档」项；左侧 rail 的
   知识文档行删除；`files` 视图的「关系说明文档」上传卡删除；`handleKnowledge`/`knowledgeRef`
   隐藏 input 删除；`uploadKnowledge`/`DocDetailView` import 删除；`fileCount` 不再 +1。
2. `DocDetailView.tsx` 组件删除（唯一使用方是本页）；`api/datasets.ts` 的 `getKnowledge` /
   `KnowledgeDoc` 随之成为孤儿一并删除（`uploadKnowledge` 保留，建模页继续用）。
3. `SemanticModelingPage`：新增隐藏 `<input type="file" accept=".docx,.md,.txt">` +
   `handleSemanticDocUpload`（调 `uploadKnowledge` → 后端自动触发增强分析 → 前端
   `refreshEnhance()` + `refresh()`；busy key `semantic-doc`）。上传入口三处，文案统一
   「上传语义文档」：引导条按钮（原「上传文档增强」）、文档语义增强卡头部（常驻入口，
   refined 后也可传新文档）、（引导条隐藏时由卡片头部承接）。
4. 后端 `PUT /{id}/knowledge` 端点与 `saveKnowledge → knowledgeText` 注入链路不动
   （文档仍每轮注入问数 SystemMessage，文档增强分析照常自动触发）。

### B. tab 导航重构

1. **tab 独立成行**：从状态头拆出，underline 风格（容器底边框，active 项 2px 主色条 + 主色
   文字），计数以浅色小徽章缀于标签后；六项一行放下不再换行。
2. **状态头精简**：标题 + `MdlStateBadge` + `MaturityBadge` + 发布时间 + 「对话建模」主按钮。
   「重新分析关系」挪到「表与关系」tab 的关系候选卡头部（与「+ 手工添加」并排——它是 schema
   专属操作）。
3. **「建模总览」卡删除**（计数与 tab 头重复、说明与引导条重复）；总览 tab 内容 =
   文档语义增强卡（卡头部含「上传语义文档」+「重新分析文档」）。

### C. 废弃上传自动建图

1. `DatasetGroupController#uploadKnowledge` 删除 `knowledgeGraphService.triggerBuild(...)` 静默块；
   `knowledgeGraphService` 字段、构造器参数、import 一并移除（该 Controller 内唯一调用点）。
2. `DatasetGroupControllerTest` 构造器同步去参。
3. 知识图谱能力本身保留：`KnowledgeGraphController` 手动构建端点、`KnowledgeGraphView` 展示、
   `semanticContext` 查询链路均不动。展示内容待定 = 本 spec 不改图谱 tab。

## 验收

- G1：知识库左侧导航无「关系说明文档」；全局 grep 无 doc 视图残留。
- G2：语义建模页「上传语义文档」→ 选择 .md → 文件保存 + 增强任务自动出现（分析中 → 提案可审）；
  上传中按钮有 busy 态。
- G3：六 tab 一行展示不换行；点「重新分析关系」在表与关系 tab 内生效；总览 tab 只有增强卡。
- G4：上传文档后 `KnowledgeGraphBuildTaskEntity` 不再新增任务（手动构建仍可用）。
- G5：`mvn test` 全绿 + `npm run build` 绿。

## 不做

- 知识图谱 tab 新展示形态（待定，后续 spec）。
- `KnowledgeGraphService`/手动构建端点删除（保留能力，仅断上传自动触发）。
- 文档查看界面（DocDetailView 能力）替代品——文档正文仍注入问数上下文，暂无查看需求。
