# BI 同步扩展资料｜2026-11-06

状态：源端核验资料。用户已授权六表脚本本地实施，最终实现和现场操作见 [spec123](../specs/123-bi-multisource-extension.md) 与 [66现场指南](../guides/66-bi-extra-sync.md)。以下保留设计前的现场观察和推导。

## 范围及已确认口径

- 原七表同步保留。新增六类数据，拟表名仅供讨论，尚未确定最终 DDL。
- 日期类数据从 2026-01-01（含）导入，后续定时更新；未确认历史确实全部存在、迟到补写/改删和具体调度。
- 用户列表全量定时覆盖；大屏对象信息沿用旧报表/仪表盘的当前全量刷新，不按创建日期过滤。
- 当前只处理数据同步，新表之间的用户/项目关联放到建模时处理；取数 SQL 内部 JOIN 仍须保证结果不被放大。
- 用户在 66 执行检查并回传截图；助手本地四个新端点 TCP 超时，未登录源库。不能把用户回传当成本地执行或正式同步成功。
- 连接名称与库：IAM→MySQL/iam；重庆分析云第一套→MySQL/dataanalysis1；平台数据库→MySQL/platform；FxyDoris→Doris/fenxiyun。大屏两表使用旧 easybi 连接。
- 容量监控只涉及 dataanalysis1，用户明确只需要第一套连接，不额外接另两套。

## 1. 页面点击（拟 bi_page_click_log）

- 来源：Doris 的 operation_log_detail；业务列 operator、event_time、module_name、URL_DECODE(url) AS url。
- 用户明确保留每条原始点击，不能沿用报表四字段 GROUP BY 去重，也不能将相同四字段作唯一键。源 eid 可作为候选技术标识，尚未验证非空/唯一。
- 原完整建模 SQL 还关联 mysql_iam.iam.user（76/355 按 account_name，79/351 按 spms_account）、duty_person_info、project、oa_person；四列原始取数是否移除这些非必要 JOIN 留待方案。
- 原报表日期参数为 event_time_st/event_time_ed；示例 2026-11-05 至 2026-11-06、LIMIT 50 是页面条件，不是最终同步范围。
- app_id 产品分类：76 数据中台，79/351 红海APP，355 自助报表，其他为其他；当前四列范围不包含产品分类。
- 用户截图：event_time datetimev2(0)、create_time datetimev2(0)、eid varchar(255)，均可空；operator/module_name/project_id varchar(255)，app_id varchar(50)，url varchar(60000)，etp int（事件类型）。
- 初轮 SHOW INDEX 返回空，不将 VERSION() 返回 5.7.99 当成真实 Doris 版本。后续用户提供完整建表定义：ENGINE=OLAP，DUPLICATE KEY(event_time,app_id)，按 event_time 每日 RANGE 分区、RANDOM BUCKETS 5；这不是唯一键模型，不能按两个 KEY 列去重。
- DDL 含 94 个连续日分区，从 p20260808 至 p20261109，整体范围 [2026-08-08,2026-11-10)。分区存在不证明其中有业务行，未来分区不等于未来数据。
- 动态分区启用，DAY/Asia/Shanghai，start=-90、end=3，create_history_partition=false，没有 reserved_history_periods。滚动淘汰更早分区，因此当前此表不能提供2026-01-01至08-07的点击历史；如要补齐须另找归档。建议目标不跟随源端分区淘汰删除已保存历史，尚未最终确定方案。
- eid 默认空字符串且允许 NULL。用户后续单日查询（2026-11-05）：total_rows=42876、empty_eid_rows=0、distinct_nonempty_eids=3，已证实eid不是记录唯一键；不得加UNIQUE(eid)或按eid去重。
- 同日 etp=0 共42876行，没有其他值；loaded_after_event_day=0，仅说明该日当前样本未见次日及以后入库，不能证明全部历史无迟到或只会有etp=0。不加etp过滤，仍按每条源记录同步。

## 2. 用户列表（拟 bi_iam_user）

- 来源：iam.user u LEFT JOIN duty_person_info d ON d.id=u.id LEFT JOIN oa_person o ON o.account_name=u.account_name。
- 18 列：id、username、account_name、spms_account、email、phonenumber、coop_flag、undertaking_flag、order_type、created_time、updated_time、jg_name、bm_name、post、duty_person、duty_person_account、wx_company_name、oa_status。
- post=CASE WHEN d.duty_person_account=u.account_name THEN d.duty_person_posts END；oa_status=o.zhzt。
- 人员类型按 account_name 是否以 wx 结尾区分外协/自有，属于计算属性，尚未决定是否物理落列。
- 外协公司/部门取责任人归属；自有人员责任人为本人；承诺书 1 已签署/0 未签署；OA 状态 10 启用/20 停用，无 OA 账号为空。
- 用户截图：user.id bigint 主键，username/account_name varchar(50)，spms_account/email varchar(200)，phonenumber bigint(11)，created_time/updated_time datetime，undertaking_flag int，order_type varchar(20)。
- duty_person_info.id 是主键，bm_name/jg_name/wx_company_name varchar(512)，duty_person/duty_person_account varchar(64)，duty_person_posts varchar(1000)；oa_person.account_name varchar(200) 主键，zhzt int。
- account_name 的源索引非唯一，spms_account 有唯一索引。coop_flag、order_type 编码含义尚不全；不自行补业务过滤。

## 3. 租户可优化数据（拟 bi_tenant_optimization_daily）

- 来源：platform.cluster_data_to_be_optimized，共 21 列。
- 维度：dayid、cluster_name、project_name、db_name、table_name、dir_name、owner、update_user、is_no_visited、is_no_compressed、is_small_file、is_expire。
- 指标：total_size、total_files、total_dirs、no_compress_size、no_compress_files、small_file_size、small_file_count、expire_size、expire_count。
- 原报表按全部 12 维度分组，9 个指标求 SUM，四个容量用 TRUNCATE(SUM(容量)/1072741824,2)。用户明确沿用这个除数，不改成其他换算值。
- 示例 project_name='咪咕总部经分'、dayid=20261106、LIMIT 50，不作为全量同步的默认过滤。
- yes/no 分别是/否；is_no_visited 表示近三个月未访问。
- 用户截图：dayid 为可空 date，有 index_dayid 非唯一索引；无主键；四类容量为 double，文件/目录计数为 int，dir_name varchar(400)，其余文本多为 varchar(200)。源重复行、历史快照完整性、物理保存原值或聚合换算值待核对。

## 4. 容量监控（拟 bi_project_capacity_daily）

- 来源：dataanalysis1.project_storage t1 LEFT JOIN project t4 ON t1.project_id=t4.id LEFT JOIN user t6 ON t4.leader_id=t6.id LEFT JOIN project_settings t5 ON t1.project_id=t5.project_id。
- 4 维度：project_id、project_name(t4.name)、leader_name(t6.username)、record_date。
- 6 指标：total_storage_tb=SUM(t5.storage)/1099511627776；used_storage_tb=ROUND(SUM(t1.storage)/1099511627776,4)；storage_usage_pct=ROUND(SUM(t1.storage/t5.storage)*100,4)；total_files_10k=SUM(t5.inodes)/10000；used_files_10k=ROUND(SUM(t1.file_num)/10000,4)；file_usage_pct=ROUND(SUM(t1.file_num/t5.inodes)*100,4)。
- 两个百分比原 SQL 同时输出数值及带 % 字符串；暂按各一个业务指标整理，不自行改写 SUM(比值) 为 SUM/ SUM。
- 示例 record_date='20261105'、LIMIT 50，不是最终范围。
- 用户截图：project_storage.id int 主键；project_id int、project_path varchar(256)、file_num/storage bigint，storage 注释 byte；record_date varchar(32)，有独立日期及项目非唯一索引。
- project.id、user.id 为 bigint 主键，project_settings.project_id 为 bigint 主键，storage/inodes bigint，可空。配额是当前信息还是历史版本、同项目同日期是否多路径从而重复累计配额，仍需核对。
- 用户日期查询：latest_date=20261106，first_date_since_2026=20260101；最早5行的LENGTH(record_date)=0、HEX为空，已确认是空字符串，不能当无历史或转换为1970日期。按20260101起的合法日期同步，空日期记录留在源端并计入异常检查，不落入某个业务日。
- 用户查询20261105同项目同日COUNT(*)>1返回空集，表示这个样本日未发现重复项目，不代表源结构保证全部日期唯一。project及user及project_settings的关联字段均为主键，未见这些维表导致JOIN放大的结构风险；不据单日样本改写原报表公式。

## 5. 大屏对象（拟 bi_screen）

- 来源 easybi.d_data_screen；保留 14 列：id、name、title、description、last_heartbeat_time、editing_session、state、org_id、org_name、create_time、update_time、create_user_id、update_user_id、temp。
- 用户最终确认仅排除 global_setting、global_param，其他字段都保留。
- 原 DDL：id varchar(255) 主键；name/org_name varchar(50)，title/description/org_id/create_user_id/update_user_id varchar(255)，editing_session varchar(2000)，temp varchar(10)；时间 datetime，state int（0待发布、1已发布）。
- 源索引为 org_id+create_time、org_id+state。全项目全状态当前快照，类似旧 bi_report/bi_dashboard，不将心跳时间当访问事件。

## 6. 大屏访问日志（拟 bi_screen_visit_log）

- 来源 easybi.sys_big_screen_visit_log；10 列：id、screen_id、screen_name、operation、operator_id、operator_user_name、operator_name、project_Id、project_name、operate_time。
- 原 id bigint 自增主键；screen/用户/项目名称及用户ID等多为 varchar(255)，project_Id varchar(32)，operate_time datetime；分别有 project_Id、screen_id、operator_id 非唯一索引。
- ip 注释为 nginx 地址且不在业务清单中，暂不纳入；operation 具体取值待核对。
- screen_name 是访问时名称；project_Id 用户说明为访问用户所在项目，不能与大屏当前所属项目混为一谈。

## 已核验与下一步

- 用户 66 截图确认：四端点 TCP 可达；IAM、dataanalysis1、platform 登录返回 MySQL 5.7.28-log；Doris 兼容版本返回 5.7.99。
- 上述 9 张源表 SHOW FULL COLUMNS / SHOW INDEX 全部成功；容量连接使用 ssl_mode=REQUIRED 成功。初轮未查业务数据；后续用户在BI数据开发执行日期边界SELECT和Doris SHOW CREATE TABLE，未执行建表、导入或UPDATE。
- 用户回传租户优化 earliest_date=first_date_since_2026=2026-10-07、latest_date=2026-11-06。当前源表无法补齐2026-01-01至10-06；原因尚不清楚，不推断具体保留策略或未入库。
- 上述补充查询已回传，足够完成本地方案和隔离验证。66 待按指南核对真实源投影、首批导入和对账。
- 原七表任务绑定 easybi。六表通过独立配置和脚本运行，实施边界见 [ADR0142](../adr/0142-bi-multisource-independent-sync.md)。

## 方案设计依据

- 用户及大屏对象全量快照先完整拉取校验，再事务替换；保留目标旧快照直到成功。
- 无可靠源唯一键的页面点击按完整业务日抽取全部行，暂存校验后原子替换目标该日；不按四列或eid去重，重跑不叠加，提取失败不覆盖。目标可加独立技术行ID；时间、URL、NULL和真实重复行保持源语义。
- 容量/优化按日期整日刷新，保留原报表聚合和换算口径；大屏日志使用源id避免重复。近期回刷窗口、历史重跑及完成水位仍需在spec中明确；单日未见迟到不是无需处理迟到的证据。
- 推荐首次从要求下界2026-01-01与源可用范围的交集开始，并在记录中明确缺失历史；页面目前最早分区2026-08-08、优化最早数据2026-10-07。已保存目标历史不因源端滚动淘汰被删除。
- 容量报表按历史record_date连接当前配额、项目及负责人，不能将其误称严格的历史配额/组织快照；需在业务口径中明确，不私自改SQL。
