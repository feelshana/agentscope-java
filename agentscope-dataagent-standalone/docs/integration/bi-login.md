# 分析云前端免登对接

依据：ADR0081 / spec069。2026-10-16用户确认：不接BI后端，密钥由BI前端持有，自动建普通用户，管理员SQL维护，同域路径嵌入。此机制可被取得前端密钥的人伪造任何用户名，包括管理员；不是可信BI Session证明，时间、防重复、同源限制不消除此边界。

可先使用[模拟BI入口](bi-simulator.md)与真实后端独立联调，再将同一`bi-login.mjs`交给BI前端；模拟工作台不属于产品默认入口。

## 双方分工

BI：读取真实登录用户名（如liyufanwx，非中文显示名），新增图标和新标签页，在BI顶部栏下挂载iframe。DataAgent：校验凭据、匹配/创建本地用户、JWT与会话隔离。BI退出/禁用不主动撤销DataAgent JWT。

BI同事直接引入 [bi-login.mjs](bi-login.mjs)，共享密钥另行私下交付，不能将示例模块中的配置放进公开Git。生产HTTPS，同源协议/主机/端口必须完全一致；嵌入页面不可使用导致opaque origin的sandbox配置。

```javascript
import { mountDataAgent } from './bi-login.mjs';
const navigation = performance.getEntriesByType('navigation')[0];
const dispose = mountDataAgent({
  iframe: document.querySelector('#dataagent-frame'),
  username: currentBiUser.username,
  sharedKey: BI_DATAAGENT_SHARED_KEY, // 双方约定的私有Base64密钥
  basePath: '/dataagent',
  newEntry: navigation?.type !== 'reload',
  onError: message => showErrorDialog(message),
});
// BI路由组件卸载时调用dispose()；每次显式点击图标新进入传newEntry:true。
```

`newEntry:true`重新按当前BI用户换取JWT；刷新wrapper传false，仅复用本标签页相同用户且未过期的JWT，不自动续期。SPA宿主须按自身路由区分“图标进入”和“页面刷新”，不能每次子组件重渲染重新mount。用户切换后显式重新进入，iframe在新凭据校验后整页重载，清除旧用户组件/模块缓存。DataAgent无自动轮询免登、无生产密码框。

## 协议与接口

POST `/dataagent/api/auth/bi-login`（代理剥离/dataagent，应用实际路径/api/auth/bi-login），JSON `{ "ticket": "v1.IV.DATA" }`。UTF-8明文严格包含username字符串、issuedAt毫秒整数、requestId标准小写UUID；不接收角色/中文名。AES-256-GCM，密钥是Base64解码后的32字节，随机IV12字节，tag128位，无AAD；IV与ciphertext+tag分别Base64URL无padding。密钥不是明文的一部分，不与JWT密钥共用。

每次新请求重新生成IV、UUID、时间。服务端允许过去60秒、未来30秒；客户端需正确校时。元数据表唯一requestId保证原子消费，重启不失效，保留到issuedAt+120秒后每5分钟清理。用户创建与消费各自提交事务，首次用户并发冲突后重新读取已有记录；若数据库/网络在登录中途失败，重新生成ticket，不复用旧请求。此情形可能已创建普通用户但未完成登录，不给失败请求发JWT。

200：`{token,userId,username,roles,expiresAt}`，expiresAt为毫秒时间戳。JWT默认1800秒固定到期，roles来自本地。GET `/api/auth/options`公开返回passwordLoginEnabled/biLoginEnabled，不返回密钥。认证响应no-store。POST密码登录在prod返回403，未启用BI时免登返回404；ticket无效/过期/已使用返回401，参数解析失败400，数据库/服务异常5xx。前端统一显示失败提示，不打印请求密文或JWT。

`dataagent:ready`/`dataagent:login`/`dataagent:accepted`带handshakeId，双方校验同源与source窗口。JWT不进URL。DataAgent只在/bi-entry接收消息，过期页不触发续期；之后接口使用Authorization Bearer。前端sessionStorage，旧localStorage令牌不迁移。关闭页面不删除历史，不撤销已签发JWT；浏览器恢复标签页可能恢复存储，服务端仍按JWT到期时间校验。

## 配置及数据库

1. prod强制禁止密码登录和Java演示管理员初始化，并启用BI；必须配置`dataagent.auth.bi.shared-key`。新密钥可用PowerShell生成：
   `[Convert]::ToBase64String([Security.Cryptography.RandomNumberGenerator]::GetBytes(32))`（PowerShell 7）。不要把输出放进截图、Git或公共日志。
2. `dataagent.jwt.ttl-seconds: 1800`；独立`dataagent.jwt.secret`。prod关闭SQL演示初始化：`spring.sql.init.mode: never`。私有配置不打包，开发默认密码登录、BI关闭。
3. ddl-auto=validate部署前先在平台库执行 [0081 SQL](../migrations/0081-bi-login-mysql.sql)。update环境由JPA创建消费表；不会修改上传库、旧知识库或用户ID。
4. 预导入用户时保留已有user_id；username匹配沿用现有忽略大小写策略，首尾空白去除。自动用户user_id为UUID，角色user，密码为不可交付的随机值，不提供初始密码。
5. 管理员SQL见迁移文件注释。维护roles_csv时保留自定义角色；角色变化在新JWT生效。不要为了导入而覆盖已有user_id（会导致历史/授权失联）。

## 同域构建与Nginx

先设`DATAAGENT_BASE_PATH=/dataagent/`再执行常规Maven package（自动构建前端），或frontend目录npm run build。本地默认/。同一个包不能假定同时支持两个构建基础路径；修改前缀须重新构建。

```nginx
location = /dataagent { return 302 /dataagent/; }
location /dataagent/ {
    proxy_pass http://127.0.0.1:8080/;
    proxy_http_version 1.1;
    proxy_set_header Host $host;
    proxy_set_header X-Forwarded-Proto $scheme;
    proxy_buffering off;
    proxy_cache off;
    proxy_read_timeout 600s;
    proxy_send_timeout 600s;
    client_max_body_size 100m;
}
```

使用prod profile并加载外置私有配置。应用frame-options=SAMEORIGIN，BI嵌入须同源；上游不能另加DENY或禁止该来源的CSP frame-ancestors。无需对外开放8080。Java会在新HTTP请求校验JWT，到期不强行截断已经开始的SSE。入口、附件、深链刷新、流式问答需在真实BI/Nginx单独联调，不把本地协议检查当生产验收。
