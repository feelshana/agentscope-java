#!/usr/bin/env bash
# Host-side, build-only command. It never stops, replaces or starts a business service.
set -Eeuo pipefail
fail() { echo "构建失败：$*" >&2; exit 1; }
[[ $# -le 1 ]] || fail '用法：bash docker/build-from-source.sh [完整提交号或HEAD]'
script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
repo="$(cd -- "$script_dir/../.." && pwd -P)"
cd -- "$repo"
[[ -d agentscope-core && -f agentscope-dataagent-standalone/pom.xml ]] || fail '需要完整agentscope-java仓库'
[[ "$(git rev-parse --show-toplevel)" == "$repo" ]] || fail '源码目录不是仓库根'
git diff --quiet && git diff --cached --quiet || fail '有未提交修改，先明确要发布的源码'
for file in build-from-source.sh build-in-container.sh build-maven-settings.xml application.Dockerfile application-entrypoint.sh; do
    git ls-files --error-unmatch "agentscope-dataagent-standalone/docker/$file" >/dev/null 2>&1 || fail "文件尚未提交：$file"
done
requested="${1:-HEAD}"
[[ "$requested" == HEAD || "$requested" =~ ^[a-fA-F0-9]{40}$ ]] || fail '只接受HEAD或完整提交号'
revision="$(git rev-parse --verify "$requested^{commit}")"
[[ "$revision" == "$(git rev-parse HEAD)" ]] || fail '指定提交与当前HEAD不同，请在独立干净源码目录构建该提交'
docker_cmd=(docker)
[[ "${DATAAGENT_DOCKER_SUDO:-true}" == false ]] || docker_cmd=(sudo docker)
d() { "${docker_cmd[@]}" "$@"; }
builder="${DATAAGENT_BUILDER_IMAGE:-m.daocloud.io/docker.io/library/maven@sha256:f58d59b6273e785ac0a4477f6e9b5ba1d7731c75b906c0f7b34076f1851318cc}"
runtime="${DATAAGENT_RUNTIME_IMAGE:-dataagent/runtime:20261028}"
d image inspect "$builder" >/dev/null 2>&1 || fail '先拉取Maven3.9.9/JDK17构建镜像'
d image inspect "$runtime" >/dev/null 2>&1 || fail '已验收runtime镜像不存在'
for ref in "$builder" "$runtime"; do
    [[ "$(d image inspect --format '{{.Os}}/{{.Architecture}}' "$ref")" == linux/amd64 ]] || fail '仅支持已验收linux/amd64镜像'
done
root="${DATAAGENT_BUILD_ROOT:-$HOME/dataagent-build}"
[[ "$root" == /* && "$root" != / && "$root" != /data ]] || fail '构建根目录无效'
mkdir -p -- "$root/jobs" "$root/cache"
root="$(cd -- "$root" && pwd -P)"
exec 9>"$root/build.lock"
flock -n 9 || fail '已有本项目构建进行中'
job="$(mktemp -d "$root/jobs/${revision:0:12}.XXXXXX")"
mkdir -p -- "$job/source" "$job/image" "$job/control"
git archive --format=tar "$revision" > "$job/source.tar"
tar -xf "$job/source.tar" -C "$job/source"
control_source="$job/source/agentscope-dataagent-standalone/docker"
cp -- "$control_source/build-in-container.sh" "$job/control/build-in-container.sh"
settings="${DATAAGENT_MAVEN_SETTINGS:-$control_source/build-maven-settings.xml}"
[[ -r "$settings" ]] || fail 'Maven设置文件不可读'
cp -- "$settings" "$job/control/maven-settings.xml"
image="dataagent/app:${revision:0:12}-$(date -u +%Y%m%d%H%M%S)-$(basename "$job" | cut -d. -f2)"
name="dataagent-build-$(basename "$job" | tr . -)"
echo "源码提交：$revision"
echo "构建记录：$job"
echo '开始容器构建；不会启停业务服务'
args=(run --rm --init --pull never --name "$name" --label "dataagent.build=$root"
    --user "$(id -u):$(id -g)" --cpus "${DATAAGENT_BUILD_CPUS:-2}" --memory "${DATAAGENT_BUILD_MEMORY:-6g}"
    --pids-limit 1024 --mount "type=bind,source=$job/source,target=/source"
    --mount "type=bind,source=$root/cache,target=/cache"
    --mount "type=bind,source=$job/control,target=/control,readonly"
    --mount "type=bind,source=$job/image,target=/result"
    -e HOME=/tmp -e "DATAAGENT_SOURCE_REVISION=$revision"
    -e "DATAAGENT_BASE_PATH=${DATAAGENT_BASE_PATH:-/dify/dataagent/}")
for key in DATAAGENT_NODE_DOWNLOAD_ROOT DATAAGENT_NPM_DOWNLOAD_ROOT DATAAGENT_NPM_REGISTRY HTTP_PROXY HTTPS_PROXY NO_PROXY http_proxy https_proxy no_proxy; do
    [[ -z "${!key:-}" ]] || args+=(-e "$key=${!key}")
done
cleanup() {
    # A cancelled Docker CLI can leave the builder running. Only stop our labelled builder.
    if [[ "$(d container inspect --format '{{index .Config.Labels "dataagent.build"}}' "$name" 2>/dev/null || true)" == "$root" ]]; then
        d stop --timeout 30 "$name" >/dev/null || true
    fi
}
trap cleanup EXIT
timeout --foreground "${DATAAGENT_BUILD_TIMEOUT:-3600}s" "${docker_cmd[@]}" "${args[@]}" \
    --entrypoint /bin/bash "$builder" /control/build-in-container.sh 2>&1 | tee "$job/build.log"
cp -- "$control_source/application-entrypoint.sh" "$job/image/application-entrypoint.sh"
cp -- "$control_source/application.Dockerfile" "$job/image/Dockerfile"
d build --pull=false --network none --build-arg "RUNTIME_IMAGE=$runtime" --build-arg "SOURCE_REVISION=$revision" \
    -t "$image" "$job/image" 2>&1 | tee "$job/image-build.log"
d run --rm --pull never --network none --read-only --tmpfs /tmp:rw,nosuid,size=256m \
    --user "$(id -u):$(id -g)" --cpus 1 --memory 1g "$image" image-check 2>&1 | tee "$job/image-check.log"
printf '%s\n' "$image" > "$job/image-reference.txt"
{
    printf 'source=%s\nimage=%s\n' "$revision" "$image"
    d image inspect --format 'imageId={{.Id}}' "$image"
    d image inspect --format 'builderId={{.Id}}' "$builder"
    d image inspect --format 'runtimeId={{.Id}}' "$runtime"
    cat "$job/image/jar.sha256"
} > "$job/build-result.txt"
echo "镜像构建及静态预检完成：$image"
echo "记录：$job/build-result.txt；未启动或替换业务服务"
