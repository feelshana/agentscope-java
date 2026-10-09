"""Start bounded Docker readiness checks in the background."""
from pathlib import Path
import os
import subprocess
import sys
root=Path(__file__).resolve().parent
with (root/"live-docker-probe.log").open("w",encoding="utf-8") as log:
    p=subprocess.Popen([sys.executable,str(root/"docker_probe.py")],stdout=log,stderr=subprocess.STDOUT,
        env=dict(os.environ,PYTHONUTF8="1"),creationflags=subprocess.CREATE_NO_WINDOW)
print("Docker readiness probe PID",p.pid)
