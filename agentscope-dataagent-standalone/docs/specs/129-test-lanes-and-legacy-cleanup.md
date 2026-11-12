# spec129：测试分层、慢上下文收窄与旧链路清理

## 背景与授权

用户认可[全量测试盘点](../test-reports/20261112-test-value-and-development-impact.md)并要求继续，沿用codex/architecture-phase1和5479d152基线，保留阶段1全部修改。本轮先落实日常与完整验证分层，再清理已确认的无调用链路，不按测试数量追求删除。

## 方案与边界

- 新增跨平台检查入口：后端可选类或排除integration/external标签，跳过前端目标；前端可选文件/部署前缀；完整检查保持clean verify。Maven原有默认构建/打包命令不变，CI仍执行全量，指定错误测试不得假绿。
- 为13个真实上下文/持久化类标记integration。H2种子与贡献测试收窄为真实JPA/文件集成，保留JpaPersistenceIntegrationTest的完整应用启动；不减少原12项业务断言。
- 清理5类无生产装配/调用的旧测试及对应代码：RefSqlMaterializer、KnowledgeEvidence/evidenceFor、WrenToolkit/WrenMcpToolkit。有效CSV边界用例保留到当前QueryDataFiles，旧协议文字检查按当前原生MCP覆盖处理。同步移除无引用TenantTablesFixture，迁移3个条件集成类到当前WrenScopedMcpClient；知识图谱2类及历史数据/条件开关本轮保留。
- 将可独立运行的15项Python逻辑/日志检查纳入CI。更新开发验证规则，任务收尾按实际变更选测，合并/发布前保持完整检查；测试HOME隔离仅作用于测试入口，不改应用启动配置。

## 验收

1. 后端选测/单元入口不执行npm目标，单元入口不运行integration类；拼错类/方法或前端文件/前缀返回失败。
2. 前端选测只运行指定文件/前缀；无参数npm test仍为三个前缀各完整测试，CI未过滤后端integration。
3. 三个保留类原12项实际通过，H2确认真实file URL，贡献仍经实际JPA事务与文件落地；完整应用启动仍通过。
4. 清理后无生产引用/默认配置残留，无新DDL、生产配置或有效API变化；当前MCP/查询数据文件/权限回归与完整检查通过。
5. 报告记录本次耗时、选测/全量范围和21项真实环境条件跳过；不把清理后的总数减少记作覆盖提升。

不自动提交/push、合并、改远端分支保护或部署。不新增业务边界/ADR；本轮不把所有其他核心类一起重构，也不删除知识图谱历史数据。IDE完整JUnit仍须独立测试HOME或委托Maven。

## 当前验收状态

本地实现与干净Java17 clean verify通过：后端1077项（21环境条件跳过）、前端三前缀各105项，Python15项及报告回归6项通过。完整逐类耗时、范围与限制见[验证报告](../test-reports/20261112-test-lanes-and-cleanup.md)。尚未提交/push或触发首次GitHub CI，故暂留当前规格区。
