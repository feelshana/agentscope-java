"""Code-owned projections and MySQL 8 schema for the independent six-table mirror."""
from dataclasses import dataclass
from datetime import timedelta


def quote(name):
    if not name.replace("_", "").isalnum():
        raise ValueError("非法代码标识符")
    return chr(96) + name + chr(96)


@dataclass(frozen=True)
class Column:
    name: str
    sql_type: str
    comment: str
    required: bool = False
    generated: bool = False

    def ddl(self):
        return (quote(self.name) + " " + self.sql_type
                + (" NOT NULL" if self.required else " NULL")
                + (" AUTO_INCREMENT" if self.generated else "")
                + " COMMENT '" + self.comment.replace("'", "''") + "'")


@dataclass(frozen=True)
class Job:
    name: str
    source: str
    columns: tuple
    primary: tuple
    indexes: tuple
    comment: str
    date_column: str = ""
    snapshot: bool = False
    monitoring: bool = False

    @property
    def names(self):
        return tuple(c.name for c in self.columns if not c.generated)

    @property
    def stage(self):
        return "bi_extra_stage_" + self.name.removeprefix("bi_")

    def ddl(self, stage=False):
        parts = [c.ddl() for c in self.columns]
        parts.append("PRIMARY KEY (" + ",".join(map(quote, self.primary)) + ")")
        parts += ["KEY " + quote("idx_" + str(i)) + " ("
                  + ",".join(map(quote, columns)) + ")"
                  for i, columns in enumerate(self.indexes)]
        comment = ("内部暂存，不关联知识库；" if stage else "") + self.comment
        return ("CREATE TABLE IF NOT EXISTS " + quote(self.stage if stage else self.name)
                + " (\n  " + ",\n  ".join(parts)
                + "\n) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin"
                + " COMMENT='" + comment.replace("'", "''") + "'")


def col(name, kind, comment, required=False):
    return Column(name, kind, comment, required)


def technical_id():
    return Column("id", "bigint", "生成的技术行号，非源事件ID；重刷后可能变化", True, True)


JOBS = {
    "bi_page_click_log": Job(
        "bi_page_click_log", "click",
        (technical_id(), col("operator", "varchar(255)", "源用户账号原值，不裁剪空格"),
         col("event_time", "datetime", "操作时间，东八区", True),
         col("module_name", "varchar(255)", "模块名称"),
         col("url", "mediumtext", "URL_DECODE解码的页面URL；保留长URL")),
        ("id",), (("event_time",), ("operator", "event_time")),
        "原始点击，一源行一记录，保留完全相同的重复行", "event_time"),
    "bi_iam_user": Job(
        "bi_iam_user", "iam",
        (col("id", "bigint", "IAM用户ID，不假定等于BI人员ID", True),
         col("username", "varchar(50)", "用户姓名"),
         col("account_name", "varchar(50)", "登录账号；wx结尾为外协"),
         col("spms_account", "varchar(200)", "SPMS账号"),
         col("email", "varchar(200)", "邮箱"),
         col("phonenumber", "varchar(32)", "手机号；源bigint转文本，不恢复丢失的前导零"),
         col("coop_flag", "int", "源人员属性编码，不猜测含义"),
         col("undertaking_flag", "int", "承诺书：1已签署，0未签署"),
         col("order_type", "varchar(20)", "开户工单类型原值"),
         col("created_time", "datetime", "账号开通时间，东八区"),
         col("updated_time", "datetime", "更新时间，东八区"),
         col("jg_name", "varchar(512)", "所属公司；外协取责任人公司"),
         col("bm_name", "varchar(512)", "所属部门；外协取责任人部门"),
         col("post", "varchar(1000)", "责任人账号等于本人时的职位，否则空"),
         col("duty_person", "varchar(64)", "责任人姓名；自有人员为本人"),
         col("duty_person_account", "varchar(64)", "责任人账号"),
         col("wx_company_name", "varchar(512)", "外协公司名称"),
         col("oa_status", "int", "OA：10启用，20停用；无账号为空")),
        ("id",), (("account_name",), ("spms_account",)),
        "IAM当前全量用户，非历史组织快照", snapshot=True),
    "bi_tenant_optimization_daily": Job(
        "bi_tenant_optimization_daily", "optimization",
        (technical_id(), col("dayid", "date", "扫描日期，东八区", True),
         col("cluster_name", "varchar(200)", "集群"),
         col("project_name", "varchar(200)", "所属项目名称"),
         col("db_name", "varchar(200)", "库名"),
         col("table_name", "varchar(200)", "表名"),
         col("dir_name", "varchar(400)", "表路径"),
         col("owner", "varchar(200)", "建表账号"),
         col("update_user", "varchar(200)", "数据更新账号"),
         col("is_no_visited", "varchar(200)", "三个月未访问：yes是，no否"),
         col("is_no_compressed", "varchar(200)", "未压缩：yes是，no否"),
         col("is_small_file", "varchar(200)", "小文件：yes是，no否"),
         col("is_expire", "varchar(200)", "过期数据：yes是，no否"),
         col("total_size", "double", "总容量；TRUNCATE(SUM原值/1072741824,2)"),
         col("total_files", "bigint", "总文件数，个"),
         col("total_dirs", "bigint", "总目录数，个"),
         col("no_compress_size", "double", "未压缩容量；TRUNCATE(SUM原值/1072741824,2)"),
         col("no_compress_files", "bigint", "未压缩文件数，个"),
         col("small_file_size", "double", "小文件容量；TRUNCATE(SUM原值/1072741824,2)"),
         col("small_file_count", "bigint", "小文件数，个"),
         col("expire_size", "double", "过期容量；TRUNCATE(SUM原值/1072741824,2)"),
         col("expire_count", "bigint", "过期文件数，个")),
        ("id",), (("dayid",), ("owner", "dayid"), ("update_user", "dayid")),
        "租户扫描日快照，原报表聚合，无快照不代表零", "dayid", monitoring=True),
    "bi_project_capacity_daily": Job(
        "bi_project_capacity_daily", "capacity",
        (col("project_id", "bigint", "项目ID", True),
         col("project_name", "varchar(30)", "项目当前名称"),
         col("leader_name", "varchar(50)", "项目当前负责人"),
         col("record_date", "date", "源yyyyMMdd转DATE，排除空日期", True),
         col("total_storage_tb", "decimal(38,12)", "总容量；SUM配额字节/1099511627776"),
         col("used_storage_tb", "decimal(38,4)", "已用容量；ROUND(SUM字节/1099511627776,4)"),
         col("storage_usage_pct", "decimal(38,4)", "容量百分比数值；SUM(已用/配额)*100"),
         col("total_files_10k", "decimal(38,4)", "总文件数量；SUM配额/10000，万"),
         col("used_files_10k", "decimal(38,4)", "已用文件数量；ROUND(SUM数量/10000,4)，万"),
         col("file_usage_pct", "decimal(38,4)", "文件百分比数值；SUM(已用/配额)*100")),
        ("project_id", "record_date"), (("record_date",),),
        "项目容量日数据；当前配额及项目资料，非历史配额快照", "record_date", monitoring=True),
    "bi_screen": Job(
        "bi_screen", "easybi",
        (col("id", "varchar(255)", "大屏源主键", True),
         col("name", "varchar(50)", "大屏名称", True),
         col("title", "varchar(255)", "大屏标题"),
         col("description", "varchar(255)", "大屏描述"),
         col("last_heartbeat_time", "datetime", "配置页心跳，不是访问时间"),
         col("editing_session", "varchar(2000)", "编辑状态原值，不作认证凭据"),
         col("state", "int", "0待发布，1已发布；不默认过滤", True),
         col("org_id", "varchar(255)", "当前所属项目ID", True),
         col("org_name", "varchar(50)", "当前所属项目名称", True),
         col("create_time", "datetime", "创建时间，东八区", True),
         col("update_time", "datetime", "更新时间，东八区"),
         col("create_user_id", "varchar(255)", "创建人ID", True),
         col("update_user_id", "varchar(255)", "更新人ID", True),
         col("temp", "varchar(10)", "临时保存标记原值")),
        ("id",), (("org_id", "create_time"), ("org_id", "state")),
        "大屏当前全量，仅排除两个global配置字段", snapshot=True),
    "bi_screen_visit_log": Job(
        "bi_screen_visit_log", "easybi",
        (col("id", "bigint", "源访问记录主键，保留原ID", True),
         col("screen_id", "varchar(255)", "大屏ID"),
         col("screen_name", "varchar(255)", "访问时大屏名称"),
         col("operation", "varchar(255)", "行为原值，不筛选"),
         col("operator_id", "varchar(255)", "访问用户ID"),
         col("operator_user_name", "varchar(255)", "访问用户账号"),
         col("operator_name", "varchar(255)", "访问用户姓名"),
         col("project_id", "varchar(32)", "访问用户所在项目ID，非大屏所属项目"),
         col("project_name", "varchar(255)", "访问用户所在项目名称"),
         col("operate_time", "datetime", "日志时间，东八区", True)),
        ("id",), (("operate_time",), ("screen_id", "operate_time"),
                  ("operator_user_name", "operate_time")),
        "大屏原始访问行为，全部类型，排除nginx IP", "operate_time"),
}
SNAPSHOTS = tuple(j.name for j in JOBS.values() if j.snapshot)
DATED = tuple(j.name for j in JOBS.values() if not j.snapshot)
REQUIRED = {
    "iam": {
        "user": ("id", "username", "account_name", "spms_account", "email", "phonenumber",
                 "coop_flag", "undertaking_flag", "order_type", "created_time", "updated_time"),
        "duty_person_info": ("id", "jg_name", "bm_name", "duty_person_posts",
                             "duty_person", "duty_person_account", "wx_company_name"),
        "oa_person": ("account_name", "zhzt")},
    "capacity": {
        "project_storage": ("project_id", "record_date", "storage", "file_num"),
        "project": ("id", "name", "leader_id"), "user": ("id", "username"),
        "project_settings": ("project_id", "storage", "inodes")},
    "optimization": {"cluster_data_to_be_optimized": JOBS["bi_tenant_optimization_daily"].names},
    "click": {"operation_log_detail": ("operator", "event_time", "module_name", "url")},
    "easybi": {
        "d_data_screen": JOBS["bi_screen"].names,
        "sys_big_screen_visit_log": tuple(
            "project_Id" if n == "project_id" else n for n in JOBS["bi_screen_visit_log"].names)},
}


def projection(job, day=None):
    """Return fixed SQL with parameterized date bounds, never arbitrary configuration SQL."""
    bounds = () if day is None else (str(day) + " 00:00:00",
                                    str(day + timedelta(days=1)) + " 00:00:00")
    if job.name == "bi_page_click_log":
        return ("SELECT operator,event_time,module_name,URL_DECODE(url) AS url"
                " FROM operation_log_detail WHERE event_time >= %s AND event_time < %s", bounds)
    if job.name == "bi_iam_user":
        return ("""SELECT u.id,u.username,u.account_name,u.spms_account,u.email,
            CAST(u.phonenumber AS CHAR),u.coop_flag,u.undertaking_flag,u.order_type,
            u.created_time,u.updated_time,d.jg_name,d.bm_name,
            CASE WHEN d.duty_person_account=u.account_name THEN d.duty_person_posts END,
            d.duty_person,d.duty_person_account,d.wx_company_name,o.zhzt
            FROM user u LEFT JOIN duty_person_info d ON d.id=u.id
            LEFT JOIN oa_person o ON o.account_name=u.account_name""", ())
    if job.name == "bi_tenant_optimization_daily":
        dimensions = job.names[:12]
        return ("SELECT " + ",".join(map(quote, dimensions))
                + ",TRUNCATE(SUM(total_size)/1072741824,2),SUM(total_files),SUM(total_dirs)"
                + ",TRUNCATE(SUM(no_compress_size)/1072741824,2),SUM(no_compress_files)"
                + ",TRUNCATE(SUM(small_file_size)/1072741824,2),SUM(small_file_count)"
                + ",TRUNCATE(SUM(expire_size)/1072741824,2),SUM(expire_count)"
                + " FROM cluster_data_to_be_optimized WHERE dayid=%s GROUP BY "
                + ",".join(map(quote, dimensions)), (day,))
    if job.name == "bi_project_capacity_daily":
        return ("""SELECT s.project_id,p.name,u.username,s.record_date,
            SUM(q.storage)/1099511627776,ROUND(SUM(s.storage)/1099511627776,4),
            ROUND(SUM(s.storage/q.storage)*100.0,4),SUM(q.inodes)/10000.0,
            ROUND(SUM(s.file_num)/10000.0,4),ROUND(SUM(s.file_num/q.inodes)*100.0,4)
            FROM project_storage s LEFT JOIN project p ON s.project_id=p.id
            LEFT JOIN user u ON p.leader_id=u.id
            LEFT JOIN project_settings q ON s.project_id=q.project_id
            WHERE s.record_date=%s GROUP BY s.project_id,p.name,u.username,s.record_date""",
                (day.strftime("%Y%m%d"),))
    if job.name == "bi_screen":
        return "SELECT " + ",".join(map(quote, job.names)) + " FROM d_data_screen", ()
    return ("SELECT " + ",".join(quote("project_Id") if n == "project_id" else quote(n)
                               for n in job.names)
            + " FROM sys_big_screen_visit_log WHERE operate_time >= %s AND operate_time < %s", bounds)
