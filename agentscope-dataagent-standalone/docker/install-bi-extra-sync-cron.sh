#!/usr/bin/env bash
# Install/remove only this task's own exact cron entry; no host Python dependency.
set -Eeuo pipefail
here="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
runner="$here/bi-extra-sync.sh"
marker='# DataAgent BI extra mirror v1'
action="${1:-install}"
[[ $# -le 1 && "$action" =~ ^(install|remove)$ ]] || { echo '仅支持install/remove'; exit 1; }
[[ "$runner" =~ ^/[A-Za-z0-9_./-]+$ ]] || { echo '源码路径含调度特殊字符'; exit 1; }
prefix=''
[[ "${DATAAGENT_DOCKER_SUDO:-true}" == false ]] && prefix='DATAAGENT_DOCKER_SUDO=false '
suffix="/bin/bash $runner sync >> /data/dataagent/formal-app/logs/bi-extra-sync/cron.log 2>&1"
entry="15 2 * * * $prefix$suffix"
if current="$(LC_ALL=C crontab -l 2>&1)"; then
  :
elif [[ "$current" == *'no crontab for'* ]]; then
  current=''
else
  echo '读取crontab失败，未修改'
  exit 1
fi
lines=()
[[ -z "$current" ]] || mapfile -t lines <<< "$current"
position=-1
for ((i=0; i<${#lines[@]}; i++)); do
  if [[ "${lines[i]}" == "$marker" ]]; then
    [[ "$position" == -1 ]] || { echo '新任务标记重复，未修改'; exit 1; }
    position=$i
  fi
done
if [[ "$position" != -1 ]]; then
  next=${lines[position+1]:-}
  [[ "$next" == "15 2 * * * $suffix" || "$next" == "15 2 * * * DATAAGENT_DOCKER_SUDO=false $suffix" ]] || { echo '标记后不是本工具精确命令，未修改'; exit 1; }
fi
if [[ "$action" == remove ]]; then
  if [[ "$position" == -1 ]]; then
    echo '没有新六表任务，其他任务未修改'
  else
    unset 'lines[position]' 'lines[position+1]'
    printf '%s\n' "${lines[@]}" | crontab -
    echo '仅移除新六表任务；旧七表及其他任务保留，运行作业未停止'
  fi
  exit
fi
[[ "$(date +%z)" == +0800 ]] || { echo '服务器时区非东八区，未安装'; exit 1; }
[[ "${DATAAGENT_FORMAL_APP_DIR:-/data/dataagent/formal-app}" == /data/dataagent/formal-app \
   && -z "${DATAAGENT_FORMAL_PRIVATE_DIR:-}" ]] || { echo '自定义目录请手工配置调度'; exit 1; }
observed="$(bash "$runner" status)"
d=(sudo -n docker)
[[ "${DATAAGENT_DOCKER_SUDO:-true}" == false ]] && d=(docker)
printf '%s\n' "$observed" | "${d[@]}" exec -i dataagent66-formal-app-app-1 python -c '
import json,sys
from datetime import datetime,timezone,timedelta
lines=[x for x in sys.stdin.read().splitlines() if x.startswith("{")]
if len(lines)!=1: raise SystemExit("未取得唯一状态，未安装")
s=json.loads(lines[0])
today=datetime.now(timezone(timedelta(hours=8))).date().isoformat()
names={"bi_page_click_log","bi_iam_user","bi_tenant_optimization_daily",
       "bi_project_capacity_daily","bi_screen","bi_screen_visit_log"}
v=s.get("last_verify") or {}
last=s.get("last_run") or {}
if (s.get("paused") or s.get("running") or v.get("until")!=today
    or set(v.get("tables",[]))!=names or v.get("from_")!="2026-01-01"
    or last.get("action")!="verify"
    or last.get("outcome") not in ("SUCCESS","SUCCESS_WITH_GAPS")):
    raise SystemExit("先完成全六表sync/verify至昨天，确认未暂停，再安装")
for name in names-{"bi_iam_user","bi_screen"}:
    if (s.get("tables",{}).get(name) or {}).get("success_until")!=today:
        raise SystemExit("日期表进度未到昨天，未安装")
    if any(g.get("reason")=="NOT_PROCESSED" for g in s["tables"][name].get("gaps",[])):
        raise SystemExit("还有未处理日期，请先完整回填，未安装")
print("新任务首次同步及核对状态通过；缺失历史以status为准")
'
if [[ "$position" != -1 ]]; then
  [[ "${lines[position+1]}" == "$entry" ]] || { echo '已有不同权限模式的新任务，保留不覆盖'; exit 1; }
  echo '相同六表02:15任务已存在，无重复安装'
  exit
fi
{ [[ -z "$current" ]] || printf '%s\n' "$current"; printf '%s\n%s\n' "$marker" "$entry"; } | crontab -
echo '已添加独立六表每日02:15任务，旧02:00七表及其他任务保留'
