"""Read-only application database checks; supplied to the candidate container via stdin."""
import os
import re
from urllib.parse import parse_qs, urlsplit


def endpoint(url):
    if not url.startswith("jdbc:mysql://"):
        raise ValueError("仅支持明确指定库的MySQL JDBC地址")
    parsed = urlsplit(url[5:])
    database = parsed.path.removeprefix("/")
    if (not parsed.hostname or parsed.username or parsed.password or parsed.fragment
            or not re.fullmatch(r"[A-Za-z0-9_]+", database)):
        raise ValueError("数据库地址必须包含主机和单一库名，账号密码单独配置")
    if any(k.lower() in {"user", "username", "password"} for k in parse_qs(parsed.query)):
        raise ValueError("数据库地址不能内嵌账号密码")
    port = parsed.port or 3306
    if not 1 <= port <= 65535:
        raise ValueError("数据库端口无效")
    return parsed.hostname, port, database


def check_databases():
    import MySQLdb
    for prefix in ("DATAAGENT_", "DATAAGENT_DATASET_"):
        host, port, database = endpoint(os.environ[prefix + "DB_URL"])
        connection = MySQLdb.connect(
            host=host, port=port, db=database, user=os.environ[prefix + "DB_USER"],
            passwd=os.environ[prefix + "DB_PASSWORD"], charset="utf8mb4",
            connect_timeout=10, read_timeout=30, write_timeout=30,
        )
        try:
            with connection.cursor() as cursor:
                cursor.execute("SELECT VERSION(), DATABASE()")
                version, actual = cursor.fetchone()
                if int(version.split(".")[0]) < 8 or actual != database:
                    raise ValueError("正式运行需要MySQL8及正确的目标库")
                if prefix == "DATAAGENT_" and os.environ.get("DATAAGENT_REQUIRE_PLATFORM_USERS") == "true":
                    cursor.execute("SELECT COUNT(*) FROM dataagent_user")
                    if cursor.fetchone()[0] < 1:
                        raise ValueError("平台库没有用户，请核对初始化")
                    cursor.execute("SELECT COUNT(*) FROM dataagent_user WHERE "
                                   "FIND_IN_SET('admin', COALESCE(roles_csv,'')) > 0")
                    if cursor.fetchone()[0] < 1:
                        raise ValueError("平台库尚未设置管理员")
            print("数据库只读预检通过：" + database, flush=True)
        finally:
            connection.close()


if __name__ == "__main__":
    try:
        check_databases()
    except Exception as error:
        # Connector errors can include deployment details; never echo raw exceptions.
        print("数据库预检失败：请核对私有连接、版本、平台结构和管理员（"
              + type(error).__name__ + "）", flush=True)
        raise SystemExit(1)
