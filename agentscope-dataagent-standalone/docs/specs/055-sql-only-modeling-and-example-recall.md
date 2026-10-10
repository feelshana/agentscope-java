# spec 055：取消 Cube 与加强问数示例召回

> 状态：代码实现与本轮验证完成（2026-10-10）。用户自行删除旧数据，本轮不迁移、不删除旧资产。

## 背景与目标

减少自由问数的调用分支，统一模型/视图 SQL 经 Wren 编译执行。修复日志暴露的时间筛选、答案顺序、旧确认提示和中文召回问题，保留可选建模问题与最终答案点赞记忆。

## 方案概述

- 移除问数/建模 Cube 工具和目录注入，写面拒绝 cubes 路径与 CUBE 问题策略，停用 Cube API；前端不展示 Cube 编辑与推荐入口。只保留历史解析类型，不做数据迁移。
- 完善提示词与模型上下文：指标公式、时间归属、单位、实体粒度、强制过滤明确；通过模型/明细视图 SQL 回答，复杂查询先规划；时间筛选与分组分开，空结果先核对 SQL，完整最终答案放在全部工具调用之后。统一发布指导为工程通过即可显式发布，可选问题不形成发布闸。
- `AnswerQueryMemory`/`WrenToolkit` 合并当前知识库的建模确认与点赞示例，优先官方向量召回；输出实际召回方式及降级原因。降级检索支持中文改写的文本匹配，忽略纯年份/数字重合，不把降级结果称为语义召回。
- 保存服务端执行来源中的原问题、业务问题、SQL 和 limit。TopN 示例保留限制含义；生成 SQL 时仍使用工具 limit 参数。用户点赞才保存，失败/诊断/未采用查询不保存；维持幂等、撤销及租户隔离。不得根据点赞反向修改模型。

## 影响面

- 后端：WrenToolkit、ModelingToolkit、模型上下文、写面校验、Cube API、AnswerQueryMemory、Wren CLI 记忆支持。
- 前端：SemanticModelingPage、建模摘要/确认卡片；示例召回工具结果继续可查看。
- 配置/依赖：官方多语言向量记忆依赖与运行状态；新增配置须同步 README、ARCHITECTURE 第 12 章。
- 文档：ARCHITECTURE 第 3/11 章、ADR 0063、spec 054 的新边界。

## 验收标准

1. Given 新建知识库，When 建模/问数，Then 无 Cube 工具、目录或操作入口；写入 Cube 与旧 Cube API 请求被明确拒绝；Wren 模型/视图 SQL 正常执行。
2. Given 年度客户数、TopN、中文改写与空结果问题，When 查询，Then 使用原始字段和正确筛选，不混淆粒度；最后展示完整最终答案。
3. Given 知识库已有正确示例，When 中文改写或换年份查询，Then 召回业务相关样例，数字相同但业务无关的样例不会仅靠数字入选；实际后端与降级可见。
4. Given Top10 SQL 调用有 limit=10，When 点赞，Then 保存示例保留前十名含义；Top5 复用必须改变参数并重新查询。
5. Given 未点赞/失败/诊断/未采用 SQL、重复点赞或撤销，Then 保存与撤销遵循 spec 054；不同租户/知识库不能串例。

## 不做与测试要求

不迁移旧数据，不新增用户已排除的治理机制，不改 AgentScope 原生循环。补充工具暴露/写面/API 退役、相关性与多查询筛选、TopN 参数和越权测试；真实 Wren 验证保存及召回，并如实记录向量依赖可用性。执行 Maven 测试/打包、Spotless 与前端构建。

## 验证记录

- `mvn -Dskip.npm=true -Dskip.installnodenpm=true spotless:apply test`：480 项测试，0 失败、0 错误、12 项跳过；包含真实本机 Wren CLI 保存、召回与撤回测试。
- `mvn package -DskipTests`：成功，包含前端 `npm run build`（TypeScript 与 Vite）和 Spotless 检查。
- `git diff --check`：通过。
- 新增覆盖：Cube 工具不注册、规范化 Cube 写路径拒绝、旧 API 410 与越权 404、文档增强不再生成或采纳 Cube、问题策略拒绝 CUBE、中文跨年份相关性、仅年份重合不召回、TopN 参数回执与保存、合并已发布/点赞示例，以及官方向量结果 nl_query/sql_query 字段适配。
- 当前本机 Wren 为 grep 后端，已实测中文词项降级召回。向量后端的索引调用与返回格式经过适配测试；未进行真实向量模型下载与语义效果测试。官方依赖在 `requirements-wren.txt`，安装和启用方式见 README。本轮未重启服务或运行浏览器端到端验证。

## 关联文档

- [ADR 0063](../adr/0063-sql-only-modeling-and-relevant-example-recall.md)、spec 054。
