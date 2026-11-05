# 66正式数据库切换与BI样板间同步

本文为2026-11-02至03本地准备，尚未在66实际执行。按阶段操作，上一阶段有异常先保留输出，不继续后续写操作。下次登录时助手仍可一次只带一个步骤。

## 已完成，不重复执行

用户已在正式MySQL8.0.36的data_agent_platform创建37表、导入1484人并补建本人；liyufanwx/王超账号均为user,admin。data_agent尚无业务表。模型服务决定在正式环境重新配置。旧Docker里的测试知识库/会话/模型配置不迁移；Docker应用和沙箱仍保留。

## A. 先发布源码，再到66构建

当前修改在本地liyufan_dev尚未提交。本地提交、合并到develop-2.0并push后才能执行服务器更新；不从未提交工作区构建。不要仅push这份文档而漏掉新示例素材和部署/同步脚本。

在66源码目录执行以下一段。它只拉取快进，不启停服务；失败不强制重置。

```bash
(
  set -e
  cd "$HOME/dataagent-source/agentscope-java"
  test "$(git rev-parse --abbrev-ref HEAD)" = develop-2.0
  test -z "$(git status --porcelain)"
  unset HTTP_PROXY HTTPS_PROXY ALL_PROXY http_proxy https_proxy all_proxy
  export NO_PROXY='*' no_proxy='*'
  timeout 1800s git fetch origin refs/heads/develop-2.0:refs/remotes/origin/develop-2.0
  git merge --ff-only origin/develop-2.0
  git log -1 --oneline
)
```

进入模块并构建。后面各阶段均在此目录执行；记录构建完成输出中的image，不用latest或猜“最后一份”。已有更新脚本默认每次拉取1800秒，最多3次。

```bash
cd "$HOME/dataagent-source/agentscope-java/agentscope-dataagent-standalone"
python3 --version
bash docker/build-from-source.sh HEAD
```

主机辅助工具要求Python3.9+。若服务器python3过旧，先停在这里，使用已验收容器运行辅助工具的方式另行安排，不修改系统Python。

## B. 私有连接准备（只写配置，不连库）

交互终端输入正式data_agent账号和BI从库easybi账号密码，各确认一次，不回显，不粘贴进命令。不把生成文件上传GitHub、不cat回传。

```bash
python3 deploy/bi-sync/configure.py
```

默认生成/data/dataagent/formal-private/database.env及bi-sync.json（600权限，目录700）。已存在则拒绝覆盖，避免重置连接；需要改错时单独修改私有文件并核对两个文件一致，不重新初始化数据库。

## C. 准备正式候选环境，保留旧服务

把下面的镜像占位符替换为A阶段构建记录中的完整镜像名，执行前必须替换。prepare复用原配置中APP_CACHE，但创建全新业务HOME/state/workspace/mdl/artifacts、独立网络和新加密密钥，不复制旧测试工程或模型配置；启用镜像内置的虚构订单和客户文件，外部示例源保持关闭。

```bash
python3 docker/formal-app.py prepare 'REPLACE_WITH_BUILT_IMAGE'
python3 docker/formal-app.py check
python3 docker/formal-app.py start
python3 docker/formal-app.py status
```

默认正式目录/data/dataagent/formal-app，Compose项目dataagent66-formal-app，候选端口18081；原应用18080继续服务。check通过JDBC URL检查正式两库、用户/管理员，并测试离线向量和兄弟沙箱文件路径；JPA validate在实际启动时验证表结构。预检/健康失败不停止旧服务。

```bash
curl --noproxy '*' --fail --max-time 10 http://10.194.2.66:18081/actuator/health
```

## D. 明确切换服务端口

只有候选Healthy/UP后执行。此命令会停止旧应用，将正式应用接替18080；不停止或删除旧MySQL，不删原目录。失败会尝试停止正式候选并恢复旧应用，仍须核对实际输出和端口。

```bash
python3 docker/formal-app.py activate
python3 docker/formal-app.py status
curl --noproxy '*' --fail --max-time 10 http://10.194.2.66:18080/actuator/health
```

从BI入口重新进入，核对两管理员身份，配置/测试/激活正式模型服务（旧配置没有迁移）。用一个普通账号验登录，再走虚构示例上传→建模→发布→问数/图表/附件，核对65代理和沙箱。健康UP不代替业务验收。

首次切换验收失败，需要恢复原环境时：

```bash
python3 docker/formal-app.py revert-service
```

它恢复旧应用/旧库服务，保留正式库和目录，不回滚任何数据；若正式环境已开放并产生真实新数据，先评估差异再决定，不把这当数据迁移工具。`formal-app.py rollback`仅回退正式应用镜像，仍连接正式库，含义不同。后续日常升级用`python3 docker/formal-app.py upgrade '明确镜像'`，不要用默认指向rehearsal目录的旧升级命令。

## E. BI镜像初始化和首轮同步

**2026-11-03更新：源库禁止新增索引，已按项目分段适配。** 用户现场计划确认项目833的项目+时间查询使用既有idx_projectId_time，项目枚举也使用索引；本地隔离测试通过不代替实际全量耗时验收。执行本阶段前须先完成A至D，新版采用39列/v2-basic任务归属；若现场曾运行旧46列版本，先停止核对，不改owner或自行删表。

先只读check，看两端连接、源字段及既有项目+时间索引；缺少该索引会拒绝，不要求新增或修改源DDL。首次数据量/耗时待现场，不承诺固定时间。

```bash
bash docker/bi-sync.sh check
bash docker/bi-sync.sh init
bash docker/bi-sync.sh sync
bash docker/bi-sync.sh verify
bash docker/bi-sync.sh status
```

init只新建7张固定bi_业务表和1张控制表，并建立主键/时间/对象/人员/关联索引，不修改平台表或ds_表；同名无任务标记或旧版任务会拒绝，不覆盖。sync首次读2026-01-01至执行当天00:00（不含今天），后续只补缺少日期并刷新5维表；日志项目从源日志枚举，保留历史项目，每项目的日数据、计数和进度原子提交，失败可直接重跑同一命令。项目列不落目标日志，表截止日须等全部项目与目标计数核对通过。verify只读逐日计数对账，不是逐字段内容校验。success_until=2026-11-03表示11月2日及以前已完成，不是包含11月3日。日志在正式目录logs/bi-sync，不含原始行或密码。

## F. 关联并发布公共知识库

请DBA为问数配置专用只读账号，按deploy/bi-sync/readonly-grants.sql.example授予7业务表SELECT；应用当前双库ALL没有全局建账号/GRANT权限，不自行执行模板期待成功，也不扩大到整个平台库。

页面添加正式MySQL8数据源→新建“BI平台使用分析”→关联同一数据源7张bi_业务表（不选bi_sync_control）→上传deploy/bi-sync/业务口径.md→字段/角色/日志时间范围→建模→试问SQL核对→显式发布→更多中的公共知识库设置全员可使用。使用普通账号验证可问不可管理。

日志保留全部操作类型，只有原SQL四列，统计称操作记录数/去重操作人数，不能称纯查看次数或导出次数，不能在问数SQL里引用log_type或日志项目/权限字段。按项目通过对象当前org_id关联，部门归属也是当前快照。桥表不直接连接日志计数；跨两类操作人数合并去重。具体建议问题和关联定义在口径文件，不把旧Excel占位表混进这个知识库。

## G. 最后才安装每日任务

前提是源/目标核验通过，status已补齐昨天，服务器调度时区为东八区。先确认非交互Docker权限；若sudo需要交互密码，请由运维安排既有授权，脚本不修改sudoers。

```bash
sudo -n docker version --format '{{.Server.Version}}'
python3 docker/install-bi-sync-cron.py
crontab -l | sed -n '/# DataAgent BI mirror v1/,+1p'
```

安装器只追加一个当前用户每日02:00任务，保留原有任务，同条重复执行不重复添加；源码默认路径不支持空格。每天检查logs/bi-sync/cron.log和status，不凭日志最大时间判断已完整。定期手动verify历史，发现差异先停止任务核对，本版不自动修复迟到/历史修改。

## H. 页面查看状态与停用预案

全局管理员从“更多→BI数据同步”查看：数据已完整同步至哪天、最近成功时间、两日志范围、最近任务起止/耗时和固定失败摘要。普通用户和单库管理员没有此接口权限。页面只读目标控制表，不访问源库或密码JSON，不执行Shell；刷新不触发同步。允许执行不代表cron已安装。凌晨03:00前给予每日任务执行时间，超过后日期仍落后会提示待更新；被杀进程遗留RUNNING但任务锁已释放时显示中断，不能当正常运行。

下列命令均在66源码模块目录、由原安装任务的SSH账号执行。暂停持久保存在目标库，pause/status/resume不需要连接BI源库。

```bash
bash docker/bi-sync.sh pause
bash docker/bi-sync.sh status
```

pause只请求暂停；正在读取中的数据库调用可能需要等待返回或超时，作业会在批次/提交前检查暂停标记，回滚未提交部分，保留已提交进度。确认status输出paused=true且running=false，或页面显示已暂停，再进行后续退役操作；PAUSING代表仍在退出中。暂停后定时器仍可能每日启动容器，但sync会跳过且不连接源库。

如果连每日触发都不要了，再移除本工具定时任务：

```bash
python3 docker/install-bi-sync-cron.py remove
```

remove只删除当前用户crontab内本工具标记及精确匹配的下一行；不删除其他任务、应用、数据库或文件，也不会强杀已经运行的作业。数据库不可达时仍可remove阻止后续定时触发；当前作业是否退出另行核对，不把移除cron当作已暂停运行任务。

确认停止后，在“公共知识库”取消全员可使用，或按需删除关联知识库。取消全员使用仍保留单独授予的成员权限；删除数据源关联的知识库不会删除这七张外部同步表。它们与同步任务没有自动停用联动。本次不提供自动删表或清理密码配置命令。

以后恢复时保留原表和进度，不重新init：

```bash
bash docker/bi-sync.sh resume
bash docker/bi-sync.sh sync
bash docker/bi-sync.sh verify
bash docker/bi-sync.sh status
python3 docker/install-bi-sync-cron.py
```

resume只解除暂停，不立即同步，也不安装cron。先手动补齐/核验成功，再恢复每日任务；如未移除原cron，安装器不会重复添加。知识库全员权限是否恢复由管理员另行决定。

## 验证边界

本地测试使用独立MySQL5.7.44/8.4.5虚构小数据，包含幂等、日水位、空日、注入失败回滚/续跑与任务互斥；正式目标8.0.36仍需上述现场验证。应用prepare/切换逻辑在隔离Linux和故障模拟中验证；本轮没有在66启动候选/切库，没有代表66完成真实模型/BI/业务验收。

## 已有任务扩展报表/仪表盘字段
2026-11-05用户授权升级为95列，按[已有任务字段升级](66-bi-asset-fields-upgrade.md)执行；不重新初始化两库/七表，不重装cron，不重置日志进度。先暂停再更新源码，按显式迁移及基础表补齐后恢复；本次不做线上备份。
