"""Start checks without tying up the IDE terminal."""
from pathlib import Path
import subprocess
import sys

out = Path(__file__).resolve().parent
jobs = [
    [sys.executable, str(out / "run_checks.py")],
    [r"D:\workspace\wren-probe\.venv\Scripts\python.exe", str(out / "verify.py"),
     "--wren", r"D:\workspace\wren-probe\.venv\Scripts\wren.exe"],
]
for index, command in enumerate(jobs):
    with (out / f"runner-{index}.log").open("w", encoding="utf-8") as log:
        process = subprocess.Popen(command, stdout=log, stderr=subprocess.STDOUT,
                                   creationflags=subprocess.CREATE_NO_WINDOW)
    print(f"Started job {index}, PID {process.pid}")
