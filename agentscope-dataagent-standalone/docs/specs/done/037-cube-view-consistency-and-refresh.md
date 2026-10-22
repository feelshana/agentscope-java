# spec 037: Cube/视图「页签计数×内容」同源、REST 写侧同步工作区、刷新链路补全

## 背景与目标

语义建模页的 Cube/视图页签计数读平台 DB 实体（overview），而内容读工作区 YAML（mdlView）；REST 写侧（手工新建、AI 提议采纳、文档增强采纳走的 createCube/createView/update/delete）只写 DB 从不落工作区，对话写侧只写工作区不回 DB，发布又只消费工作区——三股写各写一边，造成「发布后计数对不上、点开显示 暂无 Cube」的分叉。目标：读写全部以工作区为唯一事实源，计数与内容永远一致，任何写入后界面自动刷新。

## 方案概述

1. 读侧同源（SemanticModelingPage）：tabItems 的 cubes/views 计数改取 mdlView（工作区读取结果），overview 仅保留术语/规则等 DB 资产用途。
2. REST 写侧补齐工作区同步（MdlSuggestionService）：createCube/updateCube/deleteCube/createView/updateView/deleteView 在 DB 写入之外，经 `MdlWorkspaceService#withWorkspaceLock` 同步写/删工作区文件——Cube 落 `cubes/<name>/metadata.yml`（name / base_object / measures expression=agg(column) / dimensions / time_dimensions），视图落 `views/<name>/metadata.yml` + `sql.yml`；base_object 经 `MdlWorkspaceReader` 读工作区模型清单按 baseDatasetId 解析逻辑模型名；工作区未初始化或该数据集无逻辑模型时操作失败并给出明确错误，绝不出现「只写一边」；同名目录已存在返回 409。写前 `ensureWorkspace` 保证骨架存在。
3. 刷新链路补全：`SemanticModelingPage#refresh` 拆分容错（单源失败不拖垮整页）；onSaveCube/onDeleteCube/onSaveView/onDeleteView 成功后调用 refresh()；`ModelingChatPanel` 在 done 帧派发 `modeling:updated` 事件（覆盖 write_file/create_view/decide_relations 对话写路径），页面监听刷新。

需同步 ARCHITECTURE_zh.md 的 YAML-first 单一事实源约定章节。

## 影响面

- 后端：dataset/MdlSuggestionService（Cube/视图 CRUD 同步）、dataset/MdlWorkspaceService（复用，无接口变更）
- 前端：pages/configure/SemanticModelingPage.tsx、components/ModelingChatPanel.tsx
- 数据：无迁移
- 文档：ARCHITECTURE_zh.md；ADR 0045

## 验收标准

1. Given 工作区已播种模型，When 经 REST createCube 创建 Cube，Then DB 出现实体且 cubes/<name>/metadata.yml 同时生成，页签计数与列表内容一致。
2. When updateCube/deleteCube/createView/updateView/deleteView，Then 对应工作区文件同步更新/删除。
3. Given 工作区未初始化或 baseDatasetId 无对应逻辑模型，When createCube，Then 返回明确错误且 DB 不产生新实体（不出现单边写入）。
4. Given 同名 Cube 目录已存在（工作区或 DB 任一侧），When createCube，Then 返回 409。
5. Given 对话内 write_file/create_view 写入后收到 done 帧，When 页面监听 modeling:updated，Then 计数与内容自动刷新，无需手动刷新页面。
6. Given 发布完成，When MdlPublishPanel 回调 onPublished，Then 页签计数与内容同步更新。
7. Given overview 或 mdlView 单个请求失败，When refresh，Then 另一个源的数据仍然渲染，页面不空白。

## 不做的事

- 不做 DB→工作区的一次性回填迁移（历史 DB-only Cube 由用户重建或后续迁移工具处理）。
- 不改发布链（MdlPublishService 仍只消费工作区）。
- 不引入 WebSocket 推送（沿用自定义事件 + 主动刷新）。

## 测试要求

- MdlSuggestionServiceTest：Cube/视图 CRUD 的工作区文件写入/删除/409 断言；「无模型时 createCube 拒绝且不落 DB」用例。
- 前端：npm run build 通过；关键交互以手工验收为准。

## 关联

- ADR：实施后补 docs/adr/0045（语义资产以工作区为唯一事实源的写侧收口）
- 前置：specs/019、ADR 0033、specs/030
