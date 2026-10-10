# spec 016: Wren 查询结果到 Python 的文件级数据交接

> 2026-10-08 更新：ADR 0060 / spec 052：CSV 交接改为持久保存和受限延迟恢复，本文立即上传方式仅为历史实现。

## 背景与目标

问数链路两端的数据形态不对称：`wren_run_sql` / `wren_query_cube` 的输出是面向上下文的
markdown 表，`run_python` 的输入只有 `code` 字符串，两者之间没有结构化数据通道。LLM 要用
Python 处理查询结果时，唯一途径是把数据行**抄写成代码字面量**（list / DataFrame 构造）嵌进
code 参数。这条「经 LLM 上下文搬运数据」的通道在数据量增长时结构性失效：

1. 数百行数据嵌进单个工具调用参数，payload 过大导致序列化/传输反复失败；
2. LLM 被迫分多轮 `run_python` 分批灌入数据再拼接——上下文膨胀，拼接错位/丢行/重复；
3. 重试轮次吃光 ReAct 迭代预算，任务以 fallback 文本收场；
4. 大工具结果被上下文驱逐机制截为 preview 后，后续轮次看不到全量数据，抄写更不可靠；
5. `python-analysis` 技能的探查模板明确写着「Wren 查询返回的行…硬编码为 DataFrame」，
   把反模式制度化（根因之一）。

目标：在查询产物与 Python 工件之间建立文件级数据通道——LLM 只引用路径，不搬运数据。

## 方案概述

查询侧落盘 + 引导引用（取舍见 ADR 0030）：

1. `WrenToolkit` 新增 5 参构造器注入 `AbstractSandboxFilesystem`（旧 4 参构造器保留，
   filesystem 为 null 时行为完全不变）；`DataToolkitRegistrar` 传入独立
   `SandboxBackedFilesystem` 代理——与 `RunPythonTool` 同款模式，真实沙箱由
   `SandboxLifecycleMiddleware` 绑定在 RuntimeContext 上，工具调用拿到同一上下文。
2. `wren_run_sql` / `wren_query_cube` 成功后，从结果 payload（与 `renderTable` 同源：
   columns/rows JSON）渲染 CSV，写入当前会话沙箱工作区
   `/workspace/runpython/<sessionId>/data/<sha256 前 12 位 hex>.csv`。
   - CSV 约定：RFC 4180 转义（值含逗号/引号/换行加引号）、UTF-8 无 BOM（pandas 默认编码
     直读）、NULL 写空串（pandas 读为 NaN）、列名/列序与 payload 一致；
   - 文件名内容寻址：同一结果集幂等覆盖（重查不积累文件），不同查询天然共存，
     服务端零状态（无 per-session 计数器）；
   - 写入前确保目录存在（参照 `RunPythonTool` 的 upload + base64 echo 兜底双路径）。
3. 返回文本在结果表之后追加一行：
   `**数据文件：** data/<hash>.csv（N 行 × M 列；run_python 中 pd.read_csv('data/<hash>.csv') 读取）`。
   markdown 结果表渲染保持现状（本 spec 不动预览策略，见「不做的事」）。
4. 降级语义：落盘失败（沙箱未就绪/写入异常）**不阻塞查询主路径**——markdown 表照常返回，
   只是不附数据文件行；单文件超过 10MB 跳过落盘，返回文本提示「结果过大未生成数据文件，
   请加聚合或调小 limit」。`truncated` 语义沿用：数据文件行数与 markdown 行数同源一致。
5. 引导更新（消费侧）：
   - `run_python` 的 `@Tool` description 追加：查询返回大数据文件时优先
     `pd.read_csv` 读取，禁止把查询结果抄写成代码字面量；
   - `python-analysis` SKILL.md：第 2 步探查模板由「硬编码 DataFrame」改为
     `df = pd.read_csv('data/<查询返回的文件名>.csv')`；反模式区新增
     「❌ 把查询结果数据抄写成 Python 字面量——直接 read_csv 查询返回的数据文件」。
     权威源 `src/main/resources/shared/agents/data-agent/skills/` 与仓库根 `shared/`
     两处同步，`SharedSkillContentTest` 补守卫断言（技能层独有规则必有 classpath 断言）。

实施后同步 ARCHITECTURE_zh.md：4.1（run_python 职责行）、4.4（结果形态）、
4.6（三工具分工表 + 轻量预注入段之后的数据交接段）。

## 影响面

- 后端：`WrenToolkit`（构造器 + 两个查询方法 + renderCsv/persistResult 私有方法）、
  `DataToolkitRegistrar`（装配）、`RunPythonTool`（仅工具描述文案，零逻辑改动）
- 技能文档：`skills/python-analysis/SKILL.md`（resources + 根 shared/ 双处）
- 前端：无（工具返回文本经既有 SSE 透传）
- 数据：无新 JPA 实体/物理表/迁移
- 文档：`ARCHITECTURE_zh.md` 4.1 / 4.4 / 4.6；ADR 0030

## 验收标准（Given-When-Then，可测试）

1. Given 已发布知识库且注入了 filesystem，When `wren_run_sql` 返回 100 行结果，
   Then 返回文本含数据文件行（路径 + 行列数），且沙箱对应路径存在 CSV，
   行数、列名与 payload 完全一致（`wren_query_cube` 同理）。
2. Given 查询成功但 filesystem 写入抛异常，Then 查询照常返回 markdown 表与 SQL 回显，
   不含数据文件行，返回文本无 error 前缀（降级不报错）。
3. Given 同一查询（同 payload）执行两次，Then 两次数据文件名相同（幂等覆盖，
   data/ 目录不新增文件）。
4. Given 值中含逗号、双引号、换行的结果，Then CSV 按 RFC 4180 正确转义，
   pandas `read_csv` 读回的值与 markdown 表显示一致。
5. Given filesystem 为 null（旧 4 参构造器），Then 两个查询工具行为与现状完全一致
   （不落盘、不附数据文件行）。
6. Given 会话 s1 与会话 s2 各执行查询，Then 数据文件分别落在各自的
   `/workspace/runpython/<sessionId>/data/` 目录（会话隔离）；用户 A 的沙箱内不存在
   用户 B 的任何文件（容器级隔离，无新增越权面）。
7. Given `python-analysis` SKILL.md，Then 探查模板含 `pd.read_csv` 数据文件引用、
   反模式含「禁止抄写字面量」条目（`SharedSkillContentTest` classpath 断言）。

## 不做的事（明确排除项）

- 不改 markdown 结果表的渲染/截断策略——预览瘦身与否待数据通道建立后单独评估；
- 不给 `run_python` 新增任何参数（无 data_refs/query 参数——文件已在工作目录，
  `pd.read_csv` 相对路径即达，保持工具接口最小）；
- 不做服务端暂存区/引用 token（沙箱 idle TTL 即现成生命周期，见 ADR 0030 被拒方案）；
- 不落盘 `wren_describe_model` 的结果（纯元数据，无 Python 消费场景）；
- 不做 data/ 目录主动清理（hash 幂等覆盖 + 沙箱 idle 回收随容器消失 + sessionId 目录
  隔离已足够；run_python 执行时的 `rm -rf outputs/*` 不触碰 data/）；
- 不提供 run_python 绕过 Wren 直读物理表的任何通道（维持 Wren-only 边界与
  `--network=none` 沙箱模型）。

## 测试要求

- `WrenToolkitTest`：验收标准 1–5 对应用例（stub `AbstractSandboxFilesystem` 捕获
  uploadFiles 的路径与字节，断言 CSV 内容/转义/幂等/降级/null-filesystem 兼容）；
- `SharedSkillContentTest`：验收标准 7 的 classpath 断言；
- 隔离用例（验收标准 6）：断言数据文件路径按 sessionId 推导，两个 sessionId 生成
  不同目录——「看不到别人的数据」由容器隔离 + 目录隔离双层保证。

## 关联

- ADR：0030（查询结果文件级数据交接的取舍）
- 相关 ADR：0003（per-user 沙箱）、0014（沙箱会话目录隔离）、0023（同组调用串行——
  落盘发生在 Java 侧，不占 wren stdio 管道）、0029（Wren-only 通道边界）
