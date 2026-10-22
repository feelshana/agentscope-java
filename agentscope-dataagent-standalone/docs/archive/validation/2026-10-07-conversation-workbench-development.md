# 对话主工作区与多问题建模开发验证
日期：2026-10-07；关联 ADR 0050、spec 041。

## 实现
- 建模对话为主区域，右侧显示实际保存的问题；取消四个快捷任务按钮，上传合并为对话附件入口。
- 多问题弹窗按行录入并预填同一聊天框，保留已有输入，不自动发送或落盘；实际写入继续经过受保护 YAML 与原生 HITL。
- 问题卡片显示待补充口径/待完善模型/待验证/待确认/确认或过期等状态，详细口径折叠；可通过对话调整口径、设置必验或归档移除。
- 阶段统计所有问题，只有必验问题阻断发布；批量验收执行所有完整待验问题，确认仍逐项人工进行。
- 固定查看模型入口保留模型、派生模型、Cube、视图、术语规则、MDL 工作区；草稿图与草稿/发布工程文件分别标注，返回不卸载聊天。
- 业务文档提案放到术语与规则详情，已发布问数版本与草稿变更分开显示。

## 验证
本仓库是 standalone Maven 工程，无上级 reactor，执行等价命令：
- mvn test：439 tests，0 failures，0 errors，12 skipped；含混合必验/可选进度、可选验收不阻断发布、问题归档及现有租户隔离用例。
- mvn spotless:apply 后测试/打包中的 spotless:check 通过。
- npm run build：TypeScript 与 Vite 构建通过。
- mvn package -DskipTests：打包通过。
- Edge/CDP 对隔离的 localhost:8085 服务完成 11 项浏览器检查，结果见 conversation-workbench/browser-checks.json。

浏览器覆盖三个预设问题的弹窗录入、只预填不自动写入、取消、全部资产入口、草稿/发布快照切换、返回保留输入、主次布局、窄屏排列与运行时异常检查。该轮未新增三问题的真实 LLM/HITL 保存及 Wren 执行实验；多问题状态与发布资格由后端用例覆盖，原有真实单问题 Wren 验证记录见引导式建模报告。不能将浏览器预填检查称为真实三问题端到端验收。

## 产物
- conversation-workbench/conversation-main.png
- conversation-workbench/batch-question-dialog.png
- conversation-workbench/model-detail.png
- conversation-workbench/narrow-screen.png
- conversation-workbench/browser-checks.json
- 构建日志在 conversation-workbench/；后端测试日志在 wren-gap/development-test.log。

## 运行范围
只更新开发验证用的 8085 隔离服务，启动时复制构建 JAR 到独立目录，保留已有测试数据；未重启用户 8080 服务。页面上的工程文件是 YAML 事实源及发布快照，MDL 图是当前草稿结构投影，不冒充编译 JSON。
