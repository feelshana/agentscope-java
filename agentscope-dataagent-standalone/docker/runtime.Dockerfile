# Linux amd64 runtime only; the operator supplies the application release by bind mount.
ARG DOCKER_CLI_IMAGE=m.daocloud.io/docker.io/library/docker@sha256:2a4887aeef357d9c61e5ac41f308cbc59d3a1c11faf19fc57aff22b1a7edb72f
ARG WREN_BASE_IMAGE=dataagent/wren-deps-check:20261028
FROM ${DOCKER_CLI_IMAGE} AS docker_cli

# Reuse the Wren environment already built and checked on host 66.
FROM ${WREN_BASE_IMAGE}
RUN apt-get -o Acquire::Retries=2 -o Acquire::https::Timeout=30 update \
    && apt-get -o Acquire::Retries=2 -o Acquire::https::Timeout=30 install -y --no-install-recommends \
       openjdk-17-jre-headless bash util-linux coreutils curl git
COPY --from=docker_cli /usr/local/bin/docker /usr/local/bin/docker
COPY runtime-entrypoint.sh /usr/local/bin/dataagent-runtime
RUN chmod 0755 /usr/local/bin/dataagent-runtime \
    && java -version && docker --version && wren --version && python -m pip check

ENV DATAAGENT_JAVA=/usr/bin/java \
    DATAAGENT_WREN_EXECUTABLE=/usr/local/bin/wren \
    DATAAGENT_WREN_PYTHON=/usr/local/bin/python \
    WREN_MEMORY_BACKEND=lancedb \
    WREN_EMBEDDING_BACKEND=onnx \
    HF_HUB_OFFLINE=1 \
    PYTHONUTF8=1 \
    PYTHONIOENCODING=utf-8 \
    TZ=Asia/Shanghai
HEALTHCHECK --interval=30s --timeout=5s --start-period=180s --retries=3 \
    CMD python -c 'import json,os,urllib.request; u="http://127.0.0.1:"+os.getenv("DATAAGENT_PORT","18080")+"/actuator/health"; p=urllib.request.build_opener(urllib.request.ProxyHandler({})); r=p.open(u,timeout=3); assert json.load(r).get("status")=="UP"'
ENTRYPOINT ["/usr/local/bin/dataagent-runtime"]
