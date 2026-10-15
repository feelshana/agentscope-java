> Historical source record: data-agent-multiAgent-Mdl-lyf @ 6a75b6cf (2026-10-14). Branch-specific validation and release instructions below describe that source only. Current configuration, build and release: [platform-concurrency-and-release.md](platform-concurrency-and-release.md); integration: [ADR 0078](adr/0078-platform-capacity-followup-integration.md).

# DataAgent 简单发布说明

更新：2026-10-14，沿用用户现有 Supersonic 项目的 Maven Assembly 发布方式，补上传/问答容量及运行依赖说明。

## 平时只需要这些操作

1. 只改后端或配置：在 IDEA 的 Maven 面板执行本模块 `package`（也可以 `clean` 后 `package`）。
2. 改过前端：先在模块目录运行 `./frontend/build.ps1`，再执行 Maven `clean package`，清掉 target 中上次的哈希资源。
3. 上传 target 下的 tar.gz，解压到新版本目录。
4. 执行旧版本 `bin/dataagent.sh stop`，再执行新版本 `bin/dataagent.sh start`。

不需要 Python 打包，不需要 prepare/check，不需要复制 example 配置。

如果同时修改或合并了根工程的 agentscope-core / agentscope-harness / OpenAI 模型扩展，先在仓库根目录执行一次 `mvn -pl agentscope-harness,agentscope-extensions/agentscope-extensions-model/agentscope-extensions-model-openai -am install -Dmaven.test.skip=true`，把当前源码安装到本地 Maven 缓存，然后照常 package。只改本应用时不用重复执行。IDEA 直接运行可能引用 target/classes，而独立模块打包读取 Maven 缓存；缓存过旧会出现本地运行正常、发布包仍含旧框架代码的问题。


## 发布包与服务器目录

```text
target/dataagent-2.0.3-SNAPSHOT.tar.gz
  dataagent-2.0.3-SNAPSHOT/
    conf/application.yml
    conf/application-dev.yml
    conf/application-prod.yml
    bin/dataagent.sh
    lib/dataagent-2.0.3-SNAPSHOT-exec.jar
```

解压后放到 `/data/app/dataagent/dataagent-<版本>/`。首次 start 自动创建当前版本的 log/ 与父目录的公共 data/。

```text
/data/app/dataagent/
  data/                       # 工作区、模型、会话文件、附件及运行记录，升级不覆盖
  venvs/wren/                 # 项目 Wren 环境，另行安装一次
  dataagent-<版本>/
    conf/
    bin/
    lib/
    log/                      # 该版本日志
```

每次发布要使用新的版本目录。可以调整 pom.xml 的项目 version 区分版本。不要把新包直接覆盖到正在运行的旧目录。

## 三个配置文件

都在 `src/main/resources/`：

- application.yml：公共功能配置，默认使用 dev。
- application-dev.yml：本地数据库及模型账密，直接填写；已加入 Git 忽略。
- application-prod.yml：服务器数据库及模型账密，直接填写；已加入 Git 忽略。

普通 Maven package 会将三个 YAML原样放入压缩包 conf/。不用环境变量填密码，不提供 example；更改本地 prod 文件后打包即可带到服务器。

当前 dev 保留原本本地连接信息。当前 prod 已按云演练的两个数据库及 dataagent_app 账户填写，模型配置沿用本地值。正式65部署时换成公司提供的连接和模型配置。

配置没有放进 exec JAR，而是从同目录 conf/ 加载。前端仍放在 JAR中，所以lib只有一个大JAR，依赖已包含在里面；Supersonic lib很多JAR是另一种组装方式。

## 上传限制（2026-10-14）

公共 application.yml 的 `dataagent.upload` 集中配置：`max-file-size: 50MB`、`max-selection-size: 100MB`、`max-files: 10`、`max-rows: 100000`、`max-columns: 100`、`max-cells: 2000000`、`concurrent-files: 8`。前端读取后端策略，不另写一套正式限制；修改后重启后端并刷新页面。

这里的 8 是单进程上传处理名额，可按机器与数据库情况调整；不是已承诺的生产并发容量。每文件仍最多 4 个写入工作线程，未完成批次最多 4 个。WebFlux multipart 大内容和应用接收文件均使用系统临时目录，需留出临时磁盘空间；Nginx 请求体限制也要匹配文件大小并留出 multipart 开销。若把单批文件数调整到 12 以上，也要同步调整 `spring.webflux.multipart.max-parts`（默认 12）。

上传不是断点续传任务。网络中断先查看数据集和上传结果；“已导入、模型未就绪”使用现有检查/发布流程，不重新上传同一份文件。最近上传状态仅在当前后端进程内保留约 30 分钟，正式数据仍在数据集库及平台库中。导入清理失败会记录具体新表名，并保留知识库资源清单供后续处理。

接口兼容性：`POST /api/datasets/batch` 改为逐文件结果（fileName/status/dataset/error），以便返回部分成功；如果有外部调用脚本需同步读取新结构。当前页面使用单文件上传接口配合前端并发队列，不依赖旧的 batch 返回结构。

## 问答容量与运行记录（2026-10-14）

公共 `application.yml` 的 `dataagent.chat` 为单进程普通问答预算：同时执行 16 个、每用户 2 个；额外等待最多 16 个、每用户 1 个；等待最多 30 秒，实际执行最多 600 秒。等待页面显示“排队中”，可以停止；超额或超时明确报错。同一会话一轮尚未结束/停止时重复发送会被拒绝，不叠加隐式等待。这些数字限制同时进行的任务，不限制注册人数或浏览人数。

`dataagent.sandbox.max-containers` 默认 16。容量不足时回收没有活动租约的闲置容器，正在执行的作业不回收。普通问答 Wren 长期实例默认最多 16 个（`dataagent.wren.max-instances`），同库调用等待最多 30 秒（`dataagent.wren.call-wait-seconds`）；建模临时校验进程不在这个池内。本地 10/15 人结果与尚未验证的生产边界见 [容量评估](deployment-and-capacity-review-20261010.md)。

Python 停止/超时按本次作业进程组清理，保留同用户另一会话。镜像现在需要 `setsid`，`docker/sandbox.Dockerfile` 已加入 `util-linux`；首次使用新版时按既有 Docker build 命令更新分析镜像。无需额外 prepare/check 脚本。

内置 data-agent 的会话索引和内部协调记录放在固定工作区的 `.runtime/data-agent/`：按用户隔离会话记录，内部消息队列由服务端会话键路由。正常发布脚本设置工作区在公共 data 下，因此它随公共数据备份，升级不覆盖；本地使用默认工作目录时也已加入 Git 忽略。Python 数据仍在各用户容器中；正式附件、历史和模型继续按原有持久化方式保存。闲置容器替换不承诺恢复未保存为附件的任意临时文件。

本次同时修改了 agentscope-harness 和 OpenAI 模型扩展，打包前先按本文开头的根工程 install 步骤更新 Maven 缓存。模型扩展只对本次模型请求首事件前的 GOAWAY/closed/reset/EOF 重试一次，不重放已输出的响应或整轮工具；显式 maxAttempts=1 时不启用该恢复。容量参数由普通 YAML 配置维护，不增加新的部署目录或启停命令。

应用模型客户端默认 `dataagent.openai.http-version: HTTP_1_1`，用于规避本地多轮负载发现的共享 HTTP/2 连接 GOAWAY/closed 连带中断；连接仍池化复用。需要 HTTP_2 的网关可以显式覆盖，但应先回归持续流式问答。此项不修改 SDK 的全局默认协议，也不补发已输出一半的回答。

## 前端只在改动时构建

模块目录执行：

```powershell
./frontend/build.ps1
```

脚本使用 Maven 的 frontend profile 安装 Node/npm、安装依赖并构建，将结果保存到 src/main/resources/static。之后普通package使用这份静态文件，不再次运行npm。

也可在 IDEA Maven 面板勾选 frontend profile 后执行 generate-resources，然后取消该profile，再正常package。纯后端发布不勾选。

IDEA若提示PowerShell脚本执行策略，直接使用上述Maven profile方式即可。前端依赖变更需要替换运行中的二进制时，先关闭自己的前端开发进程，再重新构建。

## 服务器 start/stop

进入解压出的版本目录：

```bash
./bin/dataagent.sh stop
./bin/dataagent.sh start
```

不再有其他必需命令。脚本自动选prod、加载conf、准备data/log路径，并设置固定运行HOME。root执行且服务器已有dataagent账户时自动使用该账户，否则使用当前账户；Linux账户的Docker权限仍需服务器管理员先授予。

start检查重复进程及缺少JAR/配置等基本问题，打印PID及日志位置。打印进程启动不代表全部业务已经初始化完成，查看log/app.log中的Spring启动完成信息及实际页面。

stop核对PID创建时间及JAR，正常停止等待最多60秒，超时才结束该进程。内部的PID与锁自动维护，你无需操作。不同版本共用一份PID记录，所以不会各自启动一套服务。

Java17；需要指定JDK时设置JAVA_HOME。默认堆-Xms256m -Xmx1024m，需要调整可设置JAVA_OPTS。日志在当前版本log/app.log、launcher.log、LLM.log、LLM-modeling.log，保留轮转。

## 数据库与BI用户

两个数据库仍先由公司管理页面或DBA创建；生产使用原来的Hibernate ddl-auto=update创建/更新平台表。撤掉本轮引入的Flyway及基线命令，后续有专项迁移需要再设计。

生产 `spring.sql.init.mode=never` 且 `dataagent.seed-demo-users=false`，不自动创建admin/bob/alice，不设置初始化密码，不检查管理员是否存在。

BI用户同步到dataagent_user后，直接用SQL设置管理员，例如：

```sql
UPDATE dataagent_user SET roles_csv = 'user,admin'
WHERE username IN ('用户名1', '用户名2');
```

本地dev仍可以使用已有开发账户。BI用户导入和可信免登录接口是后续对接内容，本轮没有改变现有本地登录页面，也没有声称已经实现BI免登录。

## 更新、日志与回退

上传新版本，检查其中prod配置是否适合目标服务器，旧版本stop后新版本start。业务文件始终使用同一个公共data/，旧日志留在旧目录。

升级前备份两个项目库和data/。若新版本修改了数据库结构，回退不能只保证换旧JAR，还需确认旧代码兼容；需要时恢复匹配的数据库与文件备份。本轮不提供自动清库或自动回退。

Nginx只需首次配置转发及流式接口，后续通常不随JAR重配。Wren环境和Docker分析镜像也是一次准备、需要依赖更新时再调整，不属于每次Maven打包的三个目录。

## 当前完成情况

2026-10-12 再次执行普通 Maven package（未跳过测试）成功：508 项测试零失败，12 项条件跳过，生成 tar.gz；核对包内只有 conf/bin/lib，三份 YAML、一个脚本和一个 exec JAR。前端包含在 JAR 中，dev/prod 配置仅放外部 conf，Git 忽略规则已确认生效。普通 package 没有执行 npm。

2026-10-13 每轮选库改造后执行 clean package（未跳过测试）：521 项测试零失败，12 项条件跳过；前端单独构建后拷入资源，清理历史构建文件。包结构仍为上述五个文件，JAR 只有当前三个前端构建资源；重启恢复最近选择和历史范围已验证。

此前的 Python 打包器、example 配置、prepare/check、生产管理员初始化与校验、Flyway 原型已撤掉，相关重复说明合并到本文。云服务器的安装步骤保留在 [云演练记录](cloud-deployment-rehearsal-20261011.md)，业务待办见 [上线差距梳理](go-live-gap-review-20261012.md)。

已完成本地多账号知识库权限、共享问答/CSV 和撤权验收，见 spec 041。尚未执行服务器启停、生产数据库初始化、BI 登录或云端运行测试。
