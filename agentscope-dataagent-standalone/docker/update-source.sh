#!/usr/bin/env bash
# Fetch only the configured branch, with bounded retries; never force or merge diverged history.
set -Eeuo pipefail
fail() { echo "更新源码失败：$*" >&2; exit 1; }
script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
repo="$(cd -- "$script_dir/../.." && pwd -P)"
cd -- "$repo"
branch="${DATAAGENT_SOURCE_BRANCH:-codex/integrate-boundary-lyf-v2}"
[[ "$(git symbolic-ref --short HEAD)" == "$branch" ]] || fail '当前不是指定部署分支，不自动切换'
[[ -z "$(git status --porcelain)" ]] || fail '源码目录有本地修改或未跟踪文件，不覆盖'
exec 8>"$(git rev-parse --git-dir)/dataagent-source.lock"
flock -n 8 || fail '已有本项目源码更新进行中'
ok=false
for attempt in 1 2 3; do
    echo "拉取指定分支，第${attempt}/3次"
    if GIT_TERMINAL_PROMPT=0 timeout 90s git fetch origin "$branch"; then ok=true; break; fi
    [[ "$attempt" == 3 ]] || sleep "$((attempt * 3))"
done
[[ "$ok" == true ]] || fail '拉取失败，保留本地源码和原服务；请检查网络或读取权限'
git merge --ff-only FETCH_HEAD || fail '分支不能快进，不自动合并或reset'
git rev-parse HEAD
echo '源码已更新，尚未构建或启停业务服务'
