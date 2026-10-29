# 项目长期背景

> 只记录跨任务稳定、且不适合写进 AGENTS.md / ARCHITECTURE_zh.md 的背景与本机环境事实。规则看 [AGENTS.md](../../AGENTS.md)，实现看 [ARCHITECTURE_zh.md](../../ARCHITECTURE_zh.md)，决策演变看 [ADR 索引](../adr/README.md)。2026-10-21 之前的逐条背景记录见 [archive/PROJECT_CONTEXT-until-20261021.md](archive/PROJECT_CONTEXT-until-20261021.md)。

## 产品主流程

上传或关联数据 → 采样（字段说明、表角色；外部数据源事实表确认时间窗口）→ 自动基础建模（官方 enrich-context Auto-pilot）→ 可选：用户填写最多 5 个待验证问题，逐题确认 SQL → 页面显式发布 → 在已发布模型上问数（官方 MCP + Python/ECharts）→ 点赞保存业务问答。

问答库只有两个来源：建模时用户逐题确认的问题（`source:user-confirmed`）和问数点赞的回答。

## 本机环境事实（2026-10-20 观察，其他机器不一定相同）

- 服务实际使用的 Wren：`C:\Users\roylin\AppData\Roaming\Python\Python313`（wrenai 0.15.0）；另有测试环境 `D:\workspace\wren-probe\.venv`。
- 记忆后端：LanceDB + `paraphrase-multilingual-MiniLM-L12-v2`；`wren` CLI 冷启动约 4 秒、热启动约 1.2 秒。
- 运行时 MDL 根目录：`C:\Users\roylin\.agentscope\dataagent\mdl\<groupId>\`（`workspace/` 草稿、`published/` 发布快照、`runtime/query-project/` 问数运行工程）。
- 平台与数据集 MySQL 在本机；MySQL 连接池空闲连接为正常现象。
- Docker Desktop 曾因 Mihomo TUN 模式出现后台到 VM `192.168.65.7:2375` 的 `no route to host`，导致 `run_python` 沙箱不可用；需在 Mihomo 排除 `192.168.65.0/24` 或关闭 TUN 后重启 Docker Desktop。

## 日志与排查入口

| 文件 | 用途 |
|---|---|
| `logs/LLM-modeling.log` | 建模 Agent 完整对话转录 |
| `logs/LLM.log` | 问数 Agent 完整对话转录 |
| `logs/execution-timing.log` | 分段耗时（工具、索引、Wren 队列、`wren_cli.*`、`sql_window_gate`） |
| `logs/sql-window-gate.log` | SQL 时间门禁的实际判断、跳过、缓存与同轮阻止 |

LLM 转录没有时间戳，需要单独分析一次运行时，先停服务把旧日志移到 `logs/archive-<日期>/`，再重启。

## 验证方式

自动化：`mvn spotless:check`、`mvn test`、`mvn package -DskipTests`、`cd frontend && npm run build`。真实 LLM、Docker、Wren 相关测试为条件启用。Agent 的实际行为只能通过重启加载新包后的日志确认，提示词或单测断言不能代替。


## 当前工程补充（2026-10-26）

单知识库问数；零库为普通对话。管理员手动激活页面模型配置，新轮次/独立任务使用当前服务，在途与同轮辅助调用固定快照。Agent上下文使用框架MySQL存储，H2使用持久JSON；数据库、工作区、附件和独立模型加密主密钥须配套保留。BI正式环境禁密码登录；私有图表归操作者。完整首次DDL与升级条件见[迁移指引](../migrations/README.md)，当前整合边界见[ADR0114](../adr/0114-native-query-and-engineering-integration.md)。上述本机 roylin 路径是原观察机的历史记录，不代表所有开发者环境。

2026-10-28：BI客户端账号、人员目录、只读详情与全员库改造进行中，正式边界见ADR0116/spec096及CURRENT_TASK；本段不代表验证或部署完成。

2026-10-28补记：上述改造已完成本地代码/验收，未提交或部署；当前边界见ADR0116与本轮报告。客户端账号模式需显式启用，公共库属于全体已登录用户QUERY，私人资源边界不变。

2026-10-28后续状态：本批已提交b82bb7f4，真实BI资料曾按授权导入本地平台库；不是生产已导入。固定链接与无脚本iframe外壳已在独立真实后端/H2及同源代理实测通过，详见[专项报告](../test-reports/20261028-bi-fixed-link-rehearsal.md)。生产BI/66/代理仍待现场验收，当前待提交内容仅为专项文档补充。

2026-10-28生产66截图事实：AnolisOS7.9/x86_64，32CPU/内存available约154GiB，/data本机XFS磁盘充足；当前PATH Java8、无python3命令，用户确认无Wren。Docker客户端26.1.4，普通账号socket权限拒绝，服务端/内核/glibc尚未读到。先按[Anolis下一步](../guides/66-anolis79-next-steps.md)补信息，旧Ubuntu/Rocky安装单不适用于此主机；尚未实施或确认新的容器部署方式。

同日后续截图更新：glibc2.17、内核3.10.0-1160.an7.x86_64已实测，sudo docker可访问26.1.4服务端，已有多个长期业务容器。sudo规则包含明确禁止项。下一步建议无挂载/无端口的临时Python bookworm容器验证，不重装Docker或升级系统库；容器部署方案仍待验证与确认。

2026-10-28最终基础检查更新：66已通过DaoCloud拉取与脚本运行Python3.12.16/glibc2.36/SSL，沿用宿主3.10内核；此前ECR多次卡层。宿主环境未升级，正式容器部署仍未选定。Wren与模型的兼容性不能由基础检查推断。

2026-10-28 Wren检查进展：66已完成固定入口Wren依赖镜像构建与原生导入/CLI检查；87.2秒为安装构建耗时，不是用户问答耗时。模型文件及向量计算未验，详见Anolis安装指引。

2026-10-28向量模型检查尚未通过：HF-Mirror实际下载跳转境外CAS超时；ModelScope同名仓库四文件与HF文件元数据一致，已准备直下校验与离线缓存替代，66结果待回传。

2026-10-28后续用户回传ModelScope下载后的断网向量计算(2,384)通过，替代上方模型未通过状态；缓存为~/dataagent-env/hf-cache-modelscope。尚未完成分析沙箱及Java/应用部署，不能直接以分项检查宣布生产可用。

2026-10-29：用户已选择容器部署，正式指南为66-container-runtime.md；runtime镜像/前台入口/宿主controller及分析镜像已实现并本机实测，源头见ADR0117/spec098。运行镜像不含业务JAR，业务tar用户构建上传，宿主持久根与容器同绝对路径。66此前向量检查已通过，但新Java运行与分析环境待用户执行复制脚本；本机WSL2/Docker29不能代替生产旧内核验收。

2026-10-29用户截图补验：66正式运行及分析环境安装检查全部通过，客户端/daemon均26.1.4、Java17.0.21/Wren0.15/离线向量/兄弟目录/受限分析及中文PNG通过；取代上一段66尚未安装的历史状态。用户尚未拿到生产两库信息，业务服务未启动，下一步准备目录/端口并等待两库。

2026-10-29继续回传：66/data/dataagent持久根已创建、当前账号所有/operation组/0750，18080暂未监听；数据库信息仍未取得。下一阶段是两库初始化及私有配置、用户自行打包上传和业务启动，基础环境无需重复安装。

2026-10-29本次环境任务已完成，用户明确暂不处理数据库、打包上传和启动。环境镜像可用不等于常驻业务容器已运行；第三种同运行环境保留Java/Wren本地子进程方式，性能与效果的正式回归留待后续发布，不借本地历史测试替代。

2026-10-29当前主交付已改为GitHub源码构建（spec099/ADR0118）：66现有运行环境继续复用，构建容器内编译框架和应用、封装不可变应用镜像；生产conf/data外置。旧tar方式保留备选，不再要求用户每次本地打tar上传。构建阶段本地验证通过，66 builder/实际构建、Compose启停/回退与生产DB/入口验收仍待；本次仅本地提交，由用户本人push。
