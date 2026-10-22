"""Start read-only published snapshot query probes."""
import os
from pathlib import Path
import subprocess
import sys
root=Path(__file__).resolve().parent
with (root/"live-published-wren.log").open("w",encoding="utf-8") as log:
    process=subprocess.Popen([r"D:\workspace\wren-probe\.venv\Scripts\python.exe",str(root/"live_published_wren.py")],stdout=log,stderr=subprocess.STDOUT,
        env=dict(os.environ,PYTHONUTF8="1"),creationflags=subprocess.CREATE_NO_WINDOW)
print("Published Wren probe PID",process.pid)
