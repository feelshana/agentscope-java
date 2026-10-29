# 2026-10-30 同事新增能力整合验证

## 范围与起点

在 `codex/integrate-boundary-lyf-v2` 上，将 `dfa71bef06ccdeb83dd1805ff79c8d6a2ce08f72` 合入 `122d2b7e118a97bbde6b2cfe0b1108cb2ec9ff55`，共同祖先为 `ec6cb3d807c32e329bf7970c0b911de04c164ffb`。本次未切换分支，没有推送或操作65/66服务器。

同事新增的三个提交是 `237a2bec`（SQL试问保存/确认前dry-plan及编辑器）、`9ec35a3b`（工程内业务术语、停用caveats）、`dfa71bef`（查询CSV、分析技能、可视化分层和推荐问题）。不是将整份同事文件覆盖到当前分支。

## 用户确认与合并结果

- 采用每库 `knowledge/rules/naming.md` 的术语方案，编辑进入草稿，发布后用于问数。保留旧 `dataagent_semantic_term` 表及数据，暂不迁移测试术语；0096改为无删除操作的说明。
- 接受成功表格查询自动保存不超过10MiB的会话CSV及下载链接；通过现有附件存储和按需恢复交给Python，普通SQL查询不会因此启动沙箱。
- 保留当前管理员手动模型切换、轮次快照、持久会话、单库范围、BI账号/人员目录、公共库和只读浏览、操作用户图表归属、对话及反馈样式、源码构建与容器部署能力。
- 建模/问数采用同事新增设计：SQL dry-plan、工程内术语、停用caveats、归因/异常/预测方法、参数卡和推荐问题。分析回答只记录反馈，不成为问答召回样例。
- 合并图表扩展参数时保留 `RuntimeContext`，避免新绘图路径丢失操作用户归属；SQL编辑器遵守不可编辑/保存中状态，格式化按钮也禁用。
- 补齐聊天请求重建的可选字段。进一步追踪证实原入口也从原请求向会话上下文传递分析参数，字段遗漏不等于分析按钮完全失效；新增实际流式入口的参数确认及下一轮清空回归。
- 术语列表在取得工作区锁后重新校验QUERY及当前角色；分析参数尚未确认时Python与官方查询均拒绝执行。
- ADR0119/0120/0121、spec100/101/102/103为同事新增资料的唯一编号，原有BI、模型、容器及源码构建文档编号不变。生成的前端资源由合并源码重新构建。

## 本次实测

| 验证 | 结果与边界 |
| --- | --- |
| Java完整回归 `mvn -o test` | 993项，0失败、0错误、32跳过；使用Java17/Maven3.9.9，前端插件跳过，前端另行实测 |
| 最终重点复核 | 图表归属HTTP5项、CSV附件3项、文档一致性5项，共13项通过；图表HTTP用扩展参数的新入口，验证跨用户访问边界 |
| 前端 `npm test` | 根路径、`/dataagent/`、`/dify/dataagent/` 各53/53；覆盖分析建议阈值、最后成功结果、CSV附注及只读SQL编辑器 |
| `npm run build` | TypeScript及Vite构建通过；保留既有大bundle/混合导入警告，未在本轮做拆包 |
| `mvn spotless:check`、`mvn package -DskipTests` | 通过；仅本地测试构建，非66已升级 |
| 分析脚本实际执行 | 在既有 `agentscope/dataagent-sandbox:20261028` 镜像的断网、1CPU/1GiB、只读根容器内运行：归因变化-3548/贡献0.4566、去重指标变化-100且不加和、缺总计拒绝、5个预期异常日期、短历史拒绝、7点预测及区间包含关系、重复执行一致性均通过 |
| MySQL8.0.46 | 隔离容器内分别执行新版完整DDL及旧DDL+0097。各37表，组表字段结构相同，推荐问题两字段和public_query均符合预期，旧术语测试行保留；无宿主端口，验证后只停止本次自建容器，保留数据 |

32项条件跳过包括真实Wren/大模型、特定MySQL集成、Docker故障注入及市场/种子测试；其中4项 `AnalysisScriptsTest` 因宿主Python无matplotlib跳过，已用上表中的真实分析沙箱执行补验。不能把脚本数值检查说成真实LLM选参数、生成SQL或全流程问答准确率已经验证。

证据保存在Git忽略的 `output/branch-review-20261030/`：`final-tests.log`、`integration-final.log`、`frontend-tests-final.log`、`frontend-build-final.log`、`package-final.log`、`analysis-docker.log`、`mysql-schema.log`及脚本结果。这里不记录测试密码、人员名单或模型密钥。

## 升级与尚未验证

已有环境须在升级新镜像前补充 `dataagent_dataset_group.recommended_question` 和 `recommended_question_manual`，详见[迁移指引](../migrations/README.md)。空库使用更新后的完整DDL，不再叠加ALTER。保留旧术语表，不能执行旧版本0096中的DROP。

66当前仍是此前用户截图Healthy的演练版本，本次没有执行其SQL、拉源码、构建或重启；65 Nginx仍等待有权限人员核对挂载/配置/重载。正式两库、BI同源浏览器入口、管理员与真实模型、真实建模问答及故障恢复仍需现场验收。

既有共享boundedElastic并发阻塞问题不在本次改动中；本轮结果不能证明10–15并发稳定或回答正确率达标。新增方法建议、模型生成SQL和最终报告仍需后续真实问答体验验证。

## 既有修改保护

起点六份未提交部署记录已保存原始字节。CURRENT_TASK和SESSION_LOG与合并记录整合，旧检查点/早期日志保存在 `docs/ai/archive/`；另外四份部署文档保持原始字节及未提交状态。个人交接、私有配置、真实人员SQL和output均不加入Git。
