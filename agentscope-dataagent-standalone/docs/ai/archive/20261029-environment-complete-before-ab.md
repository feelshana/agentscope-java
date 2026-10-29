# 当前任务：66环境部署阶段完成（2026-10-29）

## 授权与基线
- 用户已确认Java17/Python/Wren同应用容器、独立分析沙箱，复用66现有Docker；仍由用户本人Maven打包上传业务tar。助手准备和本机验证，用户在66执行；不升级宿主glibc/Java8、不重启Docker或触碰其他业务。
- 分支codex/integrate-boundary-lyf-v2，HEAD b82bb7f4，未切分支/提交/推送。开始前已有数据库/BI/安装文档修改，本轮保留；先前完整进度见[归档](20261029-before-container-runtime.md)。个人LOCAL_HANDOFF继续本地忽略。
- 原业务代码、前端、应用配置项未改；新增部署脚本、镜像配方、assembly白名单材料和文档，不代打业务包。

## 66已经实测（用户回传）
- Anolis7.9/x86_64，glibc2.17，内核3.10.0-1160.an7；Docker客户端/服务端26.1.4，sudo可访问，多套既有容器运行。宿主PATH Java8，无python3/Wren。
- DaoCloud下载官方Python3.12-slim-bookworm成功；Python3.12.16、容器glibc2.36、SSL运行通过；ECR五次卡层属于已放弃路径。
- dataagent/wren-deps-check:20261028构建8/8通过，87.2秒；固定Wren/原生依赖/CLI通过。
- HF-Mirror实际重定向境外CAS下载超时，改ModelScope同文件固定修订/SHA256；用户回传断网向量(2,384)通过，缓存~/dataagent-env/hf-cache-modelscope。没有提供耗时，不推算。
- 2026-10-29用户截图确认正式Java运行/分析镜像安装检查全部通过：OpenJDK17.0.21、Wren0.15、pip检查、Docker26.1.4客户端/服务端、断网向量、兄弟沙箱路径，以及真实限制下计算/CSV/SciPy/中文字体/PNG/setsid。业务JAR/真实SQL/BI仍未验。

## 本轮已准备与本机实测
- [spec098](../specs/098-single-host-container-runtime.md)与[ADR0117](../adr/0117-single-host-container-runtime.md)记录已接受方案；spec继续进行中，等66及业务部署验收。
- docker/runtime.Dockerfile：复用Wren基础，安装Java17运行环境和固定Docker26.1.4客户端；只带客户端，不启动嵌套daemon。
- docker/runtime-entrypoint.sh复用发布包check-wren.py，exec前台Java；docker/dataagent-container.sh按操作者UID/GID和socket附加组运行，项目路径同宿主绝对路径，挂载模型缓存/持久数据，标签保护stop/replace。
- docker/sandbox-anolis79.Dockerfile准备分析库、中文SC字体及setsid；install-66-environment.sh构建两镜像并运行check-environment.sh，写版本/imageId报告；无凭据模板，assembly仅附8个白名单文本至ops/container。
- 本机实际构建通过；OpenJDK17.0.21/Python3.12.16/Wren0.15.0/客户端26.1.4访问daemon29.1.3，内核WSL2 6.18，不能替代66的3.10验收。
- 原check-environment.sh实际通过：断网ONNX向量、兄弟容器shared路径、真实只读根/tmpfs/1CPU/1GiB下分组300/150、CSV、SciPy、中文字体覆盖与PNG、setsid；本机完整ONNX权重SHA256与固定来源一致。
- 真实Docker集成11项通过（36.955秒），含主进程TERM、Java调用真实Wren、非root/socket组、误配置拒绝、标签保护、控制脚本start/拒绝热替换/stop/切目录replace/持久文件保留。使用小型Java fixture，未打新业务包。详情见[报告](../test-reports/20261029-container-runtime-environment.md)。

- 最终检查：Spotless通过、DocsConsistencyTest5通过；Python默认17通过/11默认跳过，Docker11已独立实际通过。新shell/复制文本语法、assembly8文件白名单及diff检查通过；按用户要求不运行package，未重跑无变更的业务Java/前端全量。

## 本次任务完成与后续待安排
- 安装复制脚本已在66完成并回传成功，不重复执行；用户明确尚未拿到生产两库信息。用户已回传prepare成功，/data/dataagent为当前账号所有、组operation、0750，data目录已建立；ss查询18080只有表头、未发现当前监听。环境和目录准备通过，不重复安装或prepare，不尝试业务启动。
- 当前操作以[66容器指引](../guides/66-container-runtime.md)为准；[Anolis历史与前置记录](../guides/66-anolis79-next-steps.md)已标记正式方案，不重做旧HF下载或宿主JDK安装。
- 用户最新明确今天只完成66环境部署，本次已完成；不推进数据库、打包上传或业务启动，不要求用户今天继续执行命令。后续拿到两库信息并另行安排发布后，再做初始化/私有配置/JAR/健康及业务验收；默认8CPU/16GiB不是15并发承诺。
- 两库/真实初始化/1484用户SQL、公司模型/搜索、真实Wren索引/SQL、上传建模问答附件、停止恢复、Nginx子路径/SSE和BI、混合负载与长稳仍待现场验收；不会宣布整体上线完成。
- 既有IDEA/BI等服务保留。只停止/移除本轮带随机测试标签或匹配专属fixture路径的自建容器，本机隔离fixture文件/日志保留；未清理其他项目、未部署生产或推送。

- 已提供[同事沟通说明](../guides/66-deployment-explanation.md)：实际选择第三种，旧宿主原生库兼容问题是原因；性能通常预期接近但无同机原生对照或完整问答/并发证据。常驻应用容器尚未启动，镜像和分项检查已完成；普通应用迭代复用环境，依赖/schema/配置变化另处理。

- 已补充[脚本用途与启动链路](../guides/66-container-runtime.md)：宿主controller创建应用容器→镜像entrypoint→容器Java执行宿主挂载JAR→同容器Wren。源码docker的8个文本会附ops/container；66目前已有home安装目录副本。原生bin不是本次66入口，今天只解释，不启动或打包。
