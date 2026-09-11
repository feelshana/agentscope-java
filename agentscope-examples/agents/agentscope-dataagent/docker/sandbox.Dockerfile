# Sandbox image for the agentscope-dataagent per-user Docker sandboxes.
#
# The agent's run_python tool executes pandas / matplotlib / scipy code inside these
# containers, and the containers run with --network=none, so the analysis stack must be
# baked into the image (no pip install at runtime). Noto CJK fonts are included so
# matplotlib charts can render Chinese labels.
#
# Build (from the agentscope-examples/agents/agentscope-dataagent module directory):
#   docker build -f docker/sandbox.Dockerfile -t agentscope/dataagent-sandbox:latest .
#   (regional networks: append --build-arg PIP_INDEX_URL=https://pypi.tuna.tsinghua.edu.cn/simple)
#
# Then start the app; the image tag is configurable via dataagent.sandbox.image.
FROM python:3.12-slim

# CJK fonts for matplotlib Chinese labels. Cached pip wheels keep the layer rebuildable.
RUN apt-get update \
    && apt-get install -y --no-install-recommends fonts-noto-cjk \
    && rm -rf /var/lib/apt/lists/*

# Optional: override the pip index with a regional mirror at build time, e.g.
#   --build-arg PIP_INDEX_URL=https://pypi.tuna.tsinghua.edu.cn/simple
ARG PIP_INDEX_URL=https://pypi.org/simple
RUN pip install --no-cache-dir -i ${PIP_INDEX_URL} \
        pandas \
        matplotlib \
        scipy \
        numpy

# matplotlib must never try to open a display inside the headless sandbox.
ENV MPLBACKEND=Agg

# The sandbox harness keeps the container alive with a POSIX `sh` loop and creates
# /workspace via `docker exec mkdir -p`, so the image must stay on the default root user.
WORKDIR /workspace
