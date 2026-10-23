# 平台增量验证

来源覆盖核对：`python docs/fixtures/platform-integration/audit_source_integration.py` 只读取 Git/工作区，生成 target/source-integration-audit.json，记录 10 月 9 日以来的来源提交及文件状态。该结果不自动判定行为等价，人工结论见 [逐提交核对表](../../source-branch-integration-audit-20261015.md)。

在 standalone 项目根目录执行。`isolated_acl_smoke.py` 启动已打包 exec JAR，使用临时端口、内存 H2、独立 user.home/工作区/MDL/附件目录和独立 sandbox instance-id；结束时关闭该实例。不会连接现有应用的元数据库，也不会调用模型或查询业务表。

```powershell
mvn test
mvn package '-DskipTests'
python docs/fixtures/platform-integration/isolated_acl_smoke.py
# 在 frontend 目录执行
npm test
```

先在 frontend 执行 npm run build。可用环境变量 WREN_TEST_EXECUTABLE 指定官方 CLI。隔离实例显式启用本地测试管理员与 H2 测试账户，固定测试 JWT 密钥仅传入隔离实例，不作为部署配置。

HTTP 验证涵盖创建者、全局管理员、无授权、QUERY、MANAGE、成员管理、SQL 问答对管理拒绝、撤权及列表过滤；报告位于 target/platform-integration-service/report.json，不写入登录令牌。

真实 CLI/MCP、示例召回与点赞闭环另由 OfficialWrenCliIntegrationTest 和 WrenQueryMemoryIntegrationTest 验证，需设置 Maven 属性 dataagent.test.wren。它们不替代真实模型业务效果或容量压测。

登录失效回归：先在 frontend 目录运行 `npm test`、`npm run build`，再回到 standalone 根目录执行 `python docs/fixtures/platform-integration/auth_session_browser.py`。Node 测试必须经 npm 入口编译真实 TS，不能直接用 node 执行源测试文件。浏览器脚本需要 Playwright 与 Edge，使用本地静态服务器和模拟 API，不访问真实服务或账号；通过当前 sessionStorage 令牌和 auth/options 协议覆盖缓存令牌失效、请求中途 401、认证网络故障、登录配置加载失败不显示密码表单及未登录，共五个场景，报告写入 `target/auth-session-browser/report.json`。默认 Maven 已包含 npm test 与前端构建，`-DskipTests` 只跳过 Java 测试。

采样界面回归：构建 frontend 后运行 `python docs/fixtures/platform-integration/sampling_browser.py`（需要 Playwright 与 Edge）。模拟外部表未知行数、刷新成功、刷新失败保留上次样本，检查页面无 JS 异常，报告和截图位于 `target/sampling-browser/`。该脚本不访问真实数据库，也不证明 LLM 遵循探查规则。
