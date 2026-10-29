# 10.194.2.66：Java、Python、Docker与Wren安装命令

当前66主交付方式已选择GitHub源码构建，后续操作以[源码构建指引](66-source-build.md)为准。本文保留运行环境准备或外置tar备选说明，不要求在66重复安装或重复部署。

**2026-10-28截图已确认真实66是Anolis OS7.9/x86_64，已有Java8及Docker26.1.4客户端，当前账号无Docker socket权限。请先执行[Anolis专用下一步](66-anolis79-next-steps.md)，不要执行下文A/B或公共安装步骤。以下保留为Ubuntu/Rocky参考，不是已适配66的最终命令。**

用户最新明确：本次Python检查的自动移除命令放脚本执行，服务自身清理容器没有问题，见Anolis指引的具体脚本。下文其他沙箱检查示例仍保留容器，后续维护按运维流程，不将本次说明扩大到任意删除；无需改应用回收代码。

## 适用范围

本次部署目标为10.194.2.66。以下按尚未安装相关软件、可通过软件源联网安装的场景准备，未在目标服务器执行。内存/磁盘充足不能决定安装包类型；先执行两条命令，选择匹配章节：

```bash
cat /etc/os-release
uname -m
```

| 实际输出 | 采用章节 |
| --- | --- |
| Ubuntu 24.04，x86_64 | A，然后公共步骤C～F |
| Rocky Linux 9，x86_64 | B，然后公共步骤C～F |
| 其他版本/发行版，或aarch64/其他架构 | 本文不是该环境的可执行安装单，需按实际软件源和原生依赖另行适配 |

Ubuntu使用apt，Rocky使用dnf，包名和依赖不同。x86_64对应常用linux/amd64镜像，aarch64对应linux/arm64；手动上传的JDK、Python原生扩展和沙箱镜像必须匹配CPU架构。不能因为都是Linux就混用。Ubuntu22.04、CentOS7、统信/麒麟不套用本页两套命令。

所有sudo命令由有安装权限的账号执行；若已是root且没有sudo，可去掉sudo前缀。每步出现错误即先处理，不继续后面的步骤；已有软件/目录/容器环境时先盘点，不卸载、切换系统默认解释器、覆盖配置或清空Docker。这里不操作生产数据库，不启动DataAgent应用。

安装材料中含当前项目requirements、预检脚本和Dockerfile；不是Java发布包。把材料目录的**内容**上传到 `/opt/dataagent/delivery/environment/`，保留 `bin/`、`docker/` 子目录。可先完成A/B和C，再上传材料，之后执行D～F。服务器无法访问外部软件源时，需准备匹配系统/架构的deb/rpm、Python wheel及模型缓存和镜像归档，联网命令不适用于离线情况。

## A. Ubuntu24.04 x86_64

仅此环境执行A，不执行B。Java17，Python3.12及venv；MySQL客户端开发头文件用于编译Wren的mysqlclient，不是安装MySQL数据库服务。包来源依据：[Ubuntu Python包](https://packages.ubuntu.com/noble-updates/python3.12-venv)、[Ubuntu Java包](https://packages.ubuntu.com/noble/openjdk-17-jdk-headless)、[mysqlclient说明](https://github.com/PyMySQL/mysqlclient/blob/main/README.md)。

```bash
sudo apt-get update
sudo apt-get install -y software-properties-common ca-certificates curl
sudo add-apt-repository -y universe
sudo apt-get update
sudo apt-get install -y openjdk-17-jdk-headless \
  python3.12 python3.12-venv python3.12-dev \
  default-libmysqlclient-dev build-essential pkg-config \
  ca-certificates curl util-linux tar gzip

java -version
python3.12 --version
```

Docker按[官方Ubuntu安装方式](https://docs.docker.com/engine/install/ubuntu/)添加stable仓库。以下写入新的Docker软件源，适用于尚无该源的环境；不提供卸载旧容器组件命令。

```bash
sudo install -m 0755 -d /etc/apt/keyrings
sudo curl -fsSL https://download.docker.com/linux/ubuntu/gpg -o /etc/apt/keyrings/docker.asc
sudo chmod a+r /etc/apt/keyrings/docker.asc
sudo tee /etc/apt/sources.list.d/docker.sources >/dev/null <<EOF
Types: deb
URIs: https://download.docker.com/linux/ubuntu
Suites: noble
Components: stable
Architectures: amd64
Signed-By: /etc/apt/keyrings/docker.asc
EOF
sudo apt-get update
sudo apt-get install -y docker-ce docker-ce-cli containerd.io docker-buildx-plugin
sudo systemctl enable --now docker
sudo docker version
```

以上从stable仓库安装当时可用版本，不代表已固定或验收具体Docker版本；保留实际版本输出用于部署记录。项目当前不需要Docker Compose组件。

## B. Rocky Linux9 x86_64

仅此环境执行B，不执行A。安装独立Python3.11，不修改系统python3链接。CRB的启用方式见[Rocky仓库说明](https://github.com/rocky-linux/wiki.rockylinux.org/blob/main/docs/rocky/repo.md)；Python版本方式参考[Red Hat说明](https://developers.redhat.com/blog/install-python3-rhel)。Rocky的软件源必须提供以下包；不要把本节的crb名称直接用于RHEL订阅仓库。

```bash
sudo dnf install -y dnf-plugins-core
sudo dnf config-manager --set-enabled crb
sudo dnf install -y java-17-openjdk-devel \
  python3.11 python3.11-pip python3.11-devel \
  gcc gcc-c++ make pkgconf-pkg-config mariadb-connector-c-devel \
  ca-certificates curl util-linux shadow-utils tar gzip

java -version
python3.11 --version
```

Docker方式依据[Rocky官方安装文档](https://docs.rockylinux.org/gemstones/containers/docker/)，使用其当前建议的Docker RHEL仓库。仅适用于没有Docker/Podman/containerd等既有冲突环境；本页不移除它们。

```bash
sudo dnf config-manager --add-repo https://download.docker.com/linux/rhel/docker-ce.repo
sudo dnf install -y docker-ce docker-ce-cli containerd.io docker-buildx-plugin
sudo systemctl enable --now docker
sudo docker version
```

## C. 应用账号与持久目录

此后两种系统共用。采用 `/opt/dataagent`，已有同名账号/目录时先确认归属，不覆盖现有版本或调整其他业务目录。以下按全新目录准备，不做递归chown。

```bash
if ! id dataagent >/dev/null 2>&1; then
  sudo useradd --system --user-group --home-dir /opt/dataagent --shell /usr/sbin/nologin dataagent
fi
sudo install -d -o dataagent -g dataagent -m 0750 \
  /opt/dataagent /opt/dataagent/delivery /opt/dataagent/delivery/environment \
  /opt/dataagent/data /opt/dataagent/data/home /opt/dataagent/data/state \
  /opt/dataagent/data/workspace /opt/dataagent/data/artifacts \
  /opt/dataagent/data/mdl /opt/dataagent/data/run
sudo usermod -aG docker dataagent
sudo -u dataagent -H docker version
```

`artifacts`等目录用于问答文件，`home`保留配置和模型缓存，`workspace/mdl/state`保存工作状态；不随Java版本目录替换。docker组授予较高宿主权限，作用见[Docker官方说明](https://docs.docker.com/engine/install/linux-postinstall/)，这里仅给应用账号，不修改socket权限。

上传安装材料后，核对这三项存在且应用账号可读：

```bash
ls -l /opt/dataagent/delivery/environment/requirements-wren.txt \
  /opt/dataagent/delivery/environment/bin/check-wren.py \
  /opt/dataagent/delivery/environment/docker/sandbox.Dockerfile
```

## D. 独立Wren环境

只选择与你系统对应的一条创建命令；已有 `/opt/dataagent/wren-venv` 时先核对，不在原环境上反复创建。

Ubuntu24.04执行：

```bash
sudo -u dataagent python3.12 -m venv /opt/dataagent/wren-venv
```

Rocky9执行：

```bash
sudo -u dataagent python3.11 -m venv /opt/dataagent/wren-venv
```

后续共用：

```bash
sudo -u dataagent -H /opt/dataagent/wren-venv/bin/python -m pip install --upgrade pip
sudo -u dataagent -H /opt/dataagent/wren-venv/bin/python -m pip install \
  -r /opt/dataagent/delivery/environment/requirements-wren.txt
sudo -u dataagent -H /opt/dataagent/wren-venv/bin/python -m pip check
sudo -u dataagent -H /opt/dataagent/wren-venv/bin/python \
  /opt/dataagent/delivery/environment/bin/check-wren.py dependencies \
  /opt/dataagent/wren-venv/bin/wren
```

当前固定入口依赖：wrenai[mcp,mysql,memory-onnx]0.15.0、wren-core-py0.8.0、mcp1.30.0，以本次材料requirements内容为准。MCP/MySQL/ONNX/LanceDB依赖随pip安装；LanceDB为本地嵌入式存储，不另装Chroma或一整套WrenAI网站。应用负责调用Wren/MCP进程，不在这里另启动一个常驻HTTP服务。Python头文件缺失、pkg-config找不到mysqlclient等错误须先修复系统依赖，不为过门禁更换固定Wren版本。

## E. 向量模型缓存：联网准备一次，再离线验证

使用与启动脚本一致的运行账号和HOME。下面的get_embedding_function来自当前Wren0.15.0代码；首次调用可能联网下载向量模型。它不是公司提供的聊天大模型。

```bash
sudo -u dataagent env HOME=/opt/dataagent/data/home \
  HF_HOME=/opt/dataagent/data/home/.cache/huggingface \
  WREN_EMBEDDING_BACKEND=onnx \
  /opt/dataagent/wren-venv/bin/python -c \
  'from wren.memory.embeddings import get_embedding_function; v=get_embedding_function().compute_source_embeddings(["向量模型准备检查"]); assert len(v)==1 and len(v[0])==384; print("向量模型加载通过")'
```

再执行以下命令，要求不依赖联网也能加载：

```bash
sudo -u dataagent env HOME=/opt/dataagent/data/home \
  HF_HOME=/opt/dataagent/data/home/.cache/huggingface \
  HF_HUB_OFFLINE=1 TRANSFORMERS_OFFLINE=1 WREN_EMBEDDING_BACKEND=onnx \
  /opt/dataagent/wren-venv/bin/python -c \
  'from wren.memory.embeddings import get_embedding_function; v=get_embedding_function().compute_source_embeddings(["离线向量模型检查"]); assert len(v)==1 and len(v[0])==384; print("离线向量模型通过")'
```

无法下载时采用已准备、版本匹配的完整模型缓存上传，保持相同HF目录结构和应用账号可读，再执行离线检查；不能仅创建一个空缓存文件夹。这里不自动改用第三方模型镜像。默认模型为sentence-transformers/paraphrase-multilingual-MiniLM-L12-v2；加载检查通过仍需在发布验收中测试真实索引、召回。

## F. Python分析沙箱镜像

下列两种方式二选一。镜像不包含在Maven tar.gz中。已有已验收镜像时优先采用同CPU架构的镜像归档，不重新构建覆盖。

方式一：收到归档并上传为以下路径后导入：

```bash
sudo docker load -i /opt/dataagent/delivery/dataagent-sandbox.tar.gz
```

方式二：66可访问镜像和Python包源，并且尚无该镜像时，按本次材料的项目Dockerfile构建：

用户补充删除操作审查后，此方式在66暂停，待明确允许的构建操作再使用。Dockerfile内含镜像构建层的包缓存清理，不能把“不含人工删除容器参数”当成整个构建过程没有删除行为；优先在允许的构建环境准备已验收镜像供部署。

```bash
sudo docker build --platform linux/amd64 \
  -t agentscope/dataagent-sandbox:latest \
  -f /opt/dataagent/delivery/environment/docker/sandbox.Dockerfile \
  /opt/dataagent/delivery/environment
```

构建过程中下载python基础镜像、分析库和中文字体。使用linux/amd64的前提是本页限定的x86_64主机；不在ARM主机上强行使用这条命令。随后两种方式都执行：

```bash
sudo docker image inspect agentscope/dataagent-sandbox:latest \
  --format '{{.Id}} {{.Os}}/{{.Architecture}}'
sudo -u dataagent -H docker run --name dataagent-sandbox-check --network none agentscope/dataagent-sandbox:latest \
  python -c 'import pandas,numpy,scipy,matplotlib,shutil; assert shutil.which("setsid"); print("沙箱分析依赖通过")'
```

后一个命令只创建一次检查容器，退出后保留容器，不停止或清理其他业务容器；同名已存在时先查看记录，不执行删除。保留实际imageId和Docker版本；latest只是名字，不能作为版本验收依据。

## 环境完成后

应用启动时设置的Wren路径为 `/opt/dataagent/wren-venv/bin/wren`，对应Python为 `/opt/dataagent/wren-venv/bin/python`。两者可分别通过DATAAGENT_WREN_EXECUTABLE和DATAAGENT_WREN_PYTHON指定；生产配置中的可执行路径与之保持一致。按本页目录，启动脚本默认HOME为 `/opt/dataagent/data/home`，模型缓存默认位置与E相同；若另设HF_HOME，准备和启动必须一致。

```bash
export DATAAGENT_WREN_EXECUTABLE=/opt/dataagent/wren-venv/bin/wren
export DATAAGENT_WREN_PYTHON=/opt/dataagent/wren-venv/bin/python
```

export只对当前终端及其后续进程有效，实际发布时按最终启动方式持久配置，不依赖本次SSH会话一直保持。

66只运行发布包，不需要Maven、Node/npm。MySQL由同事在其他数据库服务器准备；BI同源Nginx在实际入口代理机器配置，不因为本页安装就另外给66新增Nginx或MySQL服务。数据库连接、模型接口、入口代理和防火墙连通性在拿到实际地址后验收；不关闭防火墙或SELinux来绕过安装问题。

这份文件是安装准备单。Java/软件包版本、完整安装、Docker运行、Wren导入/模型缓存及公司网络仍未在66验证，不等同已上线。下一步发布包仍由用户本人Maven构建上传，再按[发布手册](single-host-release.md)、[数据库准备](production-database-setup.md)和[当前BI接入](../integration/bi-account-production.md)操作。
