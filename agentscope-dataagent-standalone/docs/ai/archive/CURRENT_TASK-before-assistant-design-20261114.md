# 当前任务：建模准备与字段整理 p1 设计落地

2026-11-14 Asia/Shanghai；codex/architecture-phase1。用户要求先提交既有修改，再按semantic-modeling-20261113-p1.html改造。既有修改已提交115418d9；当前UI改造保持未提交，不push/合并/部署。

## 范围与实现

- 用户指定的四屏作为已批准方案，spec139记录验收；只复用现有接口与授权，无新数据库/后端接口/依赖/ADR。
- 聚焦准备态52px步骤头；独立表导航/字段卡，全部/待确认/技术筛选，密集行与说明编辑态；未纳入字段也可写说明。
- setup说明失焦confirm=false保存；显式确认纳入选择，切表/退出防丢失，保存失败保留输入并阻止前进；adjust仍按原保存/引用核查/重新建模边界。
- 全表确认自动进准备清单；内联外部事实表时间范围（未知角色须确认），默认31天在开始前保存；上传/维度/无日期不出现时间区块；多文档沿用原CRUD，准备时不抢跑建模。
- 一个“开始自动建模”入口接回原工作台与purpose=auto请求；保留现有导航图标、MANAGE/QUERY分流、发布与问数边界。逐步引导定位点保留，时间步骤文案同步。

## 验证进度

- 最终TypeScript/Vite构建通过，静态产物已更新；仅原有chunk/ECharts导入警告。
- 3个相关前端测试文件，3种部署前缀各14项通过。
- FieldScreeningServiceTest、FactTableWindowServiceTest、DocsConsistencyTest合计20项通过（初次文档测试因spec索引缺失失败，补索引后重跑通过）。
- Playwright真实React页面+显式接口夹具：筛选、排除字段说明保存、锁定/关联提醒、失败保留与阻断、连续编辑不被旧响应覆盖、切表放弃保护、最后确认自动进准备通过。
- 文档上传/删除/失败重试、不抢跑；外部范围内联修改、失败阻断、未知角色确认、维度省略、默认先保存再恰好1个auto请求通过。QUERY即使带start参数也不进入编辑准备。
- 实际引导驱动默认范围确认→开始高亮通过；策略复查延迟750ms助手挂载1次、卸载0次。未启动真实后端/LLM，不冒充完整建模验收。
- 1360/800/390宽度无整页横向溢出；view_image查看参考/最终截图，核对头部/列宽/字体/圆角阴影/编辑态；局部深色复看通过，不新增全局主题入口。

## 环境与待完成

用户5173 Vite（PID44676）保留，本任务未启动8080/IDEA后端。隔离Chrome会话modeling-p1仅接口夹具，不写用户数据；该Chrome已关闭，临时QA脚本/截图与本任务console日志已清理。

实现与本地UI验证完成，ARCHITECTURE/spec/报告及共享记忆已更新，见../test-reports/20261114-modeling-setup-p1.md。新修改未提交；用户启动IDEA后可新建知识库上传/关联新表→去建模→整理字段→准备→开始自动建模，复核真实后端结果。既有库仍按原入口使用。最终DocsConsistencyTest与diff检查通过；不代替完整clean verify或生产验收。指定p1 HTML哈希核对未变。

此前能力实测与HTML报告/派生数值缺口仍待用户选择，不在本任务自动修复，见archive/CURRENT_TASK-before-modeling-p1-20261114.md及../test-reports/20261114-product-capability-evaluation.md。
