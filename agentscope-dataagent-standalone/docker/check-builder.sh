#!/usr/bin/env bash
# A new temporary container only: no production mounts, ports, Docker socket or service changes.
set -Eeuo pipefail
image="${DATAAGENT_BUILDER_IMAGE:-m.daocloud.io/docker.io/library/maven@sha256:f58d59b6273e785ac0a4477f6e9b5ba1d7731c75b906c0f7b34076f1851318cc}"
docker_cmd=(docker)
[[ "${DATAAGENT_DOCKER_SUDO:-true}" == false ]] || docker_cmd=(sudo docker)
"${docker_cmd[@]}" image inspect "$image" >/dev/null
"${docker_cmd[@]}" run --rm --pull never --platform linux/amd64 --network none \
    --name "dataagent-builder-probe-$(date +%Y%m%d%H%M%S)-$$" --label dataagent.builder-probe=true \
    --cpus 1 --memory 512m --pids-limit 128 --entrypoint /bin/bash "$image" \
    -lc 'set -eu; java -version; javac -version; mvn -version; uname -m; echo "构建环境基础检查通过；未下载项目依赖或启动业务服务"'
