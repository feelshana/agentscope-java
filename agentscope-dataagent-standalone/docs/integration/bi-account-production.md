# BI账号接入、用户导入与公共库发布

## 状态与前提
代码候选验收中，真实BI、10.194.2.66、代理和公司模型未验。用户已接受客户端Local Storage账号信任，不增加BI会话验证。只启用当前方案的入口，不需要BI向我们主动传参。

## 首次初始化

2026-10-28用户选择首次上线使用SQL准备人员目录：可在应用启动前完成平台DDL、1484人资料导入及指定管理员，不再要求先取平台JWT。按[数据库准备指引](../guides/production-database-setup.md)执行，真实人员SQL单独交付。下列首次登录后使用管理员API导入的步骤仍为可选方式，二者不必重复执行。

1. 同事提供两个MySQL库的主机端口、版本、库名、账号和授权；由66验证连通。平台库执行最终0000完整DDL，上传库保持空；已有库改走0116等适用升级，不运行完整建表。
2. 外置prod配置设置DATAAGENT_BI_ACCOUNT_LOGIN_ENABLED=true、JWT密钥、两库、模型与独立模型配置加密主密钥。新模式关闭原票据入口，生产仍禁止密码登录。
3. 启动prod/validate后，拟任管理员从BI入口首次进入，自动生成普通用户。
4. 在平台库确认其username唯一后赋权：`UPDATE dataagent_user SET roles_csv = CONCAT_WS(',', NULLIF(roles_csv,''), 'admin') WHERE username = '替换为实际BI账号' AND FIND_IN_SET('admin', COALESCE(roles_csv,'')) = 0;` 核对影响行数和目标，管理员关闭旧页面从入口重新进入获得新角色。
5. 在开发者工具的当前应用Session Storage中读取本人dataagent_token（平台JWT，不是BI token，不要发给他人），本地运行导入工具，默认服务端预览，无写入：
   `python docs/integration/import-bi-users.py 用户资料.xlsx --url https://实际BI主机/dify/dataagent/`
6. 核对新增/更新总数后，相同命令加`--apply`才写入。Token交互式隐藏输入，不写入命令行或文件。不导入password/phone/type，不按中文名合并。已有平台ID/角色/授权不被覆盖。仅检查文件可用`--check-only`，不联网。
7. 首次批量创建账号需要逐个生成密码哈希，可能耗时数分钟。导入工具默认HTTP等待900秒，可用`--timeout-seconds`调整；代理等待时间也需匹配。请求超时不代表事务已失败，应先重跑默认预览核对新增数，避免误判。
8. 资料左下角显示中文名与公司；未收录账号仍可首登，缺资料回退账号。停用/离职与资料来源更新需后续运营维护，不因某次名单缺失自动删除。

## 在66本机执行用户导入

可以把 `import-bi-users.py` 与 BI 用户 Excel 放在同一个独立运维目录，一起上传到66，再执行脚本。Excel属于人员资料，不放入Git或通用Maven发布包，也不在每次服务启动时自动导入。脚本只依赖Python标准库，不需要安装openpyxl或MySQL驱动。

脚本调用已运行服务的管理员接口，**不直接连接MySQL**。所以执行顺序是：空平台库执行完整DDL → 配置并启动应用 → 管理员首次BI登录 → SQL赋予admin角色 → 从BI重新进入 → 取得本人的平台JWT → 预览并导入。令牌在执行脚本时隐藏输入，不填进脚本、配置或命令参数。

假设应用在66的8080端口运行，在上传目录执行（实际端口和Excel文件名需替换）：

```bash
# Only validates the workbook; no service or administrator token is required.
python3 import-bi-users.py users.xlsx --check-only
# Preview against the locally running application; no database writes.
python3 import-bi-users.py users.xlsx --url http://127.0.0.1:8080
# Write only after checking the preview counts.
python3 import-bi-users.py users.xlsx --url http://127.0.0.1:8080 --apply
```

直接访问66本机的应用端口时不加 `/dify/dataagent/`；经外部Nginx访问时才使用带前缀的完整公开地址。导入完成后已有用户资料刷新后可见；新用户即使未提前导入，也能在首次BI进入时创建为普通用户。自动创建与批量导入的新用户目前都使用随机密码，生产BI入口不使用密码；此前本地统一密码属于本地数据操作，不会应用到未来新建用户。

BI“数据开发”中的SQL页面也可执行初始化和资料插入，前提是连接到实际平台库、使用有相应权限的账号，而非截图默认选择的其他库。一千多条资料本身并不超出SQL能力。现按用户选择提供一次性离线资料SQL，已处理平台UUID、随机密码哈希、重复账号及已有角色/授权保护；本节API导入脚本保留为备选。本轮没有增加自动同步或应用启动时自动导入。

## 入口与代理
浏览器最终地址须与BI协议、主机、端口一致；建议提供给BI图标的应用地址为 `https://实际BI主机/dify/dataagent/bi-entry`，嵌套宿主的创建仍由BI完成。`username`优先，`account`兼容；两者冲突或缺失则提示重新进入；不使用中文userName登录。Direct入口也能工作，不依赖父页面发消息。

BI图标只配置上述固定地址，不需要传账号、调用登录接口或新增postMessage处理。BI新开自己的标签页外壳，在iframe中加载该地址；应用前端读取该origin已有的Local Storage，再调用应用账号登录接口。Nginx反向代理到66，浏览器仍停留在原公开域名；仅借用一个与BI不同的Dify域名不能共享Local Storage。首次从 `/dify/dataagent/` 进入也支持账号登录，但固定图标推荐明确配置 `/bi-entry`，便于重新进入。

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

QUERY和MANAGE共用知识库/数据集详情界面，数据预览、字段、技术信息相同；QUERY隐藏成员、编辑、建模、关联、上传等写操作，后端写接口仍要求MANAGE。QUERY预览上限20行，不暴露草稿或连接凭据。样例数据应为脱敏或合成数据。公司路由模型按OpenAI兼容连接另行测试并在页面激活；搜索另需DEEPSEEK_API_KEY，没有则不注册搜索，不影响知识库问数。

## 上线验收

2026-10-28已完成本地固定链接→新标签页→无脚本iframe外壳→目标前缀前端→真实后端的隔离实测，包含首登、重复进入、账号切换、角色、资料导入、无效令牌和跨origin反例；见[实测报告](../test-reports/20261028-bi-fixed-link-rehearsal.md)。本地Node代理模拟前缀转发，不代表真实BI或现网Nginx已通过。
两个BI账号验证首次/重复进入、账号切换、过期、重名区分；使用用户只能读自己被授权的库，直接写接口拒绝；公共库对新增用户生效，关闭后新请求及排队任务复核。测试深层刷新、SSE、上传、附件、重启后资料/公开设置。真实模型测试流式、工具调用、限流超时、手动切换；保留数据/答案/耗时核对。

环境盘点、安装目录、自启、日志轮转、资源限额、备份回退按[单机发布手册](../guides/single-host-release.md)补齐；66系统未知，暂不发通用apt安装脚本，不代打包或远程部署。
