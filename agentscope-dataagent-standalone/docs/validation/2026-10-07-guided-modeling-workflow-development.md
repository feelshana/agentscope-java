# 引导式语义建模开发与本机验证

日期：2026-10-07
关联：[ADR 0049](../adr/0049-guided-modeling-workflow.md)、[spec 040](../specs/040-guided-modeling-workflow.md)。继承 ADR 0048 / spec 039 的问题验收和已确认示例链。

## 实施结果

主流程改为 **数据准备 → 对话建模 → 验证与确认 → 发布 → 问数**。上传或关联数据后，基础模型发布成功即开放问数；文档可选，用户不必先填满一批问题。普通问数使用发布快照，草稿验证使用独立 Wren 实例。

- 工作台四步骤导航与持续阶段卡。首次打开根据后端阶段定位待处理页面；保留用户明确指定的资产 URL。模型、关系、派生模型、Cube、视图、规则和 MDL 可视化进入模型详情。
- 同一问题记录在对话阶段用于需求、在验证阶段用于验收。推荐问题默认可选，升级必验需要用户认可；SQL 由助手负责。批量动作先工程校验，再验证待验问题，成功执行仍要求人员审阅。
- 后端快照由租户数据集、工作区/发布差异、问题凭据、工程凭据派生。工程收据位于保护的 `.platform/workflow/engineering.json`；模型变化使收据失效。发布重新执行原检查，收据不能绕过发布、HITL 或权限。
- 发布页先展示业务资产变更、必验确认情况和阻断原因，技术差异折叠。无待发布变更或有阻断时按钮禁用。
- 对话快捷入口、欢迎文案与助手剧本对齐流程。已有资料中的口径不重复询问；强制过滤要求落可执行资产；一次写完整需求，减少空 SQL 再补写的重复确认。问数提示限制擅自增加环比统计条件，空结果先核查。
- HITL 先展示业务变更原因，并区分草稿变更确认与实际结果确认；预检未通过、正在预检或编辑后未重验时不能采用。

没有数据库迁移、新配置、新 UI 库或新的物理 SQL 回退。README 与架构文档已同步。

## 构建与自动化检查

仓库是独立 Maven 模块，使用根 pom 的等价验证命令：

| 检查 | 结果 |
|---|---|
| `mvn spotless:apply` / 构建内 `spotless:check` | 通过 |
| `mvn test` | **436 项，0 failures，0 errors，12 skipped** |
| `mvn package -DskipTests` | 通过，生成 exec JAR |
| `cd frontend && npm run build` | 通过，TypeScript 与 Vite |
| `git diff --check` | 通过 |
| 验证脚本 `python -m py_compile` | 通过 |

新增 `ModelingWorkflowServiceTest` 9 个用例覆盖基础发布、无数据/失败初始化、同一问题流转、工程不代替业务确认、截断结果、模型变化后收据失效、可选问题、删除摘要和租户隔离；`ModelingToolkitTest` 新增状态与工程校验共享下一步的用例。

12 项跳过保留了项目原有的环境条件；不能视作实际执行。Vite 仍有既有的大 bundle 提示，本轮没有拆包。

## 真实本机 API / LLM / Wren / 浏览器验证

验证服务：`http://127.0.0.1:8085`，独立 H2 与 MDL 目录，未替换 8080 服务。真实 Wren 查询连接本机测试数据源。使用已有测试账号 bob / alice，操作仅针对新建测试知识库。

知识库：`2938f8c8-6fee-44aa-a700-6aa8c9769c98`
名称：`guided-workflow-43b1c84c`
数据：[12 行订单 CSV](wren-gap/ds_order.csv)。最终已发布 **v3**。

| 环节 | 实际结果与证据 |
|---|---|
| 空知识库 | DATA_PREPARATION / PREPARE_DATA，queryAvailable=false；[empty.json](wren-gap/guided-workflow/empty.json) |
| 上传后基础发布 | v1，COMPLETE，queryAvailable=true，0 个问题；[base.json](wren-gap/guided-workflow/base.json) |
| 跨租户访问 | alice 读取 bob 工作流被拒绝；[state.json](wren-gap/guided-workflow/state.json) |
| 对话登记问题 | 真实建模 LLM 使用 write_file、预检及原生 HITL；**一次确认**写出完整 definition + SQL，不写空 SQL 再补写；[model.log](wren-gap/guided-workflow/model.log) |
| 保存需求 | VALIDATION，草稿未发布，仍可使用已发布 v1；[requirement_saved.json](wren-gap/guided-workflow/requirement_saved.json) |
| 工程通过 | 仍不可发布，不能代替业务确认；[engineering_pass.json](wren-gap/guided-workflow/engineering_pass.json) |
| 真实草稿查询 | 2025 年 PAID/COMPLETED、is_deleted=0、amount_cents/100，返回 **3749 元**，与独立 CSV 求和一致 |
| 查询成功待确认 | CONFIRMATION / CONFIRM，canPublish=false；[awaiting_confirmation.json](wren-gap/guided-workflow/awaiting_confirmation.json) |
| 人员确认与发布 | headless Edge 点击真实按钮，先确认结果，再发布 v2；未确认时发布禁用且 API 发布被阻断 |
| 模型改变 | 仅对隔离测试工作区增加指纹变化，工程状态 NOT_CHECKED、问题 STALE、VALIDATION；已发布 v2 仍可问数；[expired.json](wren-gap/guided-workflow/expired.json) |
| 过期草稿时问数 | 真实 data-agent 调用 recall → describe → run_sql，明确使用 **已发布 v2**，返回 3749 元并生成 CSV；[query-checks.json](wren-gap/guided-workflow/query-checks.json)、[完整 SSE](wren-gap/guided-workflow/query.json) |
| 恢复验收与再次发布 | 最新页面自动进入当前验证步骤，浏览器点击批量验证、人员确认和发布，最终 v3 / COMPLETE；[published.json](wren-gap/guided-workflow/published.json) |
| 页面交互 | 14 项布尔检查通过：阶段导航、自动定位、批量重验、文案对齐、同一问题、发布禁用、差异折叠、实际确认/发布、旧 MDL URL、问数携带 groups、无运行时异常；[browser-checks.json](wren-gap/guided-workflow/browser-checks.json) |

浏览器使用本机 headless Edge 的 CDP，没有安装前端测试库。截图：

- [待确认实际结果](wren-gap/guided-workflow/awaiting-result.png)
- [发布前摘要](wren-gap/guided-workflow/ready-to-publish.png)
- [最终已发布页面](wren-gap/guided-workflow/published-settled.png)

可在验证服务打开 [工作台](http://localhost:8085/configure/modeling/2938f8c8-6fee-44aa-a700-6aa8c9769c98) 查看。

## 验证边界与过程修正

模型变化的失效检查直接修改了本次隔离测试工作区，用于验证指纹行为；真实对话写入的单次 HITL 流程另行实际执行，不能把直接 fixture 修改当作用户流程。

重新打包时曾覆盖正在运行的验证 JAR，引起类加载错误，随后改为从构建固定副本启动验证服务。恢复后重新执行并通过问数和最新页面流程。第一次浏览器断言也因早于问题异步加载而失败，已改为等待实际数据；最终记录均来自成功重跑。

这些测试证明本次流程、发布闸和一个真实业务问题闭环。不能据此宣称通用 Text2SQL 准确率、所有业务过滤都被确定性实现，或已经完成与 WrenAI 官方的全面差距基准。本轮没有重新执行 Python 数据分析全链；其原有验证见 [问题驱动建模报告](2026-10-07-question-driven-modeling-development.md)。

## 重现脚本

- `start_acceptance_service.py`：固定构建副本上的独立服务。
- `guided_workflow_e2e.py`：prepare / model / verify / published / expire / query。
- `guided_browser_check.py`：真实页面验证、确认与发布；`--snapshot` 检查最终静态状态。
- `verify_guided_query.py`：校验实际问数 SSE 与 CSV oracle。
- `start_guided_check.py` / `start_guided_browser_check.py`：后台执行并保留日志。

脚本与记录位于 `docs/validation/wren-gap/`；密钥仅在客户端内存中，不写入测试记录。
