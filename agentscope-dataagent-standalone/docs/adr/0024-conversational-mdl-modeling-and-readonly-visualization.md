# ADR 0024: 对话式语义建模与 MDL 只读可视化

日期：2026-09-29
状态：已接受（待实施，规格见 specs/013）

## 背景

产品分层已明确：语义建模面向开发/数据负责人，自然语言问数面向业务人员。表单式建模
（specs/010 M1-M4，语义建模页第五 Tab：关系候选卡 + cube 编辑器 + 发布链）已上线，
但实测与官方对照暴露三类**表单表达不了的语义盲区**：

1. **复合键关系**：表单 From列/To列 是单列对。实测（target/wren-probe/joinmdl）表粒度
   为 province × stat_date（320 行）、关系只声明 province=province 时，同省 10 行 × 10 行
   JOIN 成 100 行，cube 度量值 ×10 膨胀（97,085,200 → 970,852,000）；补第二列键后与
   直查真值分毫不差。wren 引擎忠实编译不做基数校验——**关系键的正确性责任全部在建模期**，
   而单列表单没有「第二对齐列」的确认入口。
2. **CASE 口径**（如「全国口径需排除省份行」）：cube measure 的条件语义靠表单下拉组合
   表达生硬，一句话即可确认。
3. **业务语义缺口**（枚举值含义、单位、NULL 语义、魔法值、软删默认过滤）：官方
   enrich-context 技能归纳为十类缺口，表单无承载点，却直接决定问数准确率。

同期 WrenAI v5（0.15.0）源码实证其建模主线即**对话式**：宿主通用 agent + 技能剧本
（generate-mdl 七阶段、enrich-context grill 问答）+ 确定性 CLI——官方没有内置「MDL 生成
agent」，剧本通过 `wren skills get` 以纯文本发放，由 Claude Code/Cursor 等宿主执行；
经典版 wren-ui 可视化建模页是旧产品线（可视化形态的有效参照仍来自它）。

本决策：把官方「宿主 agent + 剧本」模式移植进平台——平台内置建模助手承担宿主角色，
对话完成建模；建模成果在语义建模页以**只读** ERD 可视化呈现（参照经典 wren-ui）。

## 决策

1. **D1 交互形态 = grill 对话移植**。逐条移植官方 grill 硬规则：一次一题
   （one question per round-trip）、每题必带推荐答案（pre-draft every proposal，禁止
   lazy-ask）、accept/edit/skip 三键、skip 会话内终局不回访。与官方的关键差异：**建模
   状态以平台实体为准，不依赖会话记忆**——官方 "session lives entirely in conversation"
   断线即失，平台的实体状态机（关系 PENDING/CONFIRMED/REJECTED、组
   NONE/DIRTY/PUBLISHED）天然支持中断恢复与换人续作；每个建模工具的返回值携带当前组
   建模快照回灌上下文，防长对话漂移。
2. **D2 独立「建模助手」agent**。不把建模工具挂上 data-agent：①工具面隔离——问数
   agent 不见建模工具，避免业务人员场景下的工具选择干扰；②产品分层对应（建模给
   开发/数据负责人）；③会话域独立。实现走 `DataToolkitRegistrar` 同款后置注册模式
   （新 ModelingToolkit 注册到建模助手 agent）。
3. **D3 agent 写实体、服务层拼 YAML**（与官方 agent 直写 YAML 文件的关键分叉）。对话
   确认动作 = `@Tool` 调用 = 实体写入（复用 MdlSuggestionService 既有方法：确认/拒绝/
   手工关系/建删 cube）；YAML 仍由 MdlPublishService 从实体确定性拼装，发布链路
   （validate --strict → build → snapshot → version+1）零改动。理由：多租户过滤在服务层、
   双入口（对话写/表单看）天然一致、LLM 产出面收敛到结构化工具参数而非自由 YAML 文本。
4. **D4 复合键关系支持**。`DatasetRelationEntity` 单列对（sourceColumn/targetColumn）
   扩展为列对列表；`MdlPublishService` 关系条件渲染为 `a.c1 = b.c1 AND a.c2 = b.c2`
   （wren relationships.yml 原生支持，探针实证）。表单（specs/010 后续增强）与对话两
   入口同步支持；fan-out 实测即其必要性证据。
5. **D5 只读 MDL 可视化**。语义建模页新增「MDL 视图」Tab：ERD 画布（模型卡 = 逻辑
   模型/描述/列清单，关系列与计算列带标注；连线 = joinType + 键标注）+ cube 卡
   （指标/维度/时间维度分组）+ 发布状态条（版本/时间/changed）。参照经典 wren-ui 的
   ERD/模型页形态，但**明确只读**——无编辑、无拖拽连线；编辑回到对话建模或表单。数据
   源为后端结构化视图端点（从 MdlPublishService#assemble 的 ModelSpec/RelationSpec/
   CubeSpec 暴露只读 VO，含草稿态与已发布快照对照），前端不解析 YAML（无 yaml 依赖）。
   画布用既有 `@antv/g6`（GraphView/KnowledgeGraphView/OntologyGraphView 同款），
   不引入新库。
6. **D6 双入口一致性纪律**。对话产出与表单产出写同一组实体、走同一 DIRTY 状态机与
   同一 publish 质量闸，绝不旁路；对话建模完成后引导用户到语义建模页发布（发布 =
   显式人工动作，D9 精神延续；M1 不提供对话内发布工具）。
7. **D7 剧本硬约束执行层化**。官方三条保命规则靠 prompt 自律，平台在工具层强制兜底：
   只增不改（update/delete 类工具对已 PUBLISHED 版本覆盖需显式确认标记）、高爆炸半径
   必确认（工具落库即确认动作，无静默写入）、冲突必问人（剧本规则 + 工具返回快照）。
   平台可以比官方做得更严。

## 理由与权衡

- 为什么对话式：三类盲区都是「问一句即可确认」的语义；官方已验证 grill 交互形态；
  复合键确认是对话的自然产物（「这两张表是否按省份+日期对齐？」）。
- 为什么 agent 写实体而非直写 YAML：官方形态的前提是宿主 agent 有文件系统与 Git 审阅
  流；平台用户是 Web 端，多租户过滤、双入口一致性、确定性拼装都要求实体为唯一事实源。
- 代价：两套建模入口的一致性维护成本；建模剧本 prompt 成为新的指令层（ADR 0007 分层
  模型——剧本规则须与工具描述同步，沿用 SharedSkillContentTest 的 classpath 断言守卫
  模式）；建模助手新增约 7 个工具面。
- 明确不决策项：建模会话入口形态与列表归属细节、ERD 布局算法细节、enrich 语义缺口
  的范围裁剪（M4 可选项）、对话内发布（M3 后再评估）——留给 specs/013 与实施。

## 影响面

- 待实施（规格：docs/specs/013-conversational-mdl-modeling.md，M1-M4 里程碑）：新建
  ModelingToolkit + 建模助手 agent 装配、grill 剧本 prompt、DatasetRelationEntity 复合键
  扩展（JPA 迁移）、MdlPublishService 复合键渲染 + 发布期基数预检、MDL 只读视图端点、
  前端 SemanticModelingPage 对话入口 + MdlGraphView（G6）。
- 架构文档同步（实施期）：ARCHITECTURE_zh 2.5（工具后置注册补建模助手）、5.4（关系
  推断补复合键）、5.6（发布流补对话入口）、11（前端结构补 MDL 视图）。
- 不改动：specs/010 已实施的表单建模行为与发布链路语义；问数 agent 工具面。

## 关联

- ADR 0018（D9 建模时点与状态机、D10 双通道）、ADR 0007（提示词分层——剧本为新指令层）
- specs/010（表单建模实施基线）、specs/013（本决策实施规格）
- WrenAI 官方证据：skills_content/generate-mdl/SKILL.md（七阶段剧本 + 无外键三级降级
  + joinType 映射）、skills_content/enrich-context/SKILL.md（grill 硬规则、十类缺口、
  高爆炸半径升级问人、只增不改）、经典 wren-ui 建模页（ERD/模型卡可视化参照）
- fan-out 实测记录：target/wren-probe/joinmdl（province 单列键 ×10 膨胀 → 复合键修复
  分毫不差，D4 必要性证据）
