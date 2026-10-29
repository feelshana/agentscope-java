#!/usr/bin/env bash
# Run immutable application images against the dedicated rehearsal database.
set -Eeuo pipefail
fail() { echo "应用部署失败：$*" >&2; exit 1; }
action="${1:-}"
case "$action" in prepare|check|start|stop|status|logs|upgrade|rollback) ;; *) fail '参数：prepare [镜像]|check|start|stop|status|logs|upgrade 镜像|rollback';; esac
here="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
base="${DATAAGENT_REHEARSAL_APP_DIR:-/data/dataagent/rehearsal-app}"
[[ "$base" =~ ^/[a-zA-Z0-9_./-]+$ && "$base" != / && "$base" != /data && "$base" != /data/dataagent && ! -L "$base" ]] || fail '应用目录无效'
parent="$(cd -- "$(dirname -- "$base")" && pwd -P)"
[[ -w "$parent" ]] || fail '应用父目录不可写'
base="$parent/$(basename -- "$base")"
project="${DATAAGENT_REHEARSAL_APP_PROJECT:-dataagent66-rehearsal-app}"
[[ "$project" =~ ^[a-z0-9][a-z0-9_-]{0,62}$ ]] || fail 'Compose项目名无效'
docker_cmd=(sudo docker)
[[ "${DATAAGENT_DOCKER_SUDO:-true}" == false ]] && docker_cmd=(docker)
d() { "${docker_cmd[@]}" "$@"; }
image_id() {
    [[ "$(d image inspect --format '{{.Os}}/{{.Architecture}} {{index .Config.Labels "dataagent.application"}}' "$1")" == 'linux/amd64 true' ]] || fail '不是本项目linux/amd64应用镜像'
    d image inspect --format '{{.Id}}' "$1"
}
umask 077
if [[ "$action" == prepare ]]; then
    if [[ -e "$base" ]]; then
        [[ -f "$base/.dataagent-app" && "$(cat "$base/.dataagent-app")" == "$base|$project" ]] || fail '目录已有其他内容，拒绝覆盖'
        for file in compose.yml compose.env runtime.env conf/application-prod.yml; do
            [[ -s "$base/$file" ]] || fail '准备未完整完成，保留现场核对'
        done
        echo "配置已存在，保留原密钥/数据库/文件：$base"; exit 0
    fi
    db="${DATAAGENT_REHEARSAL_DB_DIR:-/data/dataagent/rehearsal-db}"
    db="$(cd -- "$db" && pwd -P)"
    db_project="${DATAAGENT_REHEARSAL_DB_PROJECT:-dataagent66-rehearsal-db}"
    [[ -f "$db/.dataagent-rehearsal" && "$(cat "$db/.dataagent-rehearsal")" == "$db|$db_project" && -r "$db/app-db.env" ]] || fail '演练数据库标记或连接文件无效'
    network="${db_project}_default"
    [[ "$(d network inspect --format '{{index .Labels "dataagent.rehearsal.base"}}' "$network")" == "$db" ]] || fail '数据库网络标签不匹配'
    cache="${DATAAGENT_MODEL_CACHE:-$HOME/dataagent-env/hf-cache-modelscope}"
    [[ -d "$cache/hub" ]] || fail '缺少已下载的离线模型缓存'
    cache="$(cd -- "$cache" && pwd -P)"
    [[ "$cache" =~ ^/[a-zA-Z0-9_./-]+$ && -S /var/run/docker.sock ]] || fail '缓存路径或Docker socket无效'
    image="${2:-${DATAAGENT_APP_IMAGE:-}}"
    if [[ -z "$image" ]]; then
        shopt -s nullglob
        records=("${DATAAGENT_BUILD_ROOT:-$HOME/dataagent-build}"/jobs/*/image-reference.txt)
        [[ ${#records[@]} == 1 ]] || fail '请明确指定应用镜像；仅有一份构建记录时才自动读取'
        image="$(cat "${records[0]}")"
    fi
    selected="$(image_id "$image")"
    port="${DATAAGENT_PORT:-18080}"
    [[ "$port" =~ ^[0-9]+$ && ${#port} -le 5 ]] || fail '端口无效'
    (( 10#$port >= 1 && 10#$port <= 65535 )) || fail '端口无效'
    bind_ip="${DATAAGENT_BIND_IP:-10.194.2.66}"
    [[ "$bind_ip" =~ ^[0-9]+\.[0-9]+\.[0-9]+\.[0-9]+$ ]] || fail '绑定IPv4地址无效'
    sandbox="${DATAAGENT_SANDBOX_IMAGE:-agentscope/dataagent-sandbox:20261028}"
    [[ "$(d image inspect --format '{{.Os}}/{{.Architecture}}' "$sandbox")" == linux/amd64 ]] || fail '缺少已验收沙箱镜像'
    [[ -r "$here/compose.rehearsal-app.yml" ]] || fail '缺少应用Compose模板'
    mkdir -- "$base"
    printf '%s\n' "$base|$project" > "$base/.dataagent-app"
    mkdir -p -- "$base/conf" "$base/data/home/.cache/huggingface" "$base/data/state" "$base/data/workspace" "$base/data/artifacts" "$base/data/mdl" "$base/data/deployment-probe" "$base/logs"
    cp -- "$here/compose.rehearsal-app.yml" "$base/compose.yml"
    cp -- "$here/../src/main/distribution/conf/application-prod.yml.example" "$base/conf/application-prod.yml"
    {
        printf 'APP_BASE=%s\nAPP_IMAGE=%s\nAPP_UID=%s\nAPP_GID=%s\nSOCKET_GID=%s\nAPP_CACHE=%s\nDB_ENV=%s\nDB_NETWORK=%s\nDB_DIR=%s\n' \
            "$base" "$selected" "$(id -u)" "$(id -g)" "$(stat -c %g /var/run/docker.sock)" "$cache" "$db/app-db.env" "$network" "$db"
        printf 'APP_BIND_IP=%s\nAPP_PORT=%s\nAPP_CPUS=%s\nAPP_MEMORY=%s\n' "$bind_ip" "$port" "${DATAAGENT_RUNTIME_CPUS:-8}" "${DATAAGENT_RUNTIME_MEMORY:-16g}"
    } > "$base/compose.env"
    {
        printf 'DATAAGENT_PROFILE=prod\nDATAAGENT_JWT_SECRET=%s\n' "$(od -An -N32 -tx1 /dev/urandom | tr -d ' \n')"
        printf 'DATAAGENT_MODEL_ENCRYPTION_KEY=%s\n' "$(head -c 32 /dev/urandom | base64 | tr -d '\n')"
        printf 'DATAAGENT_CORS_ALLOWED_ORIGINS=%s\nDATAAGENT_BI_ACCOUNT_LOGIN_ENABLED=true\n' "${DATAAGENT_CORS_ALLOWED_ORIGINS:-https://dc.migu.cn}"
        printf 'DATAAGENT_SANDBOX_IMAGE=%s\nDATAAGENT_SANDBOX_INSTANCE_ID=%s\n' "$sandbox" "$project"
    } > "$base/runtime.env"
    printf '%s\n' "$image" > "$base/selected-image.txt"
    echo "应用配置已准备：$base；未启动，未配置大模型，密钥仅保存在runtime.env"; exit 0
fi
[[ -f "$base/.dataagent-app" && "$(cat "$base/.dataagent-app")" == "$base|$project" ]] || fail '应用项目标记不匹配'
exec 9> "$base/.control.lock"
flock -n 9 || fail '已有本项目操作在运行'
value() { sed -n "s/^$1=//p" "$base/compose.env"; }
[[ "$(value APP_BASE)" == "$base" ]] || fail '应用路径与配置不一致'
for container in $(d ps -aq --filter "label=com.docker.compose.project=$project"); do
    [[ "$(d inspect --format '{{index .Config.Labels "dataagent.rehearsal.app"}}' "$container")" == "$base" ]] || fail '同名Compose项目包含其他目录容器'
done
[[ "$(d network inspect --format '{{index .Labels "dataagent.rehearsal.base"}}' "$(value DB_NETWORK)")" == "$(value DB_DIR)" ]] || fail '数据库网络标签不匹配'
c() { d compose -p "$project" --project-directory "$base" --env-file "$base/compose.env" -f "$base/compose.yml" "$@"; }
case "$action" in
    status) c ps; exit;;
    logs) c logs --tail 100 app; exit;;
    stop) c stop app; echo '仅停止应用，数据库和持久数据保留'; exit;;
esac
preflight() {
    local candidate="$1"
    APP_IMAGE="$candidate" c run --rm --no-deps -T app check
    APP_IMAGE="$candidate" c run --rm --no-deps -T --entrypoint python app - <<'PY'
import os, pathlib, subprocess, uuid
import MySQLdb, numpy as np
from wren.memory.embeddings import get_embedding_function
for prefix, database in [('DATAAGENT_', 'dataagent_platform'), ('DATAAGENT_DATASET_', 'data_agent')]:
    con = MySQLdb.connect(host='mysql', port=3306, user=os.environ[prefix+'DB_USER'], passwd=os.environ[prefix+'DB_PASSWORD'], db=database)
    cursor = con.cursor()
    cursor.execute('SELECT 1')
    assert cursor.fetchone()[0] == 1
    con.close()
vectors = np.asarray(get_embedding_function().compute_source_embeddings(['部署离线检查','销售收入']), dtype=np.float32)
assert vectors.shape == (2,384) and np.isfinite(vectors).all()
folder = pathlib.Path(os.environ['DATAAGENT_BASE_DIR']) / 'data/deployment-probe' / uuid.uuid4().hex
folder.mkdir()
(folder/'input.txt').write_text('same-path-ok', encoding='utf-8')
subprocess.run(['docker','run','--rm','--pull','never','--network','none','--read-only','--tmpfs','/tmp:rw,nosuid,size=64m',
    '--label','dataagent.deployment-probe='+os.environ['DATAAGENT_BASE_DIR'],
    '--mount','type=bind,source='+str(folder)+',target=/shared',os.environ['DATAAGENT_SANDBOX_IMAGE'],
    'python','-c','from pathlib import Path; assert Path("/shared/input.txt").read_text()=="same-path-ok"; Path("/shared/result.txt").write_text("sandbox-ok")'],check=True,timeout=60)
assert (folder/'result.txt').read_text() == 'sandbox-ok'
print('应用预检通过：两库连接、离线向量(2,384)、宿主Docker和兄弟沙箱同路径读写；未调用大模型',flush=True)
PY
}
ready() {
    c exec -T app python - <<'PY'
import json, os, urllib.request
base = 'http://127.0.0.1:'+os.environ['DATAAGENT_PORT']
opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
with opener.open(base+'/actuator/health',timeout=5) as r:
    assert json.load(r)['status'] == 'UP'
with opener.open(base+'/api/auth/options',timeout=5) as r:
    settings = json.load(r)
    assert settings['biAccountLoginEnabled'] and not settings['passwordLoginEnabled']
print('服务健康和BI账号入口通过；管理员、模型、65代理和真实问答仍需继续验收')
PY
}
current="$(value APP_IMAGE)"
if [[ "$action" == check ]]; then preflight "$current"; exit; fi
if [[ "$action" == start ]]; then
    preflight "$current"
    c up -d --no-deps --pull never --wait --wait-timeout 240 app
    ready; exit
fi
candidate="${2:-}"
if [[ "$action" == rollback ]]; then
    [[ -s "$base/previous-image.txt" ]] || fail '没有可回退镜像记录'
    candidate="$(cat "$base/previous-image.txt")"
fi
[[ -n "$candidate" ]] || fail '请明确指定候选镜像'
candidate="$(image_id "$candidate")"
[[ "$candidate" != "$current" ]] || { echo '已经是当前镜像'; exit 0; }
[[ "$(c ps -q app)" != '' ]] || fail '应用未运行，首次部署使用start'
preflight "$candidate"
set_image() {
    sed "s|^APP_IMAGE=.*|APP_IMAGE=$1|" "$base/compose.env" > "$base/compose.env.next"
    mv -- "$base/compose.env.next" "$base/compose.env"
}
printf '%s\n' "$current" > "$base/previous-image.txt"
set_image "$candidate"
if c up -d --no-deps --pull never --wait --wait-timeout 240 app && ready; then
    echo '应用镜像更新通过；数据库/配置/文件没有回退'; exit 0
fi
c logs --tail 150 app > "$base/logs/failed-deploy-$(date -u +%Y%m%d%H%M%S).log" 2>&1 || true
set_image "$current"
if c up -d --no-deps --pull never --wait --wait-timeout 240 app && ready; then
    fail '候选镜像启动失败，已恢复原镜像；请查看logs中的部署记录'
fi
fail '候选镜像和原镜像恢复均未就绪，请保留现场排查；数据未删除'
