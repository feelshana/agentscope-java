"""Restart only the verified isolated acceptance service on port 8085."""
import json
from pathlib import Path
import subprocess
import runpy
root=Path(__file__).resolve().parents[3]
listener=subprocess.check_output(['powershell','-NoProfile','-Command',"Get-NetTCPConnection -State Listen -LocalPort 8085 -ErrorAction SilentlyContinue | Select-Object -ExpandProperty OwningProcess -Unique"],creationflags=subprocess.CREATE_NO_WINDOW,text=True).strip()
pid=int(listener) if listener else json.loads((root/'.tmp-question-acceptance/process.json').read_text(encoding='utf-8'))['pid']
query=f"(Get-CimInstance Win32_Process -Filter 'ProcessId = {int(pid)}').CommandLine"
command=subprocess.check_output(['powershell','-NoProfile','-Command',query],creationflags=subprocess.CREATE_NO_WINDOW,text=True).strip()
if command:
    expected=str(root/'.tmp-question-acceptance/builds').lower()
    assert expected in command.lower() and '--server.port=8085' in command, 'Unexpected service process'
    subprocess.run(['powershell','-NoProfile','-Command',f'Stop-Process -Id {int(pid)} -ErrorAction Stop'],check=True,creationflags=subprocess.CREATE_NO_WINDOW)
runpy.run_path(str(Path(__file__).with_name('start_acceptance_service.py')),run_name='__main__')
