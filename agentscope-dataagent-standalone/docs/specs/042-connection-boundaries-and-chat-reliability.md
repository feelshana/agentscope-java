# spec 042：连接安全、聊天收尾与查询优化

对应 ADR 0051。范围：审查第 1、3、4、5 项。

## 配置与兼容

```yaml
dataagent:
  external-datasource:
    allowed-endpoints: ${DATAAGENT_EXTERNAL_DATASOURCE_ALLOWED_ENDPOINTS:}
```

默认空表示不限制目标地址，用户可以直接新增外部数据源，不必为每个地址修改配置或重启服务。若管理员选择限制目标，示例值为 `mysql.internal:3306,postgres.internal:5432`；此时主机名称必须与配置匹配，`localhost` 不等同于 `127.0.0.1`；IPv6 使用 `[::1]:3306`，端口也是限制条件。

若保存数据源仍返回地址限制的 403，检查运行中的后端进程是否设置了非空的 `DATAAGENT_EXTERNAL_DATASOURCE_ALLOWED_ENDPOINTS`。取消该限制可清空环境变量；继续使用限制则需使配置与页面填写的主机和端口完全一致。修改配置后重启后端。此错误发生在 JDBC 连接之前，与数据库用户名、密码或网络连通性无关。不要把个人外部数据库地址硬编码进共享的 `application.yml`。

数据源创建/修改保留 name、kind、username、password、sampling；新增 host、port、database、sslMode。host 模式与 jdbcUrl 模式互斥。新页面使用结构化模式；老调用者的 jdbcUrl 按同一策略校验。数据库类型与协议一致，仅单主机。未知参数拒绝，不能依靠前端限制。

MySQL 可兼容 useSSL/requireSSL/verifyServerCertificate/useUnicode/allowPublicKeyRetrieval、sslMode、characterEncoding、serverTimezone；高风险参数拒绝。内部超时、禁用本地导入等保护参数只接受策略固定值。PostgreSQL 只允许限定 sslmode 及固定超时参数。当前内网自助接入页面新建数据源默认不加密，可根据目标数据库能力选择加密模式；跨网络区域或敏感数据应优先使用加密并校验身份。编辑已有数据源保留其已显式设置的加密模式。

数据源表单提供“测试连接”操作：使用当前输入参数发起只读 JDBC 试连，返回成功或具体错误，不持久化数据源。编辑时密码留空则试连沿用当前用户已保存的密码。表单字段变更后清除上次试连结果；只有当前参数试连成功才能保存。试连成功不保证后续连接始终可用。

接口为 `POST /api/datasources/test-connection`，请求体同创建/修改，可选查询参数 `sourceId`；指定时先校验归属，无论是否填写新密码。返回 `{connected, error}`；此接口不修改既有数据。只读连接与 `isValid` 表示可连接，不保证账号具备所有目标表的读取权限。

MySQL 的 `sslMode` 不能与旧版 SSL 参数同时出现，避免加密要求互相冲突。

## 自动化验收

| 场景 | 预期 |
| --- | --- |
| H2/SQLite 等协议、不匹配 kind、多主机、嵌入凭证 | 连接之前拒绝 |
| 未配置允许列表时使用新主机/端口 | 目标地址校验通过，无须管理员登记 |
| 已配置允许列表时使用其他主机/端口 | 连接之前拒绝 |
| 驱动 socketFactory、文件导入、反序列化、URL 用户参数、编码参数绕过、重复参数 | 拒绝，不交给驱动 |
| 结构化 MySQL/PostgreSQL、IPv6、旧安全参数 | 生成规范 URL，可重复校验，固定超时 |
| 图谱字段含 `<img>`、引号、`&` | 显示为文本，不形成 HTML 元素 |
| SSE 分块中文、CRLF、多行 data、末尾无空行 | 正确解析 |
| 没有终止帧的 EOF、损坏 JSON | 明确提示断流或格式错误 |
| 退出 SSE 消费 | reader 取消并释放锁 |
| 自有、直接共享、全站共享、未授权、已删除 owner、撤权 | 数据库查询结果符合既有 ACL |
| 同一会话的两个 requestId | 只收到属于当前请求的工具事件 |
| gateway 索引条目过期、其他用户查询 | 不返回会话 |
| gateway 繁忙 | 返回失败，不完成为空回答 |
| 未保存参数试连、编辑密码留空、他人 sourceId | 返回具体试连结果且不落库；仅复用本人已存密码；他人凭证拒绝 |

## 手工联调

1. 不配置目标列表，创建实际外部数据源、查看连接状态、浏览 schema/table、关联和预览；若启用允许列表，再验证平台拒绝未允许的目标。
2. 普通对话执行中点击停止，可以再次发送，界面显示中断；切换会话不污染另一会话状态。
3. 在模型、SQL、Python 执行阶段分别断开网络/停止，检查计算何时结束、会话锁和资源是否释放。
4. 两个窗口对同一会话发送，确认工具事件不串流；刷新后历史与最终执行结果一致。
5. 长表格、图谱、建模确认仍能正常展示；使用真实 MySQL/PostgreSQL 做性能抽样。

本轮自动化结果以交付说明及测试报告为准；没有连接生产数据库、启动 Docker 场景或做浏览器端到端验收。提交前运行正式前端构建，将与源码一致的输出提交至 `src/main/resources/static`；正式 Maven 打包也会重新构建前端资源。

## 2026-10-09 提交前验收记录

- `mvn -q spotless:apply` 通过，模块测试过程中 `spotless:check` 通过。
- `mvn -q test -Dskip.npm=true -Dskip.installnodenpm=true`：56 个测试类，共 430 项，418 通过、12 跳过、0 失败、0 错误。跳过项不计为通过；报告位于构建目录 `target/surefire-reports`。
- `npm run build`：TypeScript 检查与 Vite 正式构建通过；前端主包约 4.23 MB，构建有体积警告，列为后续按需加载优化。
- `node --test tests/stream-and-html.test.mjs`：5 项通过，覆盖分块 UTF-8、终止帧、断流、取消及 HTML 转义。
- 模块 `mvn package`（复用已完成的测试与前端构建）通过，生成可执行 `agentscope-dataagent-2.0.3-SNAPSHOT-exec.jar`；本机打包成功不等于已在 Linux 完成部署验收。
- 补充未保存数据源试连、编辑密码复用与他人凭证拒绝的回归测试；修正旧沙箱测试对立即创建容器的假设及附件失败提示的过时断言，未改变 Wren 查询核心逻辑。
- 真实数据库、Docker 重建/回收、BI 身份和浏览器端到端场景仍待环境验收；上述结果不代表生产联调完成。
