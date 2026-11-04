# 66正式环境：日常一条命令发布

状态：spec115本地隔离验证通过，待66首次现场发布。

## 日常使用

先由开发者将需要发布的代码提交、合入并推送到`develop-2.0`。使用66现有有部署权限的SSH账号（当前为`wangchao_yy1`），执行：

```bash
bash "$HOME/dataagent-source/agentscope-java/agentscope-dataagent-standalone/docker/deploy-formal.sh"
```

执行期间保持SSH连接。除既有sudo可能要求终端认证外，无需逐阶段确认，不需要填写提交号或复制镜像名。当前运行版本已经等于主线且健康正常时，自动跳过构建和重启。

第一次使用前，需要将包含该入口的提交合入主线，再按已验收的手动`git fetch`加`git merge --ff-only`命令同步到66；此后使用固定入口即可。脚本会在拉取后转交目标提交中的完整入口继续执行，避免自身更新过程中混用阶段版本。

## 自动完成的步骤

1. 检查现行正式目录、外置两库标记、18080应用健康、部署主线和干净工作区，获取整轮发布锁。
2. 临时直连读取主线，限定超时/重试，仅快进源码；不修改永久代理，不影响构建容器原有代理环境。
3. 锁住本次源码，构建明确提交；从本次专属结果文件核对镜像ID、版本和应用标签，避免误选旧镜像。
4. 使用应用镜像里的Python及宿主Compose插件调用现有`formal-app.py upgrade`，不要求66宿主安装Python3。
5. 沿用现有两库只读、离线向量、兄弟沙箱预检和启动健康检查；新镜像启动失败时，由原控制器尝试恢复原镜像。
6. 核对运行镜像、健康接口和状态，输出发布摘要及记录目录。

升级时应用会短暂重启。数据库、runtime配置、模型服务与加密主密钥、知识库、会话、文件、每日02:00 BI任务和旧镜像都继续保留。脚本不运行首次prepare/activate、平台初始化、人员导入或cron安装，不清理资源。

## 输出与失败处理

每次记录保存在`/data/dataagent/formal-app/logs/releases/<时间.随机标识>/`：

| 文件 | 用途 |
| --- | --- |
| `deploy.log` | 脱敏阶段日志及控制器输出 |
| `status.env` | RUNNING、SUCCESS、SKIPPED或FAILED及阶段、提交、镜像 |
| `context.env` | 源码目录、目标提交、原镜像，用于同一次执行的版本交接 |
| `build-result.env` | 本次成功构建的镜像、ID及原始构建记录路径 |

主构建日志仍在`$HOME/dataagent-build/jobs/`对应作业中，入口不猜“最近一份”。仅出现Maven成功不代表整个发布成功；最终应看到`发布成功`、目标提交和`health=healthy`。

- 拉取、构建或预检失败：停止，原应用保留；查看失败阶段，不继续用旧构建冒充新发布。
- 启动失败：日志会显示原控制器恢复结果；入口另外核对原镜像是否仍健康，恢复未确认健康时明确报错。
- 发布期间有其他入口/手工修改源码、应用镜像或正式配置：停止替换并保留现场。
- SSH断开或手动中断：先查本轮`status.env`、日志和正式容器当前状态，确认是否进入升级；不盲目重放，也不手动删除锁文件“解锁”。锁随进程退出释放。

`healthy/UP`代表部署和服务健康通过。发布后仍应从原BI入口刷新页面，核对这次修改和一次真实问数，不需要重新配置模型或重建知识库。

## 可选设置

默认适配66已现场成功的临时直连；若网络环境要求沿用既有代理：

```bash
DATAAGENT_GIT_NETWORK=inherit bash "$HOME/dataagent-source/agentscope-java/agentscope-dataagent-standalone/docker/deploy-formal.sh"
```

| 环境变量 | 默认值/说明 |
| --- | --- |
| `DATAAGENT_GIT_NETWORK` | 入口默认direct；inherit保留当前代理，独立update-source脚本仍默认inherit |
| `DATAAGENT_GIT_PROBE_TIMEOUT` | 入口每次30秒，最多3次；Git分支读取检查，允许0至60秒，0表示关闭 |
| `DATAAGENT_FETCH_TIMEOUT` | 入口每次180秒，最多3次；独立更新脚本仍默认1800秒 |
| `DATAAGENT_FORMAL_APP_DIR` | `/data/dataagent/formal-app`，必须已有正确正式标记和配置 |
| `DATAAGENT_COMPOSE_PLUGIN` | `/usr/libexec/docker/cli-plugins/docker-compose`，必须存在且可执行 |
| `DATAAGENT_DOCKER_SUDO` | true；已有直接访问Docker权限时可显式false，不修改sudoers或socket权限 |
| `DATAAGENT_DOCKER_SOCKET` | `/var/run/docker.sock`，临时辅助容器只读根文件系统，显式挂载现有socket |

脚本不保存代理凭据，不启用Git HTTP详细请求头日志，不关闭TLS验证。网络暂时失败时有限重试；离线包仍是需要时的人工备用方式。

## 本地验证范围

使用真实Linux Bash/Git和隔离仓库，Docker/构建产物/正式控制器的外部副作用由故障桩替代；不连接生产、不挂载Docker socket、不调用模型。测试入口：

```bash
python src/test/python/test_deploy_formal.py
```

该测试要求Linux的`/proc`、Unix socket、Git及flock。测试中的/tmp目录只含自身夹具，结束后删除自身夹具，不操作现有工作区或服务。真实66一条命令发布的验收需后续单独完成。
