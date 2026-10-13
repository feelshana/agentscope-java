# 061：Wren LIMIT 修复与调用精简

关联：ADR 0072；在 ADR 0069 官方执行边界内修复。

## 目标

建模 query 和问数 run_sql 接受合法带 LIMIT/OFFSET 的逻辑 SQL，不因执行限行重复追加而失败；保留 Wren 编译、权限、N+1 截断行为和答案来源。减少重复工具发现与上下文读取。

## 实施

- ProcessWrenCli 与 WrenInstanceRegistry 的子进程环境共同加载随应用打包的 0.15.0 MySQL 限行兼容资源；安装目录无修改。
- 连接器执行前只收紧 SQL 最外层行数字面量，SQL 原文其它部分不改动。SQLGlot 的 token 位置无法可靠定位时明确报错，不猜测改写。
- WrenInstanceRegistry 的 Instance 持有不可变官方工具列表；成功发现后复用，失败不缓存，新实例重新发现。原有按知识库串行和每次租户鉴权保留。
- WrenQueryContextMiddleware 注入通用复用原则，含知识库/版本/上下文失效条件；不缓存业务结果、不取消官方 recall 或按问题检索。
- 建模执行节奏移除已退役 patch_file 和原生工具不存在的 files_json 提示；write_file 使用 path/content，多个独立文件同轮发起多个原生调用。复用上下文已有且未变化的目录与文件内容，避免反复发现和无效工具尝试。

## 验收

1. LIMIT 3 配执行限行 50 返回最多3条；LIMIT 50 配执行限行3遵循官方截断协议。
2. LIMIT 0、LIMIT N OFFSET M、LIMIT M,N、CTE、UNION、内层 LIMIT、注释及重复列名通过；非目标连接器/版本不改写。
3. 同实例多次发现只发生一次真实 listTools，发布失效后重新发现；不同知识库隔离，失败可重试。
4. 定向与全量后端测试通过，构建包含兼容资源；真实安装版 CLI/MCP 查询验证。LLM 冗余调用下降属于后续在线日志观察项，不能由提示词测试替代。
