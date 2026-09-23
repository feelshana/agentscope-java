# ADR 0012: 安全修复与稳定性增强

- 状态：已采纳（从 data-agent-merge 合并）
- 日期：2026-09-21
- 来源：commit 71447b61

## 背景

安全审计发现多项问题：SQL 注入风险、JWT 硬编码密钥、线程泄漏、中文输出不稳定等。

## 决策

1. **SQL 注入修复**：`DataSourceIntrospector`、`DatasetService` 使用参数化查询
2. **JWT 密钥外部化**：`JwtService` 从配置读取密钥，移除硬编码
3. **线程泄漏修复**：`SessionAgentManager` 正确关闭线程池
4. **中文输出强制**：`ChartBuilder`、`RunPythonTool` 强制中文字体配置
5. **图片显示修复**：`EChartsBlock.tsx`、`ToolInspector.tsx` 修复图片渲染
6. **系统提示词重写**：`DEFAULT_AGENT_SYS_PROMPT` 替换为新版本——显式需求优先（不主动扩展维度）、引用 sql-analysis 技能流程、新增 count 一致性规则（明细行数必须与计数匹配）、移除 matplotlib 示例代码、`@Value` 改为引用常量
7. **工具描述增强**：`prepare_data_context` 增加「字段描述已含枚举与取值提示，据此直接写 WHERE」说明；`query_structured_data` 增加反探查规则（禁止跨步 IN 复制、JOIN 前去重防扇出、禁止无意义探查查询）

## 理由与权衡

- 安全性：消除注入风险和密钥泄露
- 稳定性：防止线程泄漏导致 OOM
- 用户体验：中文输出一致，图片正常显示
- 提示词质量：减少无效工具调用和幻觉数据，count 一致性规则消除计数矛盾

## 影响面

- 后端：14 个 Java 文件（DataSourceIntrospector、DatasetService、JwtService、DataAgentConfig、DataAgentToolkit 等）
- 前端：4 个 TSX 文件（ChatPanel、EChartsBlock、ToolInspector 等）
- 配置：`application.yml` 新增 JWT 密钥配置项
