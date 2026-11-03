"""Bounded, restartable BI mirror. The source connection is always read-only."""
import argparse
from datetime import date, datetime, time, timedelta, timezone
import hashlib
import json
import os
from pathlib import Path
import stat
import sys
import time as clock
import uuid

OWNER = "dataagent-bi-sync-v2-basic"
CONTROL = "bi_sync_control"
TZ = timezone(timedelta(hours=8))
# All identifiers and types are code-owned. Config files cannot add tables or arbitrary SQL.
TABLES = {
    "sys_project": ("bi_project", "id:varchar(32),name:varchar(255),leader:varchar(255)"),
    "sys_project_operator_relation": (
        "bi_project_operator", "id:varchar(32),operatorid:varchar(32),projectid:varchar(32),createtime:datetime"),
    "sys_operator": ("bi_operator", "id:varchar(36),loginname:varchar(100),name:varchar(36),"
                     "bak_name:varchar(20),type:int,createtime:datetime,source:int,"
                     "lastloginprojectid:varchar(32),spmsaccount:varchar(50),firstloginflag:int,"
                     "state:int,passwordlastupdate:datetime,company:varchar(50),dept:varchar(50),"
                     "tip_count:int,select_area_tip_count:int,user_type:int,posts:varchar(512)"),
    "d_report": ("bi_report", "id:varchar(36),name:varchar(100),org_id:varchar(32)"),
    "d_dashboard": ("bi_dashboard", "id:varchar(36),name:varchar(100),org_id:varchar(32)"),
    "d_report_log": ("bi_report_log", "id:varchar(32),log_time:datetime,report_id:varchar(32),"
                     "operator_id:varchar(32)"),
    "d_dashboard_log": ("bi_dashboard_log", "id:varchar(32),log_time:datetime,dashboard_id:varchar(32),"
                        "operator_id:varchar(32)"),
}
DIMENSIONS = tuple(TABLES)[:5]
LOGS = tuple(TABLES)[5:]
LOG_PROJECTS = {"d_report_log": "report_project_id", "d_dashboard_log": "dashboard_project_id"}
TABLE_COMMENTS = {
    "sys_project": "BI项目当前信息维表，一行一个项目",
    "sys_project_operator_relation": "BI项目与人员关系桥表，一人可属于多个项目",
    "sys_operator": "BI人员当前信息维表，排除密码电话邮箱，不含历史组织快照",
    "d_report": "BI报表当前信息维表，一行一个报表，不是查看事件",
    "d_dashboard": "BI仪表盘当前信息维表，一行一个仪表盘，不是查看事件",
    "d_report_log": "BI报表操作记录，保留所有类型，不可区分查看和导出",
    "d_dashboard_log": "BI仪表盘操作记录，保留所有类型，不可区分查看和导出",
}
FIELD_COMMENTS = {
    "id": "本表主键，字符串编号，全表唯一，不代表时间顺序",
    "leader": "项目负责人原值，编码及多值语义尚未确认，不自动当人员编号",
    "operatorid": "关系中的人员编号，对应人员表id",
    "projectid": "关系中的项目编号，对应项目表id",
    "createtime": "建档时间，东八区",
    "loginname": "BI登录账号",
    "bak_name": "备用姓名，保留源值",
    "type": "源人员类型编码，业务含义待确认",
    "source": "源账号来源编码，业务含义待确认",
    "lastloginprojectid": "最近登录项目编号，不代表全部项目成员关系",
    "spmsaccount": "源SPMS账号，保留源值",
    "firstloginflag": "源首次登录标记，保留源值",
    "passwordlastupdate": "密码最近更新时间，不含密码内容",
    "tip_count": "源提示点击计数，不是BI操作次数",
    "select_area_tip_count": "源查询区域提示计数，不是BI操作次数",
    "company": "人员当前公司，非历史快照",
    "dept": "人员当前部门，非历史快照",
    "posts": "人员当前职务",
    "user_type": "人员类型：1自有人员，2外协，保留源值",
    "org_id": "对象当前所属项目编号，对应项目表id",
    "log_time": "事件发生时间，东八区，用于日周月统计",
    "report_id": "报表编号，对应报表表id",
    "dashboard_id": "仪表盘编号，对应仪表盘表id",
    "operator_id": "事件操作人员编号，对应人员表id",
}


def columns(source):
    return [item.split(":")[0] for item in TABLES[source][1].split(",")]


def quote(value):
    # Every caller must use constants or a validated column name from TABLES.
    return "`" + value + "`"


def scalar(connection, sql, args=()):
    with connection.cursor() as cursor:
        cursor.execute(sql, args)
        row = cursor.fetchone()
        return None if row is None else row[0]


def load_config(path):
    path = Path(path)
    if path.is_symlink() or not path.is_file():
        raise ValueError("私有配置必须是普通文件")
    if os.name == "posix" and stat.S_IMODE(path.stat().st_mode) & 0o077:
        raise ValueError("私有配置必须禁止组和其他用户读取（chmod 600）")
    config = json.loads(path.read_text(encoding="utf-8"))
    if config.get("version") != 1 or config.get("start_date") != "2026-01-01":
        raise ValueError("配置版本或历史起点不匹配")
    batch = config.get("batch_size", 1000)
    if type(batch) is not int or not 1 <= batch <= 5000:
        raise ValueError("批次大小只允许1至5000")
    for side, database in (("source", "easybi"), ("target", "data_agent")):
        endpoint = config.get(side, {})
        if endpoint.get("database") != database:
            raise ValueError("只允许既定源库easybi和目标库data_agent")
        if not isinstance(endpoint.get("port"), int) or not 1 <= endpoint["port"] <= 65535:
            raise ValueError("端口无效")
        for field in ("host", "user", "password"):
            if not isinstance(endpoint.get(field), str) or not endpoint[field]:
                raise ValueError("连接字段不完整")
    if ((config["source"]["host"], config["source"]["port"])
            == (config["target"]["host"], config["target"]["port"])):
        raise ValueError("源和目标不能是同一数据库服务")
    return config


def connect(endpoint, readonly=False):
    import MySQLdb
    con = MySQLdb.connect(host=endpoint["host"], port=endpoint["port"],
                          db=endpoint["database"], user=endpoint["user"],
                          passwd=endpoint["password"], charset="utf8mb4",
                          connect_timeout=10, read_timeout=120, write_timeout=120)
    with con.cursor() as cursor:
        cursor.execute("SET time_zone = '+08:00'")
        if readonly:
            cursor.execute("SET SESSION TRANSACTION ISOLATION LEVEL REPEATABLE READ")
            cursor.execute("SET SESSION TRANSACTION READ ONLY")
            cursor.execute("START TRANSACTION WITH CONSISTENT SNAPSHOT")
    return con


def source_identity(config):
    ep = config["source"]
    return hashlib.sha256(json.dumps([ep["host"], ep["port"], ep["database"]]).encode()).hexdigest()


def control_exists(target):
    return scalar(target, "SELECT COUNT(*) FROM information_schema.tables "
                  "WHERE table_schema=DATABASE() AND table_name=%s", (CONTROL,)) == 1


def state(target, key):
    return scalar(target, "SELECT value FROM bi_sync_control WHERE name=%s", (key,))


def save_state(target, key, value):
    with target.cursor() as cursor:
        cursor.execute("INSERT INTO bi_sync_control(name,value) VALUES(%s,%s) "
                       "ON DUPLICATE KEY UPDATE value=VALUES(value)", (key, value))


def require_owner(target, config):
    if not control_exists(target) or state(target, "owner") != OWNER:
        raise ValueError("同步表尚未初始化或不属于本任务，拒绝写入")
    if state(target, "source") != source_identity(config):
        raise ValueError("既有任务的源地址发生变化，拒绝复用水位")


def ddl(source):
    target, spec = TABLES[source]
    fields = []
    for name, kind in (x.split(":") for x in spec.split(",")):
        comment = FIELD_COMMENTS.get(name, "")
        if name == "name":
            comment = {"sys_project": "项目名称", "sys_operator": "人员当前显示姓名，禁用账号可能显示编号",
                       "d_report": "报表名称，可能重名，以id区分", "d_dashboard": "仪表盘名称，可能重名，以id区分"}[source]
        if name == "state":
            comment = "人员状态，保留源值，不自动过滤" if source == "sys_operator" else "对象状态：0待发布，1已发布，不自动过滤"
        fields.append(quote(name) + " " + kind + (" NOT NULL" if name == "id" else " NULL")
                      + " COMMENT '" + comment.replace("'", "''") + "'")
    fields.append("PRIMARY KEY (`id`)")
    if source in LOGS:
        obj = "report_id" if source == "d_report_log" else "dashboard_id"
        fields += ["KEY ix_time (`log_time`,`id`)", "KEY ix_object (" + quote(obj) + ")",
                   "KEY ix_operator (`operator_id`)"]
    elif "org_id" in columns(source):
        fields.append("KEY ix_project (`org_id`)")
    elif source == "sys_project_operator_relation":
        fields += ["KEY ix_project_operator (`projectid`,`operatorid`)",
                   "KEY ix_operator_project (`operatorid`,`projectid`)"]
    return ("CREATE TABLE IF NOT EXISTS " + quote(target) + " (" + ",".join(fields)
            + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='"
            + TABLE_COMMENTS[source] + "'")


def check_schema(target):
    for source, (table, spec) in TABLES.items():
        with target.cursor() as cursor:
            cursor.execute("SHOW COLUMNS FROM " + quote(table))
            actual = cursor.fetchall()
        expected = [item.split(":") for item in spec.split(",")]
        normalized = [(r[0].lower(), r[1].lower().replace("int(11)", "int")) for r in actual]
        if normalized != [(name, kind) for name, kind in expected] or actual[0][3] != "PRI":
            raise ValueError("目标表结构与同步版本不一致：" + table)


def initialize(target, config):
    owned = control_exists(target)
    if owned:
        require_owner(target, config)
    else:
        with target.cursor() as cursor:
            cursor.execute("SELECT table_name FROM information_schema.tables WHERE table_schema=DATABASE()")
            existing = {row[0] for row in cursor.fetchall()}
        if existing.intersection({v[0] for v in TABLES.values()}):
            raise ValueError("存在未登记的同名业务表，拒绝覆盖")
        with target.cursor() as cursor:
            cursor.execute("CREATE TABLE bi_sync_control (name varchar(64) PRIMARY KEY,"
                           "value text NOT NULL) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4")
        save_state(target, "owner", OWNER)
        save_state(target, "source", source_identity(config))
        target.commit()
    for source in TABLES:
        with target.cursor() as cursor:
            cursor.execute(ddl(source))
    check_schema(target)
    print("已准备7张镜像表和1张控制表；未复制业务数据", flush=True)


def inspect_source(source):
    for table in TABLES:
        with source.cursor() as cursor:
            cursor.execute("SHOW COLUMNS FROM " + quote(table))
            names = {row[0].lower() for row in cursor.fetchall()}
        required = set(columns(table)) | ({LOG_PROJECTS[table]} if table in LOGS else set())
        if not required.issubset(names):
            raise ValueError("源表缺少本版字段：" + table)
    for table in LOGS:
        with source.cursor() as cursor:
            cursor.execute("SHOW INDEX FROM " + quote(table))
            indexes = {}
            for row in cursor.fetchall():
                indexes.setdefault(row[2], {})[row[3]] = (row[4] or "").lower()
        if not any([parts.get(1), parts.get(2)] == [LOG_PROJECTS[table], "log_time"]
                   for parts in indexes.values()):
            raise ValueError("源表缺少既定项目+时间索引，请核对，不自动建索引：" + table)


def write_rows(target, source, rows, batch):
    if not rows:
        return
    names = columns(source)
    table = TABLES[source][0]
    sql = ("INSERT INTO " + quote(table) + " (" + ",".join(map(quote, names)) + ") VALUES ("
           + ",".join(["%s"] * len(names)) + ") ON DUPLICATE KEY UPDATE "
           + ",".join(quote(n) + "=VALUES(" + quote(n) + ")" for n in names if n != "id"))
    with target.cursor() as cursor:
        for offset in range(0, len(rows), batch):
            cursor.executemany(sql, rows[offset:offset + batch])


class SyncPaused(Exception):
    """Cooperative stop at a transaction boundary, not a data failure."""


def noop():
    pass


def refresh_dimensions(source, target, batch, checkpoint=noop):
    # Fetch every bounded snapshot before any target mutation, then replace all five atomically.
    snapshots = {}
    for name in DIMENSIONS:
        checkpoint()
        with source.cursor() as cursor:
            cursor.execute("SELECT " + ",".join(map(quote, columns(name))) + " FROM "
                           + quote(name) + " ORDER BY id LIMIT 100001")
            snapshots[name] = cursor.fetchall()
        if len(snapshots[name]) > 100000:
            raise ValueError("维表超过本版全量上限，未修改维表：" + name)
    target.begin()
    try:
        for name, rows in snapshots.items():
            checkpoint()
            table = TABLES[name][0]
            with target.cursor() as cursor:
                cursor.execute("DELETE FROM " + quote(table))
            write_rows(target, name, rows, batch)
            if scalar(target, "SELECT COUNT(*) FROM " + quote(table)) != len(rows):
                raise ValueError("维表计数不一致：" + table)
        checkpoint()
        target.commit()
    except BaseException:
        target.rollback()
        raise
    print("5张维表事务刷新成功", flush=True)


def bounds(start, until):
    return datetime.combine(start, time()), datetime.combine(until, time())


def log_tag(name):
    return "r" if name == "d_report_log" else "d"


def project_key(name, project):
    encoded = json.dumps(project, ensure_ascii=False).encode()
    return "p" + log_tag(name) + ":" + hashlib.sha256(encoded).hexdigest()[:60]


def projects(source, name):
    # Enumerate the log index, not today's project dimension: deleted projects must survive.
    column = quote(LOG_PROJECTS[name])
    with source.cursor() as cursor:
        cursor.execute("SELECT DISTINCT " + column + " FROM " + quote(name)
                       + " ORDER BY " + column + " LIMIT 100001")
        result = [row[0] for row in cursor.fetchall()]
    if len(result) > 100000:
        raise ValueError("日志项目数超过本版上限，未开始该表同步：" + name)
    return result


def project_lower(target, name, project, start):
    raw = state(target, project_key(name, project))
    if raw is None:
        return start
    record = json.loads(raw)
    if record.get("project") != project:
        raise ValueError("项目进度标识冲突，拒绝复用")
    lower = date.fromisoformat(record["until"])
    if lower < start:
        raise ValueError("项目进度早于允许的历史起点")
    return lower


def save_project(target, name, project, until):
    save_state(target, project_key(name, project),
               json.dumps(dict(project=project, until=until.isoformat()), ensure_ascii=False))


def project_where(column, project):
    return ((quote(column) + " IS NULL", ()) if project is None
            else (quote(column) + " = %s", (project,)))


def daily_counts(connection, table, start, until, project_column=None, project=None):
    if table in LOGS and project_column != LOG_PROJECTS[table]:
        raise ValueError("源日志计数必须按既有项目索引分段")
    predicate, args = project_where(project_column, project) if project_column else ("1=1", ())
    with connection.cursor() as cursor:
        cursor.execute("SELECT DATE(log_time),COUNT(*) FROM " + quote(table)
                       + " WHERE " + predicate
                       + " AND log_time >= %s AND log_time < %s GROUP BY DATE(log_time)",
                       args + bounds(start, until))
        return {row[0]: row[1] for row in cursor.fetchall()}


def journal_prefix(name):
    return "c" + log_tag(name) + ":"


def journal_counts(target, name, start, until):
    prefix = journal_prefix(name)
    with target.cursor() as cursor:
        cursor.execute("SELECT name,value FROM bi_sync_control WHERE name >= %s AND name < %s",
                       (prefix + start.isoformat(), prefix + until.isoformat()))
        rows = cursor.fetchall()
    result = {}
    for key, value in rows:
        count = int(value)
        if count < 0:
            raise ValueError("同步计数账本非法")
        if count:
            result[date.fromisoformat(key[len(prefix):])] = count
    return result


def sync_project(source, target, name, project, start, until, batch, checkpoint=noop):
    import MySQLdb.cursors
    expected = daily_counts(source, name, start, until, LOG_PROJECTS[name], project)
    if not expected:
        checkpoint()
        save_project(target, name, project, until)
        target.commit()
        return
    names = columns(name)
    date_index = names.index("log_time")
    predicate, args = project_where(LOG_PROJECTS[name], project)
    cursor = source.cursor(MySQLdb.cursors.SSCursor)
    cursor.execute("SELECT " + ",".join(map(quote, names)) + " FROM " + quote(name)
                   + " WHERE " + predicate + " AND log_time >= %s AND log_time < %s"
                   + " ORDER BY log_time,id", args + bounds(start, until))
    day = start
    count = 0
    pending = []
    target.begin()

    def finish_day():
        nonlocal count, pending
        checkpoint()
        write_rows(target, name, pending, batch)
        pending = []
        if count != expected.get(day, 0):
            raise ValueError("项目日志逐日计数不一致：" + name + "/" + day.isoformat())
        if count == 0:
            return  # Empty days need no separate transaction per project.
        key = journal_prefix(name) + day.isoformat()
        save_state(target, key, str(int(state(target, key) or "0") + count))
        save_project(target, name, project, day + timedelta(days=1))
        checkpoint()
        target.commit()
        target.begin()
        count = 0

    try:
        while True:
            rows = cursor.fetchmany(batch)
            if not rows:
                break
            for row in rows:
                event_day = row[date_index].date()
                if event_day < day or event_day >= until:
                    raise ValueError("日志排序或日期范围异常")
                while event_day > day:
                    finish_day()
                    day += timedelta(days=1)
                pending.append(row)
                count += 1
                if len(pending) >= batch:
                    checkpoint()
                    write_rows(target, name, pending, batch)
                    pending = []
        while day < until:
            finish_day()
            day += timedelta(days=1)
        checkpoint()
        save_project(target, name, project, until)
        target.commit()
    except BaseException:
        target.rollback()
        # Abort the stream rather than draining the remaining source data on failure.
        source.close()
        raise
    finally:
        try:
            cursor.close()
        except Exception:
            pass


def sync_log(source, target, name, start, until, batch, checkpoint=noop):
    checkpoint()
    table = TABLES[name][0]
    previous = state(target, table)
    table_lower = date.fromisoformat(previous) if previous else start
    if table_lower > until:
        raise ValueError("请求截止日早于已完成水位，拒绝回退")
    if table_lower == until:
        return
    project_list = projects(source, name)
    for position, project in enumerate(project_list, 1):
        checkpoint()
        lower = project_lower(target, name, project, start)
        if lower > until:
            raise ValueError("请求截止日早于项目已提交进度，拒绝回退")
        if lower < until:
            sync_project(source, target, name, project, lower, until, batch, checkpoint)
        if position == 1 or position % 25 == 0 or position == len(project_list):
            print(table + "已完成项目分段" + str(position) + "/" + str(len(project_list)), flush=True)
    # The target has only four public fields. The private count journal and project
    # checkpoints commit with writes; compare their daily totals before advancing the table.
    if journal_counts(target, name, table_lower, until) != daily_counts(target, table, table_lower, until):
        raise ValueError("日志目标与提交账本逐日计数不一致：" + table)
    checkpoint()
    save_state(target, table, until.isoformat())
    target.commit()
    print(table + "项目分段核对成功（项目数" + str(len(project_list)) + "），截至"
          + (until - timedelta(days=1)).isoformat(), flush=True)


def verify(source, target, start, until):
    for name in DIMENSIONS:
        a = scalar(source, "SELECT COUNT(*) FROM " + quote(name))
        b = scalar(target, "SELECT COUNT(*) FROM " + quote(TABLES[name][0]))
        if a != b:
            raise ValueError("维表源/目标计数不符：" + name)
        print(TABLES[name][0] + "计数核对通过：" + str(b), flush=True)
    for name in LOGS:
        expected = {}
        for project in projects(source, name):
            for day, count in daily_counts(source, name, start, until, LOG_PROJECTS[name], project).items():
                expected[day] = expected.get(day, 0) + count
        if expected != daily_counts(target, TABLES[name][0], start, until):
            raise ValueError("日志源/目标逐日计数不符：" + name)
        print(TABLES[name][0] + "逐日计数核对通过", flush=True)


def run(config, action, until, connect_fn=connect):
    source = target = monitor = None
    locked = False
    run_info = None
    phase = "START"
    started_clock = clock.monotonic()

    def checkpoint():
        # Autocommit observer reads current state, outside the data transaction's snapshot.
        if state(monitor, "paused") == "true":
            raise SyncPaused()

    def record_result(outcome):
        finished = datetime.now(timezone.utc).isoformat()
        result = dict(run_info, outcome=outcome, finished_at=finished,
                      duration_ms=max(0, round((clock.monotonic() - started_clock) * 1000)),
                      failure_phase=phase if outcome in ("FAILED", "INTERRUPTED") else None)
        monitor.begin()
        save_state(monitor, "last_run", json.dumps(result))
        if outcome == "SUCCESS":
            save_state(monitor, "last_success_at", finished)
        monitor.commit()

    try:
        target = connect_fn(config["target"], readonly=action in ("check", "status", "verify"))
        if int(scalar(target, "SELECT VERSION()").split(".")[0]) < 8:
            raise ValueError("同步目标必须为MySQL8及以上")
        if action in ("status", "pause", "resume"):
            require_owner(target, config)
            check_schema(target)
            if action in ("pause", "resume"):
                save_state(target, "paused", "true" if action == "pause" else "false")
                target.commit()
                print("已暂停后续同步；运行中的任务将在安全点退出，请用status确认running=false"
                      if action == "pause" else "已允许同步；未自动运行或安装定时任务", flush=True)
            else:
                view = {k: state(target, k) for k in ["success_until", "last_success_at"]
                        + [TABLES[n][0] for n in LOGS]}
                view.update(paused=state(target, "paused") == "true",
                            running=scalar(target, "SELECT IS_USED_LOCK('dataagent-bi-sync-v1')") is not None,
                            last_run=json.loads(state(target, "last_run") or "null"))
                print(json.dumps(view, ensure_ascii=False), flush=True)
            return
        if action == "check":
            source = connect_fn(config["source"], readonly=True)
            inspect_source(source)
            print("两端连接/源字段通过；不写数据库，不代表全量同步已完成", flush=True)
            return
        if action in ("init", "sync"):
            if scalar(target, "SELECT GET_LOCK('dataagent-bi-sync-v1',0)") != 1:
                raise ValueError("已有同步任务运行，本次退出")
            locked = True
        if action == "init":
            source = connect_fn(config["source"], readonly=True)
            inspect_source(source)
            initialize(target, config)
            return
        require_owner(target, config)
        check_schema(target)
        start = date.fromisoformat(config["start_date"])
        if action == "verify":
            source = connect_fn(config["source"], readonly=True)
            inspect_source(source)
            verify(source, target, start, until)
            return
        monitor = connect_fn(config["target"], readonly=False)
        monitor.autocommit(True)
        if state(monitor, "paused") == "true":
            print("同步已暂停，本次跳过；未连接BI源库", flush=True)
            return
        started_clock = clock.monotonic()
        run_info = dict(id=uuid.uuid4().hex, outcome="RUNNING",
                        started_at=datetime.now(timezone.utc).isoformat(),
                        finished_at=None, duration_ms=None, failure_phase=None)
        save_state(monitor, "last_run", json.dumps(run_info))
        phase = "SOURCE_CONNECT"
        source = connect_fn(config["source"], readonly=True)
        phase = "SOURCE_SCHEMA"
        inspect_source(source)
        checkpoint()
        phase = "DIMENSIONS"
        refresh_dimensions(source, target, config.get("batch_size", 1000), checkpoint)
        for name in LOGS:
            phase = "REPORT_LOG" if name == "d_report_log" else "DASHBOARD_LOG"
            sync_log(source, target, name, start, until, config.get("batch_size", 1000), checkpoint)
        phase = "FINALIZE"
        checkpoint()
        save_state(target, "success_until", until.isoformat())
        target.commit()
        record_result("SUCCESS")
        print("7表全部同步成功，完整数据截止至" + (until - timedelta(days=1)).isoformat(), flush=True)
    except SyncPaused:
        if target is not None:
            target.rollback()
        if run_info is not None:
            record_result("PAUSED")
        print("同步已在安全点暂停，未提交部分已回滚，已提交进度保留", flush=True)
    except BaseException as error:
        if target is not None:
            target.rollback()
        if monitor is not None and run_info is not None:
            try:
                monitor.rollback()
                record_result("FAILED" if isinstance(error, Exception) else "INTERRUPTED")
            except Exception:
                print("运行状态未能写入，请核对数据库及同步截止日", file=sys.stderr)
        raise
    finally:
        if target is not None:
            try:
                target.rollback()
                if locked:
                    scalar(target, "SELECT RELEASE_LOCK('dataagent-bi-sync-v1')")
            finally:
                target.close()
        if source is not None:
            try:
                source.close()
            except Exception:
                pass
        if monitor is not None:
            monitor.close()


def main():
    parser = argparse.ArgumentParser(description="BI7表镜像：只读源，固定目标，幂等和逐日水位")
    parser.add_argument("action", choices=["check", "init", "sync", "verify", "status", "pause", "resume"])
    parser.add_argument("--config", type=Path, required=True)
    parser.add_argument("--until", type=date.fromisoformat,
                        default=datetime.now(TZ).date(), help="截止日不包含当天，默认东八区今天")
    args = parser.parse_args()
    if not date(2026, 1, 1) < args.until <= datetime.now(TZ).date():
        parser.error("截止日须晚于2026-01-01且不晚于东八区今天")
    try:
        run(load_config(args.config), args.action, args.until)
    except Exception as error:
        # Never log driver strings, SQL argument values or private configuration.
        safe = str(error) if isinstance(error, ValueError) else type(error).__name__
        print("同步未完成：" + safe + "；没有标记整批成功，请核对当前表水位", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
