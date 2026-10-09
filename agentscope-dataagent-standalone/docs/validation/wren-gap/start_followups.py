"""Start sequential follow-ups with UTF-8 logs and no visible helper window."""
from pathlib import Path
import subprocess,sys,os
root=Path(__file__).resolve().parent
with (root/'live-followups.log').open('w',encoding='utf-8') as log:
    process=subprocess.Popen([sys.executable,str(root/'rerun_followups.py')],stdout=log,stderr=subprocess.STDOUT,env=dict(os.environ,PYTHONUTF8='1',PYTHONIOENCODING='utf-8'),creationflags=subprocess.CREATE_NO_WINDOW)
print(process.pid)
