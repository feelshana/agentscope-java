#!/usr/bin/env bash
# Build/check environment images only. Never builds or starts the business release.
set -Eeuo pipefail
here="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
[[ "$(uname -s)" == Linux && "$(uname -m)" == x86_64 ]] || {
    echo '本脚本仅适用于已核对的Linux x86_64主机'; exit 1;
}
d=(sudo docker)
[[ "${DATAAGENT_DOCKER_SUDO:-true}" == false ]] && d=(docker)
[[ -d "${DATAAGENT_MODEL_CACHE:-$HOME/dataagent-env/hf-cache-modelscope}/hub" ]] || {
    echo '先完成默认向量模型校验下载'; exit 1;
}
"${d[@]}" image inspect dataagent/wren-deps-check:20261028 >/dev/null || {
    echo '缺少此前已验收的Wren依赖基础镜像'; exit 1;
}
echo '第一步：构建Java17/Python/Wren运行环境'
"${d[@]}" build --pull=false --platform linux/amd64 -f "$here/runtime.Dockerfile" \
    -t dataagent/runtime:20261028 "$here"
echo '第二步：构建独立Python分析沙箱'
"${d[@]}" build --pull=false --platform linux/amd64 -f "$here/sandbox-anolis79.Dockerfile" \
    -t agentscope/dataagent-sandbox:20261028 "$here"
echo '第三步：验证离线模型、宿主Docker、共享目录、计算与中文图表'
bash "$here/check-environment.sh"
for pair in 'dataagent/runtime:20261028 runtime' 'agentscope/dataagent-sandbox:20261028 sandbox'; do
    read -r image label <<< "$pair"
    "${d[@]}" run --rm --pull never --network none --entrypoint python "$image" -m pip freeze \
        > "$here/$label-requirements-resolved.txt"
    "${d[@]}" image inspect "$image" --format '{{.Id}} {{.Os}}/{{.Architecture}}' \
        > "$here/$label-image-info.txt"
done
echo '环境镜像构建和检查全部完成；此脚本未启动业务服务'
