"""Launch browser validation in a hidden process and retain output."""
import subprocess,sys
from pathlib import Path
root=Path(__file__).resolve().parent
out=root.parent/"question-review"
out.mkdir(exist_ok=True)
log=(out/"browser.log").open("w",encoding="utf-8")
process=subprocess.Popen([sys.executable,str(root/"question_review_browser_check.py")],stdout=log,stderr=subprocess.STDOUT,creationflags=subprocess.CREATE_NO_WINDOW)
print(process.pid)
