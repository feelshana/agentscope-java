# ADR 0080：单机发布补充与来源回归测试

日期：2026-10-15
状态：已接受，用户明确授权按核对建议实施。

## 决策

1. 补回来源 LYF 的 WorkspaceBusContextTest、SessionMirrorBindingTest、DockerExecCancellationTest、WorkspaceNamespaceLockTest、OpenAIGoAwayRecoveryTest；保留当前已移植实现。
2. 在当前 Boot exec JAR 外新增 LYF 风格的 tar.gz/conf/bin/lib 单机分发；保持现有 JAR 文件名和 Maven 默认自动构建前端，避免依赖旧静态文件。可选 build.ps1 仅提供提前构建前端的便利命令。
3. 公共 application.yml 与 h2 profile 继续随 JAR 交付；本地 dev/prod 的 YAML/properties 不进入 Maven 资源和 JAR，分发只带无凭据样例，部署时显式创建目标环境配置。Git 忽略与打包排除分别实现。
4. Linux 脚本保留启停锁、PID/启动时间/JAR 身份验证和跨版本持久目录；启动检查同一 Python 环境的固定 Wren 依赖，并通过 HTTP 健康检查确认就绪。预检不安装依赖、不自动联网下载模型，不把预检当作真实问数验收。
5. 本决策替代 ADR0079 中“不采用来源发布布局”的选择；不改变 ADR0069/0074 的官方 Wren 工具、Cube 与 memory 主逻辑。历史“遗漏仅报告”的授权边界由本次明确实施授权更新。

## 验证与限制

执行来源框架测试、standalone 测试、默认前端构建、打包资源检查和隔离 Linux 脚本验收。真实 LLM 效果、生产库与目标服务器性能另行验收；不自动部署、提交或推送。
