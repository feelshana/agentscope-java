# 来源分支提交覆盖核对（2026-10-15）

来源：`origin/data-agent-multiAgent-Mdl-lyf`，已 fetch，末端 `6a75b6cf`。当前分支 `design/wren-official-capability-boundary`，HEAD `bc11f3a5`，包含现有未提交移植。核对范围从 2026-10-09 00:00 +08:00 起。

## 结论与口径

**主要平台功能已按当前架构移植；没有将来源这段历史整体 merge/cherry-pick，也不是每项内容逐字照搬。** 上传增量已提交，问答容量及配套框架代码仍在工作区。来源发布打包方案、旧领域建模实现有明确保留当前方案的差异；设计预览/来源上线报告仅作历史资料。

来源全历史在范围内共 14 个可达提交，其中主线 11 个，另 3 个是已由当前分支继承的公共历史。只查 merge-base 或提交 hash 会漏掉功能移植；只看“同名文件存在”也不能证明行为一致。

## 逐提交结果

| 日期 | 来源提交 | 核对结果 | 当前依据/差异 |
| --- | --- | --- | --- |
| 10-09 | b7f18957、a240788c | 已通过公共历史继承 | 当前 HEAD 的祖先；原建模方案后续由官方 Wren 方案演进 |
| 10-09 | 90a3d2ab | 平台功能已移植 | ExternalDataSourcePolicy、连接探测、SSE/HTML、事件隔离、会话门锁和界面；API 额外使用当前 apiFetch。另一个 service-dataplane 应用的构建 helper 不作为 standalone 交付面 |
| 10-09 | df2c553b | 历史上线审查资料保留 | 本轮补入来源 production-readiness-review；Linux 发布规划不是当前部署已验收证据 |
| 10-09 | 77c637a1 | 聊天/侧栏 UI 已移植 | ChatPanel/SessionsSidebar 与来源末版一致；静态设计预览归来源档案 |
| 10-10 | 9f8b0462 | 已通过公共历史继承 | 点赞 SQL 样例随后演进为当前官方 memory 源码、索引与召回 |
| 10-10 | a3e7a9dd | 工作台、创建者和读取边界已移植 | KnowledgeWorkspace、WorkspaceFileTree、分页/创建者/共享资产权限；本轮补齐来源布局 ADR0053/spec044 |
| 10-10 | 488de6ba | 平台修复已移植，语义流程采用当前方案 | KG 继续停用；不采用来源 Cube 禁用、旧 ModelingToolkit/旧 SQL 路由或问题必填。当前 ADR0069/0074 优先 |
| 10-10 | d20826a2 | 删除/失败/沙箱恢复已移植 | KnowledgeBaseDeletionService、Operations、重试日志、活动取消和删除权限；兼容服务不重新注册为主 Agent 工具 |
| 10-12 | 2baa6eb6 | 发布方案未采用，运维记录作历史 | 当前保留根 pom 的 npm 构建与 Boot exec JAR；没有引入 assembly.xml、src/main/bin/dataagent.sh、frontend/build.ps1 和 tar.gz 布局 |
| 10-13 | cba98f9d | QUERY/MANAGE、成员审计和身份隔离已移植 | ADR0077；当前用户运行，库 owner 只决定资源归属；排队后撤权复核，私有历史/附件/反馈 |
| 10-13 | 32c6d292 | 外部连接校验修复已适配 | MdlPublishService dry-run 显式连接文件、WrenProfileHome；不恢复来源 MDL 语义改写 |
| 10-14 | a29cc754 | 上传功能已提交到 bc11f3a5 | 大小/解析限制、Admission/Intake、前端有限调度、回滚、状态隔离；DI 与 apiFetch 采用当前实现 |
| 10-14 | 6a75b6cf | 容量及配套框架已在工作区集成 | ADR0078；额外修正官方 MCP 发现门锁、准入前鉴权；配套 harness/OpenAI 代码与新镜像必须同时发布 |

## 文件与关键行为核对

对上述主线提交涉及的 Java/TS/TSX 去重，共 201 个文件：139 个与来源末端内容一致（统一 CRLF 后比较），62 个存在差异，没有缺失整个文件的情况。这只是定位差异的机械证据，不是“201 个文件全部行为等价”的证明。

差异按当前实现逐类核对：

- DatasetService、DatasetImportService、DatasetGroupService、MdlWorkspaceService、SessionHistoryService 等保留来源权限/操作逻辑，字段注入改构造器；删除服务依赖延迟注入以免环依赖。
- DatasetGroupController 上传文档只存资料，移除来源自动 DocEnhance；MdlWorkspaceService 支持官方 Cube 和 knowledge/sql，符合当前原生建模边界。
- WrenQueryAccess/WrenProjectAccess/WrenPublishedProject 适配共享权限；WrenMcpToolkit/Registry 保留当前官方工具发现、原始调用、LIMIT 和撤权检查。
- ChatController/HarnessGateway/RunPythonTool 在当前会话范围、HITL、回执和产物实现上叠加来源容量/取消；SessionTurnParser 同时保留 toolCallId、范围快照和 JSON 解包。
- 前端 API 的主要差异为统一 apiFetch 登录处理；工具结果展示、建模页和 SQL 问答对管理使用当前分支版本。旧平台语义路由不因来源功能合并恢复。
- 源码树中的 service-dataplane 属另一个应用，存在但未按本项目迁移；不计作 standalone 功能缺失或已交付能力。

复现工具：`python docs/fixtures/platform-integration/audit_source_integration.py`，生成 `target/source-integration-audit.json`。输出记录来源/当前 HEAD、祖先关系、文件相同/不同/缺失；**仍需人工判断替代与权限行为**。后续提交/源码变化应重新运行，不能沿用这里的数量。

## ADR/spec 与系统文档

- 当前：[ADR0077](adr/0077-shared-knowledge-official-wren-integration.md)、[ADR0078](adr/0078-platform-capacity-followup-integration.md)、[ADR0079](adr/0079-source-history-and-current-documentation.md)，spec066/067。
- 来源：[档案与编号映射](imported/multiAgent-Mdl-lyf/README.md)，目前 11 份 ADR、8 份 spec；同编号文档不覆盖当前文件。
- 现行入口：[README](../README.md)、[中文 README](../README_zh.md)、[架构](../ARCHITECTURE_zh.md)、[开发约束](../AGENTS.md)、[Agent 定义](agent-definition.md)、[发布与容量](platform-concurrency-and-release.md)。项目没有单独的 architecture.md/agent.md，以这些实际文件为准。

## 验证状态

上一实施阶段实测日志：后端 613 项 0 失败/错误、18 条件跳过；harness/OpenAI 专项共 28 项，其中 2 Docker 条件跳过；官方 Wren CLI/MCP 与记忆集成 2 项；前端认证/SSE/HTML 11 项、浏览器 4 场景；构建/格式/打包通过，隔离 JAR 18 次 ACL 检查通过且进程已关闭。

本轮仅核对及更新文档，不重跑业务测试；检查引用、源码入口、配置与 diff。Docker 接口此前 500、取消实测未启用；真实 LLM 效果、多用户负载和生产发布恢复仍未验收。现有业务/框架改动未提交，本助手未重启用户服务。
