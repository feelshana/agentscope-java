# 当前任务：66更新与GitHub分发示例Excel（2026-10-31 Asia/Shanghai）

## 当前事实与授权
- develop-2.0本地整合完成：dd1fc811合入liyufan，b6105aa1合入旧design并统一上传上限，三个来源分支均为祖先，上一任务结束时工作区干净。
- 用户本轮要求核对桌面日常更新文档并给66手工操作指引；用户自行登录66，助手未执行远程部署或数据库操作。
- 起初查询远端仍3bb64fc9，提示用户核对；用户补推后fetch确认origin/develop-2.0已为b6105aa1d08f32ff37626ba2c69fe17b8efa4fff，本地与远端同步。

## 本轮完成
- 桌面文档仍是旧整合分支版；已同步项目guides/66-update-operations.md的develop-2.0首次切换与后续快进流程。
- 增加本次构建后/upgrade前的0105字段检查：平台库dataagent_user的onboarding_tour varchar(200)、onboarding_reset_at datetime(6)，只补缺列，不重复执行非幂等迁移、不初始化数据库。
- 66使用validate与受限账号，不能依赖启动自动建字段；操作使用原mysql Compose容器内部现有凭据，不读出私有env。
- 0106仅涉及已有文本时间demo_data；内置示例默认关闭，本次不默认创建示例库或启用功能。部署Excel/账号另按手册核对。
- 更新文档：docs/guides/66-update-operations.md及桌面DataAgent-66代码更新与重启命令.md完全一致；原主线合并任务归档archive/20261031-before-66-update-guidance.md。
- 本轮变更为示例文件与运维文档，无新spec/ADR或业务行为变更；用户最新明确授权完成本地提交，由用户push，不执行远程命令。

## 验证与下一步
- 本轮DocsConsistencyTest5项通过，11个Bash代码块语法通过，桌面与项目指引逐字节相同；应用验证沿用主线整合报告，不重跑无关全量。
- 用户在66按更新文档执行：切主线/fetch核对提交、构建、0105字段检查补齐、upgrade、健康/运行提交比对与本次UI试用。
- 用户截图确认66 fetch成功、切换develop-2.0，HEAD缩写b6105aa、工作区无输出。已指导构建镜像，但尚未收到构建成功、SQL或upgrade结果；不能声称本次已升级。
- 用户提供两份Excel（本地文件存在，约3.14MB/98.7KB），前一步核对b6105aa1未包含文件；代码和0105/0106已合入。用户随后明确只能通过GitHub传输，已按新要求调整。
- 已补桌面/项目指引的示例文件路径、runtime.env键、Docker mysql主机及66受限数据库账号不能自动建demo_data/用户的前提。外部示例首次导入/只读账号准备尚未执行或验证，内置上传可独立使用；不将同事本机截图当66结果。
- 后续依次处理构建回传、0105字段检查、用户是否启用外部示例及其初始化，再upgrade；线上同音乐建模对照及真实BI/输入法验收仍待用户。
- 新增deploy/demo-samples中的两份指定Excel、SHA256SUMS和README，原字节与用户微信文件一致；运行目录data/demo-samples继续忽略，只有指定源文件进入仓库候选。
- 同步spec105、README、架构配置说明、手册、ADR0122状态说明及桌面指引。仅更改文件分发约定，不增加新spec/ADR；ADR正文保留原记录。
- 本轮新增文件校验：两份原字节SHA256一致、Linux SHA256SUMS检查及XLSX ZIP完整性通过；文档一致性5项、11个Bash块语法通过。未重跑无关业务测试。
- 用户已授权将本批示例文件与部署文档作一次本地提交，提交号以Git为准；尚未push，66的b6105aa1仍无新文件。用户push本次提交后再拉取复制；无需仅为复制Excel重新编译已有镜像，运行版本仍与本次实际镜像构建记录比较。
- 本地既有隔离服务/数据保留；新的文档WIP与主线业务代码b6105aa1区分记录，无需仅为阅读桌面新版文档重构建应用。

## 已确认的临时分发安排
- 本次先提交两Excel并由用户push；66复制到持久目录、校验并确认应用可读后，下次提交再删除仓库Excel副本，不提前删。Git历史仍保留原内容。
- 保留66持久路径及runtime.env配置，后续正常拉取/upgrade不影响文件；源码README/SHA256SUMS可保留。新服务器仍需另行准备示例文件。

## 66拉取回传与旧Git兼容修复
- 用户截图确认66已拉到011d06b、两Excel SHA256均OK，但出现CRLF转换提示且Git把两文件标M，不可按干净源码继续构建。
- 本地读取HEAD和原始文件确认字节与哈希完全一致，仓库内容未损坏；根属性text=auto/eol=lf在66旧Git1.8产生判断差异。
- 在deploy/demo-samples新增.gitattributes仅将xlsx声明binary；补66本地info/attributes临时规则，保留文件/既有属性，不reset/clean或忽略真实变动。仍由用户实际执行确认。
- 该修复是上一批已授权提交的文件传输兼容收尾；本地补一次修复提交，不push。66可先用本地规则继续复制校验，无需为此等待新镜像。

## 最新66现场进展与示例初始化待办
- 用户截图确认info/attributes规则生效、源码无M，已将两Excel及SHA256SUMS复制到/data/dataagent/rehearsal-app/data/demo-samples，两轮SHA256均OK。
- 用户截图确认011d06b镜像Maven及镜像静态预检通过，尚未upgrade；镜像完整标签以66原构建记录为准，不从截图猜随机后缀。
- 用户只读检查回传：两新字段、demo_data、demo_reader均不存在；随后按指导ALTER回传onboarding_tour varchar(200)、onboarding_reset_at datetime(6)，可空YES，0105完成；不重复执行，不需要0106。
- 拟用现有DemoDataService导入，管理员创建新demo_data及demo_reader；临时给dataagent_dataset该示例库的SELECT/INSERT/UPDATE/CREATE/ALTER/INDEX，升级后导入两表，验证行数/只读查询后撤销这些临时权限，不影响data_agent原权限。
- 只读文件结构核对：访问首表98819行，用户首表2046行（用户文件4个工作表，仅取首表）；访问日期包括数值日期及文本，使用现有Java解析与归一化，不另写导入器。
- root权限仅在MySQL容器内维护，常驻应用不改root；随机只读密码在66生成保存runtime.env，不回传、不入Git。prepare命令语法已检查，尚未收到66初始化执行结果。
