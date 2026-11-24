"""Process-local workaround for wrenai's MySQL LIMIT composition bug (0.15.0, 0.18.0).

Loaded only in platform-owned Wren subprocesses. Never changes installed files.
Remove after upgrading to an upstream release with equivalent regression tests.
"""

import os

# Versions verified to still append LIMIT blindly; others are left untouched.
PATCHED_VERSIONS = ("0.15.0", "0.18.0")


def _strip(sql, dialect):
    from wren.connector.base import strip_trailing_semicolon

    # 0.18 added an optional dialect argument; 0.15 accepts the SQL only.
    if dialect is None:
        return strip_trailing_semicolon(sql)
    return strip_trailing_semicolon(sql, dialect)


def apply_limit(sql, limit, dialect=None):
    from sqlglot import exp, parse
    from wren.connector.base import coerce_limit

    limit = coerce_limit(limit)
    if limit is None:
        return _strip(sql, dialect)
    cleaned = _strip(sql, dialect)
    statements = [s for s in parse(cleaned, read=dialect or "mysql") if s is not None]
    if len(statements) != 1 or not isinstance(statements[0], exp.Query):
        raise ValueError("Wren 限行兼容仅支持单条查询")
    outer = statements[0].args.get("limit")
    if outer is None:
        return f"{cleaned}\nLIMIT {limit}"
    count = outer.expression
    if not isinstance(count, exp.Literal) or count.is_string or not count.this.isdigit():
        raise ValueError("无法安全合并 SQL 的最外层 LIMIT，请使用非负整数字面量")
    start, end = count.meta.get("start"), count.meta.get("end")
    if start is None or end is None or cleaned[start:end + 1] != count.this:
        raise ValueError("无法定位 SQL 的最外层 LIMIT，未执行改写")
    effective = min(int(count.this), limit)
    # Splice only the outer row count; preserve ordering, offsets, comments,
    # duplicate projection names, quoting, hints and nested queries verbatim.
    return cleaned[:start] + str(effective) + cleaned[end + 1:]


def install():
    from importlib.metadata import version

    if version("wrenai") not in PATCHED_VERSIONS:
        return
    from wren.connector import mysql

    # Do not replace a downstream/upstream fix already installed under this version.
    if mysql._apply_limit("SELECT 1 LIMIT 1", 2) == "SELECT 1 LIMIT 1\nLIMIT 2":
        mysql._apply_limit = apply_limit


if os.environ.get("DATAAGENT_WREN_COMPAT") == "1":
    try:
        install()
    except Exception:
        # Python normally ignores sitecustomize errors; fail visibly instead of
        # quietly starting a process without the required compatibility fix.
        import sys
        import traceback

        traceback.print_exc(file=sys.stderr)
        sys.stderr.flush()
        os._exit(78)
