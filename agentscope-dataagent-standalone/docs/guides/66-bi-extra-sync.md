# 66新增六表同步：配置、首次回填和日常更新

状态：本地实施与隔离验证完成，待66现场验收。六表使用独立任务和调度。源码交付、提交和推送由用户另行安排。

## 数据清单及运行边界

- 目标仍为现有data_agent。六张业务表及完整类型/注释见 [DDL](../../deploy/bi-sync/extra-schema.sql)：bi_page_click_log 5列、bi_iam_user 18列、bi_tenant_optimization_daily 22列、bi_project_capacity_daily 10列、bi_screen 14列、bi_screen_visit_log 10列。合计79列，其中页面/优化各一个生成主键，77个业务字段。
- 新控制表bi_extra_sync_control和六张bi_extra_stage_*是内部元数据，不能关联到知识库或授予问数只读账号。旧七表仍由bi-sync.sh负责；spec127增加管理员“BI数据同步”的“BI业务扩展 · 6表”页签，只读展示本任务控制状态；须应用更新后可用，不安装或改变调度。本轮仅本地修改、待用户验证，未部署；服务器完整状态仍可用下面status命令查看。
- 字段、源SQL及业务限制见 [资料](../ai/BI_SYNC_EXTENSION_INTAKE_20261106.md)、[规格](../specs/123-bi-multisource-extension.md)及 [业务口径](../../deploy/bi-sync/extra-business-rules.md)。不创建或改动知识库，不假定多个系统的用户/项目ID可以直接关联。
- 来源分别为IAM、dataanalysis1、platform、Doris和原easybi。源只SELECT/SHOW/EXPLAIN和会话设置，不建表、建索引或改数据；MySQL只读事务，Doris仅固定只读SQL，capacity沿用必需TLS。目标MySQL8/InnoDB及utf8mb4。
- 源密码只在交互终端输入到外部600权限配置，不出现在命令行、示例、日志或Git中。原easybi/目标连接复用formal-private/bi-sync.json，不重复录入，也不改原文件。

## A. 源码到位后生成私有配置和只读检查

在66源码模块目录执行；不要求重启应用，使用现有应用镜像里的Python/mysqlclient，不另装宿主Python。

    cd "$HOME/dataagent-source/agentscope-java/agentscope-dataagent-standalone"
    bash docker/bi-extra-sync.sh configure
    bash docker/bi-extra-sync.sh check

configure依次询问四个新源的账号及密码，均不回显，只写/data/dataagent/formal-private/bi-sync-extra.json，拒绝覆盖已有文件。不要把真实值填入源码示例。修改batch_size/lookback_days时在私有文件中操作，保留600权限，不使用shell source。
check连接五源，核对已声明字段及投影EXPLAIN；不会创建任何目标表。失败只显示固定阶段/错误类型/数字错误码，禁止为排错开启驱动原文或打印配置。已有新任务可从status查看failure_phase。

## B. 首次建表和同步（现场确认后执行）

init检查owner、源端点指纹及所有新同名表，未归属的同名表拒绝接管；不会DROP已有业务表。新任务初始暂停。MySQL DDL隐式提交，初始化中断可原命令重试；出现未归属的孤立控制表时先人工核对，不强行写owner。

    bash docker/bi-extra-sync.sh init
    bash docker/bi-extra-sync.sh status
    bash docker/bi-extra-sync.sh resume
    bash docker/bi-extra-sync.sh sync
    bash docker/bi-extra-sync.sh verify
    bash docker/bi-extra-sync.sh status

sync的默认until是东八区当天00:00，范围不含该日。用户/大屏对象每次全量刷新，包括2026年前创建、各项目各状态。日期表从2026-01-01和源可用范围的交集开始，各日先暂存、计数核对、严格类型转换，再原子替换业务日、内容校验及记账；失败保留旧目标，已成功日的进度不回退。

已知现场范围：容量存在20260101、空日期排除；优化最早2026-10-07；点击当前最早分区2026-08-08，动态保留90天，执行时重新读取分区而不固定这个日期。分区存在不证明有行。没有其他归档时不能补齐这些缺失历史，也不能将其解释为业务0。

状态按表展示requested_from、available_from、coverage_from、success_until（排他上界）、gaps；最后运行和核对结果单独记录。SUCCESS_WITH_GAPS表示已处理范围存在源历史缺失/空监控快照或NOT_PROCESSED未处理区间，不是2026全年完整。首次仅指定近期范围会明确记录跳过的历史，不能用近期核对冒充首次全范围核对。对监控类空日保留目标已有值并记为缺失；不能把status的进度上界当所有日完整证明。verify只读业务数据并记录私有核对元信息：对仍可读取的源核对结果内容/计数，对源已过期但本库有账本的归档日仅核对目标账本，不能声称再次查到源历史。

## C. 补跑、暂停和字段升级

日常sync默认回刷最近3个完整日，并补上尚未完成的范围；lookback_days允许1至31天。更早迟到/改删需指定日期重跑，不承诺无限期自动捕获。源已过期的日期不回刷、不删除已保存历史；可读范围内真正空的事件日可以更新为0行。生成的技术ID可能随重刷变化，不可用作跨系统业务关联。

源聚合/流式传输及目标写入允许每次网络读写等待600秒；MySQL会话同时设置网络读写超时，Doris不设置MySQL专用会话项。同步抽取遇到源断线错误2003/2006/2013/2055时，最多尝试3次，重新连接并完整重抽当前日/快照，清空的是本任务暂存表。目标或控制连接失败不自动重连，避免丢失任务锁后继续写入；已发布业务数据和进度保留。连续失败时status记录表名、错误码，源断线另记failure_step/failure_day，不输出驱动原文。

若SSH断开或隔夜发现失败，先用status确认running=false，再用sync续作；不用重新configure/init/migrate，也不用手改控制表。`last_run=FAILED`与`last_verify=null`表示同步/核对尚未全部完成；SOURCE_HISTORY_UNAVAILABLE是已识别的源历史缺失，不等于连接错误。网络错误2013仅能判断连接中断，不能仅凭错误码认定是客户端超时或源服务重启。

首次回填完成后若全量核对以4031退出，且status显示running=false但last_run仍为RUNNING，先核对各表已提交进度和last_verify。新版verify用持锁目标连接提交最终状态和核对记录。同步容器从66源码目录加载脚本，更新源码即可使用修复。若日期表已到昨天，直接重跑全量verify；若水位落后，先用默认起点增量sync到昨天，再用同一个截止日全量verify。安装调度时仍须满足下文的最新完整日要求。

长时间首次回填可在更新源码后放到服务器后台，固定sync/verify使用同一个排他截止日，避免本地电脑休眠关闭SSH影响启动链：

```bash
sync_until="$(date +%F)"
sync_log="/data/dataagent/formal-app/logs/bi-extra-sync/manual-$(date +%Y%m%d-%H%M%S).log"
nohup bash -c 'bash docker/bi-extra-sync.sh sync --until "$1" && bash docker/bi-extra-sync.sh verify --until "$1" && bash docker/bi-extra-sync.sh status' _ "$sync_until" > "$sync_log" 2>&1 < /dev/null &
echo "后台任务PID=$!，日志=$sync_log"
```

从另一个终端用status查看运行状态，或tail对应manual日志；查看日志的tail可用Ctrl+C退出，不会停止后台同步。sync失败会停止后面的verify，修复后重新运行该链；只有成功verify才进入调度安装和后续应用更新。

    bash docker/bi-extra-sync.sh sync --tables bi_page_click_log --from 2026-11-01 --until 2026-11-06
    bash docker/bi-extra-sync.sh verify --tables bi_page_click_log --from 2026-11-01 --until 2026-11-06
    bash docker/bi-extra-sync.sh pause
    bash docker/bi-extra-sync.sh status

pause是协作退出，要等status的running=false再升级。status/pause不连接源库，暂停/移除调度均不删数据。源指纹变化拒绝续用任务，不通过手改控制表强行迁移。

未来加字段先更新代码白名单/投影及明确迁移，再暂停任务执行migrate；只支持增加代码声明的可空字段，不自动删列、改类型、改唯一键或重置进度，DDL跨表中断可重试。随后按可用源范围回填并核对，再resume。原始源历史已过期时，新字段旧值可能无法补齐；字段版本/账本兼容需在当次升级中明确，不能把migrate当任意模式编辑器。本版从无到有使用init，不需要migrate。

## D. 首次验收后独立安装每日02:15任务

保持服务器东八区。必须先全六表sync/verify至昨天，last_run须为成功verify且不在运行/暂停，再显式执行：

    bash docker/install-bi-extra-sync-cron.sh install

安装器只追加自己的精确标记和一条02:15任务，保留旧七表02:00及其他cron。已知源缺失区间由status明示，不通过修改下界掩盖。新日志位于formal-app/logs/bi-extra-sync/，不含源SQL、原始行或凭据。服务器若不用sudo docker，调用入口及安装器前显式设置DATAAGENT_DOCKER_SUDO=false。

停用顺序为pause→status确认不再运行→remove；移除不依赖数据库或应用容器可用：

    bash docker/install-bi-extra-sync-cron.sh remove

## 验收与限制

现场需要确认Doris URL_DECODE/分区解析及长URL实际写入、每日计数、公式、原始重复行、角色只读授权、新调度执行时间。隔离MySQL替身不能替代Doris查询计划和实际吞吐验收。操作前核对目标容量及原始日志增长；本任务不自动做资源清理。同步状态不代表知识库已关联/建模/发布。

如果现有知识库数据源账号只获授权读取原七表，待验收后由DBA按同样的逐表SELECT方式授权所需的新业务表。IAM用户表包含邮箱和手机号，按实际使用范围单独决定是否授权。控制表和暂存表仅供同步账号使用。
