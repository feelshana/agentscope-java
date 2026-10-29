#!/usr/bin/env bash
# Immutable application files; configuration and all mutable paths stay outside the image.
set -Eeuo pipefail
fail() { echo "启动检查失败：$*" >&2; exit 1; }
action="${1:-run}"
[[ "$action" == run || "$action" == check || "$action" == image-check ]] || fail '参数只能为run/check/image-check'
app="${DATAAGENT_APP_HOME:-/opt/dataagent/app}"
[[ -r "$app/lib/dataagent-exec.jar" && -r "$app/bin/check-wren.py" && -r "$app/requirements-wren.txt" ]] || fail '镜像缺少应用或预检文件'
java_bin="${DATAAGENT_JAVA:-java}"
python_bin="${DATAAGENT_WREN_PYTHON:-python3}"
wren_bin="${DATAAGENT_WREN_EXECUTABLE:-wren}"
if [[ "$action" == image-check ]]; then
    export HOME=/tmp
    "$java_bin" -version
    "$python_bin" "$app/bin/check-wren.py" dependencies "$wren_bin"
    "$python_bin" - "$app/lib/dataagent-exec.jar" <<'PY'
import sys, zipfile
with zipfile.ZipFile(sys.argv[1]) as archive:
    names = archive.namelist()
    assert 'META-INF/MANIFEST.MF' in names
    for name in names:
        assert not any(name.endswith('/application-' + p + '.' + ext)
                       for p in ['prod', 'dev'] for ext in ['yml', 'yaml', 'properties']), name
print('镜像应用文件和依赖预检通过；不代表数据库或业务启动通过')
PY
    exit
fi
base="${DATAAGENT_BASE_DIR:-/data/dataagent}"
[[ "$base" == /* && "$base" != / && -d "$base" ]] || fail '持久目录不存在或无效'
base="$(cd -- "$base" && pwd -P)"
profile="${DATAAGENT_PROFILE:-prod}"
port="${DATAAGENT_PORT:-18080}"
[[ "$profile" =~ ^[a-zA-Z0-9_-]+$ ]] || fail 'profile无效'
[[ "$port" =~ ^[0-9]+$ && ${#port} -le 5 ]] || fail '端口无效'
(( 10#$port >= 1 && 10#$port <= 65535 )) || fail '端口无效'
[[ -r "$base/conf/application-$profile.yml" ]] || fail "缺少外置conf/application-$profile.yml"
export HOME="$base/data/home"
export HF_HOME="${HF_HOME:-$HOME/.cache/huggingface}"
[[ -d "$HF_HOME/hub" ]] || fail '缺少离线向量模型缓存'
mkdir -p -- "$HOME" "$base/data/state" "$base/data/workspace" "$base/data/artifacts" "$base/data/mdl" "$base/logs"
"$java_bin" -version
"$python_bin" "$app/bin/check-wren.py" dependencies "$wren_bin"
docker version --format '{{.Server.Version}} {{.Server.Os}}/{{.Server.Arch}}'
[[ -n "${DATAAGENT_SANDBOX_IMAGE:-}" ]] || fail '缺少分析沙箱镜像配置'
docker image inspect "$DATAAGENT_SANDBOX_IMAGE" >/dev/null
[[ "$action" == check ]] && { echo '应用与外置环境预检通过；尚未启动业务'; exit; }
"$python_bin" "$app/bin/check-wren.py" port "$port"
jvm=(-Xms256m -Xmx1024m)
if [[ -n "${JAVA_OPTS:-}" ]]; then read -r -a jvm <<< "$JAVA_OPTS"; fi
cd -- "$base"
exec "$java_bin" "${jvm[@]}" -Dfile.encoding=UTF-8 -Duser.timezone=Asia/Shanghai \
    "-Duser.home=$HOME" "-Dagentscope.state.home=$base/data/state" \
    -jar "$app/lib/dataagent-exec.jar" "--spring.profiles.active=$profile" \
    "--spring.config.additional-location=file:$base/conf/" "--server.port=$port" \
    "--dataagent.wren.executable=$wren_bin" "--dataagent.log-directory=$base/logs" \
    "--dataagent.workspace=$base/data/workspace" "--dataagent.artifacts.directory=$base/data/artifacts" \
    "--dataagent.wren.mdl-home=$base/data/mdl"
