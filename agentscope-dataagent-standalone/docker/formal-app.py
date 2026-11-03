"""Explicit formal environment preparation and reversible service-port handover."""
import argparse
import os
from pathlib import Path
import re
import subprocess
import sys
import json
import urllib.request

HERE = Path(__file__).resolve().parent
BASE = Path(os.environ.get("DATAAGENT_FORMAL_APP_DIR", "/data/dataagent/formal-app"))
OLD = Path("/data/dataagent/rehearsal-app")
PROJECT = "dataagent66-formal-app"
OLD_PROJECT = "dataagent66-rehearsal-app"
PRIVATE = Path(os.environ.get("DATAAGENT_FORMAL_PRIVATE_DIR", "/data/dataagent/formal-private"))


def values(path):
    return dict(line.split("=", 1) for line in path.read_text().splitlines()
                if line and not line.startswith("#") and "=" in line)


def valid_base():
    if not re.fullmatch(r"/[A-Za-z0-9_./-]+", str(BASE)) or BASE.is_symlink():
        raise ValueError("正式路径无效")
    resolved, old = BASE.resolve(), OLD.resolve()
    if (resolved in (Path("/"), Path("/data"), Path("/data/dataagent"), old)
            or old in resolved.parents or resolved in old.parents):
        raise ValueError("正式路径不能复用旧环境或根目录")


def control(action, image=None, old=False):
    env = dict(os.environ)
    env.update(DATAAGENT_REHEARSAL_APP_DIR=str(OLD if old else BASE),
               DATAAGENT_REHEARSAL_APP_PROJECT=OLD_PROJECT if old else PROJECT)
    if not old:
        env.update(DATAAGENT_DB_MODE="external",
                   DATAAGENT_EXTERNAL_DB_ENV=str(PRIVATE / "database.env"),
                   DATAAGENT_PORT="18081")
        if action == "prepare":
            previous = marked(OLD, OLD_PROJECT)
            env["DATAAGENT_MODEL_CACHE"] = previous["APP_CACHE"]
            env["DATAAGENT_BIND_IP"] = previous["APP_BIND_IP"]
    # Service ports in compose.env, not inherited deployment shell overrides, are authoritative.
    env.pop("APP_PORT", None)
    subprocess.run(["bash", str(HERE / "rehearsal-app.sh"), action]
                   + ([image] if image else []), env=env, check=True)


def marked(base, project):
    if (base / ".dataagent-app").read_text().strip() != str(base) + "|" + project:
        raise ValueError("应用目录标记不匹配")
    config = values(base / "compose.env")
    if config.get("APP_BASE") != str(base):
        raise ValueError("应用目录配置不匹配")
    return config


def set_port(port):
    p = BASE / "compose.env"
    content = p.read_text()
    if len(re.findall(r"^APP_PORT=.*$", content, re.M)) != 1:
        raise ValueError("端口配置不是唯一值")
    temporary = p.with_suffix(".env.next")
    fd = os.open(temporary, os.O_CREAT | os.O_EXCL | os.O_WRONLY, 0o600)
    with os.fdopen(fd, "w") as stream:
        stream.write(re.sub(r"^APP_PORT=.*$", "APP_PORT=" + str(port), content, flags=re.M))
        stream.flush()
        os.fsync(stream.fileno())
    temporary.replace(p)


def prepare(image):
    control("prepare", image)
    marked(BASE, PROJECT)
    marker = BASE / ".formal-ready"
    if marker.exists():
        print("正式配置已存在，保留现有文件")
        return
    # A new environment intentionally has fresh secrets and no copied model configuration.
    path = BASE / "runtime.env"
    content = path.read_text()
    content = re.sub(r"^DATAAGENT_(DEMO_ENABLED|DEMO_PASSWORD|DEMO_SAMPLES_DIR|REQUIRE_PLATFORM_USERS)=.*\n?",
                     "", content, flags=re.M)
    content += ("DATAAGENT_DEMO_ENABLED=true\nDATAAGENT_DEMO_PASSWORD=''\n"
                "DATAAGENT_DEMO_SAMPLES_DIR=" + str(BASE / "data/demo-samples") + "\n"
                "DATAAGENT_REQUIRE_PLATFORM_USERS=true\n")
    path.write_text(content)
    os.chmod(path, 0o600)
    subprocess.run([sys.executable, str(HERE.parent / "deploy/demo-samples/restore-local.py"),
                    "--directory", str(BASE / "data/demo-samples")], check=True)
    marker.write_text("formal-v1\n")
    print("正式候选环境已准备：18081；未启动或停止旧服务")


def candidate_ready():
    config = marked(BASE, PROJECT)
    docker = ["docker"] if os.environ.get("DATAAGENT_DOCKER_SUDO") == "false" else ["sudo", "docker"]
    ids = subprocess.check_output(docker + ["ps", "-q", "--filter",
        "label=com.docker.compose.project=" + PROJECT,
        "--filter", "label=com.docker.compose.service=app"], timeout=15).decode().split()
    if len(ids) != 1:
        raise ValueError("请先start候选应用并等待健康，再切换")
    result = json.loads(subprocess.check_output(docker + ["inspect", ids[0]], timeout=15))[0]
    if (result["Config"]["Labels"].get("dataagent.rehearsal.app") != str(BASE)
            or result.get("State", {}).get("Health", {}).get("Status") != "healthy"):
        raise ValueError("候选容器归属或健康状态不符，保留旧服务")
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    with opener.open("http://" + config["APP_BIND_IP"] + ":18081/actuator/health", timeout=10) as response:
        if json.load(response).get("status") != "UP":
            raise ValueError("候选健康检查失败，保留旧服务")


def handover(revert=False):
    import fcntl
    marked(BASE, PROJECT)
    marked(OLD, OLD_PROJECT)
    if not (BASE / ".formal-ready").is_file():
        raise ValueError("正式准备尚未完成")
    with (BASE / ".handover.lock").open("a") as lock:
        fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        if revert:
            # Precheck the old environment before stopping the current formal service.
            control("check", old=True)
            control("stop")
            try:
                control("start", old=True)
            except subprocess.CalledProcessError:
                control("start")
                raise
            set_port(18081)
            print("已恢复旧应用环境；正式库及全部目录保留，未回滚数据")
            return
        current = marked(BASE, PROJECT)
        if current.get("APP_PORT") == "18080":
            print("正式配置已使用18080；请执行status核对，不重复切换")
            return
        if current.get("APP_PORT") != "18081" or marked(OLD, OLD_PROJECT).get("APP_PORT") != "18080":
            raise ValueError("只支持候选18081接替原18080，请核对配置")
        if (BASE / "compose.env.next").exists():
            raise ValueError("存在未完成的配置暂存文件，保留旧服务并先核对现场")
        control("check")
        candidate_ready()
        control("stop", old=True)
        try:
            control("stop")
            set_port(18080)
            control("start")
        except BaseException:
            # Preserve both databases and every configuration; only restore service routing.
            try:
                control("stop")
            except Exception:
                print("正式容器停止失败，仍尝试恢复旧服务，请核对端口", file=sys.stderr)
            try:
                if marked(BASE, PROJECT).get("APP_PORT") != "18081":
                    set_port(18081)
            except Exception:
                print("候选端口配置未恢复，请保留暂存文件；仍尝试恢复旧服务", file=sys.stderr)
            control("start", old=True)
            raise
        print("正式应用已接替18080；旧MySQL/应用目录保留。请从BI重新进入并配置模型服务")


def main():
    parser = argparse.ArgumentParser(description="正式候选环境与服务切换，不复制旧测试业务数据")
    parser.add_argument("action", choices=["prepare", "check", "start", "stop", "status", "logs",
                                          "upgrade", "rollback", "activate", "revert-service"])
    parser.add_argument("image", nargs="?")
    args = parser.parse_args()
    valid_base()
    if args.action == "prepare":
        if not args.image:
            parser.error("prepare必须明确指定构建记录中的镜像")
        prepare(args.image)
    elif args.action in ("activate", "revert-service"):
        handover(args.action == "revert-service")
    else:
        control(args.action, args.image)


if __name__ == "__main__":
    try:
        main()
    except (OSError, ValueError, subprocess.CalledProcessError) as error:
        print("正式环境操作未完成：" + type(error).__name__
              + "；保留现场，核对上一条非敏感检查输出", file=sys.stderr)
        raise SystemExit(1)
