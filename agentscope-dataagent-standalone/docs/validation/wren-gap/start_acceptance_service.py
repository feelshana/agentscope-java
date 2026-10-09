"""Launch an isolated acceptance server; do not touch the existing port 8080 process."""
import json
import os
from pathlib import Path
import subprocess
import shutil
import uuid

root = Path(__file__).resolve().parents[3]
runtime = root / ".tmp-question-acceptance"
runtime.mkdir(exist_ok=True)
env = os.environ.copy()
envfile = root / ".env"
if envfile.exists():
    for line in envfile.read_text(encoding="utf-8-sig").splitlines():
        line = line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        name, value = line.split("=", 1)
        env[name.strip()] = value.strip().strip('"').strip("'")
env.update({
    "DATAAGENT_DB_URL": "jdbc:h2:file:" + (runtime / "platform").as_posix() + ";MODE=MYSQL;DB_CLOSE_DELAY=-1",
    "DATAAGENT_DB_DRIVER": "org.h2.Driver",
    "DATAAGENT_DB_USER": "sa",
    "DATAAGENT_DB_PASSWORD": "",
    "DATAAGENT_SQL_INIT_PLATFORM": "h2",
    "DATAAGENT_WORKSPACE": str(runtime / "workspace"),
    "DATAAGENT_WREN_MDL_HOME": str(runtime / "mdl"),
    "DATAAGENT_WREN_EXECUTABLE": "D:/workspace/wren-probe/.venv/Scripts/wren.exe",
})
build = runtime / "builds" / uuid.uuid4().hex
build.mkdir(parents=True)
jar = build / "agentscope-dataagent-2.0.3-SNAPSHOT-exec.jar"
shutil.copy2(root / "target/agentscope-dataagent-2.0.3-SNAPSHOT-exec.jar", jar)
log = (runtime / "service.log").open("w", encoding="utf-8")
process = subprocess.Popen(
    ["java", "-jar", str(jar),
     "--server.port=8085", "--spring.profiles.active=h2"],
    cwd=root, env=env, stdout=log, stderr=subprocess.STDOUT,
    creationflags=subprocess.CREATE_NO_WINDOW)
(runtime / "process.json").write_text(json.dumps({"pid": process.pid, "port": 8085}), encoding="utf-8")
print(json.dumps({"pid": process.pid, "port": 8085}))
