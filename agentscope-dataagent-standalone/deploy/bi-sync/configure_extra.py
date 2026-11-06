"""Create only the new private connection file, interactively, without echoing credentials."""
import argparse
import getpass
import json
import os
from pathlib import Path
import sys

from extra_sync import load_config

DEFAULTS = {
    "iam": dict(host="10.194.146.73", port=3361, database="iam"),
    "capacity": dict(host="10.194.146.9", port=3661, database="dataanalysis1"),
    "optimization": dict(host="10.194.146.73", port=3368, database="platform"),
    "click": dict(host="10.194.155.188", port=9030, database="fenxiyun"),
}


def prepare(path, base_path, ask=getpass.getpass):
    path, base_path = Path(path), Path(base_path)
    if path.exists() or path.is_symlink() or path == base_path:
        raise ValueError("新配置已存在，保留原文件不覆盖")
    if not path.parent.is_dir() or path.parent.is_symlink():
        raise ValueError("请使用已有正式私有目录")
    import bi_sync
    bi_sync.load_config(base_path)
    sources = {}
    for name, ep in DEFAULTS.items():
        user = ask(name + " 源库账号（不回显）：")
        password = ask(name + " 源库密码（不回显）：")
        if any(not value or any(c in value for c in "\r\n\x00") for value in (user, password)):
            raise ValueError("连接账号和密码不能为空或包含换行")
        sources[name] = dict(ep, user=user, password=password)
    config = dict(version=1, batch_size=128, lookback_days=3, sources=sources)
    fd = os.open(path, os.O_CREAT | os.O_EXCL | os.O_WRONLY, 0o600)
    with os.fdopen(fd, "w", encoding="utf-8") as stream:
        stream.write(json.dumps(config, ensure_ascii=False, indent=2) + "\n")
    load_config(path, base_path)


def main(argv=None):
    parser = argparse.ArgumentParser(description="生成六表私有配置；不连接数据库")
    parser.add_argument("--config", type=Path,
                        default=Path("/data/dataagent/formal-private/bi-sync-extra.json"))
    parser.add_argument("--base-config", type=Path,
                        default=Path("/data/dataagent/formal-private/bi-sync.json"))
    args = parser.parse_args(argv)
    if not sys.stdin.isatty():
        parser.error("请使用交互终端；密码不接受命令行或管道")
    try:
        prepare(args.config, args.base_config)
    except Exception as exc:
        print("新配置未完成，已有文件保留；错误类型=" + type(exc).__name__, file=sys.stderr)
        return 1
    print("新配置已写入600权限私有文件；旧配置未改，未连接数据库")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
