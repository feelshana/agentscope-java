# 常用问题驱动建模：开发与本机验证记录

日期：2026-10-07。对应 [ADR 0048](../adr/0048-question-driven-modeling-and-verified-examples.md) 与 [spec 039](../specs/039-question-driven-modeling.md)。

## 本次实现

核心流程为：补充常问问题 → 对话澄清指标/粒度/单位/时间/过滤 → HITL 保存问题与模型 → 草稿 Wren 实际查询 → 人员检查 SQL 和结果 → 确认必验问题 → 发布 → 问数按需召回确认示例并重新查询。

- 新增工作区问题文件、平台执行凭据、问题/模型指纹和过期检测；失败、截断、过期或伪造凭据不能确认。
- 草稿验证使用独立临时 Wren MCP 实例，不覆盖已发布实例；执行成功是 EXECUTED，人员确认才是 CONFIRMED。
- 发布检查 required 问题；失败保留上一份 published 快照。没有问题的知识库保持原流程。
- 确认示例使用官方 knowledge/sql frontmatter（nl/sql/source）；平台保护此目录，发布仅携带当前有效的确认示例。
- 前端新增“常用问题与验收”标签页，显示定义、SQL、实际表格、状态，支持验证、确认、退回和带入对话。
- 问数新增 wren_recall_examples，只读取作用域内的发布示例，并要求重新通过 Wren 查询；工具响应包含已发布 MDL 版本。
- View 描述支持定义与零行字段探索；提示词强调营收口径澄清和时间粒度一致。
- 修复 relationships: [] 第一次写入及后续关系覆盖、非法旧 YAML 吞错覆写、平台文件预览与实际写权限不一致、编码前导零丢失。
- 更新架构第 3/4/5/11 章和 README，保持 YAML-first、Wren-only 和现有 HITL/沙箱机制。

## 自动验证

本仓库为 standalone POM，使用对应的本地命令，不带不存在的 reactor -pl 路径。

| 命令 | 结果 |
|---|---|
| mvn spotless:apply / test 中 spotless:check | 通过 |
| mvn test | 426 条，0 失败，0 错误，12 条按原条件跳过 |
| mvn package -DskipTests | 通过，生成可运行 exec.jar；包含前端构建 |
| cd frontend && npm run build | 通过 |
| git diff --check -- . | 通过 |

JUnit 覆盖验证与人员确认分离、成功/失败/截断、模型与问题修改导致失效、拒绝后示例删除、过期发布门、无问题兼容、草稿执行不发布、已发布示例读取、多租户与组范围、平台路径规范化、关系批量保留和前导零。

全量测试首次暴露两条旧沙箱用例仍期望立即创建容器，与现有 LazySandbox 实现不符。用例改为验证接入上下文不创建容器、首次操作才创建、同用户同 agent 复用容器；未改动沙箱生命周期代码。

## 本机真实闭环

验证服务运行在 **http://localhost:8085**，使用独立 H2 元数据、工作区与 MDL 目录；未操作原 8080 进程。仅新增独立测试知识库和随机物理表，不修改原业务数据。收尾端口检查显示 8085 正常监听，8080 当前无监听；新版未替换到 8080。

测试知识库：question-acceptance-c8494bc8，ID f8b8ddbe-7b0f-4e41-8f4e-48e33dac3ada，owner 为测试账户 bob。使用已有 ds_order.csv 的 12 行样本。

本次测试问题明确指定独立口径：2025 自然年、order_time、status IN ('PAID', 'COMPLETED')、is_deleted=0、amount_cents/100、单位元、不扣退款。此口径与此前整套业务文档的其他问题不混用。

| 验证项 | 实际结果 |
|---|---|
| 建模对话读取真实模型后提出问题文件 | 成功 |
| write_file 经预览及 HITL 确认 | 成功，仅允许指定测试问题路径 |
| validate_modeling_question 草稿 Wren 查询 | EXECUTED，1 行，3749.0 元 |
| 独立 Python 读取 CSV 计算同一口径 | 3749.0 元，与 Wren 一致 |
| 未人员确认时发布 | 被拒绝 |
| HTTP 响应 columns/rows 表格结构 | 修复后正确 |
| 伪造 validationId | HTTP 409 |
| 当前执行凭据人员确认 | CONFIRMED |
| 确认后发布 | PUBLISHED，版本 2 |
| alice 读取 bob 的问题 | 被拒绝（403/404） |
| ./wren_project.yml 与 questions/../sql 预览 | 被拒绝 |
| 官方 wren.memory.markdown.load_query_pairs 读取发布示例 | 通过，1 个 nl/sql/source 示例 |
| 问数真实工具调用顺序 | wren_recall_examples → wren_describe_model → wren_run_sql |
| 问数实际执行与回答 | 3749 元，与草稿及 CSV 一致 |

实测发现 Spring Boot HTTP 序列化器与旧 Jackson JsonNode 类型不兼容，会把结果输出为节点属性而非 columns/rows。改为普通 Map/List 跨边界返回，新增同时经过旧存储序列化与 Spring HTTP 序列化的回归测试，重建并在真实接口复验。

证据位于 [question-acceptance/checks.json](wren-gap/question-acceptance/checks.json)、[query-check.json](wren-gap/question-acceptance/query-check.json)、[official-parser-check.json](wren-gap/question-acceptance/official-parser-check.json) 和同目录执行/SSE 记录。复现脚本位于 [question_acceptance_e2e.py](wren-gap/question_acceptance_e2e.py)，只应在独立验证服务使用；确认动作仅针对上述测试文件和测试查询结果。

## 边界与后续优先项

本次完成问题驱动建模及可信示例闭环，未宣称任意自然语言问数都已准确。426 条测试含 12 条跳过；前端进行了类型检查与生产构建，未进行浏览器自动交互测试。

此前 12 个业务问数案例和 Python 沙箱验证见 [本机基线报告](2026-10-06-local-e2e-verification.md) 与 [沙箱报告](2026-10-07-sandbox-e2e-verification.md)。本次没有重跑全部基线做同模型对照，不能把一个新闭环案例算作总体准确率提升。此前并发分析偶发的“无活跃沙箱”问题仍需框架运行时证据，未凭猜测修改。

下一阶段优先考虑：让已确认问题支持固定测试数据的自动回归及 MDL 版本对比；细化中文语义召回与问题覆盖率；形成发布前受影响问题清单；调查并发 Python 会话绑定；在真实浏览器验证确认卡片和切换知识库交互。现有检索为轻量文本匹配，未启用向量索引。SQL 安全规则对语句中的风险关键字采取保守拒绝。

业务确认绑定模型和问题，不冻结源数据；数据变化时问数仍应实时查询。新发布不携带历史未确认 SQL 示例，需要迁移为问题后重新验收。
