#!/usr/bin/env bash
# Install every local artifact directly consumed by the standalone app, from this checkout.
set -Eeuo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/../.."

# Keep the same dependency boundary as standalone/docker/build-in-container.sh.
modules=agentscope-dependencies-bom,agentscope-distribution/agentscope-bom,agentscope-harness
modules+=,agentscope-extensions/agentscope-extensions-mysql
modules+=,agentscope-extensions/agentscope-extensions-model/agentscope-extensions-model-dashscope
modules+=,agentscope-extensions/agentscope-extensions-model/agentscope-extensions-model-openai
modules+=,agentscope-extensions/agentscope-extensions-channel/agentscope-extensions-channel-dingtalk
modules+=,agentscope-extensions/agentscope-extensions-skills/agentscope-extensions-skill-git-repository
modules+=,agentscope-extensions/agentscope-extensions-nacos/agentscope-extensions-nacos-skill
modules+=,agentscope-extensions/agentscope-extensions-redis

# Framework tests remain covered by the existing framework workflow. This job tests the app.
mvn -B -ntp -Dmaven.javadoc.skip=true -Dgpg.skip=true \
    -pl "$modules" -am install -DskipTests "$@"
