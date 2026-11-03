#!/usr/bin/env python3
"""Restore the approved onboarding files as bytes without tracking runtime copies."""

import argparse
import hashlib
import sys
from pathlib import Path


MODULE_DIR = Path(__file__).resolve().parents[2]
SOURCE_DIR = Path(__file__).with_name("synthetic-v1")
FILES = ("演示订单.xlsx", "演示客户.xlsx")


def restore(directory):
    manifest = (SOURCE_DIR / "SHA256SUMS").read_bytes()
    expected = dict(
        (name, digest)
        for digest, name in (
            line.split("  ", 1)
            for line in manifest.decode("utf-8").splitlines()
            if line.strip()
        )
    )
    if set(expected) != set(FILES):
        raise ValueError("示例校验清单与指定的两份文件不一致")

    # Validate every existing and bundled synthetic file before writing anything.
    pending = {}
    for name in FILES:
        target = directory / name
        if target.exists():
            data = target.read_bytes()
        else:
            source = SOURCE_DIR / name
            if not source.is_file():
                raise RuntimeError("缺少通用示例文件，请同步包含synthetic-v1的项目代码")
            data = source.read_bytes()
            pending[target] = data
        if hashlib.sha256(data).hexdigest() != expected[name]:
            raise ValueError(f"{name} 校验不一致，保留现有文件，未覆盖或恢复其他文件")

    checksum_file = directory / "SHA256SUMS"
    if checksum_file.exists() and checksum_file.read_bytes() != manifest:
        raise ValueError("目标目录已有不同校验清单，保留现有文件，未写入")
    if not checksum_file.exists():
        pending[checksum_file] = manifest

    directory.mkdir(parents=True, exist_ok=True)
    for target, data in pending.items():
        # Exclusive creation protects files concurrently supplied by the operator.
        with target.open("xb") as output:
            output.write(data)
    for name in FILES:
        print(f"{name}: OK")
    print(f"示例目录：{directory}")


def main():
    parser = argparse.ArgumentParser(description="恢复本地新手示例文件并校验，不覆盖已有文件")
    parser.add_argument(
        "--directory", type=Path, default=MODULE_DIR / "data" / "demo-samples"
    )
    args = parser.parse_args()
    try:
        restore(args.directory.resolve())
    except (OSError, ValueError, RuntimeError) as error:
        print(f"准备失败：{error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
