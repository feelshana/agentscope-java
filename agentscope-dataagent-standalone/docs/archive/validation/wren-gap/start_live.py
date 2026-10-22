"""Start a validation phase with hidden process and local logs."""
from pathlib import Path
import os
import subprocess
import sys
root=Path(__file__).resolve().parent
phase=sys.argv[1]
with (root/f"live-runner-{phase}.log").open("w",encoding="utf-8") as log:
    process=subprocess.Popen([sys.executable,str(root/"live_validation.py"),*sys.argv[1:]],
       stdout=log,stderr=subprocess.STDOUT,env=dict(os.environ,PYTHONUTF8="1",PYTHONIOENCODING="utf-8"),creationflags=subprocess.CREATE_NO_WINDOW)
print(f"Started {phase}, PID {process.pid}")
