#!/usr/bin/env bash
# Keep Java in the foreground so the container's init forwards shutdown signals.
set -Eeuo pipefail
fail() { echo "启动检查失败：$*" >&2; exit 1; }
action="${1:-run}"
[[ "$action" == run || "$action" == check ]] || fail '参数只能为 run 或 check'
base="${DATAAGENT_BASE_DIR:-/data/dataagent}"
[[ "$base" == /* && "$base" != / ]] || fail '项目根目录必须为绝对路径且不能为 /'
[[ -d "$base" ]] || fail '项目根目录不存在'
base="$(cd -- "$base" && pwd -P)"
release="${DATAAGENT_RELEASE_DIR:-$base/current}"
[[ -d "$release" ]] || fail '发布目录不存在，请先上传解压业务包'
release="$(cd -- "$release" && pwd -P)"
[[ "$(dirname -- "$release")" == "$base" ]] || fail '发布目录必须直属项目根目录'
profile="${DATAAGENT_PROFILE:-prod}"
port="${DATAAGENT_PORT:-18080}"
[[ "$profile" =~ ^[a-zA-Z0-9_-]+$ ]] || fail '运行 profile 无效'
[[ "$port" =~ ^[0-9]+$ && ${#port} -le 5 ]] || fail '端口无效'
(( 10#$port >= 1 && 10#$port <= 65535 )) || fail '端口无效'
[[ -r "$release/conf/application-$profile.yml" ]] || fail "缺少 conf/application-$profile.yml"
[[ -r "$release/bin/check-wren.py" && -r "$release/requirements-wren.txt" ]] || fail '发布包缺少依赖预检文件'
shopt -s nullglob
jars=("$release"/lib/*-exec.jar)
[[ ${#jars[@]} == 1 ]] || fail 'lib 中必须有且仅有一个 *-exec.jar'

export HOME="$base/data/home"
export HF_HOME="${HF_HOME:-$HOME/.cache/huggingface}"
[[ -d "$HF_HOME/hub" ]] || fail '缺少已准备的向量模型缓存'
mkdir -p -- "$HOME" "$base/data/state" "$base/data/workspace" \
    "$base/data/artifacts" "$base/data/mdl" "$release/logs"
java_bin="${DATAAGENT_JAVA:-java}"
wren_bin="${DATAAGENT_WREN_EXECUTABLE:-wren}"
python_bin="${DATAAGENT_WREN_PYTHON:-python3}"
"$java_bin" -version
"$python_bin" "$release/bin/check-wren.py" dependencies "$wren_bin"
docker version --format '{{.Server.Version}} {{.Server.Os}}/{{.Server.Arch}}'
[[ -n "${DATAAGENT_SANDBOX_IMAGE:-}" ]] || fail '未配置已验收的分析沙箱镜像'
docker image inspect "$DATAAGENT_SANDBOX_IMAGE" >/dev/null
[[ "$action" == check ]] && { echo '运行环境与发布包预检通过'; exit 0; }
"$python_bin" "$release/bin/check-wren.py" port "$port"
jvm=(-Xms256m -Xmx1024m)
if [[ -n "${JAVA_OPTS:-}" ]]; then read -r -a jvm <<< "$JAVA_OPTS"; fi
cd -- "$release"
exec "$java_bin" "${jvm[@]}" -Dfile.encoding=UTF-8 -Duser.timezone=Asia/Shanghai \
    "-Duser.home=$HOME" "-Dagentscope.state.home=$base/data/state" \
    -jar "${jars[0]}" "--spring.profiles.active=$profile" \
    "--spring.config.additional-location=file:$release/conf/" "--server.port=$port" \
    "--dataagent.wren.executable=$wren_bin" \
    "--dataagent.log-directory=$release/logs" \
    "--dataagent.workspace=$base/data/workspace" \
    "--dataagent.artifacts.directory=$base/data/artifacts" \
    "--dataagent.wren.mdl-home=$base/data/mdl"
