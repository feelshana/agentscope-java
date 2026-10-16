"""Audit a release archive; optionally exercise the real launcher with a local JVM stub on Linux.

The stub isolates process control/readiness from Java/Wren/database installation. Real Wren
dependencies and the real JAR are verified separately. No existing service or data is touched.
"""
import argparse
import io
import json
import os
from pathlib import Path
import shutil
import socket
import subprocess
import tarfile
import tempfile
import time
import zipfile


def archive_checks(path):
    with tarfile.open(path, "r:gz") as archive:
        names = archive.getnames()
        for name in names:
            assert not name.startswith("/") and ".." not in Path(name).parts, name
            assert "application-dev." not in name, name
            assert not name.endswith(("application-prod.yml", "application-prod.properties",
                                      "application-prod.yaml")), name
        jars = [n for n in names if n.endswith("-exec.jar")]
        assert len(jars) == 1, jars
        assert any(n.endswith("/conf/application-prod.yml.example") for n in names)
        for suffix in ("/bin/dataagent.sh", "/bin/check-wren.py"):
            member = next(archive.getmember(n) for n in names if n.endswith(suffix))
            assert member.mode & 0o111
            assert b"\r\n" not in archive.extractfile(member).read()
        with zipfile.ZipFile(io.BytesIO(archive.extractfile(jars[0]).read())) as jar:
            resources = jar.namelist()
            assert "BOOT-INF/classes/application.yml" in resources
            assert "BOOT-INF/classes/application-h2.yml" in resources
            assert "BOOT-INF/classes/static/index.html" in resources
            assert not any(n.startswith(("BOOT-INF/classes/application-dev.",
                                         "BOOT-INF/classes/application-prod.")) for n in resources)
    return {"archive": str(path), "layoutAndPermissions": True,
            "privateConfigExcluded": True, "publicDefaultsAndFrontendIncluded": True}


def linux_checks(source):
    assert os.name == "posix" and Path("/proc").is_dir()
    with tempfile.TemporaryDirectory(prefix="dataagent-release-") as temporary:
        base = Path(temporary)
        release = base / "dataagent-test-v1"
        shutil.copytree(source / "src/main/bin", release / "bin")
        shutil.copyfile(source / "requirements-wren.txt", release / "requirements-wren.txt")
        for script in (release / "bin").iterdir():
            script.chmod(0o755)
        (release / "conf").mkdir()
        (release / "lib").mkdir()
        (release / "lib/test-exec.jar").touch()
        java = base / "java-stub"
        java.write_text('''#!/usr/bin/env python3
import json, os, sys, time
from http.server import BaseHTTPRequestHandler, HTTPServer
port=int(next(a.split('=',1)[1] for a in sys.argv if a.startswith('--server.port=')))
time.sleep(float(os.environ.get('TEST_READY_DELAY','0')))
class Handler(BaseHTTPRequestHandler):
    def log_message(self,*args): pass
    def do_GET(self):
        up=os.environ.get('TEST_NEVER_HEALTHY')!='1'
        self.send_response(200 if up else 503);self.end_headers()
        self.wfile.write(json.dumps({'status':'UP' if up else 'DOWN'}).encode())
HTTPServer(('127.0.0.1',port),Handler).serve_forever()
''', encoding="utf-8")
        java.chmod(0o755)
        wren = base / "wren-stub"
        wren.write_text("#!/bin/sh\necho 'wrenai 0.15.0'\n", encoding="utf-8")
        wren.chmod(0o755)
        python = base / "python-wrapper"
        python.write_text('''#!/usr/bin/env python3
import os,sys
if len(sys.argv)>2 and sys.argv[2]=='dependencies':
    sys.exit(1 if os.environ.get('TEST_BAD_DEPENDENCIES')=='1' else 0)
os.execvp('python3',['python3',*sys.argv[1:]])
''', encoding="utf-8")
        python.chmod(0o755)
        with socket.socket() as probe:
            probe.bind(("127.0.0.1", 0))
            port = probe.getsockname()[1]
        env = {**os.environ, "DATAAGENT_JAVA": str(java),
               "DATAAGENT_WREN_EXECUTABLE": str(wren), "DATAAGENT_WREN_PYTHON": str(python),
               "DATAAGENT_PORT": str(port), "DATAAGENT_START_TIMEOUT": "10"}
        env.pop("DATAAGENT_HEALTH_URL", None)
        env["DATAAGENT_PROFILE"] = "prod"
        script = release / "bin/dataagent.sh"
        pidfile = base / "data/run/application.pid"
        cases = []

        def run(action, success, overrides=None, command=script):
            result = subprocess.run(["bash", str(command), action], env={**env, **(overrides or {})},
                                    capture_output=True, text=True, timeout=30)
            assert (result.returncode == 0) == success, (action, result.stdout, result.stderr)
            return result

        def cleanup():
            if pidfile.exists():
                run("stop", True)

        try:
            assert "缺少" in run("start", False).stdout
            cases.append("missing-target-config")
            (release / "conf/application-prod.yml").write_text("# isolated fixture\n")
            assert "未找到Wren" in run("start", False, {"DATAAGENT_WREN_EXECUTABLE": "/missing/wren"}).stdout
            cases.append("missing-wren")
            run("start", False, {"TEST_BAD_DEPENDENCIES": "1"})
            assert not pidfile.exists()
            cases.append("failed-dependencies-do-not-launch")
            with socket.socket() as occupied:
                occupied.bind(("0.0.0.0", port)); occupied.listen()
                run("start", False)
                assert not pidfile.exists()
            cases.append("occupied-port-does-not-launch")
            started = time.monotonic()
            assert "已就绪" in run("start", True, {"TEST_READY_DELAY": "2"}).stdout
            assert time.monotonic() - started >= 2
            cases.append("waits-for-http-readiness")
            run("start", False)
            cases.append("duplicate-start-rejected")
            record = pidfile.read_text()
            pid, ticks, jar = record.splitlines()
            try:
                pidfile.write_text(f"{pid}\n{int(ticks)+1}\n{jar}\n")
                run("stop", False)
                os.kill(int(pid), 0)
                pidfile.write_text(f"{pid}\n{ticks}\n{base}/outside.jar\n")
                run("stop", False)
                os.kill(int(pid), 0)
            finally:
                pidfile.write_text(record)
            cases.append("pid-reuse-and-foreign-jar-rejected")
            sentinel = base / "data/workspace/keep.txt"
            sentinel.write_text("keep")
            run("stop", True, {"DATAAGENT_WREN_EXECUTABLE": "/missing/wren",
                               "DATAAGENT_WREN_PYTHON": "/missing/python"})
            assert not pidfile.exists() and sentinel.read_text() == "keep"
            cases.append("stop-without-wren-keeps-data")
            run("start", False, {"TEST_NEVER_HEALTHY": "1", "DATAAGENT_START_TIMEOUT": "2"})
            assert not pidfile.exists()
            cases.append("unhealthy-start-terminated")
            run("start", True)
            other = base / "dataagent-test-v2"
            shutil.copytree(release / "bin", other / "bin")
            run("start", False, command=other / "bin/dataagent.sh")
            run("stop", True, command=other / "bin/dataagent.sh")
            assert not pidfile.exists() and sentinel.read_text() == "keep"
            cases.append("sibling-release-shares-process-record")
            return cases
        finally:
            cleanup()


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--archive", type=Path)
    parser.add_argument("--linux-source", type=Path)
    args = parser.parse_args()
    result = {}
    if args.archive:
        result.update(archive_checks(args.archive))
    if args.linux_source:
        result["linuxLauncherCases"] = linux_checks(args.linux_source)
    assert result, "Specify --archive or --linux-source"
    print(json.dumps(result, ensure_ascii=False, indent=2))
