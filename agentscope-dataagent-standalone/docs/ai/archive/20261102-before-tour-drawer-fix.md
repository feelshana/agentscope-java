# 当前任务：仅放开MySQL 5.7接入，已实测（2026-11-02 Asia/Shanghai）

## 用户授权与起点
- 用户明确只改数据源连接检测/保存，其他Wren、提示词和业务逻辑不变，并要求真实创建知识库、建模、发布、问答验证。
- 起点develop-2.0@0e9f25ee，保留前轮提案文档，未切分支、提交、push或部署66。
- 后续用户授权移回个人分支：liyufan_dev已从0fbc0fae快进同步主线至0e9f25ee并切换，全部未提交修改完整保留；未新增提交或push。
- 完整AST兼容方案未采纳，本轮按spec107缩小范围；ADR0123仅取代ADR0113的连接最低版本。
- 原主线合并任务归档archive/20261102-before-mysql57-compatibility.md，66升级版本未获现场回传。

## 已完成代码
- DataSourceIntrospector允许5.7、8.0+和Doris，5.6/5.5、未知版本仍拒绝。
- JDBC连接、认证、isValid、只读、URL/权限检查保持；Controller只改注释。
- 更新版本/API测试、ARCHITECTURE §5.3、ADR/spec和索引。
- 无Agent提示、Wren兼容资源、前端源码/静态包、平台schema或配置修改。

## 本轮验证事实
- 相关Java/文档32项通过，格式检查通过，package成功；使用skip.npm/skip.installnodenpm保留前端资源。
- Docker此前未运行，本轮启动Desktop后建立专属mysql:5.7.44，绑定127.0.0.1，无现有DB挂载。
- 独立真实应用、随机端口、H2平台库/工作区/MDL，真实配置模型+官方Wren 0.15.0，经HTTP/SSE调用；未做浏览器UI验收。
- 合成customers/orders，3客户/6订单，原生SQL参考6笔/599元；reader57仅SELECT。
- 连接、保存/更新、元数据浏览、关联/采样/预览、字段确认、草稿初始化、基础建模结构验证和发布v1通过。
- 基础建模18.34秒、validate1.615秒、publish1.579秒；2模型/0关系（候选关系待确认，未擅自采纳）。
- 实际问答8.89秒失败；简单COUNT/SUM经官方MCP run_sql两次均1064，无结果。
- 对照：物理聚合成功；常量WITH及实际发布manifest的官方strict计划均在5.7报1064，确认WITH不兼容。
- 回答没有编造数字，但误把问题归因为orders关键字；本次未扩展修复。
- 报告[真实验收](../test-reports/20261102-mysql57-connection-admission.md)，脱敏JSON随报告；私有状态/原日志在target/mysql57-acceptance-20261102，不入Git。

## 结论与下一步
- 最小改动已完成，5.7可接入；不能宣称5.7问数已支持，用户决定后续是否做执行适配。
- 实测5.7.44，不是用户生产5.7.28；未重做8.0/Doris真实业务验收，版本门禁回归通过。
- 不追加转换器、不动生产库、不自动发布本地修改；收尾检查和本轮进程停止状态见SESSION_LOG。
