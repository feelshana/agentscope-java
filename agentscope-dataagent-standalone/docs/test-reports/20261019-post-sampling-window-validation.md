# 采样后事实表时间配置验证（2026-10-19）

实现依据：ADR0098/spec084。独立数据库配置不等于取消采样后的确认步骤。

- 后端全量：682项，0失败0错误18条件跳过，target/spec084-tests-final.log。
- 前端node：fact-window-setup、wren-presentation共8项通过。覆盖含日期维度不弹窗、新增事实暂停、已有配置复用、UNKNOWN行内确认、旧多候选时间字段需要选择。
- mvn spotless:check package -DskipTests通过，包含tsc与vite构建，最终进程退出0，target/spec084-package-final.log。
- 后端新增覆盖：现有schema模型调用返回角色（不增加调用）、未知回退、维度过滤、独立角色保存、确认维度移除旧窗口、跨知识库拒绝、MANAGE锁后撤权、建模上下文只含当前授权库窗口。
- JUnit使用进程级JAVA_TOOL_OPTIONS将临时目录放在target/test-temp，无生产/全局环境修改。初轮单个新测试mock重置遗漏已修复；不将失败日志当通过。

本轮未重启服务、未改线上配置或模型、未提交Git。没有真实LLM角色分类准确率或浏览器交互验收证据；需要加载新exec.jar后复测“事实+含注册时间维度”的混合关联、取消/保存失败、确认后自动建模、已配不重复与新增事实补配。旧缺角色摘要行内人工确认，不自动重采样。当前范围沿用外部关联表，不扩展Excel/CSV。
