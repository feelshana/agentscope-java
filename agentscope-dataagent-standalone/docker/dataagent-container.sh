#!/usr/bin/env bash
# Host-side controller. Only replace an explicitly stopped, labelled project container.
set -Eeuo pipefail
fail() { echo "操作失败：$*" >&2; exit 1; }
action="${1:-}"
case "$action" in prepare|check|start|stop|replace|logs|status) ;; *) fail '用法：bash dataagent-container.sh prepare|check|start|stop|replace|logs|status';; esac
base="${DATAAGENT_BASE_DIR:-/data/dataagent}"
[[ "$base" == /* && "$base" != / && "$base" != /data && "$base" != /opt ]] || fail '项目目录无效'
name="${DATAAGENT_CONTAINER_NAME:-dataagent-runtime-66}"
[[ "$name" =~ ^[a-zA-Z0-9][a-zA-Z0-9_.-]*$ ]] || fail '容器名称无效'
image="${DATAAGENT_RUNTIME_IMAGE:-dataagent/runtime:20261028}"
docker_cmd=(docker)
[[ "${DATAAGENT_DOCKER_SUDO:-true}" == false ]] || docker_cmd=(sudo docker)
d() { "${docker_cmd[@]}" "$@"; }
if [[ "$action" == prepare ]]; then
    if [[ ! -e "$base" ]]; then sudo install -d -m 0750 -o "$(id -u)" -g "$(id -g)" "$base"; fi
    [[ -d "$base" && -w "$base" ]] || fail '项目目录不可写，不自动修改既有目录所有权'
    mkdir -p -- "$base/data/home/.cache/huggingface" "$base/data/state" \
        "$base/data/workspace" "$base/data/artifacts" "$base/data/mdl"
    echo "项目持久目录已准备：$base"; exit 0
fi
[[ -d "$base" ]] || fail '先执行 prepare'
base="$(cd -- "$base" && pwd -P)"
owned() {
    [[ "$(d container inspect --format '{{index .Config.Labels "dataagent.runtime"}}' "$name")" == true && \
       "$(d container inspect --format '{{index .Config.Labels "dataagent.base"}}' "$name")" == "$base" ]] \
       || fail '同名容器不属于本项目路径，拒绝操作'
}
case "$action" in
    stop) owned; d stop --time 90 "$name"; exit;;
    logs) owned; d logs --tail 100 -f "$name"; exit;;
    status) owned; d container inspect --format '{{.Name}} {{.State.Status}} {{if .State.Health}}{{.State.Health.Status}}{{end}}' "$name"; exit;;
esac
release="${DATAAGENT_RELEASE_DIR:-$base/current}"
[[ -d "$release" ]] || fail '先上传并解压发布包，设置 DATAAGENT_RELEASE_DIR'
release="$(cd -- "$release" && pwd -P)"
[[ "$(dirname -- "$release")" == "$base" ]] || fail '发布目录必须直属项目根目录'
cache="${DATAAGENT_MODEL_CACHE:-$HOME/dataagent-env/hf-cache-modelscope}"
[[ -d "$cache/hub" ]] || fail '向量模型缓存不存在'
cache="$(cd -- "$cache" && pwd -P)"
env_file="${DATAAGENT_ENV_FILE:-$base/runtime.env}"
[[ -r "$env_file" ]] || fail '缺少私有 runtime.env'
[[ -S /var/run/docker.sock ]] || fail '未找到本机 Docker Unix socket'
socket_group="$(stat -c %g /var/run/docker.sock)"
d image inspect "$image" >/dev/null
args=(--pull never --user "$(id -u):$(id -g)" --group-add "$socket_group"
    --mount "type=bind,source=$base,target=$base"
    --mount type=bind,source=/var/run/docker.sock,target=/var/run/docker.sock
    --mount "type=bind,source=$cache,target=$base/data/home/.cache/huggingface,readonly"
    --env-file "$env_file" -e "DATAAGENT_BASE_DIR=$base" -e "DATAAGENT_RELEASE_DIR=$release"
    -e "HF_HOME=$base/data/home/.cache/huggingface"
    -e "DATAAGENT_SANDBOX_IMAGE=${DATAAGENT_SANDBOX_IMAGE:-agentscope/dataagent-sandbox:20261028}"
    -e "DATAAGENT_SANDBOX_INSTANCE_ID=${DATAAGENT_SANDBOX_INSTANCE_ID:-66-prod}")
if [[ "$action" == check ]]; then d run --rm --init "${args[@]}" "$image" check; exit; fi
port="${DATAAGENT_PORT:-18080}"
[[ "$port" =~ ^[0-9]+$ && ${#port} -le 5 ]] || fail '端口无效'
(( 10#$port >= 1 && 10#$port <= 65535 )) || fail '端口无效'
if d container inspect "$name" >/dev/null 2>&1; then
    owned
    [[ "$action" == replace ]] || fail '容器已存在；如需替换，先 stop 再 replace'
    [[ "$(d container inspect --format '{{.State.Running}}' "$name")" == false ]] || fail '先 stop，拒绝替换运行中的容器'
    # Persistent business data and model cache live outside this stopped container.
    d rm "$name"
fi
d run -d --init --name "$name" --restart unless-stopped --stop-timeout 90 \
    --label dataagent.runtime=true --label "dataagent.base=$base" \
    --cpus "${DATAAGENT_RUNTIME_CPUS:-8}" --memory "${DATAAGENT_RUNTIME_MEMORY:-16g}" \
    --pids-limit 2048 -p "${DATAAGENT_BIND_IP:-10.194.2.66}:$port:$port" \
    "${args[@]}" -e "DATAAGENT_PORT=$port" "$image"
echo '容器已创建；请检查 status、logs 和健康接口，不代表应用已就绪'
