# ADR 0054：工作区文件树扫描范围与阻塞隔离

日期：2026-10-09
状态：已实现，运行中的旧服务需重启后复验

## 证据

当前 Java 进程线程快照中 parallel-4、parallel-9 正在执行 collectChildrenFs → BaseSandboxFilesystem.ls → DockerSandbox.doExec → Process.waitFor。扫描递归深度达到 5～6 层。实际容器命令工作目录为 /workspace，控制器却从绝对路径 / 开始扫描。SharedSandboxFilesystem 未将 / 映射到 /workspace，因此会遍历容器系统目录。当前项目沙箱实测约 46% CPU、23 MiB 内存，容器数量不能说明是否存在高频命令。

知识库列表调用 groupRepository 与 datasetRepository，在 boundedElastic 上执行，没有 Docker 调用。截图 20.02 秒发生在浏览器 Stalled，不能直接解释为数据库查询耗时。无认证探针当时后端和 Vite 代理分别在约 5～17ms 返回 401，只证明连接当时正常，不代表认证后的数据库查询耗时。未取得截图时完整 HAR，不能断言 20 秒唯一由文件树造成。

## 决策

1. 文件树以工作区相对路径扫描：根用 .，子目录用相对路径；不扫描容器根目录。
2. 保留既有 6 层展示深度，限制 2000 项、扫描预算 10 秒，整个文件树请求 15 秒超时。超过限制返回明确错误，不静默截断。
3. 工作区阻塞文件操作切换到 boundedElastic；扫描检查线程中断。前端文件树、贡献页面树、知识库列表在卸载或发起新请求时取消旧请求，避免占用连接及旧响应覆盖。
4. 添加 page-read 慢请求日志；仅记录路由、请求 ID、耗时、状态与结束信号，不记录 token、文件内容及账密。用于区分服务器处理与浏览器连接等待。

## 边界与后续

本轮不改变沙箱回收、问答会话隔离、Wren 查询流程。浏览器连接池、代理及认证后的数据库实际耗时需要通过重启后的浏览器 Timing、慢请求日志复验。大型工作区后续可改为按展开目录查询。

对应 [spec 045](../specs/045-workspace-tree-scan-boundary.md)。
