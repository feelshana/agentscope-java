# spec 006: 安全修复与稳定性增强

## 背景与目标

修复安全审计发现的 SQL 注入、JWT 密钥硬编码、线程泄漏等问题，强制中文输出，修复图片显示。

## 方案概述

1. 所有 SQL 查询改用参数化查询
2. JWT 密钥从 `application.yml` 读取
3. `SessionAgentManager` 正确管理线程池生命周期
4. `ChartBuilder`、`RunPythonTool` 配置中文字体
5. 前端修复图片渲染逻辑
6. `DEFAULT_AGENT_SYS_PROMPT` 重写：显式需求优先、引用 sql-analysis 技能、count 一致性规则、移除 matplotlib 示例
7. `DataAgentToolkit` 工具描述增强：`prepare_data_context` 增加取值提示说明；`query_structured_data` 增加反探查规则（禁止跨步 IN、JOIN 前去重、禁止无意义探查）

## 影响面

- 后端：14 个 Java 文件（含 DataAgentConfig、DataAgentToolkit）
- 前端：4 个 TSX 文件
- 配置：`application.yml` 新增 `dataagent.jwt.secret` 配置项

## 验收标准

1. Given SQL 查询包含用户输入，When 执行查询，Then 无注入风险
2. Given JWT 密钥配置在 yml 中，When 应用启动，Then 使用配置的密钥
3. Given 长时间运行，When 检查线程数，Then 无泄漏
4. Given 生成图表，When 包含中文，Then 中文正常显示
5. Given 系统提示词，When 检查内容，Then 包含 sql-analysis 引用且不含 matplotlib 示例
6. Given 工具描述，When 检查 query_structured_data，Then 包含反探查规则和 JOIN 去重说明

## 不做的事

- 不引入外部安全扫描工具（后续 CI 集成）

## 测试要求

- SQL 注入防护测试用例
- JWT 密钥外部化测试
- 线程池关闭测试
- `DataAgentConfigTest`：DEFAULT_AGENT_SYS_PROMPT 内容断言（不含 matplotlib、含 sql-analysis、含 count 一致性）
- `DataAgentToolkitTest`：工具描述文本匹配（取值提示、反探查规则）

## 关联

- ADR 0012
