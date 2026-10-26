# 模拟 BI 入口：本地与云端体验

依据：[spec075](../archive/imported-boundary-lyf/specs/075-bi-simulator.md)、[ADR0086](../archive/imported-boundary-lyf/adr/0086-static-bi-simulator.md)。模拟用户名的来源，其余使用真实免登API、MySQL、JWT及DataAgent界面。接口加密协议及BI正式接入继续以[bi-login.md](bi-login.md)为准。

## 页面与身份

工作台`/bi-sim/`选择用户，点击“数据分析智能体”打开新页。宿主保持顶部栏，iframe进入`/dataagent/bi-entry`，复用`bi-login.mjs`交换JWT后进入会话页。用户名、ticket、JWT不进入URL；DataAgent会话ID等普通业务导航参数仍按现有界面使用。

- 用户列表的label只作说明，不赋予管理员或知识库权限。首次进入自动创建普通用户；管理员用平台库SQL设置后重新进入才能获得新角色。
- 同一宿主页切换用户会重新免登、替换iframe并丢弃旧用户前端缓存。不同宿主页各自保持进入时的用户；刷新保留该标签页身份和未过期凭据，不延长有效期。
- 过期后点击“重新进入”或回工作台再点击图标，生成新凭据。关闭页面不会删除历史或撤销JWT。模拟工作台不是DataAgent密码登录页，prod保持关闭密码登录。

## 本地运行

需要Node18+以及可用的实际DataAgent后端。复制`bi-simulator.config.example.json`为**外置私有**`bi-simulator.config.json`，填写与后端`dataagent.auth.bi.shared-key`一致的32字节Base64密钥。basePath、用户名列表可调整；本地默认`/dataagent`。

已按子路径打包的后端可直接使用：

```powershell
node docs/integration/bi-simulator-server.mjs --config "D:/private/bi-simulator.config.json" --backend http://127.0.0.1:18084 --port 18766
```

若本地JAR是根路径构建，可独立构建真实前端到联调输出目录，并由同一入口提供静态资源，API仍直接代理真实后端：

```powershell
# 在frontend目录执行。直接调用Vite，避免不同npm/PowerShell版本吞掉--outDir。
$env:DATAAGENT_BASE_PATH='/dataagent/'
node node_modules/typescript/bin/tsc --noEmit
node node_modules/vite/bin/vite.js build --outDir "D:/private/bi-simulator/frontend"
Remove-Item Env:DATAAGENT_BASE_PATH

# 回到standalone模块目录。
node docs/integration/bi-simulator-server.mjs --config "D:/private/bi-simulator.config.json" --backend http://127.0.0.1:18084 --port 18766 --frontend "D:/private/bi-simulator/frontend"
```

打开`http://localhost:18766/bi-sim/`。本地服务只监听127.0.0.1，后端也仅允许loopback HTTP地址；不打印凭据，不模拟认证接口，不缓冲SSE。云端部署不依赖该Node服务。若开发配置未启用BI或后端未启动，页面会明确失败，不回退密码登录。

## 云端部署准备

本地联调及独立云端模拟入口已按专项记录走通；真实BI、正式HTTPS和目标容量仍待验收。以下步骤用于复现部署，执行前按实际地址、数据库和目录确认。应用部署步骤共用[single-host-release.md](../single-host-release.md)，不要维护另一套启停方式。

1. 以`DATAAGENT_BASE_PATH=/dataagent/`进行Maven打包，上传tar.gz，按既定`bin/conf/lib`结构解压；外置prod配置及依赖、MySQL迁移准备完成后启动真实应用。
2. 将以下文件放到Nginx独立静态目录，例如`/data/app/dataagent/bi-sim/`：
   - `bi-simulator.html`、`bi-simulator-entry.html`、`bi-simulator.css`、`bi-simulator.mjs`、`bi-login.mjs`。
   - 另行放置私有`bi-simulator.config.json`，密钥与该环境后端一致。不要把example当有效配置，不把私有配置提交Git。
3. 将[bi-simulator.nginx.conf](bi-simulator.nginx.conf)对应location合入同一个HTTPS server，检查并reload。静态目录不放Java配置、数据库密码、测试日志或其他文件；不要整目录公开联调源材料。
4. 静态location必须使用配套片段中的显式MIME映射（HTML/CSS/JSON/JS/MJS）；部分系统默认mime.types没有mjs，返回application/octet-stream会让浏览器拒绝模块，表现为用户列表为空、入口保持禁用。部署后检查两个mjs的Content-Type为application/javascript，页面200本身不足以证明初始化成功。
5. 访问同一协议/主机/端口下的`/bi-sim/`和`/dataagent/`。前端加密需要HTTPS，或通过SSH映射后用浏览器的localhost安全上下文；公网IP普通HTTP不能直接替代。
6. 模拟入口只用于约定联调环境。接入真实BI后可撤下`/bi-sim/`；DataAgent产品路由、业务数据及正式接入模块不受影响。

## 验证与限制

基础回归：`node --test docs/integration/bi-simulator.test.mjs`。该测试包含代理协议夹具，不能替代实际后端验收。真实浏览器/MySQL结果、首次失败与修复、保留测试数据及环境状态记录在本轮报告（来源 `boundary-lyf@b67a179a:docs/test-reports/20261019-bi-simulator.md`）。

实际BI仍需最终确认用户名读取、路由生命周期、同源和嵌入策略。前端持钥的既有风险接受仍按ADR0081记录，本模拟入口不改变认证协议的可信边界。
