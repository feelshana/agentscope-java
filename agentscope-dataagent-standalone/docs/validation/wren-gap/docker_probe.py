"""Poll Docker readiness without changing containers."""
import json
from pathlib import Path
import subprocess
import time
root=Path(__file__).resolve().parent
status={}
for attempt in range(30):
    try:
        result=subprocess.run(["docker","info","--format","{{.ServerVersion}}"],capture_output=True,text=True,timeout=15)
        status={"attempt":attempt+1,"ready":result.returncode==0,"stdout":result.stdout.strip(),"stderr":result.stderr.strip()}
    except subprocess.TimeoutExpired:
        result=None
        status={"attempt":attempt+1,"ready":False,"stderr":"docker info timed out after 15 seconds"}
    (root/"live-docker-status.json").write_text(json.dumps(status,indent=2),encoding="utf-8")
    if status["ready"]:
        images=subprocess.run(["docker","image","ls","--format","{{.Repository}}:{{.Tag}}"],capture_output=True,text=True,timeout=15)
        status["images"]=images.stdout.splitlines()
        (root/"live-docker-status.json").write_text(json.dumps(status,indent=2),encoding="utf-8")
        break
    time.sleep(5)
print(json.dumps(status))
