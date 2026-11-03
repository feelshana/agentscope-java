#!/usr/bin/env bash
# Run fixed sync code with the already-built application runtime; never source private files.
set -Eeuo pipefail
umask 077
here="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
base="${DATAAGENT_FORMAL_APP_DIR:-/data/dataagent/formal-app}"
private="${DATAAGENT_FORMAL_PRIVATE_DIR:-/data/dataagent/formal-private}"
[[ -f "$base/.dataagent-app" && "$(cat "$base/.dataagent-app")" == "$base|dataagent66-formal-app" ]] || { echo '正式应用标记无效'; exit 1; }
[[ -f "$private/bi-sync.json" && ! -L "$private/bi-sync.json" && "$(stat -c %a "$private/bi-sync.json")" == 600 ]] || { echo '私有同步配置需为600权限普通文件'; exit 1; }
value() { sed -n "s/^$1=//p" "$base/compose.env"; }
image="$(value APP_IMAGE)"
network="$(value DB_NETWORK)"
[[ "$(value APP_BASE)" == "$base" && "$network" == dataagent66-formal-app_default && "$image" =~ ^sha256:[a-f0-9]{64}$ ]] || { echo '正式镜像/网络配置不匹配'; exit 1; }
d=(sudo -n docker)
[[ "${DATAAGENT_DOCKER_SUDO:-true}" == false ]] && d=(docker)
[[ "$("${d[@]}" network inspect --format '{{index .Labels "dataagent.rehearsal.base"}}' "$network")" == "$base" ]] || { echo '网络归属不匹配'; exit 1; }
mkdir -p -- "$base/logs/bi-sync"
"${d[@]}" run --rm --pull never --network "$network" --read-only --cpus 1 --memory 512m --pids-limit 128 \
  --user "$(id -u):$(id -g)" --tmpfs /tmp:rw,nosuid,size=67108864 \
  --label "dataagent.bi-sync=$base" --env PYTHONDONTWRITEBYTECODE=1 \
  --mount "type=bind,source=$here/../deploy/bi-sync,target=/opt/bi-sync,readonly" \
  --mount "type=bind,source=$private/bi-sync.json,target=/config/bi-sync.json,readonly" \
  --entrypoint python "$image" /opt/bi-sync/bi_sync.py "$@" --config /config/bi-sync.json \
  2>&1 | tee -a "$base/logs/bi-sync/run-$(date -u +%Y%m%d).log"
