# BI样板间同步与正式两库切换：待实施方案

## 最新准备状态（2026-11-03）

后续用户已定稿39字段：按同事SQL，人员原表排除password/phone/email、日志各四列且全类型同步，指标称操作记录数，不再支持按查看/导出拆分。源库禁加索引；项目833范围查询及日志项目枚举现场索引计划通过，按日志中的项目分段，配套项目进度/日计数账本，见ADR0126及20261103-bi-basic-project-sync报告。此前46字段和log_type=0统计段落为已取代的方案历史，不能用于本次建模/部署；字段事实见deploy/bi-sync/source-selects.sql与业务口径.md。

用户随后已完成正式37表/人员/两管理员初始化，并授权不能登录66期间做好本地准备；模型配置选择正式重配，精简字段和类型/权限统计规则已确认。spec109/ADR0125及切库、同步、配置和调度工具已准备，本地验证见20261103-formal-bi-preparation报告；下一次登录入口为docs/guides/66-formal-database-and-bi-sync.md。尚未在66连接、切库、同步或安装定时任务，代码仍在个人分支未提交。下方为初始分析过程记录，待确认项以此节及CURRENT_TASK为准。

## 状态与已确认范围

2026-11-02，用户要求先分析，不实施。已读会议纪要、四页原文及当前代码，未单独转写MP3，未连接两台业务MySQL、未执行SQL、未改生产配置、未提交或推送。录音转写有明显错字，以用户提供的真实DDL、明确确认及现场回传为准。

用户已接受先正式两库、再同步业务表、最后页面关联公共知识库，不再用少量Excel先创建上传表。用户确认公共样板间允许内部全员查询人员姓名、公司/部门、报表名称及访问记录；两日志表只追加、不修改或删除历史，暂不考虑迟到补写；报表与仪表盘日志均以log_type=0表示查看，1/2为导出相关。

通用虚构新手示例仍为独立已实现任务，尚未部署；此方案不把真实BI数据放回新手引导的通用示例。无MySQL5.7查询兼容改造；脚本只从5.7抽取数据，Wren只连接正式8.0镜像表。

## 推荐顺序及隔离

1. 核对正式服务版本、两库是否空、字符集、权限与应用容器内连通性。
2. 为data_agent_platform准备当前版本完整平台DDL、人员导入与管理员SQL；以新环境启用，不导入旧测试知识库/会话/业务表。
3. 调整66部署工具的外置数据库模式，准备新配置和干净业务运行目录，保留现有服务/数据作为回退，不删除旧MySQL。
4. 新服务验证登录、管理员、模型服务配置/激活、上传、建模/发布/问数、沙箱及新手假数据入口。
5. 同步脚本创建正式data_agent中的7张固定业务镜像表，初始数据核验后，以只读业务账号在页面关联并建模发布。
6. 全局管理员通过现有公共库配置开启全员QUERY；普通账号验收，确认后启用每日调度。

应用和分析沙箱仍用Docker；仅退出Docker MySQL作为业务存储，不删除应用Docker依赖。公共知识库开关是全库QUERY，不继承BI逐人项目/报表权限，也不是匿名公开或全员MANAGE。

## 同步表与字段草案

稳定名称建议统一bi_前缀，最终名称待实施配置固定。不得写dataagent_*平台表，也不动态猜测ds_*上传物理表。字段从源DDL显式映射，MySQL8目标统一字符集/排序规则，ID保持原字符串长度及大小写语义，不按数字转换。

| 源表 | 目标名建议 | 读取字段草案 | 每日策略 |
| --- | --- | --- | --- |
| sys_project | bi_project | id,name,leader；status仅在需要区分删除项目时补入 | 小表全量快照 |
| sys_project_operator_relation | bi_project_operator | id,operatorid,projectid,createtime | 小表全量快照 |
| sys_operator | bi_operator | id,loginname,name,company,dept,posts,state,user_type,createtime；bak_name如需显示禁用前姓名另确认 | 小表全量快照 |
| d_report | bi_report | id,name,org_id,creator,create_time,update_time,state | 小表全量快照 |
| d_dashboard | bi_dashboard | id,name,org_id,creator,create_time,update_time,state | 小表全量快照 |
| d_report_log | bi_report_log | id,log_time,report_id,report_project_id,operator_id,operator_project_id,log_type,has_permission | 首次今年以来全量，后续按时间窗口增量 |
| d_dashboard_log | bi_dashboard_log | id,log_time,dashboard_id,dashboard_project_id,operator_id,operator_project_id,log_type,has_permission | 同上 |

以上补充字段是建议，不自动扩大原SQL；冻结前需用户确认最终清单。源sys_operator确实有password、phone、email等字段，不再SELECT *；不抽取密码或会话/配置大字段。目标全员可读账号只授予这7张分析表，不授予平台库或整个上传库。

## 首次/每日运行与验收

- 首次区间为[2026-01-01 00:00:00,运行当天00:00:00)，业务时间按Asia/Shanghai；后续每天建议02:00补齐到当天00:00:00，调度时间待确认。
- 每张表保留源主键并建立唯一约束；两日志ID是varchar而非自增，不用MAX(id)作为增量水位。单次读取按(log_time,id)键集分页，严格半开日期边界，分页参数化；源端普通SELECT兼容5.7，不使用WITH。
- 小表加载暂存数据并校验后，在目标事务中替换当前镜像，不先TRUNCATE再逐行回填；历史日志用LEFT JOIN保留未匹配事件，人员组织/对象归属是当前快照，不冒充历史组织快照。
- 大表初始按日期范围分批、流式读取与批量写入；主键幂等更新，断点/水位只在对应批次或窗口成功提交后推进。续跑、补日期和核验需有独立命令；不每次重读全年，不用大OFFSET分页。
- 单任务锁防重入；仅7表白名单；源只读、目标限表；配置与密码置66私有权限文件，Git只含无密钥代码和模板。进程退出码和每表行数/日期/耗时/错误摘要可查，不打印行数据或口令。
- 对外宣称“已同步至某日”以全部7表成功及源/目标同区间对账为准，不凭MAX(log_time)推断完整。源为从库，复制延迟仍需现场核对；不考虑业务迟到不代表从库没有复制延迟。
- 每日维表快照及日志增量完成后记录统一成功截止日；失败时不标记全批成功。上线前验证重跑不重复、中断续跑、失败不跳水位、源读取异常不清空镜像。
- 源两日志没有以log_time开头的索引；先EXPLAIN核对。可请DBA评估(log_time,id)索引，脚本不自行改源表，不能认为目标建索引能降低源扫描成本。目标设置时间及常用关联索引。

## 建模、问数与业务核对

- d_report.org_id、d_dashboard.org_id注释明确为项目ID，但没有声明外键；先用同目录只读SQL验证sys_project.id匹配率及缺失示例。其他关联也按实际匹配率报告，不因少量缺失直接否定关系。
- 报表/仪表盘日志分别建事实表，人员/项目/对象为维表；项目-人员关系是桥表，不能把它直接连接到访问日志再COUNT(*)，会因一人多项目膨胀。
- 访问次数为对应日志log_type=0的事件条数；是否还排除has_permission=0需另确认，不擅自过滤。总访问次数与访问人数跨两类对象统计时需注意日志ID命名空间、同人去重，不能把两个UV相加。
- 同步保留log_type后可分别展示查看/导出/下载；对象项目与访问者项目不能混用。sys_project.leader为varchar(255)，没有确认其编码，不自行当operator.id关联。
- 同表名同字段的增量数据可被后续新SQL读取，无须每天重建模型；现有对话旧答案/CSV不会自动更新，平台采样摘要及模型里的行数/覆盖范围是快照，不能当实时水位。静态描述避免写死当前最大日期，结构变化才重新整理/建模发布。
- 外部日志事实表须按现有产品流程确认角色、分析时间列和查询窗口；不能为演示绕过门禁。启用公共库前完成代表性SQL对账及普通账号可查不可改验收。

## 正式平台库切换需要处理的现有耦合

- 新申请库名是data_agent_platform，旧默认dataagent_platform。仓库完整DDL不带USE可选正确库；桌面旧版01及其他交付SQL显式限定旧库且日期较早，必须重新按当前版本生成，不能原样执行。
- 新环境不迁移测试数据已获用户说明；真实人员1484人SQL本地已找到，但需核对库限定并按当前DDL导入，手工给liyufanwx等管理员赋权。不要导入sys_operator中的BI密码作为平台密码。
- 平台服务连接DATAAGENT_DB_URL/USER/PASSWORD；上传连接DATAAGENT_DATASET_DB_URL/USER/PASSWORD，分别指定两个逻辑库。保持生产validate、SQL init never，不靠启动自动创建平台结构。
- 现有rehearsal-app.sh prepare/入口依赖旧数据库目录标签及Docker网络，preflight将mysql:3306和dataagent_platform写死；user-admin.sh固定Docker mysql/root/socket。需要明确外置模式及DB-URL驱动预检，保留旧模式回退；只改环境变量不足。
- 空平台库会丢失测试知识库、授权、会话元数据，也会丢失平台保存的大模型服务配置与激活选择。需重配/测试/激活模型，或单独确认定向迁移；不是只导人员便保证全部功能就绪。
- 文件HOME、state、workspace、mdl、artifacts及模型加密密钥与平台数据有一致性关系。新环境使用干净业务运行目录；离线模型缓存可复用。旧目录/密钥不删除，旧配置和镜像共同保存，不能把只回滚镜像当作数据库切换回滚。
- 内置新假数据文件入口不依赖外部demo库密码；可保留上传示例而不在正式MySQL创建第三库demo_commerce_v1。现有外部demo初始化涉及建库/建只读用户，正式双库账号未必具备权限，需明确关闭这一路自动准备或另由DBA授权，不能默认为应用授予全局root。

## 仍待现场材料/确认

用户随后回传的只读现场结果：报表9218、仪表盘4610，org_id均能匹配项目（缺失0）；正式MySQL为8.0.36，两逻辑库BASE TABLE均0；data_agent@%对data_agent及data_agent_platform有库级ALL PRIVILEGES，没有全局建用户/授权权限的证据。以上来自用户BI页面查询截图，不是助手连接66或数据库的实测，应用容器内连通性尚未验证。

已在桌面DataAgent-正式库初始化-20261102准备01平台建表及02只读核对SQL；01保持当前仓库完整37表结构，全部CREATE与唯一外键显式限定data_agent_platform，移除不需要的会话FOREIGN_KEY_CHECKS设置（父表先于子表）。只完成静态结构等价与语句白名单核验，没有执行初始化或导入人员，尚未获用户开始写正式库的明确确认。

1. 执行docs/fixtures/bi-report-sync-source-checks.sql前半段及两条EXPLAIN，回传结果；日志样本段可待执行计划审阅后再跑。
2. 确认最终字段清单及has_permission过滤规则；确认是否需按creator统计创建人、显示禁用前姓名。
3. 正式版本/空库状态/双库应用权限已有截图；仍需字符集及应用容器内可达性。是否提供独立BI镜像只读账号、同步账号由DBA授予哪些权限。
4. 模型服务选择重配还是定向迁移，干净正式运行目录及切换/回退安排。

本文件是可评审建议，不是已完成同步脚本或部署指令。用户确认方案和上述材料后再写实施spec及必要ADR、代码、测试及66操作步骤。
