"""Start one isolated guided-workflow check with retained logs."""
import subprocess
import sys
from pathlib import Path
root = Path(__file__).resolve().parent
out = root / "guided-workflow"
out.mkdir(exist_ok=True)
phase = sys.argv[1]
log = (out / (phase + ".log")).open("w", encoding="utf-8")
process = subprocess.Popen([sys.executable, str(root / "guided_workflow_e2e.py"), phase],
                          stdout=log, stderr=subprocess.STDOUT,
                          creationflags=subprocess.CREATE_NO_WINDOW)
print(process.pid)
