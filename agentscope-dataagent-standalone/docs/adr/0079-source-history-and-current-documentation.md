# ADR 0079：来源提交覆盖核对与当前文档事实源

日期：2026-10-15
状态：已接受，本轮仅核对与文档同步，不新增业务实现。

## 背景

用户要求核查 data-agent-multiAgent-Mdl-lyf 自 2026-10-09 起的提交是否已合入，并按当前代码完善系统文档。此前采用功能移植，来源提交并未整体进入当前 Git 历史；代码、工作区增量、来源历史文档和目标环境验收不能混为一谈。部分主文档仍有旧工具、Cube 禁用、默认 H2、多模块构建和无粘性部署的相互矛盾描述。

## 决策

1. 以固定来源 6a75b6cf、当前 HEAD bc11f3a5 及现有工作区为核对基准。逐提交保留“直接继承、功能移植、当前替代、未采用/非运行时资料”的区别，见 [提交核对表](../archive/source-branch-integration-audit-20261015.md)。文件存在或字节一致不是功能验收，历史测试不冒充本轮实测。
2. 当前技术/权限/工具事实分别以根 pom.xml、application.yml/profile、控制器和工具注册代码为准；文档统一使用 standalone Java 17 构建、当前默认 MySQL/h2 profile、官方 Wren MCP/CLI、QUERY/MANAGE 和单进程容量约束。
3. 保留当前 Spring Boot JAR 打包；不把来源 2baa6eb6 的 Assembly、tar.gz、bin 脚本与外置配置布局标成已合入。旧建模/Cube 禁用逻辑由当前 ADR 0069/0074 替代。来源预览与上线报告是历史设计/验收资料，不作为运行时入口或部署成功证据。
4. 来源 ADR/spec 保留原编号于 docs/archive/imported/multiAgent-Mdl-lyf；补入遗漏的工作台布局 ADR 0053/spec 044。当前集成决策为 ADR 0077、0078，本次核对为本文。AGENTS.md 是开发约束，docs/reference/agent-definition.md 是 Agent 装配说明，二者分别维护；架构正文为 ARCHITECTURE_zh.md。项目根 .gitignore 为根 AGENTS.md 增加单独例外，使开发指南可随代码交付，不扩展到运行态子目录提示词。

## 影响

修改 README.md、README_zh.md、ARCHITECTURE_zh.md、AGENTS.md、Agent 定义与集群说明，补充可复查的提交覆盖表和审计脚本；不覆盖既有业务/框架未提交改动。开发验证沿用上一阶段有日志的结果，本轮文档改动只检查引用、代码锚点与 diff。Docker 实测、真实 LLM、负载及生产发布恢复仍按 ADR 0078 的未验收边界记录。
