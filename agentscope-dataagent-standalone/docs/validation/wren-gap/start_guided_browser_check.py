"""Launch the headless browser check without a visible helper window."""
import subprocess
import sys
from pathlib import Path
root = Path(__file__).resolve().parent
out = root / "guided-workflow"
out.mkdir(exist_ok=True)
log = (out / ("browser-snapshot.log" if "--snapshot" in sys.argv else "browser.log")).open("w", encoding="utf-8")
process = subprocess.Popen([sys.executable, str(root / "guided_browser_check.py"), *sys.argv[1:]],
                          stdout=log, stderr=subprocess.STDOUT,
                          creationflags=subprocess.CREATE_NO_WINDOW)
print(process.pid)
