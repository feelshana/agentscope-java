# 当前任务：仪表盘卡片权限与来源信息体验

2026-11-18 Asia/Shanghai，codex/architecture-phase1。用户按两张截图要求四项范围、权限标签/真实创建人/两行日期、知识库侧栏入口与搜索去内框；沿用spec144补验收，无新ADR/DDL。当前本地实现及隔离验证完成，待体验；未提交/推送/部署，未启停用户IDEA/原Vite。产品基准候选任务已归档且仍待用户确认，不是全产品迁移授权；全部既有改动保留。

## 本轮完成

- 全部/我创建的/可管理/可使用；管理/使用按canPublish对应知识库MANAGE区分，创建者仅QUERY仍可编辑自己的看板，不改变canEdit。状态旁显示权限标签，移除原共享/可编辑串。
- 左列知识库/创建人，右列更新与创建日期上下排列；长来源名省略可查看完整名，旧创建日期不伪造，卡片/列表/窄屏保持。
- DashboardService.View新增内部JsonIgnore creatorId；DashboardController仅对已授权列表批量查UserEntity显示名，回退账号/已移除用户，平铺JSON只增加creatorName、不泄漏ID/凭证。元数据保存保留列表显示名。
- DashboardKnowledgeDrawer按点击懒加载既有KnowledgeDataPanel，复用QUERY/只读说明/数据集/样例/字段，不新增数据接口。原生右侧dialog焦点约束、遮罩/Escape关闭与焦点返回；DatasetFieldInfo在所属dialog内挂载/定位，保留普通页面挂body。
- 搜索去掉input内层紫框/阴影，保留外层圆角焦点；未修改全局主题或其他页面搜索框。

## 本轮验证

- 后端全unit1119项，失败/错误0，Windows符号链接条件跳过1；含新增平铺HTTP/批量显示名/敏感字段排除/RUN先行拒绝测试，Spotless通过。
- 前端3个相关文件13项全通过（根前缀），TypeScript/Vite构建/正式static同步；既有大chunk提醒保留。未重复全前缀套件或clean verify。
- Chrome隔离API：4范围/创建者仅使用仍可编辑/搜索焦点/按需读库/字段弹层/Escape/焦点恢复/权限错误/元数据保留创建人/卡片列表/390px通过；意外请求/JS错误/非预期写入0，4截图已查看。
- 报告docs/test-reports/20261118-dashboard-library-polish.md；日志.runtime/dashboard-library-polish-*，截图output/playwright/dashboard-library-polish-*。共享记录后补文档/空白门禁与临时QA关闭。

## 体验与限制

刷新前端即可看新布局；重启IDEA才能加载真实创建人列表投影，旧服务显示创建人暂不可用，无SQL迁移。知识库侧栏浏览现有资料、问数仍使用已发布语义模型；不更改发布/生成/查询规则。

本轮浏览器用合成资产/资料/样例/PATCH，不访问用户真实业务资料；未调用真实LLM/Wren生成、线上接口或完整发布门禁。不提交/推送/部署；用户IDEA及已有Vite保留。

## 前序保留与待验

- [产品基准候选检查点](archive/CURRENT_TASK-before-dashboard-library-polish-20261118.md)：独立React样板/可携带review.html，用户尚未确认整站风格或批量迁移；5186预览按原状态保留。
- [spec146](../specs/146-dashboard-generation-recovery.md)：服务器任务恢复、内部摘要过滤、PATCH跨域修复；需IDEA重启后真实生成体验，单进程/重启中断边界保留。
- spec143管理历史助手分类/统一统计，spec144创建表单/元数据/筛选/视图/查看顶栏，spec145查询队列/面板自检/有界修复/检查点撤销/工作台UI，全部未提交工作保留。历史报告不作为本轮线上验证证据。

最终补记：最终构建/静态资源同步通过；DocsConsistencyTest、JPA集成及DashboardControllerTest共15项通过，失败/错误/跳过0，空白检查通过。临时dashboard-library-polish QA浏览器及本轮5175 Vite已关闭，用户服务/原预览保留。
