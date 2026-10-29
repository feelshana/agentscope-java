#!/usr/bin/env bash
# Environment-only validation; no release, production DB or LLM credentials are used.
set -Eeuo pipefail
runtime="${DATAAGENT_RUNTIME_IMAGE:-dataagent/runtime:20261028}"
sandbox="${DATAAGENT_SANDBOX_IMAGE:-agentscope/dataagent-sandbox:20261028}"
cache="${DATAAGENT_MODEL_CACHE:-$HOME/dataagent-env/hf-cache-modelscope}"
[[ -d "$cache/hub" ]] || { echo '缺少已校验的模型缓存'; exit 1; }
cache="$(cd -- "$cache" && pwd -P)"
[[ -S /var/run/docker.sock ]] || { echo '缺少本机Docker Unix socket'; exit 1; }
probe="$HOME/dataagent-env/runtime-probe-shared"
mkdir -p "$probe"
printf '%s' 'dataagent-shared-path-check' > "$probe/probe.txt"
probe="$(cd -- "$probe" && pwd -P)"
d=(sudo docker)
[[ "${DATAAGENT_DOCKER_SUDO:-true}" == false ]] && d=(docker)
socket_group="$(stat -c %g /var/run/docker.sock)"
"${d[@]}" image inspect "$runtime" "$sandbox" >/dev/null
echo '检查Java/Wren/离线向量、宿主Docker与兄弟沙箱路径'
"${d[@]}" run --rm --init --pull never --network none --cpus 2 --memory 4g \
  --user "$(id -u):$(id -g)" --group-add "$socket_group" \
  --mount type=bind,source=/var/run/docker.sock,target=/var/run/docker.sock \
  --mount "type=bind,source=$cache,target=/cache/huggingface,readonly" \
  --mount "type=bind,source=$probe,target=$probe,readonly" \
  -e HOME=/tmp -e HF_HOME=/cache/huggingface -e HF_HUB_OFFLINE=1 \
  -e "PROBE_SHARED=$probe" -e "PROBE_SANDBOX=$sandbox" \
  --entrypoint bash -i "$runtime" -s <<'RUNTIME'
set -Eeuo pipefail
java -version
wren --version
python -m pip check
docker version --format '{{.Client.Version}} -> {{.Server.Version}} {{.Server.Os}}/{{.Server.Arch}}'
python -u - <<'PY'
import os, subprocess
import numpy as np
from wren.memory.embeddings import get_embedding_function, resolve_embedding_backend
assert resolve_embedding_backend() == 'onnx'
v=np.asarray(get_embedding_function().compute_source_embeddings(['运行环境离线检查','销售收入']),dtype=np.float32)
assert v.shape == (2,384) and np.isfinite(v).all()
assert np.allclose(np.linalg.norm(v,axis=1),1,atol=1e-3)
print('Java/Wren同环境断网向量计算通过',flush=True)
subprocess.run(['docker','run','--rm','--pull','never','--network','none',
 '--label','dataagent.environment-probe=true',
 '--mount','type=bind,source='+os.environ['PROBE_SHARED']+',target=/probe,readonly',
 os.environ['PROBE_SANDBOX'],'python','-c',
 'from pathlib import Path; assert Path("/probe/probe.txt").read_text()=="dataagent-shared-path-check"; print("兄弟沙箱同路径共享检查通过")'],check=True)
PY
RUNTIME

echo '按真实沙箱的只读根、tmpfs、1CPU/1GiB与断网限制检查分析能力'
"${d[@]}" run --rm --pull never --network none --cpus 1 --memory 1024m --memory-swap 1024m \
  --read-only --tmpfs /tmp:rw,nosuid,nodev,size=67108864 \
  --tmpfs /workspace:rw,nosuid,nodev,size=536870912 --pids-limit 256 \
  -e HOME=/tmp -e MPLCONFIGDIR=/tmp/matplotlib -i "$sandbox" python -u - <<'PY'
from pathlib import Path
import shutil,subprocess
import pandas as pd
import numpy as np
from scipy.stats import linregress
import matplotlib.pyplot as plt
from matplotlib import font_manager
from matplotlib.ft2font import FT2Font
assert shutil.which('setsid')
subprocess.run(['setsid','--wait','sh','-c','printf setsid_ok'],check=True)
df=pd.DataFrame({'区域':['华东','华东','华南'],'销售额':[100,200,150]})
totals=df.groupby('区域')['销售额'].sum()
assert totals.to_dict()=={'华东':300,'华南':150}
csv=Path('/workspace/probe.csv'); df.to_csv(csv,index=False,encoding='utf-8-sig')
pd.testing.assert_frame_equal(pd.read_csv(csv),df)
assert np.isclose(linregress([1,2,3],[2,4,6]).slope,2)
font=font_manager.findfont(font_manager.FontProperties(family='Noto Sans CJK SC'),fallback_to_default=False)
assert all(ord(c) in FT2Font(font).get_charmap() for c in '区域销售额华东华南')
plt.rcParams['font.family']='Noto Sans CJK SC'; plt.rcParams['axes.unicode_minus']=False
totals.plot.bar(title='区域销售额'); plt.savefig('/workspace/probe.png'); plt.close('all')
png=Path('/workspace/probe.png')
assert png.stat().st_size>1000 and png.read_bytes().startswith(b'\x89PNG\r\n\x1a\n')
print('\n分析沙箱检查通过：计算、CSV、SciPy、中文字体、PNG、setsid',flush=True)
PY
echo '环境部署检查通过；业务包、生产数据库、BI与并发验收另行执行'
