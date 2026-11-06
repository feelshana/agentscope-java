"""Independent, fixed-projection BI extension. Never modifies the original seven-table task."""
import argparse
from contextlib import ExitStack
from datetime import date, datetime, timedelta, timezone
from decimal import Decimal
import hashlib
import json
from pathlib import Path
import re
import stat
import os
import sys
import uuid

import bi_sync
from extra_schema import JOBS, DATED, REQUIRED, projection, quote

OWNER = "dataagent-bi-extra-v1"
CONTROL = "bi_extra_sync_control"
LOCK = "dataagent-bi-extra-sync-v1"
START = date(2026, 1, 1)
TZ = timezone(timedelta(hours=8))
CONTROL_DDL = ("CREATE TABLE IF NOT EXISTS " + quote(CONTROL)
               + " (name varchar(190) NOT NULL PRIMARY KEY,value longtext NOT NULL)"
               + " ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin")
SOURCE_DATABASES = {"iam": "iam", "capacity": "dataanalysis1",
                    "optimization": "platform", "click": "fenxiyun"}


class Paused(Exception):
    pass


def scalar(con, sql, args=()):
    with con.cursor() as cur:
        cur.execute(sql, args)
        row = cur.fetchone()
        return row[0] if row else None


def private_json(path):
    path = Path(path)
    if path.is_symlink() or not path.is_file():
        raise ValueError("私有配置必须是普通文件")
    if os.name == "posix" and stat.S_IMODE(path.stat().st_mode) & 0o077:
        raise ValueError("私有配置必须为600权限")
    return json.loads(path.read_text(encoding="utf-8"))


def load_config(path, base_path):
    raw = private_json(path)
    base = bi_sync.load_config(base_path)
    if set(raw) - {"version", "batch_size", "lookback_days", "sources"} or raw.get("version") != 1:
        raise ValueError("扩展配置版本或字段不匹配")
    sources = raw.get("sources", {})
    if set(sources) != set(SOURCE_DATABASES):
        raise ValueError("需要四个既定源连接")
    for key, database in SOURCE_DATABASES.items():
        ep = sources[key]
        if set(ep) != {"host", "port", "database", "user", "password"} or ep.get("database") != database:
            raise ValueError("源库或配置字段不匹配")
        if type(ep["port"]) is not int or not 1 <= ep["port"] <= 65535:
            raise ValueError("源端口无效")
        if any(not isinstance(ep[n], str) or not ep[n]
               or any(c in ep[n] for c in "\x00\r\n") for n in ("host", "user", "password")):
            raise ValueError("源连接字段无效")
    batch, lookback = raw.get("batch_size", 128), raw.get("lookback_days", 3)
    if type(batch) is not int or not 1 <= batch <= 1000:
        raise ValueError("批次须为1至1000")
    if type(lookback) is not int or not 1 <= lookback <= 31:
        raise ValueError("近期回刷须为1至31天")
    sources = dict(sources, easybi=base["source"])
    if any((e["host"], e["port"]) == (base["target"]["host"], base["target"]["port"])
           for e in sources.values()):
        raise ValueError("源不能与目标使用同一数据库服务")
    return dict(sources=sources, target=base["target"], batch_size=batch, lookback_days=lookback)


def fingerprint(config):
    identities = {k: [v["host"], v["port"], v["database"]] for k, v in config["sources"].items()}
    identities["_target"] = [config["target"][n] for n in ("host", "port", "database")]
    return hashlib.sha256(json.dumps(identities, sort_keys=True).encode()).hexdigest()


def connect(endpoint, role):
    import MySQLdb
    opts = dict(host=endpoint["host"], port=endpoint["port"], db=endpoint["database"],
                user=endpoint["user"], passwd=endpoint["password"], charset="utf8mb4",
                connect_timeout=10, read_timeout=120, write_timeout=120)
    if role == "capacity":
        opts["ssl_mode"] = "REQUIRED"
    con = MySQLdb.connect(**opts)
    try:
        with con.cursor() as cur:
            cur.execute("SET time_zone = '+08:00'")
            if role in ("target", "monitor"):
                cur.execute("SET SESSION sql_mode="
                            "'STRICT_ALL_TABLES,NO_ZERO_DATE,NO_ZERO_IN_DATE,"
                            "ERROR_FOR_DIVISION_BY_ZERO,NO_ENGINE_SUBSTITUTION'")
            elif role != "click":
                cur.execute("SET SESSION TRANSACTION ISOLATION LEVEL REPEATABLE READ")
                cur.execute("SET SESSION TRANSACTION READ ONLY")
        con.autocommit(role in ("click", "monitor"))
        return con
    except BaseException:
        con.close()
        raise


def state(con, key, default=None):
    value = scalar(con, "SELECT value FROM " + quote(CONTROL) + " WHERE name=%s", (key,))
    return default if value is None else json.loads(value)


def save(con, key, value):
    with con.cursor() as cur:
        cur.execute("INSERT INTO " + quote(CONTROL) + " (name,value) VALUES(%s,%s)"
                    " ON DUPLICATE KEY UPDATE value=VALUES(value)",
                    (key, json.dumps(value, ensure_ascii=False, sort_keys=True)))


def table_exists(con, name):
    return bool(scalar(con, "SELECT COUNT(*) FROM information_schema.tables"
                      " WHERE table_schema=DATABASE() AND table_name=%s", (name,)))


def require_owner(con, config):
    if not table_exists(con, CONTROL) or state(con, "owner") != OWNER:
        raise ValueError("新任务未初始化或归属不匹配")
    if state(con, "sources") != fingerprint(config):
        raise ValueError("源指纹变化，拒绝复用现有数据和进度")
    if state(con, "schema_version") != 1:
        raise ValueError("需要显式结构升级")


def normalized_type(kind):
    return re.sub(r"\b(bigint|int|smallint|tinyint)\(\d+\)", r"\1", kind.lower()).replace(" ", "")


def inspect_schema(con, migrate=False):
    """Validate every business/staging table before sync; never adopt arbitrary existing tables."""
    for job in JOBS.values():
        for table in (job.name, job.stage):
            if not table_exists(con, table):
                raise ValueError("目标表缺失，执行init续作")
            with con.cursor() as cur:
                cur.execute("SHOW COLUMNS FROM " + quote(table))
                found = {r[0]: r for r in cur.fetchall()}
            expected = {c.name: c for c in job.columns}
            if set(found) - set(expected):
                raise ValueError("目标存在未声明字段，拒绝自动删除")
            for name, column in expected.items():
                if name not in found:
                    if not migrate or column.required or column.generated:
                        raise ValueError("目标缺列，需要显式可空列迁移")
                    with con.cursor() as cur:
                        cur.execute("ALTER TABLE " + quote(table) + " ADD COLUMN " + column.ddl())
                    continue
                row = found[name]
                if (normalized_type(row[1]) != normalized_type(column.sql_type)
                        or row[2] != ("NO" if column.required else "YES")
                        or ("auto_increment" in row[5]) != column.generated):
                    raise ValueError("目标字段类型或属性与白名单不符")
            with con.cursor() as cur:
                cur.execute("SHOW INDEX FROM " + quote(table))
                rows = cur.fetchall()
            primary = tuple(r[4] for r in sorted(rows, key=lambda r: r[3]) if r[2] == "PRIMARY")
            if primary != job.primary or any(r[1] == 0 and r[2] != "PRIMARY" for r in rows):
                raise ValueError("主键不匹配或存在可能丢行的额外唯一键")
            keys = {}
            for row in sorted(rows, key=lambda r: r[3]):
                keys.setdefault(row[2], []).append(row[4])
            available = {tuple(v) for v in keys.values()}
            for index, fields in enumerate(job.indexes):
                if fields not in available:
                    if not migrate:
                        raise ValueError("目标缺少代码声明的查询索引")
                    with con.cursor() as cur:
                        cur.execute("ALTER TABLE " + quote(table) + " ADD KEY "
                                    + quote("idx_" + str(index)) + " ("
                                    + ",".join(map(quote, fields)) + ")")
            engine = scalar(con, "SELECT ENGINE FROM information_schema.tables"
                            " WHERE table_schema=DATABASE() AND table_name=%s", (table,))
            if engine != "InnoDB":
                raise ValueError("目标必须使用InnoDB事务")


def initialize(con, config):
    owned = table_exists(con, CONTROL)
    if owned:
        require_owner(con, config)
    elif any(table_exists(con, n) for j in JOBS.values() for n in (j.name, j.stage)):
        raise ValueError("发现未归属新任务的同名表，拒绝接管")
    else:
        with con.cursor() as cur:
            cur.execute(CONTROL_DDL)
        save(con, "owner", OWNER)
        save(con, "sources", fingerprint(config))
        save(con, "schema_version", 1)
        save(con, "paused", True)
        con.commit()
    for job in JOBS.values():
        with con.cursor() as cur:
            cur.execute(job.ddl())
            cur.execute(job.ddl(stage=True))
    inspect_schema(con)
    con.commit()


def inspect_source(con, source):
    for table, fields in REQUIRED[source].items():
        with con.cursor() as cur:
            cur.execute("SHOW COLUMNS FROM " + quote(table))
            names = {r[0].lower() for r in cur.fetchall()}
        if not {n.lower() for n in fields} <= names:
            raise ValueError("源表缺少白名单字段")
    if source != "click" and int(str(scalar(con, "SELECT VERSION()")).split(".")[0]) < 5:
        raise ValueError("源MySQL版本不支持")


def doris_days(con):
    """Read only structural DDL; never print properties, endpoints or user values."""
    with con.cursor() as cur:
        cur.execute("SHOW CREATE TABLE operation_log_detail")
        ddl = cur.fetchone()[1]
    if not re.search(r"DUPLICATE\s+KEY", ddl, re.I) or not re.search(
            r"PARTITION\s+BY\s+RANGE\s*\(\s*[\x60]?event_time[\x60]?\s*\)", ddl, re.I):
        raise ValueError("Doris表模型/日期分区与已核验结构不同")
    ranges = re.findall(r"PARTITION\s+p\d+\s+VALUES\s*\[\s*\('([^']+)'\)\s*,\s*\('([^']+)'\)\s*\)",
                        ddl, re.I)
    if len(ranges) != len(re.findall(r"\bPARTITION\s+\S+\s+VALUES\b", ddl, re.I)):
        raise ValueError("存在不支持的Doris分区定义，拒绝忽略")
    days = set()
    for lo, hi in ranges:
        a, b = datetime.fromisoformat(lo), datetime.fromisoformat(hi)
        if a.time() != datetime.min.time() or b - a != timedelta(days=1):
            raise ValueError("Doris分区不是完整日")
        days.add(a.date())
    if not days:
        raise ValueError("未取得Doris有效日分区")
    return days


def availability(con, job):
    if job.source == "click":
        days = doris_days(con)
        return min(days), days
    table, field, lower = {
        "bi_project_capacity_daily": ("project_storage", "record_date", "20260101"),
        "bi_tenant_optimization_daily": ("cluster_data_to_be_optimized", "dayid", START),
        "bi_screen_visit_log": ("sys_big_screen_visit_log", "operate_time", START),
    }[job.name]
    value = scalar(con, "SELECT " + quote(field) + " FROM " + quote(table)
                   + " WHERE " + quote(field) + ">=%s ORDER BY " + quote(field) + " LIMIT 1", (lower,))
    if value is None:
        if job.name == "bi_screen_visit_log":
            return START, None
        raise ValueError("源日期范围没有有效记录，保留目标，不推进")
    if job.source == "capacity":
        floor = datetime.strptime(str(value), "%Y%m%d").date()
    else:
        floor = value.date() if isinstance(value, datetime) else value
    if not isinstance(floor, date):
        floor = date.fromisoformat(str(floor)[:10])
    # Absence before first event is not evidence of log retention loss.
    return (START if job.name == "bi_screen_visit_log" else max(START, floor)), None


def where_day(job, day):
    if day is None:
        return "", ()
    return (" WHERE " + quote(job.date_column) + ">=%s AND " + quote(job.date_column) + "<%s",
            (day, day + timedelta(days=1)))


def count_query(con, sql, args):
    return int(scalar(con, "SELECT COUNT(*) FROM (" + sql + ") bi_extra_projection", args))


def normalize_row(job, row):
    values = list(row)
    if job.name == "bi_project_capacity_daily":
        values[3] = datetime.strptime(str(values[3]), "%Y%m%d").date()
    return tuple(values)


def digest_query(con, sql, args=(), transform=lambda row: row):
    """Order-independent multiset digest, preserving duplicate multiplicity and numeric values."""
    import MySQLdb.cursors
    total, amount = 0, 0

    def atom(value):
        if isinstance(value, (int, float, Decimal)):
            number = Decimal(str(value))
            if not number.is_finite():
                raise ValueError("数值不是有限数")
            text = format(number, "f")
            if "." in text:
                text = text.rstrip("0").rstrip(".")
            return ["n", "0" if text in ("-0", "") else text]
        if isinstance(value, (date, datetime)):
            return ["t", value.isoformat()]
        return value

    with con.cursor(MySQLdb.cursors.SSCursor) as cur:
        cur.execute(sql, args)
        while True:
            rows = cur.fetchmany(128)
            if not rows:
                break
            for row in map(transform, rows):
                raw = json.dumps([atom(v) for v in row], ensure_ascii=False,
                                 separators=(",", ":"), allow_nan=False).encode()
                total = (total + int.from_bytes(hashlib.sha256(raw).digest(), "big")) % (1 << 256)
                amount += 1
    return dict(count=amount, digest=format(total, "064x"))


def rows_digest(con, table, names, suffix="", args=()):
    return digest_query(con, "SELECT " + ",".join(map(quote, names))
                        + " FROM " + quote(table) + suffix, args)


def extract(source, target, job, day, batch, checkpoint):
    import MySQLdb.cursors
    source.rollback()
    sql, args = projection(job, day)
    before = count_query(source, sql, args)
    if job.snapshot and before > 100000:
        raise ValueError("全量快照超过10万行，未更新业务表")
    target.begin()
    with target.cursor() as cur:
        cur.execute("DELETE FROM " + quote(job.stage))
    target.commit()
    insert = ("INSERT INTO " + quote(job.stage) + " (" + ",".join(map(quote, job.names))
              + ") VALUES (" + ",".join(["%s"] * len(job.names)) + ")")
    extracted = 0
    with source.cursor(MySQLdb.cursors.SSCursor) as cursor:
        cursor.execute(sql, args)
        while True:
            rows = cursor.fetchmany(batch)
            if not rows:
                break
            checkpoint()
            with target.cursor() as cur:
                cur.executemany(insert, [normalize_row(job, r) for r in rows])
            target.commit()
            extracted += len(rows)
    after = count_query(source, sql, args)
    if before != after or after != extracted:
        raise ValueError("抽取期间源计数发生变化或抽取不完整，未发布")
    result = rows_digest(target, job.stage, job.names)
    if result["count"] != extracted:
        raise ValueError("暂存计数不一致，未发布")
    checkpoint()
    return result


def add_gap(gaps, lower, upper, reason):
    if lower >= upper:
        return gaps
    ranges = [(date.fromisoformat(g["from"]), date.fromisoformat(g["until"]), g["reason"])
              for g in gaps]
    ranges.append((lower, upper, reason))
    output = []
    for lo, hi, why in sorted(ranges):
        if output and output[-1]["reason"] == why and lo <= date.fromisoformat(output[-1]["until"]):
            output[-1]["until"] = str(max(hi, date.fromisoformat(output[-1]["until"])))
        else:
            output.append(dict(from_=str(lo), until=str(hi), reason=why))
            output[-1]["from"] = output[-1].pop("from_")
    return output


def remove_gap_day(gaps, day):
    result = []
    end = day + timedelta(days=1)
    for gap in gaps:
        lo, hi = date.fromisoformat(gap["from"]), date.fromisoformat(gap["until"])
        if hi <= day or lo >= end:
            result.append(gap)
        else:
            if lo < day:
                result.append(dict(gap, until=str(day)))
            if hi > end:
                item = dict(gap)
                item["from"] = str(end)
                result.append(item)
    return result


def progress(con, job, day, floor, result, missing=False):
    key = "table:" + job.name
    info = state(con, key, dict(requested_from=str(START), gaps=[]))
    if day is None:
        info.pop("requested_from", None)
        info.update(count=result["count"], digest=result["digest"],
                    refreshed_at=datetime.now(TZ).isoformat(), kind="current_snapshot")
        save(con, key, info)
        return
    previous = date.fromisoformat(info.get("success_until", str(START)))
    info["gaps"] = add_gap(info["gaps"], previous, floor, "SOURCE_HISTORY_UNAVAILABLE")
    info["gaps"] = add_gap(info["gaps"], max(previous, floor), day, "NOT_PROCESSED")
    info["available_from"] = str(floor)
    info["success_until"] = str(max(previous, day + timedelta(days=1)))
    if missing:
        info["gaps"] = add_gap(info["gaps"], day, day + timedelta(days=1), "SOURCE_SNAPSHOT_EMPTY")
        result = dict(result, outcome="SOURCE_SNAPSHOT_EMPTY", target_preserved=True)
    else:
        info["gaps"] = remove_gap_day(info["gaps"], day)
        info["coverage_from"] = min(info.get("coverage_from", str(day)), str(day))
    save(con, "day:" + job.name + ":" + str(day), result)
    save(con, key, info)


def publish(target, job, day, floor, result, checkpoint):
    """Only the owned staging table is replaceable outside this atomic live-data transaction."""
    checkpoint()
    target.begin()
    try:
        if job.monitoring and result["count"] == 0:
            # Missing daily snapshots are not authoritative business zeroes.
            suffix, args = where_day(job, day)
            preserved = rows_digest(target, job.name, job.names, suffix, args)
            progress(target, job, day, floor, dict(result, preserved_target=preserved), missing=True)
        else:
            suffix, args = where_day(job, day)
            with target.cursor() as cur:
                cur.execute("DELETE FROM " + quote(job.name) + suffix, args)
                names = ",".join(map(quote, job.names))
                cur.execute("INSERT INTO " + quote(job.name) + " (" + names + ") SELECT "
                            + names + " FROM " + quote(job.stage))
                if cur.rowcount != result["count"]:
                    raise ValueError("发布写入数不一致")
            if rows_digest(target, job.name, job.names, suffix, args) != result:
                raise ValueError("发布内容与暂存不一致")
            progress(target, job, day, floor, result)
        checkpoint()
        target.commit()
    except BaseException:
        target.rollback()
        raise


def sync_job(source, target, job, until, start, batch, lookback, checkpoint):
    if job.snapshot:
        result = extract(source, target, job, None, batch, checkpoint)
        publish(target, job, None, START, result, checkpoint)
        print(job.name + "当前快照已发布，行数=" + str(result["count"]), flush=True)
        return
    floor, partitions = availability(source, job)
    info = state(target, "table:" + job.name, {})
    previous = date.fromisoformat(info.get("success_until", str(START)))
    if start is None and until < previous:
        raise ValueError("截止日早于已有进度，请显式指定--from补跑")
    # Include the latest complete days and catch up from the committed boundary after downtime.
    lower = start or min(previous, until - timedelta(days=lookback))
    lower = max(lower, floor, START)
    if lower >= until:
        raise ValueError("请求范围无可用完整日，未更新")
    day = lower
    while day < until:
        checkpoint()
        if partitions is not None and day not in partitions:
            raise ValueError("Doris请求日分区缺失，保留目标历史")
        result = extract(source, target, job, day, batch, checkpoint)
        # Refresh metadata immediately before publishing: TTL may move while a backfill runs.
        if job.source == "click" and day not in doris_days(source):
            raise ValueError("Doris分区在抽取期间过期，未发布")
        publish(target, job, day, floor, result, checkpoint)
        print(job.name + "/" + str(day) + "，源结果行数=" + str(result["count"])
              + ("，无快照，保留目标并标缺失" if job.monitoring and not result["count"] else "，已核对发布"),
              flush=True)
        day += timedelta(days=1)


def verify_job(source, target, job, until, start):
    if job.snapshot:
        sql, args = projection(job)
        info = state(target, "table:" + job.name)
        actual = rows_digest(target, job.name, job.names)
        if (not info or info["count"] != actual["count"] or info["digest"] != actual["digest"]
                or actual != digest_query(source, sql, args, lambda r: normalize_row(job, r))):
            raise ValueError("当前快照核对不一致")
        return
    floor, partitions = availability(source, job)
    lower = max(start or START, floor)
    if lower >= until:
        raise ValueError("没有可核对的完整日")
    archived = start or START
    while archived < min(floor, until):
        ledger = state(target, "day:" + job.name + ":" + str(archived))
        if ledger:
            suffix, args = where_day(job, archived)
            actual = rows_digest(target, job.name, job.names, suffix, args)
            expected = ledger.get("preserved_target", {k: ledger[k] for k in ("count", "digest")})
            if actual != expected:
                raise ValueError("归档日目标内容与已提交账本不符")
        archived += timedelta(days=1)
    day = lower
    while day < until:
        if partitions is not None and day not in partitions:
            raise ValueError("核对范围存在源分区缺失")
        ledger = state(target, "day:" + job.name + ":" + str(day))
        if not ledger:
            raise ValueError("缺少已提交日账本")
        sql, args = projection(job, day)
        source.rollback()
        expected = count_query(source, sql, args)
        suffix, target_args = where_day(job, day)
        actual = rows_digest(target, job.name, job.names, suffix, target_args)
        if ledger.get("outcome") == "SOURCE_SNAPSHOT_EMPTY":
            if expected != 0 or actual != ledger["preserved_target"]:
                raise ValueError("空快照后来已补齐，请补跑该日")
        else:
            if (actual != ledger or actual["count"] != expected
                    or actual != digest_query(source, sql, args, lambda r: normalize_row(job, r))):
                raise ValueError("源计数或目标内容与日账本不符")
        day += timedelta(days=1)


def status(target):
    tables = {name: state(target, "table:" + name) for name in JOBS}
    return dict(owner=OWNER, schema_version=state(target, "schema_version"),
                paused=state(target, "paused", True),
                running=scalar(target, "SELECT IS_USED_LOCK(%s)", (LOCK,)) is not None,
                last_run=state(target, "last_run"), last_verify=state(target, "last_verify"),
                tables=tables, has_gaps=any(v and v.get("gaps") for v in tables.values()))


def run(config, action, until, start=None, tables=None, connect_fn=connect, after_extract=None):
    names = tuple(tables or JOBS)
    if not names or len(names) != len(set(names)) or any(n not in JOBS for n in names):
        raise ValueError("仅允许六张白名单表，不能重复")
    if until > datetime.now(TZ).date() or until <= START or (start and (start < START or start >= until)):
        raise ValueError("日期范围须从2026-01-01起且仅包含已结束日")
    phase, locked, active = "TARGET_CONNECT", False, False
    target = monitor = None
    run_info = None
    try:
        target = connect_fn(config["target"], "target")
        if int(str(scalar(target, "SELECT VERSION()")).split(".")[0]) < 8:
            raise ValueError("目标必须为MySQL8及以上")
        if action not in ("status", "check", "pause"):
            if scalar(target, "SELECT GET_LOCK(%s,0)", (LOCK,)) != 1:
                raise ValueError("扩展任务已有操作运行")
            locked = True
        if action == "init":
            initialize(target, config)
            print("六表及私有控制/暂存已准备，暂停状态请查看status", flush=True)
            return
        if action == "check":
            if table_exists(target, CONTROL):
                require_owner(target, config)
                inspect_schema(target)
            elif any(table_exists(target, n) for j in JOBS.values() for n in (j.name, j.stage)):
                raise ValueError("发现未归属同名表，拒绝接管")
            for source in {JOBS[n].source for n in names}:
                print("只读检查源：" + source, flush=True)
                con = connect_fn(config["sources"][source], source)
                try:
                    inspect_source(con, source)
                    for n in names:
                        job = JOBS[n]
                        if job.source != source:
                            continue
                        sql, args = projection(job, until - timedelta(days=1) if not job.snapshot else None)
                        with con.cursor() as cur:
                            cur.execute("EXPLAIN " + sql, args)
                            cur.fetchall()
                        if source == "click":
                            doris_days(con)
                finally:
                    con.close()
            print("源登录/白名单字段/投影计划检查通过；未写库，不代表已同步", flush=True)
            return
        require_owner(target, config)
        if action == "status":
            print(json.dumps(status(target), ensure_ascii=False, sort_keys=True), flush=True)
            return
        if action in ("pause", "resume", "migrate"):
            if action == "migrate":
                if not state(target, "paused", True):
                    raise ValueError("结构升级须先暂停")
                inspect_schema(target, migrate=True)
            else:
                if action == "resume":
                    inspect_schema(target)
                save(target, "paused", action == "pause")
            target.commit()
            print("扩展任务操作完成；没有自动运行或修改定时任务", flush=True)
            return
        inspect_schema(target)
        if action == "sync" and state(target, "paused", True):
            print("扩展任务暂停中，本轮跳过；未连接源", flush=True)
            return
        monitor = connect_fn(config["target"], "monitor")
        run_info = dict(id=uuid.uuid4().hex, action=action, tables=names,
                        started_at=datetime.now(TZ).isoformat(), outcome="RUNNING")
        save(monitor, "last_run", run_info)
        monitor.commit()
        active = True

        def checkpoint():
            if state(monitor, "paused", True):
                raise Paused()

        with ExitStack() as stack:
            for name in names:
                job = JOBS[name]
                phase = name
                if action == "sync":
                    checkpoint()
                source = connect_fn(config["sources"][job.source], job.source)
                stack.callback(source.close)
                inspect_source(source, job.source)
                if action == "verify":
                    verify_job(source, target, job, until, start)
                else:
                    def publish_hook():
                        checkpoint()
                        if after_extract:
                            after_extract(job, target, monitor)
                    sync_job(source, target, job, until, start, config["batch_size"],
                             config["lookback_days"], publish_hook)
                # Release each source promptly; no long-lived cross-source global snapshot.
                stack.pop_all().close()
        result = status(target)
        run_info.update(outcome="SUCCESS_WITH_GAPS" if result["has_gaps"] else "SUCCESS",
                        finished_at=datetime.now(TZ).isoformat())
        save(monitor, "last_run", run_info)
        if action == "verify":
            save(monitor, "last_verify", dict(until=str(until), from_=str(start or START), tables=names,
                                           at=run_info["finished_at"], has_gaps=result["has_gaps"]))
        monitor.commit()
        print("扩展任务核对完成；" + ("存在明确缺失范围，请查看status" if result["has_gaps"]
                                      else "所选范围已完成"), flush=True)
    except BaseException as exc:
        if target is not None:
            target.rollback()
        if active and monitor is not None:
            try:
                run_info.update(outcome="PAUSED" if isinstance(exc, Paused) else "FAILED",
                                failure_phase=phase, finished_at=datetime.now(TZ).isoformat())
                save(monitor, "last_run", run_info)
                monitor.commit()
            except Exception:
                print("状态写入失败，需核对已有提交进度", file=sys.stderr)
        if isinstance(exc, Paused):
            print("扩展任务已安全暂停；未发布部分保留旧数据", flush=True)
        else:
            raise
    finally:
        if target is not None:
            try:
                target.rollback()
                if locked:
                    scalar(target, "SELECT RELEASE_LOCK(%s)", (LOCK,))
            finally:
                target.close()
        if monitor is not None:
            monitor.close()


def main(argv=None):
    parser = argparse.ArgumentParser(description="独立BI六表同步；源只读，目标按表/日原子发布")
    parser.add_argument("action", choices=["ddl", "check", "init", "migrate", "sync",
                                          "verify", "status", "pause", "resume"])
    parser.add_argument("--config", type=Path)
    parser.add_argument("--base-config", type=Path)
    parser.add_argument("--until", type=date.fromisoformat, default=datetime.now(TZ).date())
    parser.add_argument("--from", dest="start", type=date.fromisoformat)
    parser.add_argument("--tables", nargs="+", choices=tuple(JOBS))
    args = parser.parse_args(argv)
    try:
        if args.action == "ddl":
            print(";\n\n".join(j.ddl() for j in JOBS.values()) + ";")
            return 0
        if not args.config or not args.base_config:
            raise ValueError("需要新配置及旧私有连接配置路径")
        config = load_config(args.config, args.base_config)
        run(config, args.action, args.until, args.start, args.tables)
        return 0
    except Exception as exc:
        code = exc.args[0] if exc.args and type(exc.args[0]) is int else None
        # Driver errors may contain SQL, data or secrets. Never print their messages.
        print("扩展任务未完成；错误类型=" + type(exc).__name__
              + (", 错误码=" + str(code) if code is not None else "")
              + "；请核对参数、私有配置、字段和status", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
