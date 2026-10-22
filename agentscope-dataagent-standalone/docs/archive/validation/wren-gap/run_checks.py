"""Run repository checks in a separate process and record exact exit codes."""
import json
from pathlib import Path
import subprocess

ROOT = Path(__file__).resolve().parents[3]
OUT = Path(__file__).resolve().parent
checks = [
    ("backend_tests", ["mvn.cmd", "test"], ROOT),
    ("package", ["mvn.cmd", "package", "-DskipTests"], ROOT),
    ("frontend_build", ["npm.cmd", "run", "build"], ROOT / "frontend"),
]
results = []
for name, command, cwd in checks:
    with (OUT / (name + ".log")).open("w", encoding="utf-8") as log:
        result = subprocess.run(command, cwd=cwd, stdout=log, stderr=subprocess.STDOUT)
    results.append({"name": name, "command": command, "cwd": str(cwd), "exit_code": result.returncode})
    (OUT / "build-results.json").write_text(json.dumps(results, indent=2), encoding="utf-8")
