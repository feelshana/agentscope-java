# ADR 0030: Wren 查询结果到 Python 的文件级数据交接

> 2026-10-08 更新：ADR 0060 / spec 052：查询 CSV 改为先持久保存，Python 执行时才恢复到沙箱，替代本文立即上传的实现。

- 状态：已采纳（实施见 specs/016）
- 日期：2026-10-03

## 背景

问数链路的两端工具在数据形态上不对称：`wren_run_sql` / `wren_query_cube` 的输出是面向
LLM 上下文的 markdown 表，`run_python` 的输入只有 `code` 字符串——两者之间没有结构化数据
通道。LLM 要用 Python 处理查询结果时，唯一途径是把数据行抄写成 Python 字面量嵌进 code。

这条「经 LLM 上下文搬运数据」的通道在数据量增长时结构性失效（非偶发 bug，是架构缺口）：

1. **大 payload**：数百行数据嵌进单个工具调用参数，序列化/传输反复失败；
2. **分批抄写污染**：被迫分多轮 `run_python` 分批灌入再拼接，上下文膨胀、行错位/丢失/重复；
3. **迭代耗尽**：重试轮次吃光 ReAct 迭代预算，任务以 fallback 文本收场；
4. **上下文驱逐放大**：大工具结果被驱逐为 preview 后，后续轮次看不到全量数据，抄写更不可靠；
5. **反模式制度化**：`python-analysis` 技能的探查模板明确教「Wren 查询返回的行…硬编码为
   DataFrame」——技能文档本身在教 LLM 走这条死路。

根因：把「给人看的数据」（markdown，走上下文）与「给程序处理的数据」（应走文件）挤进了
同一条窄管道。平台已有反向先例——`run_python` 的图片产物用 `image_ref` 路径引用而非
base64 内联（保持 tool result 轻量），说明「产物落文件 + 路径引用」模式在本沙箱体系成立，
只是查询侧的数据入口从未建立。

## 决策

**查询侧落盘 + 引导引用**：`wren_run_sql` / `wren_query_cube` 成功后，服务端把结果渲染成
CSV 写入当前会话的沙箱工作区 `/workspace/runpython/<sessionId>/data/`（与 `RunPythonTool`
共用 workDir 约定），返回文本附路径与行列数；LLM 在 `run_python` 的 code 中用
`pd.read_csv()` 按相对路径读取。**`run_python` 工具接口零改动。**

关键机制：

- **同 workDir 约定**：数据文件与 `RunPythonTool` 的执行目录同根（`/workspace/runpython/
  <sessionId>/`），code 以 `data/<file>.csv` 相对路径即达，无需任何新参数；
- **独立沙箱代理注入**：`WrenToolkit` 注入 `SandboxBackedFilesystem` 代理——真实沙箱由
  `SandboxLifecycleMiddleware` 绑定于 RuntimeContext，同轮工具调用拿到同一上下文
  （`RunPythonTool` 已验证的模式，见 `DataToolkitRegistrar` Javadoc）；
- **内容寻址文件名**（结果 payload 的 sha256 前 12 位）：同结果幂等覆盖不积累垃圾、
  不同查询天然共存、服务端零状态（不需要 per-session 计数器或注册表）；
- **降级不阻塞**：落盘失败只导致返回文本少一行数据文件引用，查询主路径（markdown 表）
  不受影响——数据通道是增强，不是前置依赖；
- **消费侧引导**：`run_python` 工具描述与 `python-analysis` SKILL.md 探查模板改为
  read_csv 数据文件，反模式区明令禁止字面量抄写（技能层规则配 `SharedSkillContentTest`
  classpath 守卫断言）。

## 理由与权衡（被拒方案）

- **服务端暂存区 + 引用 token**：查询结果存 Java 侧（内存/DB/磁盘），`run_python` 凭 token
  执行前注入沙箱。拒绝理由：暂存区 TTL/清理/容量是全新的生命周期管理问题；而沙箱 idle TTL
  （默认 15 分钟，`dataagent.sandbox.idle-ttl-min`）本来就是现成的 TTL，文件随容器消失，
  复用既有隔离边界优于再造一份全局状态。
- **`run_python` 新增 data_refs/query 参数**：拒绝理由：让 Python 工具理解 wren 查询语义，
  职责混杂；数据注入对 code 是隐式的（magic），可解释性差；且仍需暂存区承接「查询时数据
  在哪」，没有消除状态。
- **提示词修复（教 LLM 分批抄写）**：拒绝理由：payload 物理大小限制不是提示词能解决的；
  分批拼接本身的可靠性（错位/丢行）也无法靠提示词保证。结构性缺口要靠通道，不是话术。
- **wren 子进程直接写文件**：拒绝理由：wren 子进程连接的是数据集 MySQL，没有沙箱文件系统
  访问权；跨进程写文件语义复杂且破坏「wren 只做查询」的单一职责。
- **`run_python` 直连数据库读原始表**：拒绝理由：沙箱 `--network=none`；且违反 Wren-only
  边界（ADR 0029）——agent 不接触物理 schema，查询必须经已发布 MDL。

接受的代价：

- **沙箱 idle 回收后文件失效**（15 分钟空闲）：`run_python` 报 `FileNotFoundError`，
  LLM 重查自愈——失败模式可见、可自纠，优于静默数据错误；
- **CSV 类型有损**（数字变字符串、NULL 变 NaN）：SKILL.md 第 2 步本来就有 dtypes 探查
  义务，无新增负担；
- **查询关键路径多一次沙箱写**：小结果毫秒级；写失败降级（见上）；单文件 10MB 上限
  防御性跳过并提示收敛；
- **多副本部署**：数据文件在容器本地，多副本必须维持既有 sticky-by-user 路由前提
  （`UserSandboxRegistry` Javadoc 已声明），本决策不改变该前提。

## 影响面

- `WrenToolkit`（新增 5 参构造器 + CSV 渲染落盘 + 返回文本行）、`DataToolkitRegistrar`
  （装配传 `SandboxBackedFilesystem`）、`RunPythonTool`（仅 `@Tool` 描述文案）
- 技能文档 `python-analysis/SKILL.md`（resources 权威源 + 仓库根 `shared/` 落盘副本同步）
- `ARCHITECTURE_zh.md` 4.1 / 4.4 / 4.6；测试 `WrenToolkitTest` / `SharedSkillContentTest`
- 隔离语义：数据文件位于 per-(userId, agentId) 容器的 per-sessionId 目录，租户 + 会话
  双层隔离，无新增越权面（与 ADR 0003 / 0014 一致）
