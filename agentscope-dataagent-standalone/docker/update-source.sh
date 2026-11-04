#!/usr/bin/env bash
# Fetch only the configured branch, with bounded retries; never force or merge diverged history.
set -Eeuo pipefail
fail() { echo "更新源码失败：$*" >&2; exit 1; }
# Parse the complete operation before fetch can replace this script.
main() {
    script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
    repo="$(cd -- "$script_dir/../.." && pwd -P)"
    cd -- "$repo"
    branch="${DATAAGENT_SOURCE_BRANCH:-develop-2.0}"
    fetch_timeout="${DATAAGENT_FETCH_TIMEOUT:-1800}"
    network="${DATAAGENT_GIT_NETWORK:-inherit}"
    probe_timeout="${DATAAGENT_GIT_PROBE_TIMEOUT:-0}"
    case "$network" in
        direct) unset HTTP_PROXY HTTPS_PROXY ALL_PROXY http_proxy https_proxy all_proxy; export NO_PROXY='*' no_proxy='*';;
        inherit) ;;
        *) fail '网络策略只支持direct或inherit';;
    esac
    [[ "$probe_timeout" =~ ^[0-9]+$ && ${#probe_timeout} -le 2 ]] || fail 'Git读取检查超时无效'
    (( 10#$probe_timeout <= 60 )) || fail 'Git读取检查最长60秒'
    git check-ref-format "refs/heads/$branch" >/dev/null || fail '分支名无效'
    [[ "$fetch_timeout" =~ ^[0-9]+$ && ${#fetch_timeout} -le 5 ]] || fail '拉取超时秒数无效'
    (( 10#$fetch_timeout >= 1 && 10#$fetch_timeout <= 3600 )) || fail '拉取超时允许1至3600秒'
    [[ "$(git symbolic-ref --short HEAD)" == "$branch" ]] || fail '当前不是指定部署分支，不自动切换'
    [[ -z "$(git status --porcelain)" ]] || fail '源码目录有本地修改或未跟踪文件，不覆盖'
    exec 8>"$(git rev-parse --git-dir)/dataagent-source.lock"
    flock -n 8 || fail '已有本项目源码更新进行中'
    if (( 10#$probe_timeout > 0 )); then
        echo '检查Git主线读取'
        probe_ok=false
        for attempt in 1 2 3; do
            if advertised="$(GIT_TERMINAL_PROMPT=0 timeout "${probe_timeout}s" git ls-remote origin "refs/heads/$branch")"; then
                [[ "$advertised" =~ ^([a-f0-9]{40})[[:space:]](.+)$ && "${BASH_REMATCH[2]}" == "refs/heads/$branch" ]] || fail '远端没有返回指定主线'
                probe_ok=true
                break
            fi
            echo "Git主线读取未完成，第${attempt}/3次"
            [[ "$attempt" == 3 ]] || sleep "$((attempt * 3))"
        done
        [[ "$probe_ok" == true ]] || fail 'Git分支读取失败；未开始拉取或替换服务'
    fi
    ok=false
    for attempt in 1 2 3; do
        echo "拉取指定分支，第${attempt}/3次"
        if GIT_TERMINAL_PROMPT=0 timeout "${fetch_timeout}s" git fetch origin "refs/heads/$branch:refs/remotes/origin/$branch"; then ok=true; break; fi
        [[ "$attempt" == 3 ]] || sleep "$((attempt * 3))"
    done
    [[ "$ok" == true ]] || fail '拉取失败，保留本地源码和原服务；请检查网络或读取权限'
    git merge --ff-only "refs/remotes/origin/$branch" || fail '分支不能快进，不自动合并或reset'
    git rev-parse HEAD
    echo '源码已更新，尚未构建或启停业务服务'
}

main "$@"; exit $?
