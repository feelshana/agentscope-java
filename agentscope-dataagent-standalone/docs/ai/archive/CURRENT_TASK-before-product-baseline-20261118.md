# 当前任务：看板生成恢复与体验缺陷修复

2026-11-18 Asia/Shanghai，codex/architecture-phase1，基线590d3e4e。用户批准任务恢复，并要求处理内部假用户消息、紧凑卡片信息、描述保存403。spec146/ADR0151，本地实现与隔离验证完成，待重启IDEA体验；不提交/推送/部署，未启停用户服务。spec143/144/145全部既有未提交工作保留，旧CURRENT已归档。

## 本轮已完成

- DashboardGenerationService/Controller由服务端持有原ChatController.stream订阅，浏览器轮询GET；请求ID/期望修订幂等，同看板单活跃任务。原生执行/选库/RUN/容量/超时/工具权限保留，不另造Agent循环；离开页面不取消，显式stop才取消。
- generation.json及按用户哈希的各自最近终态原子写入资产目录，回复有界；其他用户不可看任务消息，后续另一管理者生成不覆盖原用户恢复结果。重启遗留任务标INTERRUPTED、不自动运行；活动时阻止删除/恢复/发布/检查点，删除包含任务文件，不复活已删目录。
- 手动任务保存原检查点；自动子任务关联parentRunId，最多一次且继承检查点。恢复旧完成任务不启动修复，失败/取消/中断明确显示。
- SessionTurnParser按框架保留名/ID过滤compaction摘要，历史每进程首读重索引未变源、清旧内部投影；分页和前端再过滤。真实用户相同英文保留；原生上下文文件不改。
- 卡片KB/创建归属/更新/创建时间紧凑信息带，宽屏一排、长名省略、窄屏换行；继续使用owned，不伪造创建人姓名/旧日期。
- 描述403已定位旧IDEA的CORS缺PATCH：同来源GET预检200/PATCH403。只补允许方法PATCH，未放宽来源/授权。

## 本轮验证

- python scripts/check.py backend --unit：1118项，失败/错误0，跳过1（Windows符号链接条件）。最终Spotless通过。
- 任务服务/控制器/CORS/Parser/资产/H2历史HTTP/JPA/架构选测47项，失败/错误0，同一跳过1。H2回归验证旧缓存摘要清理、统计/标题修复且源不变。
- 前端5个相关文件24项在三个部署前缀各通过，TypeScript/Vite构建通过、静态资源同步；保留既有大chunk提示。
- Chrome隔离任务API/SQL交互：刷新/离开重进同ID、不重复提交；各终态/停止/摘要隐藏/描述保存/桌面单行/390px通过；一次自动修复parent与原检查点继承、完成刷新不重跑、列表窄屏通过。意外API/JS异常0，4截图已查看。
- 验证日志.runtime/dashboard-generation-*，截图output/playwright/dashboard-generation-*，正式报告docs/test-reports/20261118-dashboard-generation-recovery.md。
- 文档与共享记录同步后补跑DocsConsistencyTest和git diff --check；结果见下方最终补记。

## 用户体验与限制

需要用户自行重启IDEA加载新generation接口与PATCH跨域修复，再刷新前端。发送后刷新/切页回来检查任务状态，显式停止、失败重试和描述保存；无需SQL迁移。未操作66/线上，未提交。

本轮未调用真实模型/Wren/知识库完整生成，浏览器用隔离任务/查询响应，不能作为真实全链路或发布门禁。单进程执行，不承诺跨实例调度或服务重启续跑；重启如实中断。旧页面内容仍为单HTML/整页预览，运行自检不证明业务口径正确。

## 前序保留

spec143：管理历史问数/建模/仪表盘/全部分类、统一统计/长源码折叠与同排筛选。
spec144：库名搜索选择器/名称快捷填充/描述/必填、元数据/排序/筛选/卡片列表/查看顶栏及权限/删除。
spec145：FIFO查询、面板运行约定/独立重试/目标诊断、最多一次修复/检查点撤销；紧凑顶栏、独立助手图标/真实进度、六快捷方向、Markdown/技术折叠、滚动跟随、面包屑、消息身份、chart选中态。

以前1109后端和真实模型单次工具契约生成等证据在旧报告/归档，本轮结果不能替代本分支完整clean verify或线上验收。连续会话保留；未来新轮创作/多人版本冲突治理仍待另议。

最终补记：DocsConsistencyTest 5项通过，失败/错误/跳过0；git diff --check通过。临时dashboard-generation QA浏览器已关闭，用户IDEA/Vite保留。
