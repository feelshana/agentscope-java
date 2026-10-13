# ADR 0072：Wren MySQL 限行兼容与工具发现复用

- 状态：Accepted（用户要求优先修复 LIMIT，再减少冗余调用）
- 日期：2026-10-12

## 背景

安装版 wrenai 0.15.0 的 `wren.connector.mysql._apply_limit` 直接追加 LIMIT。建模 CLI query 与 MCP run_sql 都会把执行行数传到该函数，因此原 SQL 已有 LIMIT 时产生 MySQL 1064。MCP 的 N+1 截断探测使 limit=50 实际追加 LIMIT 51。Cube 自带限行且传给连接器的 limit 为 None，不应改动。

## 决策

1. 在平台启动的 Wren 子进程内加载版本限定兼容补丁；不修改安装目录、官方 skill、MDL、模型提交的 SQL 或保存的示例。CLI/MCP 共用同一入口。
2. 仅对仍表现为直接追加 LIMIT 的 0.15.0 MySQL/Doris 连接器修复。使用 Wren 已依赖的 SQLGlot 解析最外层 LIMIT，仅替换行数字面量为 min(SQL 行数, 连接器行数)，保留原文其余部分、OFFSET、排序、内层 LIMIT、CTE/UNION。无最外层 LIMIT 时沿用官方追加。拒绝无法可靠定位的表达式，不用正则删 LIMIT、不取消执行上限、不套 SELECT 子查询（避免重复列名错误）。
3. 补丁资源随应用打包，临时部署到平台生成的独立目录，通过仅对子进程设置的 PYTHONPATH/sitecustomize 启用。升级 Wren 时重验并移除此临时补丁；非目标版本不启用。
4. 官方工具 schema 按 MCP 实例缓存，发布失效、进程退出或重建自然清除；工具调用仍每次鉴权。业务结果、召回结果和 schema 内容不做跨实例缓存。
5. 在通用上下文中提醒 Agent 复用当前会话同知识库/发布版本已取得的官方技能、工具 schema 和元数据；问题相关上下文不足再读取。成功结果足够回答时不追加无关诊断，保留错误恢复和必要核验。不固定调用次数、不实现领域路由。
6. 核对 Harness 原生 FilesystemTool 后纠正 ADR 0071 实现中的提示：现有文件使用 edit_file，write_file 参数为 path/content，不存在 files_json。多个独立文件用同轮多个原生调用及现有批次授权；目录和文件未变化时复用当前上下文，减少无效工具尝试与重复读取。

## 验证与边界

覆盖原有 LIMIT 大小比较、0、OFFSET/逗号分页、内层限制、CTE、UNION、字符串/注释、重复列名；实际 CLI/MCP 验证分页与截断。缓存验证实例隔离、失效及错误重试。只改变兼容与调用开销，不代表语义召回已启用或 P0–P7 已验收。
