#!/usr/bin/env bash
# Independent task; no changes to the original seven-table runner or cron.
set -Eeuo pipefail
umask 077
here="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
base="${DATAAGENT_FORMAL_APP_DIR:-/data/dataagent/formal-app}"
private="${DATAAGENT_FORMAL_PRIVATE_DIR:-/data/dataagent/formal-private}"
[[ -f "$base/.dataagent-app" && "$(cat "$base/.dataagent-app")" == "$base|dataagent66-formal-app" ]] || { echo '正式应用标记无效'; exit 1; }
value() { sed -n "s/^$1=//p" "$base/compose.env"; }
image="$(value APP_IMAGE)"
network="$(value DB_NETWORK)"
[[ "$(value APP_BASE)" == "$base" && "$network" == dataagent66-formal-app_default && "$image" =~ ^sha256:[a-f0-9]{64}$ ]] || { echo '正式镜像/网络配置不匹配'; exit 1; }
d=(sudo -n docker)
[[ "${DATAAGENT_DOCKER_SUDO:-true}" == false ]] && d=(docker)
[[ "$("${d[@]}" network inspect --format '{{index .Labels "dataagent.rehearsal.base"}}' "$network")" == "$base" ]] || { echo '网络归属不匹配'; exit 1; }
[[ "${1:-}" == configure ]] && configure=true || configure=false
files=(bi-sync.json)
$configure || files+=(bi-sync-extra.json)
for file in "${files[@]}"; do
  [[ -f "$private/$file" && ! -L "$private/$file" && "$(stat -c %a "$private/$file")" == 600 ]] || { echo '私有文件缺失或权限不是600'; exit 1; }
done
common=(run --rm --pull never --network "$network" --read-only --cpus 1 --memory 512m --pids-limit 128
  --user "$(id -u):$(id -g)" --tmpfs /tmp:rw,nosuid,size=67108864
  --label "dataagent.bi-extra-sync=$base" --env PYTHONDONTWRITEBYTECODE=1
  --mount "type=bind,source=$here/../deploy/bi-sync,target=/opt/bi-sync,readonly"
  --entrypoint python)
if $configure; then
  [[ $# == 1 && -t 0 ]] || { echo 'configure仅允许在交互终端执行'; exit 1; }
  [[ ! -e "$private/bi-sync-extra.json" && ! -L "$private/bi-sync-extra.json" ]] || { echo '新配置已存在，保留不覆盖'; exit 1; }
  "${d[@]}" "${common[@]}" -it \
    --mount "type=bind,source=$private,target=/config" "$image" /opt/bi-sync/configure_extra.py \
    --config /config/bi-sync-extra.json --base-config /config/bi-sync.json
else
  mkdir -p -- "$base/logs/bi-extra-sync"
  "${d[@]}" "${common[@]}" \
    --mount "type=bind,source=$private/bi-sync.json,target=/config/base.json,readonly" \
    --mount "type=bind,source=$private/bi-sync-extra.json,target=/config/extra.json,readonly" \
    "$image" /opt/bi-sync/extra_sync.py "$@" \
    --config /config/extra.json --base-config /config/base.json \
    2>&1 | tee -a "$base/logs/bi-extra-sync/run-$(date -u +%Y%m%d).log"
fi
