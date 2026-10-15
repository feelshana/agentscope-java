# 平台增量验证

来源覆盖核对：`python docs/fixtures/platform-integration/audit_source_integration.py` 只读取 Git/工作区，生成 target/source-integration-audit.json，记录 10 月 9 日以来的来源提交及文件状态。该结果不自动判定行为等价，人工结论见 [逐提交核对表](../../source-branch-integration-audit-20261015.md)。

在 standalone 项目根目录执行。`isolated_acl_smoke.py` 启动已打包 exec JAR，使用临时端口、内存 H2、独立 user.home/工作区/MDL/附件目录和独立 sandbox instance-id；结束时关闭该实例。不会连接现有应用的元数据库，也不会调用模型或查询业务表。

```powershell
mvn '-Dskip.npm=true' '-Dskip.installnodenpm=true' test
mvn '-Dskip.npm=true' '-Dskip.installnodenpm=true' package '-DskipTests'
python docs/fixtures/platform-integration/isolated_acl_smoke.py
node --test frontend/tests/stream-and-html.test.mjs
```

先在 frontend 执行 npm run build。可用环境变量 WREN_TEST_EXECUTABLE 指定官方 CLI。隔离实例显式启用本地测试管理员与 H2 测试账户，固定测试 JWT 密钥仅传入隔离实例，不作为部署配置。

HTTP 验证涵盖创建者、全局管理员、无授权、QUERY、MANAGE、成员管理、SQL 问答对管理拒绝、撤权及列表过滤；报告位于 target/platform-integration-service/report.json，不写入登录令牌。

真实 CLI/MCP、示例召回与点赞闭环另由 OfficialWrenCliIntegrationTest 和 WrenQueryMemoryIntegrationTest 验证，需设置 Maven 属性 dataagent.test.wren。它们不替代真实模型业务效果或容量压测。

登录失效回归：先构建 frontend，然后运行 `node --test frontend/tests/auth-session.test.mjs frontend/tests/stream-and-html.test.mjs` 和 `python docs/fixtures/platform-integration/auth_session_browser.py`。浏览器脚本需要 Playwright 与 Edge，使用本地静态服务器和模拟 API，不访问真实服务或账号；覆盖缓存令牌失效、请求中途 401、网络故障及未登录，报告写入 `target/auth-session-browser/report.json`。
