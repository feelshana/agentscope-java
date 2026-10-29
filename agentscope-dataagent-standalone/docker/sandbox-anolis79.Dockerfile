# Analysis image for host 66; keep the same user/workspace contract as sandbox.Dockerfile.
FROM m.daocloud.io/docker.io/library/python:3.12-slim-bookworm
RUN sed -i 's|http://deb.debian.org|https://mirrors.tuna.tsinghua.edu.cn|g' /etc/apt/sources.list.d/debian.sources \
    && apt-get -o Acquire::Retries=2 -o Acquire::https::Timeout=30 update \
    && apt-get -o Acquire::Retries=2 -o Acquire::https::Timeout=30 install -y --no-install-recommends \
       fonts-noto-cjk util-linux
RUN python -m pip install --no-cache-dir --timeout 60 --retries 2 \
    -i https://pypi.tuna.tsinghua.edu.cn/simple pandas numpy scipy matplotlib \
    && python -m pip check
# The existing run_python font discovery looks for SC .ttf files, not TTC collections.
RUN python -c 'from pathlib import Path; from fontTools.ttLib import TTCollection; p=Path("/usr/share/fonts/truetype/dataagent"); p.mkdir(parents=True,exist_ok=True); c=TTCollection("/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc"); f=next(f for f in c.fonts if any(n.nameID==1 and n.toUnicode()=="Noto Sans CJK SC" for n in f["name"].names)); f.save(str(p/"NotoSansCJKSC.ttf"))'
ENV MPLBACKEND=Agg
WORKDIR /workspace
