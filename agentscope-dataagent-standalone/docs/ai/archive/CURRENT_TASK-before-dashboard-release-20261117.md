# 当前任务：看板输出截断与执行取消修复

2026-11-17 Asia/Shanghai；codex/architecture-phase1。用户明确授权加诊断、复现及修复。缺陷修复沿用spec141/ADR0149，不新增spec/ADR；本地修复及验收完成，未提交/推送/合并/部署66。旧CURRENT已归档，前一轮界面/权限/删除改动及全部既有文件保留。

## 根因与修复

- 使用当前服务真实SSE探针，默认8192输出tokens时finish_reason=length、长HTML参数未结束；显式16384时9023tokens正常闭合。Java原始分片回放完整，确认截断→JSON异常被吞→空参数校验→反复生成的因果链。
- 仅dashboard-agent申请16384；保留固定模型服务/连接快照。core ReasoningContext识别length工具输出立即终止，正常完成时验证完整JSON，避免空参数继续执行；用户中断的部分上下文保存仍可用。
- core ReActAgent/ToolCallsAccumulator与ExecutionTimingMiddleware补model.output/model.tool_arguments计数及预算；不记录提示词、参数内容或凭证。
- ChatCapacity截止publisher先正常终止取消源，再向HTTP报超时；工具事件截止同样关闭订阅。原生模型订阅取消测试由失败转通过，Python实际结束前保留预算规则不变。
- DashboardWorkbench消费tool_result，失败不再称为页面已更新；无需改变GenBI官方技能、权限、查询或发布机制。

## 本轮验证

- core专项22项通过，覆盖length只调用模型1次/写工具0次、千余分片完整HTML、残缺JSON与部分中断；core/harness/OpenAI扩展及依赖install成功。
- 最终backend --unit 1078项，失败0/错误0/条件跳过1（Windows不能创建symlink）；此前新增计时null输入回归修正后已全单元重跑通过。
- 前端dashboards.test.mjs三个前缀各8项及TypeScript/Vite构建通过，既有ECharts大包提示保留；最后文档门禁见SESSION_LOG补记。未跑完整发布clean verify。
- 两张真实临时看板：82966/72241ms，各仅1次write_file、无空参数或写错误；HTML44849/42450字符，官方verify与查询200。写入模型输出14180/13817tokens，参数47016/44760字符，日志预算16384。
- 第二张浏览器首次4Canvas/6查询200；第一张HTML自身ID/并发问题另经原生对话14476ms修改后4Canvas/7查询200。错误/警告范围与初次差异见报告，不把verify当浏览器保证。截图已view_image核对。
- 两个临时看板已DELETE204/读取404/目录无残留；已有样板a26cddc8及用户新看板1b2187c2元数据摘要未变。bfb24d84在本轮清理前已不存在，不是本轮删除。
- 详细证据docs/test-reports/20261117-dashboard-output-limit-fix.md，先前诊断记录保留。隔离探针/回放/QA日志在.runtime/dashboard-arguments，verification-server.log含敏感启动输出勿整贴。

## 当前服务与下一步

- 用户IDEA服务未停止或接管；用户需停止并重新运行DataAgentApp以加载core/framework修改，不能把磁盘编译完成说成旧JVM已更新。
- 临时18081验证服务（独立sandbox.instance-id）与QA浏览器已关闭；5173前端仍保留。验证服务单独端口/实例锁，未操作用户容器。
- 下一步用户重启IDEA后试用；发布前再完整验证。本轮无代码阻塞，不自动提交或部署。
