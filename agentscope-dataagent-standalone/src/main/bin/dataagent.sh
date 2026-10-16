#!/usr/bin/env bash
# Linux single-host launcher. Durable state is shared by sibling release directories.
set -Eeuo pipefail
RELEASE_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd -P)"
BASE_DIR="$(cd -- "$RELEASE_DIR/.." && pwd -P)"
RUN_DIR="$BASE_DIR/data/run"
PID_FILE="$RUN_DIR/application.pid"
JAVA_BIN="${DATAAGENT_JAVA:-${JAVA_HOME:+$JAVA_HOME/bin/}java}"
ACTION="${1:-}"
[[ "$ACTION" == start || "$ACTION" == stop ]] || { echo '用法：./bin/dataagent.sh start|stop'; exit 1; }

# Keep the source launcher's optional privilege drop to the deployment account.
if (( EUID == 0 )) && id dataagent >/dev/null 2>&1; then
    install -d -o dataagent -g "$(id -gn dataagent)" -m 0750 "$BASE_DIR/data" "$RUN_DIR" \
        "$BASE_DIR/data/home" "$BASE_DIR/data/state" "$BASE_DIR/data/workspace" \
        "$BASE_DIR/data/artifacts" "$BASE_DIR/data/mdl" "$RELEASE_DIR/log"
    exec runuser -u dataagent -- env \
        DATAAGENT_JAVA="$JAVA_BIN" JAVA_OPTS="${JAVA_OPTS:-}" \
        "$RELEASE_DIR/bin/dataagent.sh" "$ACTION"
fi
mkdir -p -- "$RUN_DIR"
exec 9>"$RUN_DIR/control.lock"
flock -w 10 9 || { echo '另一个启停命令正在执行'; exit 1; }

alive() {
    kill -0 "$PID" 2>/dev/null && [[ -r "/proc/$PID/stat" ]] &&
        [[ "$(awk '{print $3}' "/proc/$PID/stat")" != Z ]]
}

running() {
    [[ -f "$PID_FILE" ]] || return 1
    local record=()
    mapfile -t record < "$PID_FILE"
    [[ ${#record[@]} == 3 && "${record[0]}" =~ ^[0-9]+$ && "${record[1]}" =~ ^[0-9]+$ ]] || {
        echo 'PID记录异常，拒绝操作'; exit 1;
    }
    PID="${record[0]}"; TICKS="${record[1]}"; ACTIVE_JAR="${record[2]}"
    (( PID > 1 )) || { echo 'PID记录无效'; exit 1; }
    alive || return 1
    [[ "$(awk '{print $22}' "/proc/$PID/stat")" == "$TICKS" ]] || {
        echo 'PID已被复用，拒绝操作'; exit 1;
    }
    [[ "$ACTIVE_JAR" == "$BASE_DIR"/dataagent-*/lib/*-exec.jar ]] || {
        echo 'PID不属于此项目，拒绝操作'; exit 1;
    }
    tr '\0' '\n' < "/proc/$PID/cmdline" | grep -Fxq -- "$ACTIVE_JAR" || {
        echo '进程身份不符，拒绝操作'; exit 1;
    }
}

stop_process() {
    echo "停止 PID=$PID"
    kill -TERM "$PID"
    for ((i=0; i<60; i++)); do alive || break; sleep 1; done
    if alive; then
        running || return 1
        echo '正常停止超时，强制结束该进程'
        kill -KILL "$PID"
    fi
    for ((i=0; i<10; i++)); do alive || break; sleep 1; done
    if alive; then echo '进程尚未退出，请检查日志'; return 1; fi
    rm -f -- "$PID_FILE"
}

if [[ "$ACTION" == stop ]]; then
    if ! running; then rm -f -- "$PID_FILE"; echo '项目未运行'; exit 0; fi
    stop_process
    echo '已停止'
    exit 0
fi
if running; then echo "已有实例运行：PID=$PID，请先 stop"; exit 1; fi
PROFILE="${DATAAGENT_PROFILE:-prod}"
PORT="${DATAAGENT_PORT:-8080}"
START_TIMEOUT="${DATAAGENT_START_TIMEOUT:-120}"
[[ "$PROFILE" =~ ^[a-zA-Z0-9_-]+$ && "$PORT" =~ ^[0-9]+$ && "$START_TIMEOUT" =~ ^[1-9][0-9]*$ ]] || {
    echo 'PROFILE、PORT或START_TIMEOUT无效'; exit 1;
}
[[ -r "$RELEASE_DIR/conf/application-$PROFILE.yml" ]] || {
    echo "缺少 conf/application-$PROFILE.yml；请从样例创建目标环境配置"; exit 1;
}
shopt -s nullglob
JARS=("$RELEASE_DIR"/lib/*-exec.jar)
[[ ${#JARS[@]} == 1 ]] || { echo 'lib中应有一个 *-exec.jar'; exit 1; }
WREN_BIN="$(command -v -- "${DATAAGENT_WREN_EXECUTABLE:-wren}" || true)"
[[ -n "$WREN_BIN" ]] || { echo '未找到Wren，请安装 requirements-wren.txt 并设置 DATAAGENT_WREN_EXECUTABLE'; exit 1; }
WREN_PYTHON="${DATAAGENT_WREN_PYTHON:-}"
if [[ -z "$WREN_PYTHON" ]]; then
    if [[ -x "$(dirname -- "$WREN_BIN")/python" ]]; then
        WREN_PYTHON="$(dirname -- "$WREN_BIN")/python"
    else
        WREN_PYTHON=python3
    fi
fi
export DATAAGENT_WREN_EXECUTABLE="$WREN_BIN"
mkdir -p -- "$BASE_DIR/data/home" "$BASE_DIR/data/state" "$BASE_DIR/data/workspace" \
    "$BASE_DIR/data/artifacts" "$BASE_DIR/data/mdl" "$RELEASE_DIR/log"
export HOME="$BASE_DIR/data/home"
"$WREN_PYTHON" "$RELEASE_DIR/bin/check-wren.py" dependencies "$WREN_BIN" || {
    echo '请在Wren使用的Python环境安装 requirements-wren.txt；可设置 DATAAGENT_WREN_PYTHON'; exit 1;
}
"$WREN_PYTHON" "$RELEASE_DIR/bin/check-wren.py" port "$PORT" || exit 1
JVM=(-Xms256m -Xmx1024m)
if [[ -n "${JAVA_OPTS:-}" ]]; then read -r -a JVM <<< "$JAVA_OPTS"; fi
cd -- "$RELEASE_DIR"
nohup "$JAVA_BIN" "${JVM[@]}" -Dfile.encoding=UTF-8 -Duser.timezone=Asia/Shanghai \
    "-Duser.home=$HOME" "-Dagentscope.state.home=$BASE_DIR/data/state" \
    -jar "${JARS[0]}" "--spring.profiles.active=$PROFILE" \
    "--spring.config.additional-location=file:$RELEASE_DIR/conf/" "--server.port=$PORT" \
    "--dataagent.wren.executable=$WREN_BIN" \
    "--dataagent.log-directory=$RELEASE_DIR/log" \
    "--dataagent.workspace=$BASE_DIR/data/workspace" \
    "--dataagent.artifacts.directory=$BASE_DIR/data/artifacts" \
    "--dataagent.wren.mdl-home=$BASE_DIR/data/mdl" \
    >> "$RELEASE_DIR/log/launcher.log" 2>&1 < /dev/null 9>&- &
PID=$!
[[ -r "/proc/$PID/stat" ]] || { echo '启动失败，请查看 log/launcher.log'; exit 1; }
printf '%s\n%s\n%s\n' "$PID" "$(awk '{print $22}' "/proc/$PID/stat")" "${JARS[0]}" > "$PID_FILE"
HEALTH_URL="${DATAAGENT_HEALTH_URL:-http://127.0.0.1:$PORT/actuator/health}"
DEADLINE=$((SECONDS + START_TIMEOUT))
while (( SECONDS < DEADLINE )); do
    if ! running; then
        rm -f -- "$PID_FILE"
        echo '应用启动失败，请查看 log/launcher.log'; exit 1
    fi
    if "$WREN_PYTHON" "$RELEASE_DIR/bin/check-wren.py" health "$HEALTH_URL"; then
        running || { echo '应用已退出'; exit 1; }
        echo "应用已就绪 PID=$PID；日志：$RELEASE_DIR/log"
        exit 0
    fi
    sleep 1
done
echo '等待应用健康检查超时，停止本次启动的进程；请查看 log/launcher.log'
if running; then stop_process; else rm -f -- "$PID_FILE"; fi
exit 1
