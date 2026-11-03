# BI同步状态与停用本地验收

2026-11-03 Asia/Shanghai；liyufan_dev@19fcdbde，暂存为空。用户授权实现轻量管理员只读页面及服务器停用预案；spec110/ADR0127。本轮未提交、推送、切分支或操作66，既有正式环境、合成演示数据等WIP保留。

## 实现与边界

- 更多的管理区增加BI数据同步。GET /api/admin/bi-sync鉴权先于数据库访问，普通用户和单库管理员不可用；JDBC在boundedElastic执行，响应no-store。
- 只读数据集库固定控制键及运行锁，页面展示完整数据截止日、最近成功、两日志范围、最近任务起止/耗时、固定失败摘要。控制记录中的任意额外信息/异常原文不向Web投影，不读取凭据JSON，不访问BI源或扫描业务行。未配置/未初始化/不可用/日期落后与执行失败分别呈现。
- sync记录固定last_run，异常保存阶段码；进程遗留RUNNING但锁已释放时显示中断。pause/status/resume只访问目标，sync独立观察连接检查暂停，安全点退出、回滚未提交日，保留已提交进度。暂停是协作机制，源读取返回或超时之前可能仍在运行；需status确认paused=true且running=false。
- cron remove只移除当前账号的唯一标记及精确命令，保留其他任务。移除不强杀现有作业；取消全员使用或删除关联知识库不自动停止同步，也不删除这七张外部表。恢复不用init，resume后补齐核验再安装每日任务。
- 本版不提供Web同步/暂停/凭据编辑/删表；不声称cron状态可以由暂停标记证明。启动脚本、Docker权限或目标连接失败可能无法持久记录该次执行，应结合截止日和服务器日志判断。

## 实测

1. Python真实MySQL5.7.44→8.4.5：test_bi_sync_mysql共15项通过，无跳过；在原39列/项目分段12项上增加成功及源连接失败安全元数据、源不可用下暂停/状态/恢复、暂停后跳过源、实际运行锁、中途暂停日回滚与续跑。隔离标签a0fc63c1a2，两容器核标签后停止并保留数据。之后仅将耗时起点放到last_run开始前，对齐记录起止时间，不改变数据事务或暂停流程。
2. Linux已有运行镜像、无网络运行test_formal_preparation：14项通过，无跳过；包含精确cron移除/保留其他任务、异常标记拒绝和暂停时禁止安装。应用切换和cron输出来自隔离模拟，没有真实安装服务器或本机cron。
3. BiSyncStatusServiceTest 5项、AdminBiSyncControllerTest 2项通过：401/403无数据访问、POST405、管理员no-store和调度、固定只读SQL、安全投影、不可用/未初始化/中断/暂停、东八区日期及凌晨宽限。DocsConsistencyTest 5项通过，最终文档门禁另见SESSION_LOG。
4. 前端npm test：70项分别在/、/dataagent/、/dify/dataagent/通过，无跳过；新增状态投影、固定安全文案/日期和只读管理入口检查。npm run build通过TypeScript及Vite，正式静态包更新；已有ECharts导入及大包警告不属于本轮功能回归。
5. Playwright独立命名会话bi-sync-ui及19146模拟入口加载真实BiSyncPage和CSS，只拦截本页状态接口返回虚构数据，不登录或访问用户正在运行的前后端。检查成功/失败/暂停/HTTP错误重试，刷新实际再次请求；1440px桌面及390px窄屏。窄屏.kw-scroll的scrollWidth与clientWidth均390，卡片356，无横向裁剪。截图已逐张视觉检查，均为隔离示例而非生产状态：
   - [成功桌面](assets/bi-sync-20261103/bi-sync-success-desktop.png)
   - [暂停窄屏](assets/bi-sync-20261103/bi-sync-paused-mobile.png)
   - [失败桌面](assets/bi-sync-20261103/bi-sync-failed-desktop.png)

## 验证过程修正

初次前端SSR测试导入路由页导致临时测试包找不到scheduler，抽取纯状态组件后全部通过。窄屏模拟缺少真实index.html全局box-sizing导致内层裁剪，加入真实重置并在新页局部设置box-sizing后重新检查。一次CLI误选SVG引用已改为新快照中的实际按钮，无强制点击。初次文档索引漏注册、Spotless命令格式及通配静态导入失败均已修正重跑；未扩大格式化到无关Java文件。

## 仍需现场

正式MySQL8.0.36、66目标连接权限、源码镜像发布和后端启动、首次全量同步/耗时、cron真实权限与时区、业务库关联发布、普通用户问答均未由本轮代验。已有正式平台37表/人员初始化不重复。页面只读观测减少排查成本，不能保证今后完全无需运维。
