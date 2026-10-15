# 平台增量合入分析

2026-10-15。状态：已完成适用增量移植和验证，变更留在当前分支工作区，未提交 Git。对应 ADR 0077。

## 基线

当前 design/wren-official-capability-boundary 为 4c96099b。本地及 fetch 后远端 data-agent-multiAgent-Mdl 均为 9f8b0462，已是当前祖先，无新增提交。用户已确认使用远端 data-agent-multiAgent-Mdl-lyf（32c6d292），包含共享权限和部分稳定性修复。

分析依据：Git、源码及来源 spec 041、go-live-gap-review-20261012.md；未访问另一账号私有问答记录。

## 核对结果

| 需求 | 来源实际情况 |
| --- | --- |
| 共享权限 | QUERY/MANAGE、授权审计、全局管理员、分页选库、持久化每轮范围已实现 |
| 上传处理 | 操作登记、取消、批量失败传播及删除重试已实现；有界队列、累计字节和全局导入预算仍列为待办 |
| 问答容量 | 会话忙错误、锁内接受范围及撤权复核已有；全局/每用户准入、有限等待和 Wren 总进程预算仍为待办 |
| 修复 | 外部源连接校验、删除重试、沙箱恢复、SSE/HTML 等需要逐项筛选 |

## 合入边界

建模、问数保留当前官方 Wren skills/CLI/MCP、原生文件工具/HITL、显式发布、中文展示、LIMIT 与问答对召回管理。不能整文件覆盖旧工具链。

共享权限需专门适配当前 WrenQueryAccess、WrenProjectAccess、WrenPublishedProject、SqlQaExampleService 等入口：QUERY 仅查询，MANAGE 管理；当前操作者与知识库创建者分离，历史/沙箱/附件仍私有。特别核对空选库、撤权后查询、点赞样例归属与外部源凭据隔离。

来源确认后按权限服务、当前 Wren 入口、前端、稳定性修复顺序集成。来源不存在的容量控制须单独列为新增开发，不能记为已迁移。来源 ADR/spec 编号存在冲突，不能覆盖当前文档或配置。

实际已完成上述移植：三方合并源码和测试后逐处处理当前官方 Wren 的访问边界，保留当前中文工具展示、LIMIT、SQL 问答对管理。新增共享查询/撤权/草稿拒绝测试及共享点赞、私有反馈测试。来源字段注入改为构造器注入；保留当前构建和运行配置。

## 验收

覆盖无权限、QUERY、MANAGE、创建者、管理员及直接 API/工具越权；覆盖撤权与排队、私有历史/附件、导入失败取消、会话繁忙。执行根 Maven test/package、前端 build 和 diff 检查；服务加载和真实并发压测单独记录。

本次结果：全量 Maven 570 项，0 失败/错误、18 条件跳过；真实官方 CLI/MCP 专项 5 项通过；最终 package、前端构建通过；SSE/HTML 5 项、工具解析 8 项、浏览器工具展示 6 项通过；隔离 exec JAR 健康、前端和 18 次 HTTP ACL 检查通过，验证进程已清理。脚本见 docs/fixtures/platform-integration；target/integration-*.log 和 target/platform-integration-service/report.json 为本机证据。现有用户服务未重启，未做真实模型效果或容量压测。
