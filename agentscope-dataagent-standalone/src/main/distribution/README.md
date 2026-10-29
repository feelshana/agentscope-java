# DataAgent 单机发布包

生产66（Anolis7.9）当前主方案为从GitHub源码构建应用镜像，见[源码构建指引](docs/guides/66-source-build.md)；源码构建需要完整仓库，本tar不能替代源码。本文及ops/container/保留外置tar运行备选，不在66宿主直接套用下方原生bin步骤。

按[单机发布手册](docs/guides/single-host-release.md)准备配置、依赖和持久目录，再用 `bin/dataagent.sh` 启动、查看状态和停止。服务日志在当前版本 `logs/`，版本外的 `data/` 应持久保存。

首次安装先阅读[数据库初始化与升级](docs/migrations/README.md)。`0000-initial-platform-mysql.sql` 只用于空平台库；上传数据集库先建空库。已有环境不得重复执行完整初始化 SQL 或全部增量 ALTER。

[65 服务器环境采集与准备](docs/guides/65-server-preparation.md)包含按适用系统标注的命令；[发布执行清单](docs/guides/release-checklist.md)用于逐项验收；[BI 接入](docs/integration/bi-login.md)说明票据登录和管理员配置。架构决策、设计和历史测试请查同一源码版本的完整文档，不以历史验收代替本次部署检查。

包内只有无凭据配置样例，不包含个人交接、私有 dev/prod 配置、生产账号或模型 Key。模型配置主密钥、数据库、工作区和附件需匹配备份；更换发布包不应覆盖持久数据。
