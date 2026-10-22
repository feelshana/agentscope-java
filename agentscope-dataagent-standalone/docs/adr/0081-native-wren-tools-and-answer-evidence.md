# ADR 0081：官方工具直接调用与业务结果证据

- 状态：Accepted（2026-10-16 用户确认三项方案并授权实施；实现状态见spec069）
- 关联：spec069；架构审查docs/test-reports/20261016-wren-architecture-review.md。
- 替代范围：ADR0069的问数双工具入口和依赖查询前答案标注的实现；官方技能、MDL、权限、只读查询及显式发布边界保留。

## 背景

通用wren_call_tool的Map参数导致模型二次包装，日志出现五次缺tool_name；编译通过仍遗漏固定过滤；成功结果在图文中被算错、误读。继续追加提示不能代替工具契约和验证。

## 决策

1. 模型直接使用安装版官方工具schema，平台通过AgentScope原生MCP注册/执行能力管理调用；不自建ReAct或反射执行。工具发现和执行仍经WrenQueryAccess/WrenQueryGateway授权与门锁，排队后复核撤权。禁用store_query等问数写面，保留点赞授权。
2. 工具绑定必须限定本轮用户、选库、已发布工程；共享Agent不能保存可变的当前用户/当前库。单库使用官方名称，多库使用确定且无歧义的名称绑定，原始arguments不添加平台字段。每轮工具定义与实际执行绑定一致，空选库无Wren能力。原生McpClientWrapper不直接接收RuntimeContext，因此客户端只允许绑定不可变调用上下文并在调用时复核，禁止全局缓存用户客户端。
3. 区分技术验证和业务验证。官方validate/build/计划检查不代表业务口径正确。关键指标验收覆盖过滤、去重粒度、关联基数、空值及时间覆盖，使用独立预期结果夹具；用户未确认的口径明确待确认，不要求每次填写问题表。实际指标固定条件进入官方可执行资产，不统一改写SQL或MDL。
4. 成功查询自动形成候选结果记录，包含结果ID、工程版本、SQL/工具、数据及截断状态；执行成功不自动成为最终答案或点赞样例。最终回答显式关联采用结果及其子问题，再由点赞保存，诊断/失败不入memory。不向官方参数添加answer_question/query_type。
5. SQL/Python负责派生数值，图表与文字引用同份结果。统一引用减少抄写错误，不能证明业务分母正确；业务验证独立保留。对任意自然语言不能保证自动证明所有结论正确，不以检查通过作此承诺。
6. 分阶段实施并在每阶段明确是否已切换主运行时。工具schema和执行隔离未验证前不移除旧通道。测试覆盖直接官方、固定SQL经平台、自然语言端到端三个层级，不能用前两层代替第三层。

## 影响与限制

保留官方skills原文、平台通用文件授权与页面发布。不修用户已发布业务模型、不自动迁移历史memory、不重启服务。Docker故障单独治理。本ADR是用户已授权的架构变更，不要求再次确认；新增框架能力须在core/harness层实现和测试。

## AgentScope 接入约定

AgentScope 的 `Toolkit.registerMcpClient` 负责读取官方 `McpSchema.Tool` 并生成原生 `McpTool`。
运行时通过 `RuntimeContext` 绑定本次调用的作用域 Toolkit；模型 schema 获取和工具执行必须读取同一个作用域 Toolkit。
不能把绑定具体 project 的 MCP client 注册到共享 Agent Toolkit，也不能在并发请求之间切换共享 Toolkit 的激活组。
作用域 Toolkit 由基础 Toolkit 复制后注册当前已授权 project 的 MCP client，调用结束后关闭 client；基础 Toolkit
只包含平台通用工具。MCP client 的 project、profile 和用户身份由平台绑定，模型参数不能覆盖。
