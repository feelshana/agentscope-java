# Docker未就绪与MEMORY.md报错：隔离故障模拟

提交状态更新（2026-10-19）：已纳入本轮主题提交，尚未部署。以下保留原验收日期、测试结果及当时的提交状态；当前上线安排见[总账](../go-live-gap-review-20261012.md)。

日期：2026-10-18；boundary-lyf / 270aff22加既有未提交修改。用户授权模拟定位，本轮只新增测试与文档，未改生产沙箱逻辑、未提交Git。

## 结论与代码依据

**已复现同事提供的MEMORY.md读取ERROR和统一中文异常，但无法仅凭该异常确定同事机器上的底层原因。** 八类故障均可产生相同结果；原始原因在前一条orphan cleanup failed WARN，新的中文异常没有保留cause。

BaseSandboxFilesystem#read构造MEMORY.md存在性判断与读取命令，SandboxBackedFilesystem#execute经LazySandbox#exec首次获取沙箱，最终调用UserSandboxRegistry#acquire/reserveSlot/ensureOrphanCleanupComplete。

UserSandboxRegistry#cleanupOrphanedContainers先执行docker ps -aq，按managed=true与部署instance-id标签过滤；无结果才完成检查，有结果则验证ID并执行rm。失败只记录WARN、应用继续启动，orphanCleanupComplete仍为false。下一次申请会重试，仍失败才抛统一中文异常。dockerCommand对单条命令等待30秒，查询和清理分别计时，不是整次请求最多30秒。

因此可将本次报错缩小到：**当前JVM的启动遗留容器检查尚未成功完成，本次重试仍失败**。已经完成启动检查后再发生的Docker执行故障，不走同一条readiness异常。此判断基于当前代码，其他版本需另行核对。

MEMORY.md不存在有正常file_not_found返回；sed的2147483647是无读取行数限制的实现，不是此条Docker异常的原因。沙箱容量用满也有另一条“分析任务繁忙”提示。

## 模拟矩阵

新增UserSandboxDockerReadinessTest，在ProcessBuilder启动边界注入结果，运行真实registry→LazySandbox→SandboxBackedFilesystem→MEMORY.md读取链路。测试使用唯一instance-id，不调用本机真实Docker进行查询或删除。

| 场景 | WARN关键特征 | 同样中文异常 | 方式 |
|---|---|---|---|
| 找不到docker命令 | Cannot run program / CreateProcess error=2 | 是 | 注入；另有真实子JVM空PATH验证 |
| Docker服务不可达 | Cannot connect to the Docker daemon | 是 | 非零进程输出注入 |
| context/地址不可达 | open ...pipe... / cannot find | 是 | 注入；另有真实CLI不存在命名管道验证 |
| 权限不足 | permission denied ... daemon socket | 是 | 注入，未改宿主权限 |
| 查询容器超时 | Docker cleanup timed out | 是 | waitFor超时分支注入 |
| 遗留容器清理失败 | Error response from daemon / removal ... in progress | 是 | 合法模拟ID+rm失败，无真实删除 |
| 清理容器超时 | Docker cleanup timed out | 是 | rm阶段超时分支注入 |
| 非合法容器ID | Unexpected docker container id | 是 | 异常输出注入，确认未执行rm |

额外控制与恢复验证：

- 服务不可达、rm失败后恢复正常输出：同一个registry及LazySandbox可再次读取成功，不重启后端；后续读取不重复启动清理检查。
- 检查成功但MEMORY.md不存在：只有file_not_found，无同样ERROR。
- 检查成功后exec连接失败：出现exec错误，不是readiness中文异常。
- 用真实ProcessBuilder验证不存在可执行文件、真实Java子进程非零stderr传递、真实Java子进程挂起触发30秒超时；超时进程由现有finally清理。
- 既有UserSandboxCapacityTest两项通过，未绕过容量或清理保护。

## 执行与证据

在standalone模块执行：

```powershell
mvn.cmd spotless:apply '-Dskip.npm=true'
mvn.cmd test '-Dskip.npm=true' '-Dtest=UserSandboxDockerReadinessTest,UserSandboxCapacityTest' '-Ddataagent.test.docker-readiness-timeout=true'
```

**17项通过，0失败/错误/跳过**：新增15项、既有容量2项。真实超时用例约30秒，并非只模拟时间；不开显式开关时该慢用例条件跳过。只新增测试与文档，本次未重跑全套业务回归、前端构建或容量验收。日志中的ERROR是刻意触发并断言的故障，不是测试失败。

另外独立子JVM使用真实生产类和CLI完成两组环境复现：

- PATH指向空测试目录：0.599秒，真实CreateProcess error=2。
- DOCKER_HOST指向唯一不存在的Windows命名管道：0.637秒，真实Docker CLI连接失败；仅清除测试子进程的context/TLS覆盖，未改变宿主环境。

两组均断言读失败且包含同一中文异常，没有启动HTTP/调用LLM或数据库，没有关闭Docker/删除现有容器。Java探针、完整日志和actual-process-results.json保存在仓库上层`.runtime/docker-readiness-20261018/`。JUnit日志在模块`target/docker-readiness-tests.log`，明细在`target/surefire-reports/TEST-io.agentscope.dataagent.web.workspace.UserSandboxDockerReadinessTest.xml`。

## 下一步定位现场

需要同事提供异常前完整的这一条WARN：

```text
[sandbox-registry] orphan cleanup failed; startup continues: ...
```

问题发生时，可在IDEA终端执行只读诊断：

```powershell
where.exe docker
docker version
docker info
docker context show
docker ps -aq --filter "label=dataagent.managed=true" --filter "label=dataagent.instance=standalone"
```

instance值需替换实际配置。IDEA终端与运行中的Java进程环境不一定一致；当前查询成功也不能追溯故障时刻。命名管道不存在可能是服务未启动或地址错误，单条连接失败本身未必能区分二者。已请求现场WARN，待提供后归类。

建议后续保留底层cause、显示沙箱未就绪状态、超时记录ps/rm阶段；这是待确认改进，未实施。不删除MEMORY.md、不跳过遗留容器检查、不全局清容器。
