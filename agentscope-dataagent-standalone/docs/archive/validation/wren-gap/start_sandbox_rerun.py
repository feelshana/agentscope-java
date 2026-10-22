"""Archive previous chat attempts, then start isolated localhost validation."""
from pathlib import Path
from datetime import datetime
import subprocess
import sys
root=Path(__file__).resolve().parent
archive=root/'attempts'/datetime.now().strftime('%Y%m%d-%H%M%S')
archive.mkdir(parents=True,exist_ok=True)
for pattern in ('live-Q*-sse*.json*','live-python-sse*.json*'):
    for file in root.glob(pattern):
        file.rename(archive/file.name)
for phase in ('questions','python'):
    subprocess.run([sys.executable,str(root/'start_live.py'),phase],check=True)
print('Previous attempts archived:',archive)
