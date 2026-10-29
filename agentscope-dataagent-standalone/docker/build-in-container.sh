#!/usr/bin/env bash
# Build an archived commit; never mount production data or the host Docker socket.
set -Eeuo pipefail
cd /source
test -f pom.xml && test -f agentscope-dataagent-standalone/pom.xml
mkdir -p /cache/maven /cache/npm /result/app/bin /result/app/lib
export MAVEN_CONFIG=/cache/maven
export npm_config_cache=/cache/npm
export npm_config_registry="${DATAAGENT_NPM_REGISTRY:-https://registry.npmmirror.com}"
export DATAAGENT_BASE_PATH="${DATAAGENT_BASE_PATH:-/dify/dataagent/}"
common=(-B -ntp -Dmaven.repo.local=/cache/maven/repository
    -Dmaven.wagon.http.retryHandler.count=2 -Daether.connector.connectTimeout=30000
    -Daether.connector.requestTimeout=120000 -Dmaven.javadoc.skip=true -Dgpg.skip=true)
if [[ -f /control/maven-settings.xml ]]; then common+=(-s /control/maven-settings.xml); fi
java -version
mvn -version
# Include every directly used local SNAPSHOT and both BOMs, not only harness/core.
modules=agentscope-dependencies-bom,agentscope-distribution/agentscope-bom,agentscope-harness
modules+=,agentscope-extensions/agentscope-extensions-mysql
modules+=,agentscope-extensions/agentscope-extensions-model/agentscope-extensions-model-dashscope
modules+=,agentscope-extensions/agentscope-extensions-model/agentscope-extensions-model-openai
modules+=,agentscope-extensions/agentscope-extensions-channel/agentscope-extensions-channel-dingtalk
modules+=,agentscope-extensions/agentscope-extensions-skills/agentscope-extensions-skill-git-repository
modules+=,agentscope-extensions/agentscope-extensions-nacos/agentscope-extensions-nacos-skill
modules+=,agentscope-extensions/agentscope-extensions-redis
mvn "${common[@]}" -pl "$modules" -am install -DskipTests
mvn "${common[@]}" -f agentscope-dataagent-standalone/pom.xml verify \
    -Dfrontend.npm.install.arguments=ci \
    "-DnodeDownloadRoot=${DATAAGENT_NODE_DOWNLOAD_ROOT:-https://npmmirror.com/mirrors/node/}" \
    "-DnpmDownloadRoot=${DATAAGENT_NPM_DOWNLOAD_ROOT:-https://registry.npmmirror.com/npm/-/}"
module=agentscope-dataagent-standalone
shopt -s nullglob
jars=("$module"/target/*-exec.jar)
[[ ${#jars[@]} == 1 ]] || { echo '构建结果必须有且仅有一个exec JAR' >&2; exit 1; }
cp -- "${jars[0]}" /result/app/lib/dataagent-exec.jar
cp -- "$module/src/main/bin/check-wren.py" /result/app/bin/check-wren.py
cp -- "$module/requirements-wren.txt" /result/app/requirements-wren.txt
cp -- "$module/src/main/distribution/conf/application-prod.yml.example" /result/application-prod.yml.example
printf '%s\n' "$DATAAGENT_SOURCE_REVISION" > /result/app/source-revision
sha256sum /result/app/lib/dataagent-exec.jar > /result/jar.sha256
echo 'Java与前端验证完成；待宿主封装及预检应用镜像，未启动业务服务'
