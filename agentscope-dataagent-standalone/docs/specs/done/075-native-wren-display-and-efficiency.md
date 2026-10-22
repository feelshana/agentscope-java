# spec 075：原生Wren展示与调用效率

## 目标与方案

修复原生工具回执被误报截断，统一中文结果展示；移除冲突建模步骤；补齐查询分段耗时，恢复无选库普通聊天。依据当前日志与ADR0087，不修改官方skills和业务SQL。

## 影响面

前端ToolInspector/ToolCallBlock/wrenToolPresentation；后端DataAgentConfig、WrenScopedToolkitMiddleware、WrenInstanceRegistry及OfficialWrenToolkit；同步架构及共享记录，无数据库迁移。

## 验收

1. 原生run_sql正常回执、双重JSON、JSON后附件说明均正常显示；SQL转义保持；不能仅因解析失败声称截断。
2. 官方truncated=true及真实错误继续可见；普通工具的非JSON原文不丢失。
3. 基础建模提示不再要求Cube创建/查询或自动聚合探测；官方技能不改。
4. 空选库正常进入next，选库调用保持身份隔离、结束清理；测试真实Reactor空完成行为。
5. MCP日志区分等待/实例准备/执行时长及结果大小，不包含业务行值；不承诺未经真实重测的加速比例。

## 验证

前端解析回归、后端middleware/提示测试及mvn test/package、npm run build。真实LLM性能复测另记，不把编译通过当业务效果验收。
