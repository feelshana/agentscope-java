# 66环境部署：完成范围、选择原因与性能说明

当前66主交付方式已选择GitHub源码构建，后续操作以[源码构建指引](66-source-build.md)为准。本文保留运行环境准备或外置tar备选说明，不要求在66重复安装或重复部署。

适用于2026-10-29与同事沟通。已接受的架构选择见[ADR0117](../adr/0117-single-host-container-runtime.md)，实际操作见[容器部署指引](66-container-runtime.md)，证据见[验收记录](../test-reports/20261029-container-runtime-environment.md)。

## 今天完成了什么

本次目标仅为66生产服务器的环境部署，已完成。实机截图确认Java17.0.21、Python/Wren依赖、离线ONNX向量计算、Docker客户端/daemon26.1.4、兄弟沙箱同路径共享，以及1CPU/1GiB/只读根/tmpfs/断网条件下的计算、CSV、SciPy、中文字体、PNG和setsid通过。/data/dataagent持久目录已建，18080当时未监听。

不把数据库、业务包、生产配置、业务启动或BI联调列入今天完成范围。当前准备好的是运行镜像与部署材料；正式常驻应用容器尚未启动，完整业务/性能验收尚未发生。临时检查容器已按脚本退出，镜像和模型缓存保留。

## 为什么选择第三种

最初按开发机思路准备宿主原生Java/Python/Wren。实际盘点才确认66为Anolis7.9/glibc2.17，且有多套长期运行的现有业务。本地Windows环境可用，不代表相同原生组件的Linux安装包兼容该旧系统。

难点主要是Python/Wren完整原生依赖组合，不是Java17本身必须进入Docker，也不是wren-core-py0.8本身要求glibc2.28。已核对的DuckDB1.5.0 x86_64预编译包标记manylinux2.26/2.28；ONNX/LanceDB的已核对版本也有更高系统库门槛。原生安装不是理论上绝无办法，但需要专门构建/维护兼容依赖并重新验收；本次不升级共享宿主glibc、不替换系统Python或Java8。

选择容器提供较新Linux用户空间，把Java17/Python/Wren放在同一运行环境，保留Java直接启动官方CLI/MCP子进程、共享文件路径/临时文件/环境变量的代码方式。若Java宿主、Wren容器分开，还要补跨环境的文件、stdio和取消/回收适配。独立分析沙箱仍复用宿主Docker，在本地问答流程中也已采用过Docker，并非此次才改变为沙箱执行。

对同事可以直接这样说明：

> 因为66的系统库比当前Wren部分原生依赖要求旧，我们保留宿主现有环境，用容器提供兼容运行环境。Java和Wren仍在同一环境内以本地子进程交互，问答、建模逻辑不变。代码包与数据留在66宿主目录，镜像只装运行依赖。现在环境分项已在66验证通过，业务速度与并发将在正式部署后验收。

## 性能、速度和效果

Linux容器仍使用66的CPU、内存和内核，不是远程执行，也不是给每轮问答启动完整虚拟机。正式应用容器常驻，问答不会每轮重新安装依赖或重建应用容器；Wren进程/模型的冷启动和缓存仍按现有逻辑运行。

| 关注点 | 预期与当前证据 |
| --- | --- |
| CPU计算 | 仍由同机CPU执行，预计不因隔离本身出现大幅下降，但未做同机原生对照，不给零损耗或固定百分比保证 |
| 磁盘/网络 | 有容器文件系统和网络配置差别；业务数据/MDL/日志/附件使用宿主bind mount。是否构成瓶颈需完整业务测量 |
| 资源预算 | 初始脚本的8CPU/16GiB是拟用上限，不是整机全部资源，也不是15并发承诺；给8核与原生可用32核的负载不能当成同条件对照 |
| 业务与模型效果 | 容器本身不更改提示词、规则、模型能力或Agent流程；版本/参数/数据相同才便于比较，LLM随机性及原生库版本差异仍需回归 |

今天证明的是依赖可加载、模型可实际计算、示例分析结果正确、文件/字体及Docker协作可用。没有跑真实业务完整问答、同机原生/容器A/B、10—15并发或长稳；不能把本地历史多轮测试的响应时间直接归给66，也不能现在承诺与本地每题同速。

数据库/模型接口网络、SQL、向量模型冷启动、资源竞争和配额都可能比容器隔离本身更影响速度。固定入口版本不等于所有传递依赖和本地完全一致；66安装材料目录已记录两镜像pip freeze与imageId，后续比较时核对版本并作为变量控制。

## 后续更新方式

### 镜像、容器与入口端口

docker ps列正在运行的容器，docker ps -a还列出已停止但保留的容器；docker image ls（docker images）列镜像。66普通账号需sudo docker。今日检查创建过临时容器，按脚本退出移除，因此不一定留在ps -a；两套环境镜像已经存在，可直接列出。正式应用容器在后续配置齐全并执行start后创建，默认名dataagent-runtime-66。

正式应用容器常驻；需要分析沙箱时，现有框架按用户/Agent创建或复用独立容器，其依赖来自提前构建的分析镜像，不是每个问题都强制新建。整个应用在66服务器上的容器内执行，CPU/内存/内核仍来自66；JAR、配置和持久数据在66磁盘，通过挂载读取。

当前脚本预设端口映射为10.194.2.66:18080到应用容器18080，并向Java传server.port=18080。后续Nginx上游使用http://10.194.2.66:18080，经Docker映射到应用，无需直接使用容器动态IP。18080只是已核对当时无监听的拟用端口；业务服务、Nginx到66连通性、防火墙及SSE/上传仍待发布阶段验收，现在无应用监听属于未启动状态。

```text
浏览器 /dify/dataagent/
  → 现有Nginx入口
  → 10.194.2.66:18080
  → Docker端口映射
  → 应用容器内18080的Java服务
```

这里只说明机制，不在本次环境任务中启动服务或修改Nginx。依据：[容器列表](https://docs.docker.com/reference/cli/docker/container/ls/)、[镜像列表](https://docs.docker.com/reference/cli/docker/image/ls/)、[端口发布](https://docs.docker.com/engine/network/port-publishing/)。

### 日常发布

JAR、外置配置、日志、数据在宿主目录，通过挂载使用，不打进运行镜像。普通业务/前端更新仍可本地打包上传，通过宿主脚本stop、替换JAR或切到新版本目录、replace、健康/业务检查完成；replace复用已安装运行镜像，通常不重建环境。容器停止/替换不删除宿主持久数据。

不能泛化为所有更新只换JAR：Java/Python/Wren版本变化需更新运行镜像；新增分析依赖需更新沙箱镜像；schema变化需执行相应数据库迁移；配置变化需更新外置文件，环境文件变化通过重新创建应用容器加载，单纯docker restart不会重读新的env-file内容。前端静态文件虽在JAR里，变更发布前缀仍须在打包时设置。

日常发布操作可以封装得与原生部署接近，环境维护、启停和启动控制台日志的命令仍有区别。原生bin/dataagent.sh不用于本次宿主直接启动；容器控制脚本是docker/dataagent-container.sh（服务器材料目录中已提供），启动控制台看Docker logs，分文件日志仍在宿主版本logs目录。

## 资料依据

- [Docker：容器共享宿主内核](https://docs.docker.com/get-started/docker-concepts/the-basics/what-is-a-container/)
- [Docker：bind mounts](https://docs.docker.com/engine/storage/bind-mounts/)
- [Docker：资源上限](https://docs.docker.com/engine/containers/resource_constraints/)
- [DuckDB1.5.0官方安装包](https://pypi.org/project/duckdb/1.5.0/#files)

以上区分代码事实、66实测、预期与未验证项，不据此宣称业务上线完成或性能已经达标。
