# BI账号接入、用户导入与公共库发布

## 状态与前提
代码候选验收中，真实BI、10.194.2.66、代理和公司模型未验。用户已接受客户端Local Storage账号信任，不增加BI会话验证。只启用当前方案的入口，不需要BI向我们主动传参。

## 首次初始化
1. 同事提供两个MySQL库的主机端口、版本、库名、账号和授权；由66验证连通。平台库执行最终0000完整DDL，上传库保持空；已有库改走0116等适用升级，不运行完整建表。
2. 外置prod配置设置DATAAGENT_BI_ACCOUNT_LOGIN_ENABLED=true、JWT密钥、两库、模型与独立模型配置加密主密钥。新模式关闭原票据入口，生产仍禁止密码登录。
3. 启动prod/validate后，拟任管理员从BI入口首次进入，自动生成普通用户。
4. 在平台库确认其username唯一后赋权：`UPDATE dataagent_user SET roles_csv = CONCAT_WS(',', NULLIF(roles_csv,''), 'admin') WHERE username = '替换为实际BI账号' AND FIND_IN_SET('admin', COALESCE(roles_csv,'')) = 0;` 核对影响行数和目标，管理员关闭旧页面从入口重新进入获得新角色。
5. 在开发者工具的当前应用Session Storage中读取本人dataagent_token（平台JWT，不是BI token，不要发给他人），本地运行导入工具，默认服务端预览，无写入：
   `python docs/integration/import-bi-users.py 用户资料.xlsx --url https://实际BI主机/dify/dataagent/`
6. 核对新增/更新总数后，相同命令加`--apply`才写入。Token交互式隐藏输入，不写入命令行或文件。不导入password/phone/type，不按中文名合并。已有平台ID/角色/授权不被覆盖。仅检查文件可用`--check-only`，不联网。
7. 资料左下角显示中文名与公司；未收录账号仍可首登，缺资料回退账号。停用/离职与资料来源更新需后续运营维护，不因某次名单缺失自动删除。

## 入口与代理
浏览器最终地址须与BI协议、主机、端口一致；建议提供给BI图标的应用地址为 `https://实际BI主机/dify/dataagent/bi-entry`，嵌套宿主的创建仍由BI完成。`username`优先，`account`兼容；两者冲突或缺失则提示重新进入；不使用中文userName登录。Direct入口也能工作，不依赖父页面发消息。

打包由用户本人执行：先设置 `DATAAGENT_BASE_PATH=/dify/dataagent/`，再按正常Maven流程打包上传。当前仓库静态产物保持根路径开发构建；发布构建会按环境重新生成，不能只改运行变量。

以下仅是最后一层代理的示例，假定该层收到完整前缀；若上层已剥离/dify，必须按实际请求修改location。现网配置尚未获取，禁止直接覆盖：
```nginx
location /dify/dataagent/ {
    proxy_pass http://10.194.2.66:8080/;
    proxy_set_header Host $http_host;
    proxy_set_header X-Real-IP $remote_addr;
    proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
    # Preserve the externally observed scheme through the actual trusted proxy chain.
    proxy_buffering off;
    proxy_cache off;
    proxy_read_timeout 600s;
    client_max_body_size 120m;
}
```
8080及上传限制按实际端口/应用限制核对；每层代理的SSE超时/缓冲及上传上限都需检查。保留外部HTTPS地址，不302跳到内网IP，不占用根/api以免影响BI。Dify容器配置应挂载管理，避免重建丢失。iframe同源限制与前缀刷新、附件下载均需验收。

## 管理与授权
普通用户拥有单库使用/管理权限，全局管理员配置模型及公共库。更多页提供管理员系统入口；分析文件仍在对话中预览下载。

管理员在“公共知识库”设置已发布库为全员使用，新用户自动获得QUERY，不生成逐人授权。关闭后单独成员授权保留，私人历史/图表/文件不公开。组织筛选批量授权保存选中的人员快照；成员管理不会移除公共来源权限。第一版不做公共库个人排除和动态组织规则。

QUERY只读页显示有限样例、字段、已发布模型/口径；管理接口仍要求MANAGE。样例数据应为脱敏或合成数据。公司路由模型按OpenAI兼容连接另行测试并在页面激活；搜索另需DEEPSEEK_API_KEY，没有则不注册搜索，不影响知识库问数。

## 上线验收
两个BI账号验证首次/重复进入、账号切换、过期、重名区分；使用用户只能读自己被授权的库，直接写接口拒绝；公共库对新增用户生效，关闭后新请求及排队任务复核。测试深层刷新、SSE、上传、附件、重启后资料/公开设置。真实模型测试流式、工具调用、限流超时、手动切换；保留数据/答案/耗时核对。

环境盘点、安装目录、自启、日志轮转、资源限额、备份回退按[单机发布手册](../guides/single-host-release.md)补齐；66系统未知，暂不发通用apt安装脚本，不代打包或远程部署。
