# spec 039: 持久会话历史与附件生命周期

> ADR 0048 的已实施规格；日期 2026-10-08。

## 背景与目标

历史列表不访问 Docker；附件在容器回收后仍能授权展示、下载；控制资源并减少清理对交互请求的阻塞。

## 方案概述与影响面

- `SessionController` / `SessionHistoryService`：数据库展示投影、旧日志补齐、游标分页、活动会话删除保护。
- API：`/api/agents/{agentId}/sessions/inbox-page` 默认 20 条，返回 `items/nextCursor/hasMore`；`/{key}/messages` 默认 50 条，返回 `items/nextBefore/hasMore`。旧 `/inbox` 保留数组格式。
- `ArtifactStore` / `ArtifactController` / `SandboxArtifactReader`：持久文件、用户归属校验、容量预占、分段锁、分批补偿清理。永久接口 `/api/artifacts/{id}/content` 仅接受请求头认证。
- `RunPythonTool` / `WrenToolkit`：独立执行目录，CSV 延迟上传，最近输入恢复及显式旧附件恢复。
- `LazySandbox` / `UserSandboxRegistry` / `HarnessGateway`：租约、容器限额、标签隔离清理与回收失败重试。
- 前端：历史列表和消息分页；Markdown、Python 产物及工具检查器使用认证文件请求和 Blob 图片，支持下载失败提示。
- 数据：新增三张表，见 `docs/migrations/20261008-session-history-artifacts.sql`；运行参数见 `application.yml` 和部署说明。同步 `ARCHITECTURE_zh.md` 的会话/附件补充章节。

## 默认限制

附件：单文件 20 MiB、每用户 1024 MiB、每会话 100 个、总计 10240 MiB、磁盘预留 2048 MiB。输入自动恢复最近 10 个文件、总量预算 100 MiB，保留沙箱至少 32 MiB 可用空间。沙箱：最多 8 个、每个 1 CPU / 1024 MiB、workspace 512 MiB、空闲 15 分钟。配置均见部署说明；不是按 10 轮对话恢复，也不会主动淘汰暖沙箱已有输入。

## 验收标准（Given-When-Then）

1. Given 已有历史，When 分页浏览列表，Then 不创建/查询沙箱，列表 20 条一页，正文按 50 条加载。
2. Given 新产物成功保存，When 回收沙箱、重启服务，Then 所属用户仍可展示、下载；数据库、工作区、附件目录需持久化。
3. Given 用户 B 或未登录请求，When 访问用户 A 的附件，Then 拒绝；仅有 query token 也不能访问永久附件。
4. Given 超过任一配额或磁盘不足，When 保存附件，Then 返回明确失败，已有附件可访问，失败不产生有效永久链接。
5. Given 删除/重置空闲会话，When 清理完成，Then 展示消息、关联附件元数据与文件消失；活动会话返回冲突。
6. Given 大量历史附件，When 执行清理，Then 每批有界、单项失败不终止全批，保存不受全程全局锁阻塞。
7. Given 超过 10 个旧输入或恢复空间不足，When 执行 Python，Then 恢复量受限并给模型提示，旧永久附件保持可下载。
8. Given 回收失败或启动孤儿清理失败，When 请求新沙箱，Then 不因提前释放名额造成无界增长；启动清理失败需修复 Docker 后重启。

## 验证记录及待验收

Java 编译、TypeScript 检查、前端生产构建与差异格式检查已通过。用户反馈首轮附件保存、回收后读取、重启后读取、删除清理流程符合预期。后续请求头认证、配额、分批清理、最近输入恢复尚未完成运行联调；未新增/执行自动化测试，未进行备份恢复演练。上述验收条目不是全部已通过的声明。

## 明确边界

不实现附件分享、对象存储迁移、多实例配额或自动过期删除，不保证旧容器已丢失文件恢复。长期保留策略仍需独立决策。

## 关联

[ADR 0048](../adr/0048-durable-session-history-and-artifact-lifecycle.md)；[部署说明](../session-history-and-artifacts.md)。
