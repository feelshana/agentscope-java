# 问题优先建模与统一验收验证（2026-10-08）

## 最新日志分析

测试123（bd823e8b-fef8-46e4-bfce-95a0e48cd554）的两个问题是“每月有效营收的变化情况”和“客户的营收变化分析”。助手已读取模型和业务规则，但保存为 required:false、空 SQL，等待澄清时间范围、粒度及变化定义。澄清缺失口径本身合理；问题在于分级门禁允许这些需求未完成就发布，工具仍提示确认必验问题，且初始聊天与批量表单争夺入口，用户看不出下一步。

## 实现

spec 045 / ADR 0055。初始业务文档栏 + 批量问题表单，显式提交一次后展示对话；已有问题或待确认原生调用直接恢复。新增问题统一由清单表单进入，对话用于澄清。移除 API、进度、提示词和页面中的 required 分类，所有未归档问题均需 SQL、Wren 成功完整执行、有效人工确认；旧确认指纹兼容，真实模型或问题变化仍失效。问题驱动复用/构建 View/Cube，写入仍使用原生 HITL。

## 验证

- mvn test：446 项，0 失败、0 错误、12 跳过；Spotless 通过。
- mvn package -DskipTests：通过；包含前端 tsc --noEmit / Vite build。
- frontend npm run build：通过。
- 独立服务 8085，新测试知识库 76649222-bc35-4c9c-88e4-7d672b3b0b92；8080 用户服务未重启。
- 初始表单、单次 SSE 提交、隐藏聊天、恢复待确认提案、已保存问题恢复对话、API 不输出分类均通过。
- 最终包追加问题表单可批量填写，取消不保存；760px 窄屏无横向溢出，无浏览器运行异常。累计 22 项检查全部通过。
- 真实建模助手经原生 HITL 保存两份问题、create_view 创建 valid_order、patch_file 完善金额 SQL，并实际验证两题；未改用户业务工程。
- Wren 查询 2025 年有效订单金额 3749 元、笔数 9，与 ds_order.csv 按 PAID/COMPLETED、is_deleted=0、2025 年独立计算一致。两题复用同一个视图，确认依据包含完整视图 SQL。
- 向独立测试问题加入历史 required:false：两题均阻断未确认发布；确认一题仍阻断，两题均确认后 canPublish=true。没有执行发布。
- 具体浏览器检查和结果见 [browser-checks.json](question-first/browser-checks.json)、[reviews.json](question-first/reviews.json)、[approved-writes.json](question-first/approved-writes.json)。
- 初始页面见 [initial-intake.png](question-first/initial-intake.png)，最终工作台见 [confirmed-question-workbench.png](question-first/confirmed-question-workbench.png)；最终确认与阶段快照见 [final-reviews.json](question-first/final-reviews.json)、[final-workflow.json](question-first/final-workflow.json)。最终包于 16:23:26 构建通过，8085 已重新启动验证。

## 验证过程与边界

第一次验证连到残留旧监听进程；核对端口实际监听进程及其独立工程路径后替换，重新验证新接口。页面恢复预检与测试追加预检时出现一次 Windows scratch 目录占用；代码已有同组工作区锁，测试等待已有预检结束后恢复成功，没有据此修改锁或绕过预检。辅助脚本缺少 csv 导入导致计算步骤中止，补齐后恢复同一测试工程验证成功。

测试只自动批准独立测试知识库的受保护写提案及已独立核对结果的业务确认。真实用户仍需自行批准写入和结果确认。已有问题的库直接恢复对话；无问题的库首次打开为批量表单。用户 8080 后端需要重启才能使用新规则，历史未确认问题将统一纳入验收。
