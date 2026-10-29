# 2.66已确认Anolis7.9：下一步与安装边界

当前66主交付方式已选择GitHub源码构建，后续操作以[源码构建指引](66-source-build.md)为准。本文保留运行环境准备或外置tar备选说明，不要求在66重复安装或重复部署。

**当前正式操作（2026-10-29）**：用户已确认Java/Python/Wren同容器、独立分析沙箱的部署方式。原生依赖和模型检查已通过；2026-10-29用户已回传正式Java运行/分析环境安装检查全部通过，两库信息尚未取得，下一步只准备正式目录/端口；后续以[容器环境与发布操作](66-container-runtime.md)为准。下文保留已执行步骤、故障与备选记录，不重复执行旧HF-Mirror下载或宿主JDK安装建议。

## 当前事实（2026-10-28截图）

生产操作约束（用户最新明确）：本次Python检查的docker run自动移除参数放进脚本执行，服务自身清理容器没有问题。按此范围准备透明的普通用户脚本，内部调用已允许的sudo docker；不使用sudo bash，不泛化为允许任意删除或免审查。服务现有回收机制不改，之前保留检查容器的临时建议由本节最新说明取代。

- 系统为Anolis OS7.9，ID_LIKE为rhel/fedora/centos，架构x86_64；不是此前准备的Ubuntu24.04或Rocky9。
- 当前PATH的Java为OpenJDK1.8.0_262，不能用于运行本项目Java17基线程序；不据此判断其他位置绝无JDK17。
- python3命令不存在，Wren按用户确认未安装处理；不再要求重复检查Wren。不能替换系统python，yum等系统工具可能依赖它。
- 首批截图Docker普通访问被拒；补充截图已确认sudo docker version读取成功，客户端/服务端均26.1.4、linux/amd64，containerd1.6.33/runc1.1.12。已有多套业务容器持续运行，不重装或重启Docker。先前docker info的CPU0/Memory0是请求失败后的空值，不是资源结论。
- glibc已实测2.17，内核3.10.0-1160.an7.x86_64；Python/Wren的直接安装路径需要适配，不能再当作待猜测的系统版本。
- 当前是普通运维账号，截图组为operation；sudo配置有明确禁止项，已验证docker命令可执行，不代表任意root命令都可执行，不绕过其限制。bash/curl/flock/setsid/runuser/gcc/pkg-config可见，文件句柄上限65535。
- CPU32，内存available约154GiB；/data为本机XFS磁盘，容量14T且空余充足，另有多个NFS挂载。建议后续为本项目使用独立本机/data/dataagent目录，不能把已有NFS业务目录当空目录覆盖。最终路径/权限尚未创建或核实。

## 已通过：国内镜像拉取与66基础Python检查

用户先后通过DockerHub拉取遇503、ECR拉取五次在同一层停滞，不能认为66能访问公网就能正常下载全部境外镜像层。crane只是曾提出的备选，未获得66执行结果，不列作已安装工具。随后用户通过DaoCloud完成拉取和检查脚本运行；没有修改daemon.json或重启Docker。

2026-10-28用户截图的实际结果：Python3.12.16、容器内glibc2.36、宿主内核3.10.0-1160.an7.x86_64、OpenSSL3.0.22。imageId为sha256:37ccb536022bb36c4570856b55ebfa85bf07827e3c2fa3eba37f0dac62eebc05，来源索引digest为sha256:e697c81897302a4c4333bade000d98038fbb21eb064f8ee1fd42d02ebcd9d337，与本轮Docker官方及ECR镜像相同。助手此前通过Registry V2匿名token核对DaoCloud的索引、amd64清单和配置摘要；仅索引可读不能证明层可下载，用户后续截图补齐拉取及运行证据。

已在66成功执行的拉取命令：

```bash
sudo docker pull --platform linux/amd64 m.daocloud.io/docker.io/library/python:3.12-slim-bookworm
sudo docker image inspect m.daocloud.io/docker.io/library/python:3.12-slim-bookworm --format '{{.Id}}'
```

已在66成功执行的基础检查脚本（按运维要求在脚本中运行自动移除命令）：

```bash
cat > ~/dataagent-python-probe-daocloud.sh <<'EOF'
#!/usr/bin/env bash
set -euo pipefail

sudo docker run --rm --pull never --network none --memory 512m \
  m.daocloud.io/docker.io/library/python:3.12-slim-bookworm \
  python -c 'import sys,platform,ssl; print(sys.version); print("glibc:",platform.libc_ver()); print("kernel:",platform.release()); print(ssl.OPENSSL_VERSION)'
EOF
bash ~/dataagent-python-probe-daocloud.sh
```

基础检查没有挂载宿主目录或发布端口，不改变宿主Java8/Python/glibc。它只证明基础解释器与SSL可运行，尚不能证明Wren、向量模型、Java、分析沙箱或业务查询可用。

来源：[DaoCloud公开镜像说明](https://github.com/DaoCloud/public-image-mirror)。阿里云镜像加速器目前停止同步最新镜像，并有访问环境限制，不能随意换成某个aliyuncs公共地址；正式上线宜使用公司镜像仓库或经批准的ACR仓库中转。参考[阿里云加速器现状](https://help.aliyun.com/zh/acr/user-guide/accelerate-the-pulls-of-docker-official-images)。公开镜像服务的可达性和tag内容不能作为未来保证，正式验收需固定镜像与依赖版本。

## 已通过：Wren依赖检查镜像构建

用户2026-10-28截图确认构建全部8/8成功，总耗时87.2秒。pip安装/一致性检查步骤61.9秒，原生模块导入及CLI版本检查步骤3.9秒，无缓存标记；镜像dataagent/wren-deps-check:20261028已生成。此结果只验证固定入口依赖在66构建和加载，不挂载业务目录、不连接数据库、不启动应用、不决定完整应用容器还是其他最终部署方式。用户本人Maven打包上传的流程不变。此检查镜像保留编译工具和构建缓存，尚不是最终生产运行镜像；依赖完整解析版本应在通过后记录并锁定。

在当前普通账号下创建独立目录和Dockerfile。基础镜像使用已下载的DaoCloud路径；只在此镜像内部改用清华Debian/PyPI镜像，不修改宿主软件源，不关闭TLS或软件包签名验证。软件源说明：[Debian](https://mirrors.tuna.tsinghua.edu.cn/help/debian/)、[PyPI](https://mirrors.tuna.tsinghua.edu.cn/help/pypi/)。

```bash
mkdir -p ~/dataagent-env/wren-check-20261028
cd ~/dataagent-env/wren-check-20261028
cat > Dockerfile <<'EOF'
FROM m.daocloud.io/docker.io/library/python:3.12-slim-bookworm

RUN sed -i 's|http://deb.debian.org|https://mirrors.tuna.tsinghua.edu.cn|g' /etc/apt/sources.list.d/debian.sources \
    && apt-get -o Acquire::Retries=2 -o Acquire::https::Timeout=30 update \
    && apt-get -o Acquire::Retries=2 -o Acquire::https::Timeout=30 install -y --no-install-recommends \
       build-essential default-libmysqlclient-dev pkg-config

RUN python -m pip install --no-cache-dir --timeout 60 --retries 2 \
    -i https://pypi.tuna.tsinghua.edu.cn/simple \
    'wrenai[mcp,mysql,memory-onnx]==0.15.0' \
    'wren-core-py==0.8.0' 'mcp==1.30.0' \
    && python -m pip check

RUN python -c 'import wren,wren_core,mcp,MySQLdb,lancedb,onnxruntime; print("Wren native dependencies imported successfully")' \
    && wren --version
EOF

cat > build-wren-check.sh <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
cd "$HOME/dataagent-env/wren-check-20261028"
sudo docker build --pull=false --platform linux/amd64 \
  -t dataagent/wren-deps-check:20261028 .
EOF

bash build-wren-check.sh
```

构建使用Docker默认的隔离构建流程，成功后保留镜像和构建缓存；没有额外提供或执行镜像/容器清理命令。若旧内核、构建器、APT或pip下载失败，保留完整错误，先确认失败环节，不升级宿主glibc/内核，不调整全局Docker配置或更换固定入口版本。BuildKit默认成功时不展开RUN标准输出，截图未直接展示版本文本，但该RUN包含导入及wren --version，且以成功状态完成。不能把这一步等同于已完成向量模型计算或数据库查询。

后续仍须单独验证向量模型缓存及真实计算、Wren索引与SQL、Java17、Python分析沙箱，以及路径/取消/重启/文件下载。仅在依赖结果明确后选择和实施正式服务启动方式。

## 已通过：ModelScope校验下载，HF缓存断网加载

2026-10-28用户实际运行原HF-Mirror向量脚本：tokenizer.json下载跳转cas-bridge.xethub.hf.co，报read operation timed out并自动重试，未出现向量结果。此证据是模型下载链路故障，不是已证明ONNX计算失败。已让用户Ctrl+C中断，不重复等待。HF_HUB_DISABLE_XET仅禁用hf-xet客户端，不能保证HTTP下载不重定向境外CAS/CDN；此前把它表述为可以避开整条Xet链路不准确，现纠正。官方[环境变量](https://huggingface.co/docs/huggingface_hub/package_reference/environment_variables)和[下载机制](https://huggingface.co/docs/huggingface_hub/guides/download)可核对。

改为从[ModelScope同名仓库](https://modelscope.cn/models/sentence-transformers/paraphrase-multilingual-MiniLM-L12-v2/files)直接下载四个必要文件（约480MB），不安装ModelScope SDK、不改Wren官方代码/模型、不增加代理服务。固定ModelScope修订3d26be06a662164d54f7ffe720321c69d67b3766；与HF修订e8f8c211226b894fcb81acc59f3b34ba3efd5f42的文件相同。助手本机核对：大文件SHA256/大小与HF LFS清单一致，tokenizer.json完整9081518字节下载校验通过；两份配置完整下载的SHA256及HF Git对象hash均一致；onnx/model.onnx已Range请求返回206、总长470301610、实际落到cdn-lfs-cn-1.modelscope.cn，仅小范围传输，未完整下载或计算。证据在本机忽略的output/modelscope-minilm-metadata-20261028.json，不把本机可达说成66已通过。

下面脚本直接使用宿主curl下载公开文件；支持断点续传、低速超时、有限重试、进度和逐文件SHA256校验。文件放新的当前账号~/dataagent-env/hf-cache-modelscope，保留旧HF-Mirror缓存不覆盖/删除；四个文件全部校验后才写refs/main。按官方[HF缓存布局](https://huggingface.co/docs/huggingface_hub/guides/manage-cache)准备独立快照，保留文件本体而非依赖外部软链接。不会把ModelScope的修订号冒充HF修订；这里只映射已逐项比对的四份原文件，不宣称完整仓库快照。

随后只启动一次无网络容器、只读挂载缓存，HF_HUB_OFFLINE=1，实际验证ONNX后端、2×384向量、有限值、归一化及加载/计算耗时。自动移除仅在透明脚本里执行，镜像/缓存保留；默认CPU模型无需GPU/大模型Key。此目录仍为安装检查缓存，正式应用路径、权限和备份尚未确定。

```bash
cat > ~/dataagent-env/prepare-wren-model-modelscope.sh <<'SCRIPT'
#!/usr/bin/env bash
set -euo pipefail
cache="$HOME/dataagent-env/hf-cache-modelscope"
repo="$cache/hub/models--sentence-transformers--paraphrase-multilingual-MiniLM-L12-v2"
revision="e8f8c211226b894fcb81acc59f3b34ba3efd5f42"
snapshot="$repo/snapshots/$revision"
base="https://modelscope.cn/models/sentence-transformers/paraphrase-multilingual-MiniLM-L12-v2/resolve/3d26be06a662164d54f7ffe720321c69d67b3766"
mkdir -p "$snapshot/1_Pooling" "$snapshot/onnx" "$repo/refs"
cd "$snapshot"

while read -r expected file; do
  if printf '%s  %s\n' "$expected" "$file" | sha256sum -c --status 2>/dev/null; then
    echo "已校验，跳过下载：$file"
    continue
  fi
  echo "正在下载：$file"
  curl --fail --location --progress-bar --continue-at - \
    --connect-timeout 15 --max-time 1800 --speed-time 60 --speed-limit 1024 \
    --retry 2 --output "$file" "$base/$file"
  printf '%s  %s\n' "$expected" "$file" | sha256sum -c -
done <<'FILES'
4be450dde3b0273bb9787637cfbd28fe04a7ba6ab9d36ac48e92b11e350ffc23  1_Pooling/config.json
70f4448f31320443fe3557cacea5abf2dcc4915dda8c80646bec9f3bb0aa5a1f  sentence_bert_config.json
2c3387be76557bd40970cec13153b3bbf80407865484b209e655e5e4729076b8  tokenizer.json
10f7a088420252b26caf819236ca2c9d2987afd0fc06fec7553b542a5655a05a  onnx/model.onnx
FILES

printf '%s' "$revision" > "$repo/refs/main"
echo '四个文件校验通过，开始断网计算'
sudo docker run --rm --pull never --network none --cpus 2 --memory 4g \
  --user "$(id -u):$(id -g)" \
  --mount "type=bind,source=$cache,target=/cache/huggingface,readonly" \
  -e HOME=/tmp -e HF_HOME=/cache/huggingface \
  -e HF_HUB_OFFLINE=1 -e HF_HUB_DISABLE_IMPLICIT_TOKEN=1 \
  -e WREN_EMBEDDING_BACKEND=onnx \
  dataagent/wren-deps-check:20261028 python -u -c 'import time
import numpy as np
from wren.memory.embeddings import get_embedding_function, resolve_embedding_backend
t = time.perf_counter()
assert resolve_embedding_backend() == "onnx"
v = np.asarray(get_embedding_function().compute_source_embeddings(["知识库检索测试", "2025年销售额"]), dtype=np.float32)
assert v.shape == (2, 384), v.shape
assert np.isfinite(v).all()
assert np.allclose(np.linalg.norm(v, axis=1), 1, atol=1e-3)
print("断网向量计算通过：", v.shape, "耗时：", round(time.perf_counter()-t, 2), "秒", flush=True)'
SCRIPT
bash ~/dataagent-env/prepare-wren-model-modelscope.sh
```

用户最新明确回传最后出现“断网向量计算通过：(2, 384)”。按set -e脚本顺序，这表示四份下载文件校验与断网ONNX计算均通过，脚本还检查finite与L2归一化；尚未回传具体耗时，不填写或推算。缓存实际位置为当前账号~/dataagent-env/hf-cache-modelscope，需后续正式应用挂载复用。此结果不能代替官方索引/召回/SQL、Java17、分析沙箱和完整应用验收。此前HF-Mirror下载脚本在桌面保留作历史，不再作为当前建议重复执行。

## 当前下一步：分析沙箱镜像（待66执行）

基于当前docker/sandbox.Dockerfile和DockerSandbox#doExec的setsid要求，准备等价依赖的国内源操作版本。使用已验证bookworm基础，而非跟随未验证的python:3.12-slim滚动到其他Debian发行版；不修改仓库Dockerfile、默认配置或既有镜像标签。镜像新建为agentscope/dataagent-sandbox:20261028，正式启动时需显式设置DATAAGENT_SANDBOX_IMAGE=agentscope/dataagent-sandbox:20261028，尚未配置或启动。

构建仅安装当前分析栈pandas/numpy/scipy/matplotlib、fonts-noto-cjk及util-linux；依项目镜像约定保留root用户、/workspace、MPLBACKEND=Agg，不含Wren/向量模型。分析沙箱原本就与Wren运行环境分开。通过国内APT/PyPI下载，未关闭TLS，不包含显式删除命令。构建保留镜像及Docker缓存，不运行宿主安装或全局改源。

检查在当前默认单沙箱1CPU/1024MiB/network none下运行：setsid实际子进程、分组销售额300/150、CSV往返一致、SciPy线性回归、中文字符字体覆盖、有效PNG生成。无宿主业务目录挂载，样例CSV/PNG仅在检查容器/tmp；script内自动移除检查容器，宿主镜像及构建目录保留。此检查不模拟10-15人并发，也不等同于真实Agent调用/文件下载/UI验收。成功后以第二个临时容器记录pip freeze及镜像编号，供后续锁定版本。

```bash
mkdir -p ~/dataagent-env/analysis-sandbox-20261028
cd ~/dataagent-env/analysis-sandbox-20261028
cat > Dockerfile <<'EOF'
FROM m.daocloud.io/docker.io/library/python:3.12-slim-bookworm

RUN sed -i 's|http://deb.debian.org|https://mirrors.tuna.tsinghua.edu.cn|g' /etc/apt/sources.list.d/debian.sources \
    && apt-get -o Acquire::Retries=2 -o Acquire::https::Timeout=30 update \
    && apt-get -o Acquire::Retries=2 -o Acquire::https::Timeout=30 install -y --no-install-recommends \
       fonts-noto-cjk util-linux

RUN python -m pip install --no-cache-dir --timeout 60 --retries 2 \
    -i https://pypi.tuna.tsinghua.edu.cn/simple \
    pandas numpy scipy matplotlib \
    && python -m pip check

ENV MPLBACKEND=Agg
WORKDIR /workspace
EOF

cat > build-and-check.sh <<'SCRIPT'
#!/usr/bin/env bash
set -euo pipefail
build_dir="$HOME/dataagent-env/analysis-sandbox-20261028"
cd "$build_dir"
image="agentscope/dataagent-sandbox:20261028"
sudo docker build --pull=false --platform linux/amd64 -t "$image" .
sudo docker run --rm --pull never --network none --cpus 1 --memory 1024m \
  -i "$image" python -u - <<'PY'
from pathlib import Path
import io, shutil, subprocess
import pandas as pd
import numpy as np
from scipy.stats import linregress
import matplotlib.pyplot as plt
from matplotlib import font_manager
from matplotlib.ft2font import FT2Font

assert shutil.which("setsid"), "setsid missing"
subprocess.run(["setsid", "--wait", "sh", "-c", "printf setsid_ok"], check=True)
df = pd.DataFrame({"区域": ["华东", "华东", "华南"], "销售额": [100, 200, 150]})
grouped = df.groupby("区域")["销售额"].sum()
assert grouped.to_dict() == {"华东": 300, "华南": 150}
csv = Path("/tmp/dataagent-sandbox-check.csv")
df.to_csv(csv, index=False, encoding="utf-8-sig")
pd.testing.assert_frame_equal(pd.read_csv(csv), df)
assert np.isclose(linregress([1, 2, 3], [2, 4, 6]).slope, 2)
font = font_manager.findfont(font_manager.FontProperties(family="Noto Sans CJK SC"), fallback_to_default=False)
assert all(ord(c) in FT2Font(font).get_charmap() for c in "区域销售额华东华南")
plt.rcParams["font.family"] = "Noto Sans CJK SC"
plt.rcParams["axes.unicode_minus"] = False
grouped.plot.bar(title="区域销售额")
png = Path("/tmp/dataagent-sandbox-check.png")
plt.savefig(png)
plt.close("all")
assert png.stat().st_size > 1000
assert png.read_bytes().startswith(b"\x89PNG\r\n\x1a\n")
print("\n分析沙箱检查通过：分组计算、CSV读写、SciPy、中文字体、PNG生成、setsid", flush=True)
PY

sudo docker run --rm --pull never --network none --memory 256m \
  "$image" python -m pip freeze > "$build_dir/sandbox-requirements-resolved.txt"
sudo docker image inspect "$image" --format '{{.Id}} {{.Os}}/{{.Architecture}}' \
  > "$build_dir/sandbox-image-info.txt"
echo '依赖版本及镜像编号已记录在构建目录'
SCRIPT
bash build-and-check.sh
```

助手已做bash和内嵌Python语法检查，未实际构建本镜像或运行分析检查，66需返回结果。成功应看到“分析沙箱检查通过”与版本/镜像信息记录提示；若出错返回首处错误，不改宿主Docker配置。下一阶段再准备Java17、正式持久目录/运行账号，以及应用如何接入Wren和分析沙箱；当前完成的是环境分项验收，尚未选择或验证正式应用启动方式。

## 已准备的备选：离线导入临时检查镜像

前一轮补充的glibc、内核、sudo与Docker服务端命令已执行，无需重复。66拉取python:3.12-slim-bookworm时在registry-1.docker.io/v2返回Service Unavailable，尚未下载/运行镜像，不能归因于Python或旧内核不兼容。单凭503也不能确定是公司代理、网络或仓库侧哪一层故障。

本机已成功拉取[官方Python镜像](https://github.com/docker-library/official-images/blob/master/library/python)的linux/amd64版本，额外赋予 `dataagent/runtime-probe:py312-bookworm-20261028` 专用标签并导出gzip归档，避免覆盖66的通用python标签。本包只是Python/SSL基础检查镜像，不是业务发布包，不含Java/Wren/向量模型。

归档名dataagent-python-probe-linux-amd64.tar.gz，45043323字节（约43MiB），SHA256为13ef2e38c43d14e298d6490cef3f99aa0055a6e15c418f6e84a82c9ceb540ab7。另附SHA256SUMS、image-info.json和导入说明，桌面单独交付。来源digest为python@sha256:e697c81897302a4c4333bade000d98038fbb21eb064f8ee1fd42d02ebcd9d337，imageId为sha256:37ccb536022bb36c4570856b55ebfa85bf07827e3c2fa3eba37f0dac62eebc05。

先在66建立登录账号自己的上传目录：

```bash
mkdir -p ~/dataagent-offline-image
```

把归档、SHA256SUMS及image-info.json上传到该目录，不解压。随后逐步执行，校验失败不继续：

```bash
cd ~/dataagent-offline-image
sha256sum -c SHA256SUMS
sudo docker load -i dataagent-python-probe-linux-amd64.tar.gz
sudo docker image inspect dataagent/runtime-probe:py312-bookworm-20261028 --format '{{.Id}} {{.Os}}/{{.Architecture}}'
```

目标Docker26.1.4的load命令不加--platform（该选项需要更高API）；导出包已限制为linux/amd64，并检查含兼容manifest.json、正确配置哈希和层文件。导出/导入依据[Docker save](https://docs.docker.com/reference/cli/docker/image/save/)及[Docker load](https://docs.docker.com/reference/cli/docker/image/load/)。

导入成功、imageId与上述一致后运行，不再访问Docker Hub：

```bash
sudo docker run --name dataagent-python-probe-offline-20261028 --pull never --network none --memory 512m \
  dataagent/runtime-probe:py312-bookworm-20261028 \
  python -c 'import sys,platform,ssl; print(sys.version); print("glibc:",platform.libc_ver()); print("kernel:",platform.release()); print(ssl.OPENSSL_VERSION)'
```

本机Docker29.1.3已实际检查：Python3.12.16、glibc2.36、OpenSSL3.0.22，宿主为WSL2内核6.18；与66的3.10不同，不能替代其验收。66应打印Python3.12、容器内glibc版本、仍为宿主机的3.10内核及OpenSSL版本。检查容器无宿主挂载、不发布端口、无容器网络，退出后保留容器和镜像，不自动清理。没有修改66的镜像源、代理或daemon配置。

容器与宿主共享内核，因此即使这一步通过，也只证明Python/SSL基础加载成功；还必须测试固定Wren原生包、向量模型、Java、索引/查询、取消/重启、文件路径及分析沙箱。不要为通过检查加privileged、挂宿主根目录或关闭安全限制。最终运行账号的Docker授权随部署方式明确后配置。

正式应用当前框架DockerSandbox#shutdown会停止并移除其自管理容器，DockerSandbox#doDestroyWorkspace会在容器内清理对应工作区，应用默认配置含15分钟闲置回收。用户已明确服务自身清理容器没有问题，本轮无需把服务回收改成脚本，也不关闭或改写现有机制；操作说明仍保留实际行为，遵循运维流程。

## 为什么Python/Wren不能照旧安装

本机系统glibc已确认2.17，当前Wren依赖组合的直接预编译安装存在兼容问题，单独装Python3.11不能解决。保留系统glibc，不原地升级以满足本项目依赖。

2026-10-28核对项目当前requirements、已安装wrenai0.15.0分发元数据和PyPI官方文件：

| 组件 | 本次核对事实 |
| --- | --- |
| wrenai0.15.0 | Python>=3.11，并依赖duckdb>=1.5.0等原生包 |
| wren-core-py0.8.0 | 官方x86_64 wheel为manylinux_2_17；它本身不是2.28限制的来源 |
| DuckDB1.5.0 | x86_64 wheel标记manylinux_2_26/manylinux_2_28 |
| 本地ONNX Runtime1.23.2 | x86_64 wheel标记manylinux_2_27/manylinux_2_28 |
| 本地LanceDB0.40.0 | x86_64 wheel标记manylinux_2_28 |

官方来源：[Wren Core](https://pypi.org/project/wren-core-py/0.8.0/)、[DuckDB](https://pypi.org/project/duckdb/1.5.0/)、[ONNX Runtime](https://pypi.org/project/onnxruntime/1.23.2/)、[LanceDB](https://pypi.org/project/lancedb/0.40.0/)。原始文件名证据保存在本机忽略的output/anolis79-wheel-evidence-20261028.json。除固定入口版本外，ONNX/LanceDB并未在requirements逐一固定；这里列出当前本地实际版本，不代表已证明所有历史版本都不能用于2.17。也不把缺少现成wheel说成绝对不能自行源码编译。

本轮交流中曾把2.28要求误归于Wren Core，已纠正；真正需要评估的是完整原生依赖组合。不要为尝试安装而升级系统glibc、替换系统Python或随意降级固定Wren依赖。

## 安装安排

| 项目 | 下一步 |
| --- | --- |
| Docker | 服务端26.1.4已通过sudo访问，DaoCloud拉取与基础Python临时容器检查已通过 |
| Java17 | 用户已选择应用容器内Java17；66已实测Java17运行通过，不再要求宿主单独安装 |
| Python3.11/3.12、Wren | 66基础Python/Wren依赖/向量断网计算已通过；正式运行镜像分项检查已通过，业务JAR接入仍待执行 |
| 向量模型缓存、分析沙箱镜像 | 66模型缓存和断网计算已通过；分析镜像已在66通过真实限制检查 |
| 目录 | 倾向使用本机/data/dataagent；最终确认账号和部署方式后创建 |

若宿主确为glibc2.17，建议优先评估在66现有Docker中提供较新的Linux用户空间，把Java/Python/Wren放在本应用专属运行环境内；这样保留宿主Java8和既有软件。完整应用容器方案比单独套一个Wren docker-exec壳更容易统一文件路径/stdio/进程生命周期，但也需要配套Docker客户端/宿主socket、宿主与容器一致的持久路径、运行权限、端口和取消/重启/文件下载验收，不能直接当成已有支持。另一条路径是具备新系统的独立VM/主机，继续原tar/bin部署。

用户现已确认同容器运行方式；本机已准备并实测运行镜像/脚本，66仍待执行新的Java运行环境与分析沙箱步骤。用户原定本地Maven打包上传tar的交付流程保留，不代打业务包；正式操作见上方容器环境指引。此前基础检查不代替完整应用验收。

## 如继续宿主机JAR方案，Java17的独立安装参考

先取得兼容旧glibc的Linux x64 JDK17归档，按发行方校验SHA256。可参考[Temurin归档安装](https://adoptium.net/installation/archives/)及其[构建基线](https://github.com/adoptium/temurin-build/blob/master/FAQ.md)。供应商/精确版本、下载包和服务器运行结果尚未确定；不要把任意Linux JDK17包都当作兼容。

将已验证的归档命名为jdk17-linux-x64.tar.gz，上传到/data/dataagent/delivery；该目录和runtime/jdk17须先由有权限的运维账号为本项目准备，确认目标为空后执行：

```bash
sha256sum /data/dataagent/delivery/jdk17-linux-x64.tar.gz
sudo tar -xzf /data/dataagent/delivery/jdk17-linux-x64.tar.gz \
  -C /data/dataagent/runtime/jdk17 --strip-components=1 --no-same-owner
/data/dataagent/runtime/jdk17/bin/java -version
export DATAAGENT_JAVA=/data/dataagent/runtime/jdk17/bin/java
```

不运行alternatives，不改系统PATH/JAVA_HOME，不卸载Java8。这里的export只指向DataAgent后续启动进程；不会改变其他已经运行的服务。先明确运行方式，再决定是否需要这一项宿主安装。

本轮完成截图盘点、分发文件核对、本机镜像拉取/离线导出/运行及操作文档调整，没有连接66安装软件、改权限、重启服务或升级系统。当前可靠检查点是DaoCloud拉取及66基础Python运行已通过；固定Wren依赖镜像构建和原生模块加载也已由66截图确认通过；ModelScope直下校验和断网向量计算已获用户回传通过；分析沙箱与Java运行镜像的环境安装检查也已获66截图确认通过；下一个检查点为正式目录/端口，生产两库信息仍待同事提供。
