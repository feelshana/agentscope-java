"""Run standalone repository verification in hidden processes with retained logs."""
from pathlib import Path
import json,subprocess,sys,os
root=Path(__file__).resolve().parents[3]
logs=Path(__file__).resolve().parent
phase=sys.argv[1]
commands={'format':['mvn.cmd','spotless:apply'],'test':['mvn.cmd','test'],'package':['mvn.cmd','package','-DskipTests'],'frontend':['npm.cmd','run','build']}
cwd=root/'frontend' if phase=='frontend' else root
with (logs/f'development-{phase}.log').open('w',encoding='utf-8') as log:
    code=subprocess.call(commands[phase],cwd=cwd,stdout=log,stderr=subprocess.STDOUT,env=dict(os.environ,PYTHONUTF8='1'),shell=False)
(logs/f'development-{phase}-result.json').write_text(json.dumps({'phase':phase,'exit_code':code,'command':commands[phase],'cwd':str(cwd)},indent=2),encoding='utf-8')
sys.exit(code)
