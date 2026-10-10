"""Start an acceptance phase without keeping the IDE terminal request blocked."""
from pathlib import Path
import subprocess
import sys
root = Path(__file__).resolve().parent
out = root / "question-acceptance"
out.mkdir(exist_ok=True)
phase = sys.argv[1]
assert phase in {"model", "verify", "query"}
with (out / (phase + ".log")).open("w", encoding="utf-8") as log:
    p = subprocess.Popen([sys.executable, str(root / "question_acceptance_e2e.py"), phase],
        cwd=root.parents[2], stdout=log, stderr=subprocess.STDOUT,
        creationflags=subprocess.CREATE_NO_WINDOW)
print(p.pid)
