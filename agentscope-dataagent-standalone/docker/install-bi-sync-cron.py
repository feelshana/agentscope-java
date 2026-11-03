"""Install a single user cron entry only after an explicit successful initial sync."""
from datetime import datetime, timedelta, timezone
import argparse
import json
import os
from pathlib import Path
import subprocess
import time
import re
import sys

HERE = Path(__file__).resolve().parent
MARKER = "# DataAgent BI mirror v1"


def main(argv=()):
    parser = argparse.ArgumentParser(description="安装或移除本工具当前用户定时任务，保留其他任务")
    parser.add_argument("action", choices=["install", "remove"], nargs="?", default="install")
    args = parser.parse_args(argv)
    if args.action == "remove":
        remove()
        return
    if time.strftime("%z") != "+0800":
        raise ValueError("服务器调度时区不是东八区，请先确认调度时间；脚本不改系统时区")
    result = subprocess.run(["bash", str(HERE / "bi-sync.sh"), "status"],
                            capture_output=True, text=True, check=True)
    records = [line for line in result.stdout.splitlines() if line.startswith("{")]
    if len(records) != 1:
        raise ValueError("未取得唯一同步状态")
    status = json.loads(records[0])
    if status.get("paused"):
        raise ValueError("同步已暂停，请先resume并验收，再启用每日任务")
    today = datetime.now(timezone(timedelta(hours=8))).date().isoformat()
    if status.get("success_until") != today:
        raise ValueError("需先手动sync/verify并补齐至昨天，再启用每日任务")
    base = Path(os.environ.get("DATAAGENT_FORMAL_APP_DIR", "/data/dataagent/formal-app"))
    if base != Path("/data/dataagent/formal-app") or os.environ.get("DATAAGENT_FORMAL_PRIVATE_DIR"):
        raise ValueError("自动安装只适用于文档默认目录，自定义目录请手工配置")
    log = base / "logs/bi-sync/cron.log"
    # Paths entering crontab are code-owned or checked, never connection strings.
    command = str(HERE / "bi-sync.sh")
    if not re.fullmatch(r"/[A-Za-z0-9_./-]+", command):
        raise ValueError("源码路径包含调度特殊字符，请使用标准发布路径")
    prefix = "DATAAGENT_DOCKER_SUDO=false " if os.environ.get("DATAAGENT_DOCKER_SUDO") == "false" else ""
    entry = MARKER + "\n0 2 * * * " + prefix + "/bin/bash " + command + " sync >> " + str(log) + " 2>&1\n"
    current = subprocess.run(["crontab", "-l"], capture_output=True, text=True,
                             env=dict(os.environ, LC_ALL="C"))
    if current.returncode != 0 and not (current.returncode == 1 and "no crontab for" in current.stderr):
        raise ValueError("无法读取当前用户crontab")
    old = current.stdout if current.returncode == 0 else ""
    if MARKER in old:
        if entry in old:
            print("相同每日任务已存在，没有重复安装")
            return
        raise ValueError("已存在不同版本同步任务，请核对，不覆盖其他任务")
    subprocess.run(["crontab", "-"], input=old.rstrip() + "\n" + entry,
                   text=True, check=True)
    print("已添加当前用户每日02:00同步任务，原有任务保留")


def remove():
    # Removal must remain possible even when either database is unavailable.
    current = subprocess.run(["crontab", "-l"], capture_output=True, text=True,
                             env=dict(os.environ, LC_ALL="C"))
    if current.returncode != 0:
        if current.returncode == 1 and "no crontab for" in current.stderr:
            print("当前用户没有定时任务，无需移除")
            return
        raise ValueError("无法读取当前用户crontab")
    lines = current.stdout.splitlines(keepends=True)
    matches = [i for i, line in enumerate(lines) if line.rstrip("\r\n") == MARKER]
    if not matches:
        print("未找到本工具定时任务，没有修改其他任务")
        return
    if len(matches) != 1 or matches[0] + 1 >= len(lines):
        raise ValueError("任务标记不唯一或不完整，保留原配置，请核对")
    position = matches[0]
    command = str(HERE / "bi-sync.sh")
    log = "/data/dataagent/formal-app/logs/bi-sync/cron.log"
    expected = {"0 2 * * * " + prefix + "/bin/bash " + command + " sync >> " + log + " 2>&1"
                for prefix in ("", "DATAAGENT_DOCKER_SUDO=false ")}
    if lines[position + 1].rstrip("\r\n") not in expected:
        raise ValueError("标记后的任务不是当前工具标准命令，保留原配置，请核对")
    remaining = "".join(lines[:position] + lines[position + 2:])
    subprocess.run(["crontab", "-"], input=remaining, text=True, check=True)
    print("已移除本工具每日任务，其他任务/运行中的作业/数据库和文件未删除；请确认作业已暂停")


if __name__ == "__main__":
    try:
        main(sys.argv[1:])
    except (OSError, ValueError, subprocess.CalledProcessError) as error:
        print("定时任务未安装：" + (str(error) if isinstance(error, ValueError) else type(error).__name__))
        raise SystemExit(1)
