# 当前任务：本地数据源选择与事实表默认范围修复

2026-11-18 Asia/Shanghai，codex/architecture-phase1。用户要求修复本地关联数据源下拉不能选和首次时间范围默认不限制。缺陷/默认值调整，无新spec/ADR；全部既有工作保留，不提交/部署，未启停用户IDEA/Vite。前任务已归档。

## 已完成

- 选择故障不是层级：本机平台库8连接enabled=0、列无SQL默认、没有停用审计，均创建于9月24日至11月2日，早于11月13日共享源功能；开发ddl-auto=update+实体仅Java初值，与补布尔列旧行填0一致，未取得当时DDL日志。
- Entity增加ColumnDefault(true)，与0146正式迁移一致；不启动时全量启用。原前端disabled/后端requireActive保留，新增全停用原因提示。
- 已定向修复实际本机平台库8旧记录：限定本机、功能引入前、enabled=0且无DISABLE审计，行锁复核集合，变更前快照.runtime/datasource-enabled-repair-20261118.json（无凭证）；恢复状态与8条MIGRATION_ENABLE审计同事务，actor=system:local-schema-repair，不冒充用户。现有enabled列补DEFAULT b'1'，回读8启用、8审计，未动线上/上传表。
- factWindowDraft统一准备清单/独立弹窗：未配置默认UNLIMITED（原代码31天，90只是可选项），已保存LIMITED/天数/时间列保持。准备文案同步，仍保存成功才允许启动建模，不改变SQL门禁。

## 验证

- 后端SharedDataSourceHttpTest/FactTableWindowServiceTest/FactQueryWindowPolicyTest共23项全通过，无失败/错误/跳过。新增真实H2生成DDL验证旧式省略enabled默认启用，显式停用仍拒绝。
- 前端fact-window-setup 5项全通过；TypeScript/Vite构建通过、static同步；大chunk提醒保留。
- Chrome隔离挂载实际组件：键盘选中恢复连接/原生下拉选择、停用仍禁用/全停用提示、弹窗与准备默认UNLIMITED、已保存90天不覆盖、开始建模前PUT保存UNLIMITED且无days，通过。JS异常/未知请求0，3截图已查看。
- 报告docs/test-reports/20261118-datasource-window-fixes.md，日志.runtime/datasource-window-*，截图output/playwright/datasource-window-*。
- ARCH/迁移说明/共享记录已同步，最后补DocsConsistencyTest/空白检查/临时QA关闭。

## 体验与限制

重新打开关联弹窗即可读取已恢复启用的8连接，刷新前端看新默认值；实体DDL预防下次IDEA启动加载。已有策略不改，线上不执行本地恢复或批量启用；正式已有b'1'无需新增迁移。

浏览器使用合成数据源/库表及策略接口，只测试实际组件，无外部业务查询/真实Agent/Wren生成或本轮全量clean verify。平台库实际操作仅上述状态/默认值与修复审计，不读取连接凭证到输出。

## 前序保留

[卡片/侧栏检查点](archive/CURRENT_TASK-before-datasource-window-fixes-20261118.md)：四范围、真实创建人、两行日期、知识库只读侧栏/字段弹层、搜索焦点，spec144待真实体验。
spec146任务恢复/摘要过滤/PATCH，spec143历史分类、spec145队列/自检/修复/撤销/工作台UI，以及独立产品基准候选全部既有未提交工作保留。候选不是整站迁移授权；线上BI hash网址404尚未取得用户直达入口结果，不据此操作服务器。

最终补记：DocsConsistencyTest 5项通过，失败/错误/跳过0；git diff --check通过。临时datasource-window浏览器及本轮5175预览已关闭，用户服务保留。
