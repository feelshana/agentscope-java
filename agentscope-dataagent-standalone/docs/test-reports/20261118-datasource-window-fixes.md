# 本地数据源不可选择与时间范围默认值

2026-11-18 Asia/Shanghai，codex/architecture-phase1。用户要求修复本地关联下拉选择及默认“不限制”。缺陷修复/默认值调整，无新spec/ADR，不提交/部署，全部既有改动保留。

## 原因与修复

截图选项均“已停用”。AssociateTablesModal对enabled=false设置原生option disabled，因此鼠标/键盘均跳过，并非遮罩或层级问题。只读检查开发配置的本机平台库：8个连接全部enabled=0，enabled为NOT NULL bit(1)且无SQL默认，停用审计表为空；这些连接创建于9月24日至11月2日，早于11月13日共享数据源功能引入。开发profile使用ddl-auto=update，实体只有Java enabled=true，没有DDL默认。证据与Hibernate为已有行补布尔列时填0一致；未取得当时DDL日志，不宣称已追溯到具体历史执行语句。

- 实体补ColumnDefault(true)，使新建/自动补结构与正式0146迁移的默认启用一致；不会在启动时批量修改已有停用记录。原disabled和服务端requireActive保持。
- 已修复本机已确认8条旧记录：仅本机目标、创建于功能引入前、enabled=0且无DISABLE审计；加锁复核受影响集合后恢复启用。变更前快照.runtime/datasource-enabled-repair-20261118.json只含ID/原状态/更新时间，无连接凭证；与数据更新同事务保存8条MIGRATION_ENABLE审计，actor=system:local-schema-repair，不冒充人工操作。另补现有列DEFAULT b'1'。回读8条均启用，默认b'1'，修复审计8条；未操作线上或上传数据表。
- 全部可见数据源确实停用时，界面提示原因和启用位置，不再仅留下不可选下拉。
- 原准备清单/独立弹窗代码均默认为LIMITED/31天，90天只是可选值。共享factWindowDraft统一首次默认UNLIMITED，准备文案同步；已保存限制/天数/时间列保留，仍需成功保存后才开始建模，不修改原SQL门禁或批量放开已配置表。

## 验证

- SharedDataSourceHttpTest、FactTableWindowServiceTest、FactQueryWindowPolicyTest：23项，失败/错误/跳过0。新增真实H2 DDL/仓库回归，省略enabled的旧式INSERT默认启用，显式停用仍拒绝访问。
- 前端fact-window-setup 5项通过：首次UNLIMITED、已保存90天/时间列保留、原角色及已有策略门禁。
- TypeScript/Vite构建通过并同步static，保留既有大chunk提醒。
- 浏览器隔离验证和文档检查最后补记；真实本地库仅检查/修复上述状态，没有读取或执行外部业务表查询，未执行真实Agent/Wren/全量clean verify。

本地数据源状态修复即时生效，重新打开关联弹窗即可；前端默认值刷新后可见。实体DDL预防逻辑下次IDEA启动加载；不要求重启来清空或覆盖任何既有策略。线上正式默认已有b'1'时无需执行本地修复或批量启用。

浏览器最终结果：实际组件隔离挂载（非业务页全链路），键盘选择恢复连接、原生下拉选择、显式停用仍禁用、全停用提示、独立弹窗/准备清单默认不限制、旧90天保留、启动前保存UNLIMITED（请求不含days），全部通过；JS异常/未知请求0。3张截图已查看。期间修正了夹具CJS默认导出适配与原生option须等待attached（关闭的原生option不可等待visible），不作为产品故障。

最终补记：DocsConsistencyTest 5项通过，失败/错误/跳过0；git diff --check通过。临时datasource-window浏览器及本轮5175预览已关闭，用户服务保留。
