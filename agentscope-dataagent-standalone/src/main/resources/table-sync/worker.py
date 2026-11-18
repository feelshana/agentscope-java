"""Private MySQL 5.7/8 -> MySQL 8 mirror worker. One JSON request per process."""

import contextlib
import datetime as dt
import hashlib
import itertools
import json
import os
import re
import sys
import time
import uuid

import MySQLdb
import MySQLdb.cursors
from MySQLdb.constants import FIELD_TYPE
from MySQLdb.converters import conversions


ZONE = dt.timezone(dt.timedelta(hours=8))
FULL_LIMIT = 100_000
PERIOD_LIMIT = 100_000
BATCH = 500
BATCH_BYTES = 4 * 1024 * 1024
TRANSACTION_BYTES = 64 * 1024 * 1024
BATCH_PAUSE_SECONDS = 0.05
WORKER_LOCK = "dataagent:table-sync:worker"
MAX_DAYS = 3660
DATE_TYPES = {"date", "datetime", "timestamp"}
INT_TYPES = {"int", "mediumint", "bigint"}
TEXT_TYPES = {"char", "varchar"}
FORMATS = {
    "YYYYMMDD": ("DAY", "%Y%m%d"), "YYYY-MM-DD": ("DAY", "%Y-%m-%d"),
    "YYYY/MM/DD": ("DAY", "%Y/%m/%d"), "YYYYMM": ("MONTH", "%Y%m"),
    "YYYY-MM": ("MONTH", "%Y-%m"), "NATIVE": (None, None),
}
COLUMN_TYPE = re.compile(
    r"(?:tinyint|smallint|mediumint|int|bigint|decimal|float|double|bit|"
    r"char|varchar|binary|varbinary|tinytext|text|mediumtext|longtext|"
    r"tinyblob|blob|mediumblob|longblob|date|datetime|timestamp|time|year|json)"
    r"(?:\([0-9]+(?:,[0-9]+)?\))?(?: unsigned)?(?: zerofill)?"
)
SAFE_TOKEN = re.compile(r"[a-zA-Z0-9_]+")
HOST = re.compile(r"[a-zA-Z0-9][a-zA-Z0-9.-]{0,252}")
TARGET = re.compile(r"sync_[a-z0-9][a-z0-9_]{0,58}")
ID = re.compile(r"[a-f0-9]{32}")
COLS = "COLUMN_NAME, DATA_TYPE, COLUMN_TYPE, IS_NULLABLE, CHARACTER_SET_NAME, COLLATION_NAME, COLUMN_COMMENT, EXTRA"
TARGET_DB = "data_agent"
CONTROL_DB = "data_agent_platform"
TIME_CONVERSIONS = dict(conversions)
for code in (FIELD_TYPE.DATE, FIELD_TYPE.DATETIME, FIELD_TYPE.TIMESTAMP, FIELD_TYPE.TIME):
    TIME_CONVERSIONS[code] = str


class SyncError(Exception):
    pass


def require(condition, code):
    if not condition:
        raise SyncError(code)


def identifier(value):
    require(isinstance(value, str) and 0 < len(value) <= 64
            and "%" not in value and all(ord(c) >= 32 for c in value), "INVALID_INPUT")
    return "`" + value.replace("`", "``") + "`"


def qualified(database, table):
    return f"{identifier(database)}.{identifier(table)}"


def now():
    return dt.datetime.now(ZONE).replace(tzinfo=None)


def date_value(value):
    try:
        day = dt.date.fromisoformat(value)
        require(value == day.isoformat() and day.year >= 1970, "INVALID_PERIOD")
        return day
    except (ValueError, TypeError):
        raise SyncError("INVALID_PERIOD") from None


def next_period(day, grain):
    if grain == "DAY":
        return day + dt.timedelta(days=1)
    return (day.replace(day=28) + dt.timedelta(days=4)).replace(day=1)


def previous_period(day, grain):
    yesterday = day - dt.timedelta(days=1)
    return yesterday if grain == "DAY" else (day.replace(day=1) - dt.timedelta(days=1)).replace(day=1)


def parse_periods(data, grain):
    if grain == "FULL":
        return None, None
    start, through = map(date_value, (data.get("from"), data.get("through")))
    require(start <= through <= previous_period(now().date(), grain)
            and (through - start).days <= MAX_DAYS
            and (grain != "MONTH" or (start.day == through.day == 1)), "INVALID_PERIOD")
    return start, through


def periods(start, through, grain):
    while start <= through:
        yield start
        start = next_period(start, grain)


def execute(con, sql, args=()):
    with con.cursor() as cursor:
        cursor.execute(sql, args)
        return cursor.rowcount


def query(con, sql, args=()):
    with con.cursor(MySQLdb.cursors.DictCursor) as cursor:
        cursor.execute(sql, args)
        return list(cursor.fetchall())


def one(con, sql, args=()):
    rows = query(con, sql, args)
    return rows[0] if rows else None


def scalar(con, sql, args=()):
    row = one(con, sql, args)
    return next(iter(row.values())) if row else None


def settings(role, key, default=None):
    return os.getenv(f"DATAAGENT_TABLE_SYNC_{role}_{key}", default)


def connection(host, port, database, username, password, role, *, ssl_mode=None):
    require(isinstance(host, str) and HOST.fullmatch(host) and type(port) is int
            and 0 < port <= 65535 and isinstance(username, str) and 0 < len(username) <= 128
            and isinstance(password, str) and 0 < len(password) <= 1024, "INVALID_INPUT")
    mode = settings(role, "SSL_MODE", "VERIFY_IDENTITY") if ssl_mode is None else ssl_mode
    require(mode in ("VERIFY_IDENTITY", "VERIFY_CA", "REQUIRED", "DISABLED"), "INVALID_INPUT")
    ca = settings(role, "SSL_CA")
    return MySQLdb.connect(
        host="127.0.0.1" if host.lower() == "localhost" else host,
        port=port, db=database, user=username, passwd=password, charset="utf8mb4",
        ssl_mode=mode, ssl={"ca": ca} if ca else None, local_infile=0,
        connect_timeout=5, read_timeout=40, write_timeout=40, autocommit=True,
        **({"conv": TIME_CONVERSIONS} if role == "SOURCE" else {}),
    )


def source_connection(source, password):
    host, port = source.get("host"), source.get("port")
    require(isinstance(host, str) and HOST.fullmatch(host) and type(port) is int
            and 0 < port <= 65535, "INVALID_INPUT")
    allowed = {item.strip().lower() for item in
               os.getenv("DATAAGENT_TABLE_SYNC_ALLOWED_SOURCES", "").split(",") if item.strip()}
    require(not allowed or f"{host.lower()}:{port}" in allowed, "SOURCE_NOT_ALLOWED")
    database, username = source.get("database"), source.get("username")
    identifier(database)
    return connection(host, port, database, username, password, "SOURCE",
                      ssl_mode=source.get("sslMode"))


def target_connection(target=None, database=None):
    if target is None:
        target = {"host": settings("TARGET", "HOST"), "port": int(settings("TARGET", "PORT", "3306")),
                  "username": settings("TARGET", "USER"), "password": settings("TARGET", "PASSWORD"),
                  "controlSchema": settings("TARGET", "CONTROL_DB", CONTROL_DB)}
        require(target["host"] and target["username"] and target["password"], "NOT_CONFIGURED")
    database = database or target.get("controlSchema", CONTROL_DB)
    identifier(database)
    return connection(target["host"], target["port"], database, target["username"],
                      target["password"], "TARGET", ssl_mode=target.get("sslMode"))


def check_target(target):
    require(isinstance(target, dict), "INVALID_INPUT")
    try:
        con = target_connection(target, TARGET_DB)
    except MySQLdb.Error:
        raise SyncError("TARGET_CONNECTION") from None
    with contextlib.closing(con):
        version = table_version(con)
        require(version.startswith("8."), "UNSUPPORTED_TARGET")
    try:
        with contextlib.closing(target_connection(target)) as platform:
            for table in ("dataagent_table_sync_task", "dataagent_table_sync_run", "dataagent_table_sync_coverage"):
                execute(platform, f"SELECT 1 FROM {identifier(table)} LIMIT 0")
    except MySQLdb.Error as error:
        if error.args[0] in (1049, 1146):
            raise SyncError("MIGRATION_REQUIRED") from None
        raise SyncError("CONTROL_ACCESS") from None
    return {}


def source_metadata(con, database, table, version):
    identifier(table)
    names = (database, table)
    storage = one(con, "SELECT TABLE_TYPE, ENGINE FROM information_schema.TABLES "
                  "WHERE BINARY TABLE_SCHEMA=%s AND BINARY TABLE_NAME=%s", names)
    require(storage and storage["TABLE_TYPE"] == "BASE TABLE" and storage["ENGINE"] == "InnoDB",
            "UNSUPPORTED_TABLE")
    columns = query(con, f"SELECT {COLS} FROM information_schema.COLUMNS "
                    "WHERE BINARY TABLE_SCHEMA=%s AND BINARY TABLE_NAME=%s ORDER BY ORDINAL_POSITION", names)
    visibility = " AND IS_VISIBLE='YES'" if version.startswith("8.") else ""
    indexes = query(con, "SELECT INDEX_NAME, COLUMN_NAME, SEQ_IN_INDEX, INDEX_TYPE "
                    "FROM information_schema.STATISTICS WHERE BINARY TABLE_SCHEMA=%s AND BINARY TABLE_NAME=%s "
                    + visibility + " ORDER BY INDEX_NAME, SEQ_IN_INDEX", names)
    require(columns and len(columns) <= 300, "UNSUPPORTED_TABLE")
    first = {i["COLUMN_NAME"] for i in indexes if i["SEQ_IN_INDEX"] == 1 and i["INDEX_TYPE"] == "BTREE"}
    primary = [i["COLUMN_NAME"] for i in indexes if i["INDEX_NAME"] == "PRIMARY"]
    return [{
        "name": c["COLUMN_NAME"], "kind": c["DATA_TYPE"], "type": c["COLUMN_TYPE"],
        "nullable": c["IS_NULLABLE"] == "YES", "charset": c["CHARACTER_SET_NAME"],
        "collation": c["COLLATION_NAME"], "comment": c["COLUMN_COMMENT"],
        "extra": c["EXTRA"], "indexed": c["COLUMN_NAME"] in first,
    } for c in columns], primary


def validated_columns(columns):
    for col in columns:
        identifier(col["name"])
        require(COLUMN_TYPE.fullmatch(col["type"]) and "generated" not in col["extra"].lower(),
                "UNSUPPORTED_COLUMN")
        for option in (col["charset"], col["collation"]):
            require(option is None or SAFE_TOKEN.fullmatch(option), "UNSUPPORTED_COLUMN")


def table_version(con):
    version = scalar(con, "SELECT VERSION()")
    require(version.startswith(("5.7.", "8.")) and "mariadb" not in version.lower(), "UNSUPPORTED_SOURCE")
    return version


def timestamp_zone(con, columns):
    if not any(col["kind"] == "timestamp" for col in columns):
        return "+00:00"
    zone = one(con, "SELECT @@session.time_zone AS zone, @@system_time_zone AS system_zone")
    value = zone["zone"]
    if value.upper() in ("UTC", "GMT") or (value == "SYSTEM" and zone["system_zone"] in ("UTC", "GMT")):
        return "+00:00"
    require(value in ("+00:00", "+08:00"), "SOURCE_TIME_ZONE")
    return value


def inspect(con, source, data):
    version = table_version(con)
    table = data.get("table")
    identifier(table)
    columns, primary = source_metadata(con, source["database"], table, version)
    selected = data.get("columns")
    require(isinstance(selected, list) and all(isinstance(name, str) for name in selected)
            and len(set(selected)) == len(selected), "INVALID_INPUT")
    available = {column["name"] for column in columns}
    require(set(selected) <= available, "SCHEMA_CHANGED")
    columns = [c for c in columns if c["name"] in selected]
    require(columns, "INVALID_INPUT")
    validated_columns(columns)
    primary = primary if set(primary) <= set(selected) else []
    grain = data.get("grain")
    require(grain in ("FULL", "DAY", "MONTH"), "INVALID_INPUT")
    field = next((c for c in columns if c["name"] == data.get("dateField")), None)
    fmt, storage_zone = data.get("dateFormat"), data.get("storageZone", "+08:00")
    zone = timestamp_zone(con, columns)
    if grain != "FULL":
        require(field and field["indexed"], "DATE_INDEX_REQUIRED")
        require(isinstance(fmt, str) and fmt in FORMATS, "INVALID_FORMAT")
        require(storage_zone in ("+00:00", "+08:00"), "INVALID_FORMAT")
        if fmt == "NATIVE":
            require(field["kind"] in DATE_TYPES, "INVALID_FORMAT")
            if field["kind"] == "timestamp":
                storage_zone = zone
        else:
            require(FORMATS[fmt][0] == grain, "INVALID_FORMAT")
            is_compact = fmt in ("YYYYMMDD", "YYYYMM")
            require(field["kind"] in TEXT_TYPES or (is_compact and field["kind"] in INT_TYPES),
                    "INVALID_FORMAT")
        src = qualified(source["database"], table)
        require(not scalar(con, f"SELECT 1 FROM {src} WHERE {identifier(field['name'])} IS NULL LIMIT 1"),
                "NULL_DATE")
    else:
        field, fmt, storage_zone = None, None, None
    return {
        "table": table, "columns": columns, "primary": primary, "grain": grain,
        "dateField": field["name"] if field else None, "dateKind": field["kind"] if field else None,
        "dateFormat": fmt, "storageZone": storage_zone, "timestampZone": zone,
    }


def fingerprint(config):
    return hashlib.sha256(json.dumps(config, sort_keys=True, ensure_ascii=False).encode()).hexdigest()


def bounds(config, day):
    field = identifier(config["dateField"])
    fmt = config["dateFormat"]
    if fmt != "NATIVE":
        value = day.strftime(FORMATS[fmt][1])
        if config["dateKind"] in INT_TYPES:
            value = int(value)
        return f" WHERE {field}=%s", (value,)
    end = next_period(day, config["grain"])
    if config["dateKind"] != "date":
        start_time = dt.datetime.combine(day, dt.time.min)
        end_time = dt.datetime.combine(end, dt.time.min)
        if config["storageZone"] == "+00:00":
            start_time -= dt.timedelta(hours=8)
            end_time -= dt.timedelta(hours=8)
        return f" WHERE {field}>=%s AND {field}<%s", (start_time, end_time)
    return f" WHERE {field}>=%s AND {field}<%s", (day, end)


def input_source(data):
    source = {key: data.get(key) for key in ("host", "port", "database", "username", "sslMode")}
    return source, data.get("password")


def explore(action, data, target_config):
    source, password = input_source(data)
    try:
        with contextlib.closing(source_connection(source, password)) as con:
            database = source["database"]
            if action == "tables":
                version = table_version(con)
                tables = query(con, "SELECT TABLE_NAME AS name FROM information_schema.TABLES "
                               "WHERE BINARY TABLE_SCHEMA=%s AND TABLE_TYPE='BASE TABLE' "
                               "AND ENGINE='InnoDB' AND LOCATE(%s, TABLE_NAME)>0 "
                               "ORDER BY TABLE_NAME LIMIT 501", (database, str(data.get("search", ""))[:100]))
                return {"version": version, "tables": [t["name"] for t in tables[:500]],
                        "truncated": len(tables) > 500}
            if action == "columns":
                version = table_version(con)
                columns, _ = source_metadata(con, database, data.get("table"), version)
                return {"columns": columns}
            config = inspect(con, source, data)
            if action == "test-date":
                require(config["grain"] != "FULL", "INVALID_PERIOD")
                day = date_value(data.get("sampleDate"))
                require(config["grain"] != "MONTH" or day.day == 1, "INVALID_PERIOD")
                clause, args = bounds(config, day)
                projection = ", ".join(identifier(col["name"]) for col in config["columns"])
                table = qualified(database, config["table"])
                rows = query(con, f"SELECT {projection} FROM {table}{clause} LIMIT 3", args)
                return {"matches": len(rows), "sampleValues": [str(row[config["dateField"]]) for row in rows]}
    except MySQLdb.Error:
        raise SyncError("SOURCE_QUERY") from None
    target = target_name(data)
    marker = mirror_marker(source, config, target)
    with contextlib.closing(target_connection(target_config, TARGET_DB)) as dst, dst.cursor() as cursor:
        existing = table_comment(dst, TARGET_DB, target)
        if existing is not None:
            require(existing == marker, "TARGET_OWNERSHIP")
            assert_mirror_schema(dst, TARGET_DB, target, config)
        sql, params = ddl(qualified(TARGET_DB, target), config, marker)
        return {"fingerprint": fingerprint(config), "ddl": cursor.mogrify(sql, params) + ";",
                "targetReady": existing is not None}


def mirror_marker(source, config, target):
    origin = {key: source[key] for key in ("host", "port", "database")}
    return "dataagent-table-sync:" + fingerprint({"source": origin, "selection": config, "target": target})


def target_name(data):
    value = data.get("targetTable")
    require(isinstance(value, str) and TARGET.fullmatch(value), "INVALID_TARGET")
    return value


def ddl(name, config, owner=None):
    definitions, params = [], []
    for col in config["columns"]:
        sql = f"{identifier(col['name'])} {col['type']}"
        if col["charset"]:
            sql += f" CHARACTER SET {col['charset']} COLLATE {col['collation']}"
        sql += " NULL" if col["nullable"] else " NOT NULL"
        if col["comment"]:
            sql += " COMMENT %s"
            params.append(col["comment"])
        definitions.append(sql)
    if config["primary"]:
        primary = ", ".join(map(identifier, config["primary"]))
        definitions.append(f"PRIMARY KEY ({primary})")
    field = config["dateField"]
    if field and config["primary"][:1] != [field]:
        definitions.append(f"KEY sync_period ({identifier(field)})")
    sql = f"CREATE TABLE {name} (\n  " + ",\n  ".join(definitions) + "\n) ENGINE=InnoDB"
    if owner:
        sql += " COMMENT=%s"
        params.append(owner)
    return sql, params


def enqueue(con, task_id, kind, start, through, actor, slot=None, accept_empty=False):
    run_id = uuid.uuid4().hex
    execute(con, "INSERT INTO dataagent_table_sync_run(id,task_id,kind,slot,date_from,date_through,"
            "accept_empty,actor,queued_at) VALUES(%s,%s,%s,%s,%s,%s,%s,%s,%s)",
            (run_id, task_id, kind, slot, start, through, accept_empty, actor, now()))
    return run_id


def create(data, actor, target_config):
    source, password = input_source(data)
    name, target = data.get("name"), target_name(data)
    require(isinstance(name, str) and 0 < len(name.strip()) <= 100
            and isinstance(password, str) and 0 < len(password) <= 1024, "INVALID_INPUT")
    try:
        with contextlib.closing(source_connection(source, password)) as con:
            config = inspect(con, source, data)
    except MySQLdb.Error:
        raise SyncError("SOURCE_QUERY") from None
    require(fingerprint(config) == data.get("fingerprint"), "SCHEMA_CHANGED")
    start, through = parse_periods(data, config["grain"])
    task_id = uuid.uuid4().hex
    config["marker"] = mirror_marker(source, config, target)
    config["source"] = source
    config["credentialFormat"] = "plain"
    with contextlib.closing(target_connection(target_config)) as con:
        owner = table_comment(con, TARGET_DB, target)
        require(owner is not None, "TARGET_TABLE_REQUIRED")
        require(owner == config["marker"], "TARGET_OWNERSHIP")
        assert_mirror_schema(con, TARGET_DB, target, config)
        require(not scalar(con, f"SELECT 1 FROM {qualified(TARGET_DB, target)} LIMIT 1"), "TARGET_NOT_EMPTY")
        con.autocommit(False)
        try:
            execute(con, "INSERT INTO dataagent_table_sync_task(id,name,target_table,config,credential,created_by,"
                    "created_at,initial_from,initial_through) VALUES(%s,%s,%s,%s,%s,%s,%s,%s,%s)",
                    (task_id, name.strip(), target, json.dumps(config), password, actor, now(), start, through))
            enqueue(con, task_id, "INITIAL", start, through, actor)
            con.commit()
        except MySQLdb.IntegrityError:
            raise SyncError("TARGET_EXISTS") from None
    return {"id": task_id}


def find_task(con, task_id, lock=False):
    require(isinstance(task_id, str) and ID.fullmatch(task_id), "INVALID_INPUT")
    row = one(con, "SELECT * FROM dataagent_table_sync_task WHERE id=%s" + (" FOR UPDATE" if lock else ""), (task_id,))
    require(row, "NOT_FOUND")
    return row


def task_action(con, action, data, actor):
    con.autocommit(False)
    task = find_task(con, data.get("id"), lock=True)
    task_id, initialized = task["id"], bool(task["initialized"])
    config = json.loads(task["config"])
    grain = config["grain"]
    if action == "schedule":
        time = data.get("time")
        require(time is None or (isinstance(time, str) and re.fullmatch(r"(?:[01]\d|2[0-3]):[0-5]\d", time)),
                "INVALID_INPUT")
        require(time is None or initialized, "INITIAL_REQUIRED")
        execute(con, "UPDATE dataagent_table_sync_task SET schedule_time=%s,schedule_enabled_at=%s WHERE id=%s",
                (time, now() if time else None, task_id))
    else:
        require(not scalar(con, "SELECT 1 FROM dataagent_table_sync_run WHERE task_id=%s AND state IN ('QUEUED','RUNNING') LIMIT 1",
                           (task_id,)), "ALREADY_QUEUED")
        if not initialized:
            enqueue(con, task_id, "INITIAL", task["initial_from"], task["initial_through"], actor)
        else:
            latest = previous_period(now().date(), grain)
            if grain != "FULL" and action == "run-now":
                start = through = latest
            else:
                start, through = parse_periods(data, grain)
            require(start is None or start >= task["initial_from"], "INVALID_PERIOD")
            enqueue(con, task_id, "MANUAL", start, through, actor, accept_empty=data.get("acceptEmpty") is True)
    con.commit()
    return {}


def scheduled_due(task, when, grain):
    if grain == "MONTH" and when.day != 1:
        return False
    planned = dt.datetime.combine(when.date(), dt.time()) + task["schedule_time"]
    return task["schedule_enabled_at"] <= planned <= when


def next_job(con, dispatch=True):
    when = now()
    slot = when.date()
    due = query(
        con, "SELECT t.id FROM dataagent_table_sync_task t "
        "WHERE t.initialized=TRUE AND t.schedule_time IS NOT NULL "
        "AND TIMESTAMP(%s,t.schedule_time) BETWEEN t.schedule_enabled_at AND %s "
        "AND (%s=1 OR JSON_UNQUOTE(JSON_EXTRACT(t.config,'$.grain'))<>'MONTH') "
        "AND NOT EXISTS (SELECT 1 FROM dataagent_table_sync_run r WHERE r.task_id=t.id AND r.slot=%s) "
        "ORDER BY t.schedule_time,t.created_at,t.id", (slot, when, slot.day, slot))
    for item in due:
        con.autocommit(False)
        task = find_task(con, item["id"], lock=True)
        grain = json.loads(task["config"])["grain"]
        if task["schedule_time"] is not None and scheduled_due(task, when, grain):
            existing = scalar(con, "SELECT 1 FROM dataagent_table_sync_run WHERE task_id=%s AND slot=%s", (task["id"], slot))
            if not existing:
                day = None if grain == "FULL" else previous_period(slot, grain)
                enqueue(con, task["id"], "DAILY" if grain != "MONTH" else "MONTHLY",
                        day, day, "scheduler", slot)
        con.commit()
        con.autocommit(True)
    if not dispatch or scalar(con, "SELECT IS_FREE_LOCK(%s)", (WORKER_LOCK,)) != 1:
        return {}
    job = one(con, "SELECT r.id, t.credential, "
              "JSON_UNQUOTE(JSON_EXTRACT(t.config,'$.credentialFormat')) AS credential_format "
              "FROM dataagent_table_sync_run r JOIN dataagent_table_sync_task t ON r.task_id=t.id "
              "WHERE r.state IN ('QUEUED','RUNNING') ORDER BY r.state='RUNNING' DESC, r.queued_at,r.id LIMIT 1")
    return job or {}


RUN_FIELDS = ("id, kind, actor, state, phase, date_from, date_through, progress_date, "
              "rows_read, rows_committed, error_code, queued_at, started_at, finished_at")


def task_view(con, task):
    config = json.loads(task["config"])
    grain = config["grain"]
    schedule = task["schedule_time"]
    result = {
        "id": task["id"], "name": task["name"], "targetTable": task["target_table"],
        "sourceTable": f"{config['source']['database']}.{config['table']}", "grain": grain,
        "dateField": config["dateField"], "dateFormat": config["dateFormat"],
        "initialized": bool(task["initialized"]), "from": task["initial_from"],
        "through": task["initial_through"], "scheduleTime": str(schedule).rjust(8, "0")[:5] if schedule is not None else None,
        "lastRun": one(con, f"SELECT {RUN_FIELDS} FROM dataagent_table_sync_run WHERE task_id=%s ORDER BY queued_at DESC LIMIT 1",
                       (task["id"],)),
    }
    if schedule is not None:
        when = now()
        day = when.date().replace(day=1) if grain == "MONTH" else when.date()
        planned = dt.datetime.combine(day, dt.time()) + schedule
        if planned <= when:
            day = next_period(day, "MONTH" if grain == "MONTH" else "DAY")
            planned = dt.datetime.combine(day, dt.time()) + schedule
        result["nextRunAt"] = planned
        result["nextPeriod"] = previous_period(day, grain) if grain != "FULL" else None
    return result


def detail(con, task):
    result = task_view(con, task)
    result["runs"] = query(con, f"SELECT {RUN_FIELDS} FROM dataagent_table_sync_run WHERE task_id=%s ORDER BY queued_at DESC LIMIT 50",
                           (task["id"],))
    grain = result["grain"]
    if grain == "FULL":
        return result
    known = {row["business_date"]: row["row_count"] for row in
             query(con, "SELECT business_date,row_count FROM dataagent_table_sync_coverage WHERE task_id=%s", (task["id"],))}
    upper = previous_period(now().date(), grain) if task["initialized"] else task["initial_through"]
    gaps, continuous = [], None
    for day in periods(task["initial_from"], upper, grain):
        if day not in known:
            if gaps and next_period(gaps[-1]["through"], grain) == day:
                gaps[-1]["through"] = day
            else:
                gaps.append({"from": day, "through": day})
        elif not gaps and task["initialized"]:
            continuous = day
    result.update(gaps=gaps, continuousThrough=continuous,
                  emptyPeriods=sum(1 for count in known.values() if count == 0))
    return result


def table_comment(con, database, table):
    return scalar(con, "SELECT TABLE_COMMENT FROM information_schema.TABLES "
                  "WHERE TABLE_SCHEMA=%s AND TABLE_NAME=%s", (database, table))


def assert_mirror_schema(con, database, table, config):
    actual, primary = source_metadata(con, database, table, "8.")

    def signature(columns):
        return [
            (c["name"], re.sub(r"^((?:tiny|small|medium|big)?int)\(\d+\)", r"\1", c["type"]),
             c["nullable"], (c["collation"] or "").replace("utf8_", "utf8mb3_"), c["comment"])
            for c in columns
        ]

    require(signature(actual) == signature(config["columns"]) and primary == config["primary"]
            and all(c["indexed"] for c in actual if c["name"] == config["dateField"]),
            "TARGET_OWNERSHIP")


def update_phase(con, run_id, phase, day=None):
    execute(con, "UPDATE dataagent_table_sync_run SET phase=%s,progress_date=%s WHERE id=%s", (phase, day, run_id))


def batches(stream):
    batch, size = [], 0
    while (row := stream.fetchone()) is not None:
        # Account for UTF-8 and SQL escaping with a conservative byte estimate.
        row_size = sum(8 * len(v) + 4 if isinstance(v, str)
                       else 2 * len(v) + 8 if isinstance(v, bytes) else 64 for v in row)
        require(row_size <= BATCH_BYTES, "ROW_TOO_LARGE")
        if batch and (len(batch) >= BATCH or size + row_size > BATCH_BYTES):
            yield batch, size
            batch, size = [], 0
        batch.append(row)
        size += row_size
    if batch:
        yield batch, size


def transfer(src, dst, source_db, config, mirror, clause, args, run):
    cols = ", ".join(identifier(col["name"]) for col in config["columns"])
    placeholders = ", ".join(["%s"] * len(config["columns"]))
    source = qualified(source_db, config["table"])
    limit = FULL_LIMIT if config["grain"] == "FULL" else PERIOD_LIMIT
    stream = src.cursor(MySQLdb.cursors.SSCursor)
    count, total_bytes = 0, 0
    try:
        stream.execute(f"SELECT /*+ MAX_EXECUTION_TIME(60000) */ {cols} FROM {source}{clause} LIMIT {limit + 1}", args)
        pending = batches(stream)
        first = next(pending, None)
        existing = scalar(dst, f"SELECT 1 FROM {mirror}{clause} LIMIT 1", args)
        if run["kind"] == "INITIAL":
            require(not existing, "TARGET_NOT_EMPTY")
        elif existing:
            require(first is not None or run["accept_empty"], "EMPTY_RESULT")
            deleted = execute(dst, f"DELETE FROM {mirror}{clause} LIMIT {limit + 1}", args)
            require(deleted <= limit, "ROW_LIMIT")
        with dst.cursor() as writer:
            for batch, size in itertools.chain(() if first is None else (first,), pending):
                count += len(batch)
                total_bytes += size
                require(count <= limit and total_bytes <= TRANSACTION_BYTES, "ROW_LIMIT")
                written = writer.executemany(f"INSERT INTO {mirror} ({cols}) VALUES ({placeholders})", batch)
                require(written == len(batch), "COUNT_MISMATCH")
                time.sleep(BATCH_PAUSE_SECONDS)
    except MySQLdb.Error:
        src.close()
        raise SyncError("TRANSFER_ERROR") from None
    except BaseException:
        src.close()
        raise
    else:
        stream.close()
    return count


def copy_period(src, dst, task, run, config, mirror, day):
    clause, args = bounds(config, day) if day is not None else ("", ())
    update_phase(dst, run["id"], "READING", day)
    dst.autocommit(False)
    try:
        count = transfer(src, dst, config["source"]["database"], config, mirror, clause, args, run)
        if day is not None:
            execute(dst, "INSERT INTO dataagent_table_sync_coverage(task_id,business_date,run_id,row_count) VALUES(%s,%s,%s,%s) "
                    "ON DUPLICATE KEY UPDATE run_id=%s,row_count=%s",
                    (task["id"], day, run["id"], count, run["id"], count))
        execute(dst, "UPDATE dataagent_table_sync_run SET rows_read=rows_read+%s,rows_committed=rows_committed+%s WHERE id=%s",
                (count, count, run["id"]))
        if day is None:
            finish_run(dst, task, run)
        dst.commit()
    except BaseException:
        dst.rollback()
        dst.autocommit(True)
        raise
    dst.autocommit(True)


def finish_run(dst, task, run):
    if run["kind"] == "INITIAL":
        execute(dst, "UPDATE dataagent_table_sync_task SET initialized=TRUE WHERE id=%s", (task["id"],))
    execute(dst, "UPDATE dataagent_table_sync_run SET state='SUCCESS',finished_at=%s WHERE id=%s", (now(), run["id"]))


def publish_run(dst, task, run, password):
    config = json.loads(task["config"])
    target = task["target_table"]
    require(TARGET.fullmatch(target) and config.get("marker"), "TARGET_OWNERSHIP")
    require(table_comment(dst, TARGET_DB, target) == config["marker"], "TARGET_OWNERSHIP")
    assert_mirror_schema(dst, TARGET_DB, target, config)
    mirror = qualified(TARGET_DB, target)
    execute(dst, "SET SESSION innodb_lock_wait_timeout=10, SESSION time_zone=%s, "
            "SESSION sql_mode='STRICT_ALL_TABLES,NO_ZERO_DATE,NO_ZERO_IN_DATE,NO_ENGINE_SUBSTITUTION'",
            (config["timestampZone"],))
    update_phase(dst, run["id"], "CHECKING")
    source = config["source"]
    try:
        src = source_connection(source, password)
    except MySQLdb.Error:
        raise SyncError("SOURCE_QUERY") from None
    try:
        selection = {**config, "columns": [col["name"] for col in config["columns"]]}
        try:
            current = inspect(src, source, selection)
        except MySQLdb.Error:
            raise SyncError("SOURCE_QUERY") from None
        require(current == {key: value for key, value in config.items()
                            if key not in ("source", "credentialFormat", "marker")}, "SCHEMA_CHANGED")
        grain = config["grain"]
        if grain == "FULL":
            copy_period(src, dst, task, run, config, mirror, None)
            return
        done = {r["business_date"]: r["run_id"] for r in query(
            dst, "SELECT business_date,run_id FROM dataagent_table_sync_coverage WHERE task_id=%s", (task["id"],))}
        for day in periods(run["date_from"], run["date_through"], grain):
            committed = day in done if run["kind"] == "INITIAL" else done.get(day) == run["id"]
            if not committed:
                copy_period(src, dst, task, run, config, mirror, day)
        dst.autocommit(False)
        try:
            finish_run(dst, task, run)
            dst.commit()
        except BaseException:
            dst.rollback()
            dst.autocommit(True)
            raise
        dst.autocommit(True)
    finally:
        try:
            src.close()
        except MySQLdb.Error:
            pass


def run_job(dst, data):
    if scalar(dst, "SELECT GET_LOCK(%s,0)", (WORKER_LOCK,)) != 1:
        return {}
    run_id = data.get("id")
    require(isinstance(run_id, str) and ID.fullmatch(run_id), "INVALID_INPUT")
    run = one(dst, "SELECT * FROM dataagent_table_sync_run WHERE id=%s AND state IN ('QUEUED','RUNNING')", (run_id,))
    if not run:
        return {}
    task = find_task(dst, run["task_id"])
    execute(dst, "UPDATE dataagent_table_sync_run SET state='RUNNING',error_code=NULL,started_at=COALESCE(started_at,%s) WHERE id=%s",
            (now(), run_id))
    try:
        require(not data.get("credentialError"), "CREDENTIAL_ERROR")
        publish_run(dst, task, run, data.get("password"))
    except (SyncError, MySQLdb.Error) as error:
        reason = str(error) if isinstance(error, SyncError) else "DATABASE_ERROR"
        execute(dst, "UPDATE dataagent_table_sync_run SET state='FAILED',error_code=%s,finished_at=%s WHERE id=%s AND state='RUNNING'",
                (reason, now(), run_id))
    return {}


def serve(action, data, actor, target=None):
    if action == "check-target":
        return check_target(target)
    if action in ("tables", "columns", "preview", "test-date"):
        return explore(action, data, target)
    if action == "create":
        return create(data, actor, target)
    with contextlib.closing(target_connection(target)) as con:
        if action == "list":
            rows = query(con, "SELECT id,name,target_table,config,initialized,initial_from,initial_through,"
                         "schedule_time FROM dataagent_table_sync_task ORDER BY created_at DESC")
            return {"tasks": [task_view(con, row) for row in rows], "today": now().date()}
        if action == "detail":
            return detail(con, find_task(con, data.get("id")))
        if action in ("schedule", "run-now", "rerun"):
            return task_action(con, action, data, actor)
        if action == "next-job":
            return next_job(con, data.get("dispatch", True))
        if action == "run-job":
            return run_job(con, data)
    raise SyncError("INVALID_INPUT")


def encode(value):
    if isinstance(value, dt.datetime):
        return value.replace(tzinfo=ZONE).isoformat()
    if isinstance(value, dt.date):
        return value.isoformat()
    raise TypeError()


def main():
    try:
        request = json.loads(sys.stdin.read())
        value = serve(request["action"], request["input"], request["actor"], request.get("target"))
        print(json.dumps({"body": value}, default=encode, ensure_ascii=False))
    except SyncError as error:
        print(json.dumps({"error": str(error)}))
    except MySQLdb.Error:
        print('{"error":"DATABASE_ERROR"}')
    except (ValueError, TypeError, KeyError, OverflowError):
        print('{"error":"INVALID_INPUT"}')


if __name__ == "__main__":
    main()
