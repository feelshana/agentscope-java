# 平台权限、上传与问答容量运维补充

更新：2026-10-15。适用于当前 standalone 分支；[ADR 0077](../adr/0077-shared-knowledge-official-wren-integration.md)、[ADR 0078](../adr/0078-platform-capacity-followup-integration.md) 和 [spec 067](../specs/done/067-platform-followup-integration.md) 为当前集成依据。来源文档见 [快照索引](../archive/imported/multiAgent-Mdl-lyf/README.md)，来源验收不能代替当前环境验收。

## 权限与身份

知识库使用者 QUERY 可问已发布模型；管理员 MANAGE 可建模、发布、编辑和管理成员，创建者及全局管理员天然管理。每轮选库按当前请求用户的可见范围校验，排队后再次复核。会话、附件、反馈及沙箱属于请求用户；共享点赞样例归库保存。401 通过 SessionGate 和统一 apiFetch 返回登录页，403 不清除登录。

## 上传

浏览器从 `/api/datasets/upload-policy` 读取服务端策略，有限调度单文件请求。服务端限制单文件、整批、数量、解析行/列/单元格和同时在途文件，超出全局在途名额返回 429，同库同名在途任务返回 409。上传内容先暂存到磁盘；解析写入限制未完成批次，导入失败清理物理表、元数据和暂存文件。`/api/datasets/upload-status?groupId=...` 只返回当前上传者的任务，仍需库管理权限。

默认策略：单文件 50MB、整批选择 100MB、10 文件、100000 行、100 列、2000000 单元格、全局 8 在途文件（可配置 1—32）。浏览器队列不是服务端持久队列；进程重启不恢复任务状态。批次允许部分成功，`IMPORTED_MODEL_PENDING` 表示数据已入库而基础模型发布待处理，不能当作导入失败删除成功数据；断连后先刷新状态，避免重复提交。

## 问答与资源预算

ChatController 的 stream/send 问数默认全局 16 执行、每用户 2 执行、全局 16 等待、每用户 1 等待；排队超时 30 秒，执行超时 600 秒。同会话不得重复准入。等待不创建沙箱或运行模型；SSE `capacity` 的 queued/running 对应前端提示。send 超限返回 429，stream 用 error 帧结束。建模不计入此预算，外部通道也不经过 ChatController 的问答准入；框架仍有会话门锁。

每用户沙箱上限默认 16，满额按 LRU 驱逐无活动 lease 的容器；执行中不会被容量驱逐。Wren MCP 默认最多 16 实例，每库公平门锁最多等待 30 秒，发现和查询共用同一门锁，独立库可并行。关闭失败保留实例名额并重试清理。该限制不覆盖官方 CLI 的普通短进程，不是机器上所有 Wren 进程总预算。

问答取消传播到 Python 执行，Docker exec 独立进程组停止目标及子进程，`timeout --foreground` 防止 Python 逃离被管理的组。停止未完成时 ChatCapacity 保留操作租约；需沙箱镜像提供 `setsid`（util-linux）。控制记录放在用户隔离的宿主路由，聊天启动不会为读取消息总线记录而预热所有沙箱。

这些是单 JVM 的资源约束。16 是默认配置，不是已验收的吞吐量；增加副本会增加总准入量，Redis 会话锁不提供跨副本容量预算。上线按真实模型延时、内存和数据源连接预算压测后调整。

## 构建与发布

本次包含同仓库 `agentscope-harness` 与 `agentscope-extensions-model-openai` 改动，standalone 从本地 Maven 仓库读取依赖。先在仓库根目录执行：

```powershell
mvn -pl agentscope-harness,agentscope-extensions/agentscope-extensions-model/agentscope-extensions-model-openai -am install -DskipTests
```

随后在 standalone 根目录执行 `mvn test`、`mvn package -DskipTests`；前端目录执行 `npm run build`。当前 pom 保留 Spring Boot exec JAR，并按 ADR0080 额外生成 dataagent-<version>.tar.gz，采用 bin/conf/lib 与版本外 data/ 布局。默认 Maven 仍构建当前前端；本地 dev/prod 配置不进入 JAR/发布包，部署时由无凭据样例创建目标配置。Linux 脚本检查 Wren CLI/Python 依赖并等待应用健康后才返回成功；停止只校验进程身份，不依赖 Wren。详见[单机发布](single-host-release.md)。若跳过 Maven 内的 npm 步骤，必须先用本分支前端完成构建，禁止复制来源的 hash JS 产物。沙箱用本分支 `docker/sandbox.Dockerfile` 重建镜像，再按既有环境配置部署新包。重启会清空内存准入队列，应先停止接收新任务并等待正在执行的任务退出。

部署前备份平台元数据库、MDL 工作区与 published、历史及附件；QUERY/MANAGE 授权表也属于平台库。回滚应用时需配套框架 JAR、前端与镜像，保留持久目录。本文不包含凭据，使用现有安全配置注入。

## 验收与排障

1. 使用者能问已发布库、不能建模/改成员；撤权后排队工具不得执行，其他用户会话和附件不可见。
2. 上传并行、小文件/大文件超限、同名重复、部分失败、断连后状态及失败回滚。
3. 多用户占满执行名额时显示排队；取消排队不会启动工作；同用户额度不能挤占其他用户的名额。
4. 问答停止后核对实际 Python 子进程退出；Wren/沙箱满额只驱逐空闲资源，确认关闭失败重试。
5. 401 先重新登录；429 查看上传或问答预算；Wren 503 查看每库等待和实例预算。不要仅通过调高上限掩盖泄漏或数据源连接问题。

本轮测试结果与条件跳过记录在 [CURRENT_TASK](../ai/CURRENT_TASK.md)，日志位于 target/followup-*；来源云 MySQL/Doris 和发布演练结果保留历史身份。真实 LLM、多用户容量和云端发布恢复验收须在目标环境执行。
