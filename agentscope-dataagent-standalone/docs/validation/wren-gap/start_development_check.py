"""Start one project check without blocking IDE terminal or showing helper windows."""
import subprocess,sys
from pathlib import Path
root=Path(__file__).resolve().parent
process=subprocess.Popen([sys.executable,str(root/'run_development_checks.py'),sys.argv[1]],creationflags=subprocess.CREATE_NO_WINDOW)
print(process.pid)
