# 2026-10-19 SQL 时间门禁复核

旧日志不是新实现的验收证据。LLM.log 的“最近一年的营收”总耗时66.76秒、21次模型/24次工具，16次run_sql加dry_run/dry_plan共18次拒绝。recall_queries和describe_model各约29.67秒且有重叠；累计模型28.66秒。不能把重叠工具时间相加，也不能将总耗时归因为数据库扫描。

旧实现按全库最小窗口和字段字符串正则检查，别名条件被误报缺日期。建模CLI取库级默认、MCP取表级汇总，行为不同。当前已替换成独立数据库配置+共用LLM检查器，需重载服务复测，不以历史日志宣称通过。

本机安装版源码核对：D:/workspace/wren-probe/.venv/Lib/site-packages/wren/mcp_server.py 中 run_sql 文档明确只接受MDL逻辑模型；engine.py 中 WrenEngine.query 先dry_plan再connector.query(dialect_sql, limit)，dry_run走connector.dry_run。connector/base.py只有query/dry_run抽象方法，没有平台注册的执行前钩子。CLI query和Cube均使用WrenEngine，不存在公开共用的物理SQL MCP工具。要在物理执行点拦截须修改/包装Python运行时并传递知识库上下文，同时解决物理名与登记逻辑名映射，本次不实施该方案，也不修改安装包。

真实LLM表识别准确率、误拒绝率和新增门禁耗时待服务加载后验证。mock测试仅验证协议和执行边界。

## 本次代码验证结果

mvn test：673项，0失败、0错误、18条件跳过；mvn package -DskipTests成功，包含npm run build；mvn spotless:check成功。日志分别是target/spec083-tests-workspace-temp.log、target/spec083-package.log、target/spec083-spotless.log。Java验证进程使用JAVA_TOOL_OPTIONS将java.io.tmpdir放到工作区target/test-temp，解决默认临时目录AccessDenied；不改全局/生产配置。首轮失败及后续中止重跑不算通过。新增mock初始化错误已修复。代码未加载到用户服务，未改线上表配置。
