"""Create private connection files interactively; never source them as shell code."""
import argparse
import getpass
import json
import os
from pathlib import Path


def secret(label):
    value = getpass.getpass(label + "：")
    if not value or any(c in value for c in "\r\n\x00"):
        raise ValueError("密码不能为空或含换行")
    if getpass.getpass("再次输入确认：") != value:
        raise ValueError("两次密码不一致，未生成配置")
    return value


def dotenv(value):
    # Compose expands double dollars once; JSON quoting preserves slashes/quotes exactly.
    return json.dumps(value.replace("$", "$$"), ensure_ascii=False)


def prepare(directory, target_password, source_password):
    directory = Path(directory)
    if directory.is_symlink():
        raise ValueError("私有配置目录不能是符号链接")
    if directory.exists() and (not directory.is_dir() or any(directory.iterdir())):
        raise ValueError("目标目录已有内容，保留现有文件和权限，不覆盖")
    if directory.resolve() in (Path("/"), Path("/data"), Path("/data/dataagent"), Path.home().resolve()):
        raise ValueError("请使用专用私有配置子目录")
    for value in (target_password, source_password):
        if not isinstance(value, str) or not value or any(c in value for c in "\r\n\x00"):
            raise ValueError("密码不能为空或含换行")
    if any((directory / name).exists() for name in ("database.env", "bi-sync.json")):
        raise ValueError("私有配置已存在，保留原文件，不覆盖")
    directory.mkdir(parents=True, exist_ok=True, mode=0o700)
    os.chmod(directory, 0o700)
    source = dict(host="10.194.146.9", port=3664, database="easybi",
                  user="easybi", password=source_password)
    target = dict(host="10.194.146.153", port=3322, database="data_agent",
                  user="data_agent", password=target_password)
    lines = []
    for prefix, database in (("DATAAGENT_", "data_agent_platform"),
                             ("DATAAGENT_DATASET_", "data_agent")):
        url = ("jdbc:mysql://10.194.146.153:3322/" + database
               + "?useUnicode=true&characterEncoding=UTF-8&serverTimezone=Asia/Shanghai")
        lines += [prefix + "DB_URL=" + dotenv(url), prefix + "DB_USER='data_agent'",
                  prefix + "DB_PASSWORD=" + dotenv(target_password)]
    config = dict(version=1, start_date="2026-01-01", batch_size=1000,
                  source=source, target=target)
    for name, text in (("database.env", "\n".join(lines) + "\n"),
                       ("bi-sync.json", json.dumps(config, ensure_ascii=False, indent=2) + "\n")):
        fd = os.open(directory / name, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
        with os.fdopen(fd, "w", encoding="utf-8") as stream:
            stream.write(text)


def main():
    parser = argparse.ArgumentParser(description="准备正式应用和BI同步私有连接；不连接数据库")
    parser.add_argument("--directory", type=Path, default=Path("/data/dataagent/formal-private"))
    args = parser.parse_args()
    if not os.isatty(0):
        parser.error("请在交互终端执行，密码不接受命令行参数或管道")
    try:
        prepare(args.directory, secret("正式data_agent账号密码"), secret("BI从库easybi账号密码"))
    except (OSError, ValueError) as error:
        print("配置未完成：" + str(error))
        return 1
    print("私有配置已准备（600权限）；未连接数据库、未启动应用，勿提交Git")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
