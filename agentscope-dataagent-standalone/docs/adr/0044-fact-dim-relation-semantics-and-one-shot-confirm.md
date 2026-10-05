# ADR 0044: 关系候选只建事实↔维与确认一次即生效

- 状态：已采纳（实施规格 specs/036）
- 日期：2026-10-05
- 来源：用户拍板（LLM 层按表描述/行数/示例数据做事实-维语义判定，不复刻主键机械过滤；事实表之间严禁推荐关联）+ 批量确认卡噪声实测

## 背景

关系候选此前按「共享同名列」全对互连，批量确认卡里混入大量噪声对：事实↔事实（订单表↔发货表共享「下单日期」）、维↔维（两张字典表互连），用户逐条甄别成本高。同时主键形态（首列或 `<表>_id`）无法单独定方向——维表被事实表引用的列完全可能不是维表主键（订单表.商品编码 ↔ 商品表.商品编码，业务键关联合法），机械按主键位置过滤会误杀这类合法关联。

## 决策

1. **D1 规则层 XOR 确定性定向（RelationInferenceService）**：候选仅在「恰好一侧为主键形态列（首列或 `<表>_id`）」时生成；pkLike 侧为维表（target）、另一侧为事实表（source），方向确定性归一。双主键形态侧（字典表互连）与双非键侧（事实↔事实共享属性列）一律不成边——宁缺勿滥，噪声对在源头消失。
2. **D2 LLM 层语义判定只做反向修复（MdlSuggestionService#buildRelationPrompt / parseRelationSuggestions）**：提示词两步法——先结合表描述/表行数/示例数据（preview 前 5 行，逐表容错、单元格截断 40 字符）判定各表是事实表还是维表，再只提议事实→维方向的关联；明确「维表被引用列不一定是主键、事实表与事实表/维表与维表严禁推荐」。解析层不复刻 D1 过滤：仅当提案恰好维侧 pkLike 而事实侧非时做方向反转修复，其余（如非主键业务键关联）原方向保留、永不丢弃——宁全勿弃，人工兜底。
3. **D3 确认一次即生效**：`decide_relations` 确认即写 `relationships.yml` 并附 `context validate --strict` 结论，之后模型不得复述或再次征求确认（MODELING_SCRIPT 硬约束）；`suggest_relations` 纯重算从 `ModelingHitlMiddleware.WRITE_TOOL_NAMES` 门禁摘除（确认卡单一事实源仍是 WRITE_TOOL_NAMES，ChatController#confirmModeling 同读一份）；展示层精简为三要素（表.字段 → 表.字段 + 连接基数），业务含义与置信度从工具协议与确认卡退场。

## 理由与权衡

- 规则层与 LLM 层职责分离：规则层确定性保证底线质量，LLM 层语义覆盖弥补形态误判；两层不互相复刻过滤逻辑——LLM 复刻 XOR 会双重误杀非主键业务键关联。
- 一次即生效的代价是拒绝后重提需重新批量提案；收益是「确认 → 写入 → 校验结论」闭环无二次交互，对话轮次显著缩短。
- note/reason 从工具协议与卡片移除；历史 DB 行中的 note 向后兼容（读取端忽略，不做迁移）。

## 影响面

- 修改：`RelationInferenceService`（XOR 配对环 + 方向归一）、`MdlSuggestionService`（两步法提示词 + appendSampleRows + 解析反向修复）、`ModelingToolkit`（decide_relations 文案、renderState 候选行三要素）、`DataAgentConfig`（MODELING_SCRIPT 硬约束第 8 条）、`ModelingHitlMiddleware`（WRITE_TOOL_NAMES）、前端 `ModelingHitlCard`/`SemanticModelingPage`（三要素渲染、RelationRow 删描述与置信度）。
- 测试：`RelationInferenceServiceTest`（事实-事实不成边/方向归一/双键不成边）、`MdlSuggestionServiceTest`（提示词携带表事实与示例数据/反向修复/非主键维键保留原方向）、`ModelingHitlMiddlewareTest`（suggest_relations 出门禁）。
