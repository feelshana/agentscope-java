# spec083：统一逐表 SQL 时间窗口门禁

## 目标

建模 CLI（query/dry-run/dry-plan/memory store）与问数 MCP（run_sql/dry_run/dry_plan）共用检查器。输入只有原始 SQL、当前知识库数据库中登记的逻辑表名、时间列和窗口；不读取 MDL、工程目录或视图定义，不修改 SQL。

## 行为

配置独立存入平台数据库，知识库页面管理，不依赖 MDL、建模、问数会话或发布状态；配置资产不依赖建模，采样后自动建模前的确认交互由spec084补充。

- LLM 识别实际使用的表及别名，对每张已登记且 LIMITED 的表独立检查对应时间列、有限起止日期及窗口。未使用配置不影响本次查询；未登记表、匹配不上的视图及 UNLIMITED 表跳过。
- 返回 allowed、tables（table/timeColumn/hasCondition/windowDays/maxWindowDays）和中文 reason。解析失败、超时或无法确定范围拒绝；空配置直接通过。
- 拒绝后返回明细及询问用户的指令，本轮停止 SQL 重试。新轮重新读配置，不持久化日期状态。
- 页面从采样时间列中指定一列；旧配置多个候选列暂按任一候选列形成有效范围判断，再次保存后收敛到指定列。LIMITED 缺少选择时提示用户。
- query_cube 不做血缘推导，不宣称受逐表 SQL 门禁保护。当前基础建模 Cube 命令禁用规则不在本次调整。
- 只读、租户授权及排队撤权继续生效；门禁耗时独立记录。模型识别可能漏检，不保证数据库执行计划性能。

## 验收

覆盖 a/b/c 示例返回解析、别名/中文名输入保持、独立窗口、未登记/不限制跳过、错误输出和超时、同轮拒绝停止、CLI/MCP 执行前接入及权限回归。单元测试使用可控模型返回，不等同真实 LLM 识别验收。执行 mvn test、mvn package -DskipTests、frontend/npm run build；服务加载后复测真实 SQL。
