#!/usr/bin/env bash
# One foreground release, reusing the existing formal controller and build pipeline.
set +x
set -Eeuo pipefail
umask 077

fail() { echo "日常发布失败：$*" >&2; exit 1; }
value() {
    local key="$1" file="$2"
    [[ "$(grep -c "^${key}=" "$file" || true)" == 1 ]] || fail "记录缺少唯一字段：$key"
    sed -n "s/^${key}=//p" "$file"
}
safe_path() { [[ "$1" =~ ^/[a-zA-Z0-9_./-]+$ && "$1" != / && "$1" != /data && "$1" != /data/dataagent && "$1" != *'/../'* && "$1" != */.. ]]; }
redact() {
    sed -u -E 's#(https?://)[^/@[:space:]]+@#\1[redacted]@#g; s#(Bearer )[A-Za-z0-9._~+/-]+=*#\1[redacted]#g'
}
write_status() {
    printf 'status=%s\nphase=%s\nsource=%s\nimage=%s\n' \
        "$1" "$phase" "${revision:-}" "${release_image:-}" > "$run_dir/status.next"
    mv -- "$run_dir/status.next" "$run_dir/status.env"
}
finish() {
    local result=$?
    if [[ "$result" != 0 ]]; then
        write_status FAILED
        echo "发布未完成：阶段=$phase；日志=$run_dir/deploy.log"
        echo '保留现场，不自动重新执行；先核对当前服务状态。'
    fi
}
source_ready() {
    [[ "$(git symbolic-ref --short HEAD)" == develop-2.0 ]] || fail '当前不是develop-2.0，不自动切分支'
    [[ -z "$(git status --porcelain)" ]] || fail '源码有修改或未跟踪文件，不覆盖'
}
current_service() {
    local ids
    ids="$(d ps -q --filter label=com.docker.compose.project=dataagent66-formal-app --filter label=com.docker.compose.service=app)"
    [[ "$ids" =~ ^[a-f0-9]{12,64}$ ]] || fail '正式应用必须恰有一个运行容器'
    container="$ids"
    [[ "$(d inspect --format '{{index .Config.Labels "dataagent.rehearsal.app"}}' "$container")" == "$base" ]] || fail '容器目录归属不符'
    running_image="$(d inspect --format '{{.Image}}' "$container")"
    running_health="$(d inspect --format '{{.State.Health.Status}}' "$container")"
}
health() {
    curl --noproxy '*' --silent --show-error --fail --connect-timeout 5 --max-time 10 \
        "http://$bind_ip:$port/actuator/health"
    echo
}
formal() {
    d run --rm --init --pull never --network host --read-only \
        --user "$(id -u):$(id -g)" --group-add "$socket_group" \
        --env HOME=/tmp --env DATAAGENT_DOCKER_SUDO=false \
        --env "DATAAGENT_FORMAL_APP_DIR=$base" --env PYTHONDONTWRITEBYTECODE=1 \
        --tmpfs /tmp:rw,nosuid,size=268435456,mode=1777 \
        --mount "type=bind,source=$module,target=$module,readonly" \
        --mount "type=bind,source=$parent,target=$parent" \
        --mount "type=bind,source=$model_cache,target=$model_cache,readonly" \
        --mount "type=bind,source=$docker_socket,target=/var/run/docker.sock" \
        --mount "type=bind,source=$compose_plugin,target=/usr/local/lib/docker/cli-plugins/docker-compose,readonly" \
        --workdir "$module" --entrypoint python "$release_image" "$module/docker/formal-app.py" "$@"
}
main() {
    local continuing=false expected_old="" config_fingerprint
    if [[ "${1:-}" == --help ]]; then
        echo '用法：bash docker/deploy-formal.sh'
        echo '日常正式发布；默认临时直连。DATAAGENT_GIT_NETWORK=inherit沿用代理。执行时保持SSH连接。'
        return
    fi
    [[ $# == 0 || ( $# == 2 && "$1" == --continue ) ]] || fail '参数无效，使用--help'
    base="${DATAAGENT_FORMAL_APP_DIR:-/data/dataagent/formal-app}"
    safe_path "$base" && [[ ! -L "$base" && -d "$base" ]] || fail '需要已有正式环境目录'
    base="$(cd -- "$base" && pwd -P)"
    parent="$(dirname -- "$base")"
    config="$base/compose.env"
    [[ -f "$base/.dataagent-app" && "$(cat "$base/.dataagent-app")" == "$base|dataagent66-formal-app" ]] || fail '正式环境标记不符，不初始化'
    [[ -r "$config" && -r "$base/runtime.env" ]] || fail '正式配置未准备，不自动创建'
    [[ "$(value APP_BASE "$config")" == "$base" && "$(value DB_MODE "$config")" == external ]] || fail '只支持现有正式外置两库'
    port="$(value APP_PORT "$config")"
    bind_ip="$(value APP_BIND_IP "$config")"
    [[ "$port" == 18080 && "$bind_ip" =~ ^[0-9]+\.[0-9]+\.[0-9]+\.[0-9]+$ ]] || fail '正式端口或绑定地址不符'
    model_cache="$(value APP_CACHE "$config")"
    safe_path "$model_cache" && [[ -d "$model_cache/hub" ]] || fail '模型缓存路径无效'
    case "${DATAAGENT_DOCKER_SUDO:-true}" in
        true) docker_cmd=(sudo docker);;
        false) docker_cmd=(docker);;
        *) fail 'DATAAGENT_DOCKER_SUDO仅支持true/false';;
    esac
    d() { "${docker_cmd[@]}" "$@"; }

    if [[ "${1:-}" == --continue ]]; then
        continuing=true
        run_dir="$2"
        safe_path "$run_dir" && [[ -d "$run_dir" && ! -L "$run_dir" ]] || fail '发布目录无效'
        run_dir="$(cd -- "$run_dir" && pwd -P)"
        [[ "$run_dir" == "$base/logs/releases/"* ]] || fail '发布目录不属于正式环境'
        [[ "$(readlink /proc/$$/fd/7)" == "$base/.deploy.lock" ]] || fail '缺少继承的发布锁'
        flock -n 7 || fail '发布锁已丢失'
        repo="$(value repo "$run_dir/context.env")"
        revision="$(value source "$run_dir/context.env")"
        expected_old="$(value previousImage "$run_dir/context.env")"
        safe_path "$repo" && [[ "$revision" =~ ^[a-f0-9]{40}$ ]] || fail '发布上下文无效'
        [[ "$(readlink /proc/$$/fd/8)" == "$repo/.git/dataagent-source.lock" ]] || fail '缺少继承的源码锁'
        flock -n 8 || fail '源码锁已丢失'
    else
        local here
        here="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
        repo="$(cd -- "$here/../.." && pwd -P)"
        safe_path "$repo" || fail '源码路径无效'
        cd -- "$repo"
        [[ "$(git rev-parse --show-toplevel)" == "$repo" && -d "$repo/.git" ]] || fail '需要普通完整源码仓库'
        source_ready
        exec 7>"$base/.deploy.lock"
        flock -n 7 || fail '已有正式发布进行中'
        [[ ! -L "$base/logs" && ! -L "$base/logs/releases" ]] || fail '发布日志目录不能为符号链接'
        mkdir -p -- "$base/logs/releases"
        run_dir="$(mktemp -d "$base/logs/releases/$(date -u +%Y%m%dT%H%M%S).XXXXXX")"
        exec > >(redact | tee -a "$run_dir/deploy.log") 2>&1
    fi
    phase=environment
    trap finish EXIT
    trap 'exit 130' INT
    trap 'exit 143' TERM
    trap 'exit 129' HUP
    echo "发布记录：$run_dir"
    cd -- "$repo"
    module="$repo/agentscope-dataagent-standalone"
    source_ready
    current_service
    [[ "$running_health" == healthy && "$running_image" == "$(value APP_IMAGE "$config")" ]] || fail '现行镜像或健康状态异常，先核对现场'
    old_image="$running_image"
    [[ -z "$expected_old" || "$old_image" == "$expected_old" ]] || fail '拉取期间现行服务发生变化，停止替换'
    config_fingerprint="$(sha256sum "$config")"

    if [[ "$continuing" == false ]]; then
        phase=source-update
        write_status RUNNING
        (
            export DATAAGENT_SOURCE_BRANCH=develop-2.0
            export DATAAGENT_GIT_NETWORK="${DATAAGENT_GIT_NETWORK:-direct}"
            export DATAAGENT_GIT_PROBE_TIMEOUT="${DATAAGENT_GIT_PROBE_TIMEOUT:-30}"
            export DATAAGENT_FETCH_TIMEOUT="${DATAAGENT_FETCH_TIMEOUT:-180}"
            bash "$module/docker/update-source.sh"
        )
        exec 8>"$repo/.git/dataagent-source.lock"
        flock -n 8 || fail '源码正在被其他任务更新'
        source_ready
        revision="$(git rev-parse HEAD)"
        [[ "$revision" == "$(git rev-parse refs/remotes/origin/develop-2.0)" ]] || fail '主线引用与HEAD不一致'
        printf 'repo=%s\nsource=%s\npreviousImage=%s\n' "$repo" "$revision" "$old_image" > "$run_dir/context.env"
        # Continue with the target commit's complete entry point, not a half-reloaded shell file.
        git show "$revision:agentscope-dataagent-standalone/docker/deploy-formal.sh" > "$run_dir/deploy-formal.sh"
        exec bash "$run_dir/deploy-formal.sh" --continue "$run_dir"
    fi
    [[ "$(git rev-parse HEAD)" == "$revision" ]] || fail '源码在发布过程中发生变化'
    [[ "$expected_old" =~ ^sha256:[a-f0-9]{64}$ ]] || fail '原运行镜像记录无效'
    if [[ "$(d image inspect --format '{{index .Config.Labels "org.opencontainers.image.revision"}}' "$old_image")" == "$revision" ]]; then
        phase=already-current
        health
        release_image="$old_image"
        write_status SKIPPED
        echo "已经运行目标提交$revision，健康正常；跳过构建和重启。"
        return
    fi
    compose_plugin="${DATAAGENT_COMPOSE_PLUGIN:-/usr/libexec/docker/cli-plugins/docker-compose}"
    safe_path "$compose_plugin" && [[ -x "$compose_plugin" ]] || fail 'Compose插件不存在，请设置DATAAGENT_COMPOSE_PLUGIN'
    docker_socket="${DATAAGENT_DOCKER_SOCKET:-/var/run/docker.sock}"
    safe_path "$docker_socket" && [[ -S "$docker_socket" ]] || fail 'Docker socket不可用'
    socket_group="$(stat -c %g "$docker_socket")"
    phase=build
    write_status RUNNING
    DATAAGENT_BUILD_RESULT_FILE="$run_dir/build-result.env" bash "$module/docker/build-from-source.sh" "$revision"
    phase=image-validation
    local record="$run_dir/build-result.env" image_id
    [[ -f "$record" && ! -L "$record" && "$(value source "$record")" == "$revision" ]] || fail '本次构建记录缺失或提交不符'
    release_image="$(value image "$record")"
    image_id="$(value imageId "$record")"
    [[ "$release_image" =~ ^dataagent/app:[a-zA-Z0-9_.-]+$ && "$image_id" =~ ^sha256:[a-f0-9]{64}$ ]] || fail '构建镜像格式无效'
    [[ "$(d image inspect --format '{{.Id}}' "$release_image")" == "$image_id" ]] || fail '镜像ID与本次记录不符'
    [[ "$(d image inspect --format '{{index .Config.Labels "org.opencontainers.image.revision"}}' "$release_image")" == "$revision" ]] || fail '镜像源码版本不符'
    [[ "$(d image inspect --format '{{.Os}}/{{.Architecture}} {{index .Config.Labels "dataagent.application"}}' "$release_image")" == 'linux/amd64 true' ]] || fail '候选不是本项目应用镜像'
    source_ready
    [[ "$(git rev-parse HEAD)" == "$revision" ]] || fail '构建后源码版本变化'
    [[ "$(sha256sum "$config")" == "$config_fingerprint" ]] || fail '构建期间正式配置变化，停止替换'
    current_service
    [[ "$running_image" == "$old_image" && "$running_health" == healthy && "$(value APP_IMAGE "$config")" == "$old_image" ]] || fail '构建期间现行服务变化，停止替换'
    phase=upgrade
    write_status RUNNING
    if ! formal upgrade "$release_image"; then
        phase=upgrade-failed
        if current_service && [[ "$running_image" == "$old_image" && "$running_health" == healthy ]]; then
            echo '发布失败；正式服务仍为原镜像且健康（预检拒绝替换或原镜像已恢复）。'
        else
            echo '发布失败且原镜像未确认健康，请按日志人工处理，不自动重复升级。'
        fi
        exit 1
    fi
    phase=verification
    current_service
    [[ "$running_image" == "$image_id" && "$running_health" == healthy && "$(value APP_IMAGE "$config")" == "$image_id" ]] || fail '最终运行镜像或健康不符'
    health
    formal status
    phase=complete
    write_status SUCCESS
    echo "发布成功：提交=$revision；镜像=$release_image；health=healthy；端口=$port"
    echo "记录：$run_dir；请从原BI入口确认本次改动及真实问数。"
}

main "$@"
