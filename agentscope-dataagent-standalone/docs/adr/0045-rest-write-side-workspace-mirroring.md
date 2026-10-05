# ADR 0045: 语义资产写侧收口——REST 同步工作区与刷新链路

- 状态：已采纳（实施规格 specs/037）
- 日期：2026-10-05
- 来源：specs/030 工作台「发布后页签计数与内容分叉」缺陷定位 + ADR 0033 D1 补全

## 背景

三股写各写一边：REST 写侧（语义建模页手工新建、AI 提议采纳、文档增强采纳走的 createCube/createView/update/delete）只写 DB 实体从不落工作区；对话写侧（write_file/create_view）只写工作区；发布链只消费工作区。结果是「发布后 Cube 页签计数对不上、点开显示暂无 Cube」——计数读 DB（overview）而内容读工作区（mdlView），两侧各说各话。

## 决策

1. **D1 读侧同源**：`SemanticModelingPage` 的 Cube/视图页签计数改读 `mdlView`（工作区解析结果），与页签内容同源；overview 仅保留术语等 DB 资产用途。
2. **D2 REST 写侧镜像工作区（MdlSuggestionService）**：Cube/视图六个写方法（createCube/updateCube/deleteCube/createView/updateView/deleteView）在工作区锁内 `ensureWorkspace` 后镜像写/删 `cubes|views/<name>/` 官方 YAML——cube：name/base_object/measures/dimensions/time_dimensions；view：metadata.yml（`properties.description`）+ sql.yml（`statement`）。文件写先于 DB save——工作区失败中止整个操作，绝不单边写（与 persistConfirmedRelation 同一模式）。`base_object` 经工作区模型清单按 datasetId 解析（复用 resolveModelName 的 modelName 内核），未播种报 400；同名工作区目录已存在报 409；改名时清旧目录。成员 `type` 取播种模型列的 parse-types 归一化类型、回退 VARCHAR（缺 type 会炸 context build serde，specs/023 实证；不经 WrenTypeNormalizer——那是阻塞子进程，不进 CRUD 路径）；specs/026 caseFilter 渲染为 `AGG(CASE WHEN <filter> THEN col END)`。
3. **D3 刷新链路**：`refresh` 拆分容错（`Promise.allSettled`，单源失败不空白整页）；onSaveCube/onDeleteCube/onSaveView/onDeleteView 成功后 refresh；`ModelingChatPanel` 在 done 帧广播 `modeling:updated`（覆盖 write_file/create_view/decide_relations 对话写路径），页面监听原地刷新、不丢失当前位置。

## 理由与权衡

- 与其做 DB→工作区对账迁移，不如让全部写入路径收敛到 ADR 0033 D1 声明的唯一事实源——REST 是漏网的侧写，收口后「分叉」这一类缺陷结构性消失。
- 文件先行的代价是 DB 失败可能留脏文件（工作区目录存在但 DB 无记录），由 409 目录检查兜底并提示清理；不引入分布式事务。
- 不做一次性回填迁移：历史 DB-only Cube 由用户重建或后续迁移工具处理。
- 不改发布链（MdlPublishService 仍只消费工作区）、不引入 WebSocket 推送（沿用自定义事件 + 主动刷新）。

## 影响面

- 修改：`MdlSuggestionService`（syncCubeToWorkspace/syncViewToWorkspace/deleteWorkspaceDir/renderCubeMembers/readJsonList）、前端 `SemanticModelingPage`（计数同源/refresh 容错/保存删除刷新）、`ModelingChatPanel`（done 帧广播）。
- 测试：`MdlSuggestionServiceTest` 新增 6 用例（创建镜像含 CASE 表达式与类型推导/视图双文件/改名搬目录/删除清目录/无模型 400 且不落库/目录被占 409）。
