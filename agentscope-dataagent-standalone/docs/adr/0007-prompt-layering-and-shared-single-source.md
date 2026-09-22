# ADR 0007: 提示词三层分工 + `shared/` 单一事实源与 seeder 版本化

- 状态：已采纳
- 日期：2026-09-22
- 证据：`application.yml`（原第 144-148 行的 `dataagent.agent.sys-prompt` 覆盖块）、根目录
  `shared/agents/data-agent/skills/sql-analysis/SKILL.md`（0 字节，且已作为 empty blob
  `e69de29` 提交进 git）、`src/main/resources/shared/` 同名文件（6678B，含 ADR 0006 的 CTE
  规则）、两份 `subagents/*.md`（根目录旧英文版 vs resources 中文新版）
- 关系：修正 ADR 0006 第 1 条决策的**落地载体**（规则内容不变），ADR 0006 文本保持原样

## 背景

ADR 0006 决定把「JOIN 优先、禁止结果搬运、维表 CTE 去重」写进系统提示词的「数据源规则」，
并把细节写进 `sql-analysis` 技能。复查发现这套引导实际上完全没有生效，共三处失效：

1. **规则从未进入模型上下文。** `DEFAULT_AGENT_SYS_PROMPT` 只是
   `dataagent.agent.sys-prompt` 的兜底默认值，而 `application.yml` 显式声明了该键，于是
   Spring 注入的是 yml 里那段通用角色描述；`DataAgentConfig#ensureAgentscopeConfig` 首次
   scaffold 时写进 `workspace/AGENTS.md` 的正是被覆盖后的值。ADR 0006 新增的规则一行都没生效。
2. **承载细节的技能文件是 0 字节。** 根目录 `shared/.../sql-analysis/SKILL.md` 被清空并提交，
   harness `WorkspaceSkillRepository` 解析 frontmatter 失败 → 技能从 `available_skills` 静默消失，
   没有任何告警。
3. **`shared/` 存在双向漂移的双副本。** 出厂内容同时存在于 `src/main/resources/shared`（classpath）
   与项目根 `shared/`（git 跟踪），两边各有更新：根副本的 `python-analysis` 工具名较新但丢了一条
   反模式，两个 subagents 是旧英文版；resources 侧则相反。谁都不是事实源。

根因是**缺少「哪条规则放在哪一层」的约定**，以及**缺少「哪份副本是源」的约定**：
技能按需加载（常驻上下文只有技能名 + 描述），而 `@Tool` 描述随每轮请求下发；
根目录 `shared/` 既是出厂内容副本，又是 `MarketContributionService` 审批产物的落盘目录，
两个身份混在同一棵被 git 跟踪的树里。

## 决策

1. **提示词三层分工，硬门下移到 `@Tool` 描述。**
   - 常驻层 A —— `DataAgentToolkit#queryStructuredData` 的 `@Tool(description=...)` 追加
     跨表硬门：多表关联必须在同一条 SQL 内用 JOIN/CTE 完成、禁止把上一步结果作为字面量
     复制进 `IN (...)`、维表一个关联键对应多行时先 `WITH ... SELECT DISTINCT` 去重再 JOIN
     以防扇出放大 `COUNT`/`SUM`，并指向 `sql-analysis` 技能。
   - 常驻层 B —— `DEFAULT_AGENT_SYS_PROMPT` 只保留「模型加载任何技能之前就必须成立」的内容：
     角色、最高优先级原则（含「不编造数据」）、工作流程骨架、输出语言与产物门（不主动出图 /
     不主动出文件 / 全中文输出）。原「# 数据源规则」整段删除：其中「先看
     `[DATA_SOURCES_OVERVIEW]` 确认表名、用 `tables` 参数一次批量取回 1–3 张直接相关表」
     改为工作流程第 3 步一句「先加载 sql-analysis 技能并严格按其步骤执行」，细则由技能第 2 步
     承载（技能版本更完整，还带「不要盲写列名」的反模式）；「JOIN/CTE 去重」由常驻层 A 承载。
     「回答规则」里的 matplotlib 中文标题示例删除，`python-analysis` 的反模式清单已有更强版本
     （含 `Noto Sans CJK SC` 字体约束）。净效果：同一条规则只在一层出现，常驻提示词由
     30 行降到 21 行。
   - 按需层 —— `shared/agents/data-agent/skills/sql-analysis/SKILL.md` 承载完整步骤、
     示例与反模式，本次不改文案。
   - 修断链：删除 `application.yml` 的 `sys-prompt` 键（**不能**置空字符串，键存在而值为空
     会 scaffold 出空 `AGENTS.md`），`@Value` 改为三级
     `${DATAAGENT_AGENT_SYS_PROMPT:${dataagent.agent.sys-prompt:<常量>}}`，
     即环境变量 > yml > 内置常量。
2. **`src/main/resources/shared` 是唯一事实源，项目根 `shared/` 降为运行态目录。**
   `git rm -r --cached agentscope-dataagent-standalone/shared` 取消跟踪（工作区文件保留），
   `.gitignore` 追加锚定的 `/shared/` 并注明理由。根目录 `shared/` 仍需运行时可写：它同时是
   `UserSandboxRegistry` 的工作区投影挂载点和 `LocalApprovalMarketplace` 审批产物的落盘处。
3. **`SharedWorkspaceSeeder` 从 write-if-missing 升级为 manifest 版本化三态策略。**
   `SharedWorkspaceSeeder#seedSharedTree` 在 `${cwd}/shared/.seed-manifest.json` 记录
   `relPath -> sha256(出厂字节)`，每次启动按下表判定，并由 `SeedResult` 分类计数写日志：

   | 磁盘状态 | 动作 |
   | --- | --- |
   | 不存在，或存在但 0 字节 | 写入出厂内容并登记 manifest（**0 字节自愈**） |
   | 与出厂内容一致 | 不动，刷新 manifest 条目 |
   | 在 manifest 中且磁盘 hash == 记录值（未被手改），但出厂已变 | 覆盖为新版本并更新 manifest（**随版本升级**） |
   | 在 manifest 中但磁盘 hash != 记录值（运营手改过） | 保留不动 |
   | 不在 manifest 中且 manifest 已存在（市场贡献产物） | 保留不动 |
   | 不在 manifest 中且 manifest 整体缺失（策略引入前的老部署） | 一次性「采纳」：覆盖为出厂内容 |

   manifest 读写失败降级为只按第 1、5 行处理并 warn，绝不抛异常阻断启动；manifest 存在但
   解析失败仍算「已存在」，宁可停止升级也不覆盖运营手改。删除 `.seed-manifest.json`
   重新进入采纳模式，删除单个磁盘文件只强制重刷该文件。

## 理由与权衡

- **硬门放 `@Tool` 描述而非系统提示词**：工具 schema 每轮随请求下发且紧贴动作点，模型在
  决定调用 `query_structured_data` 的同一时刻读到约束；系统提示词经 `AGENTS.md` scaffold →
  harness 追加，链路长、易被配置覆盖（本次事故正是如此）。代价是工具描述变长、每轮都占 token，
  故只放「必须每次遵守的硬门」，示例与反模式留在按需加载的技能里。
- **常量瘦身 + 技能承载细节**：同一条规则只在一层出现，避免再次出现「yml / 常量 / SKILL.md
  三处各说一半」的漂移。代价是细节生效依赖模型真的调用 `load_skill_through_path`，
  因此常驻层 B 在工作流程第 3 步显式写了「先加载 sql-analysis 技能并严格按其步骤执行」。
- **判断某条规则该不该下沉的准绳**：它是否需要在「模型还没决定用哪个技能」时就生效——
  是则常驻，否则下沉。据此「不主动生成图表」必须常驻（`chart-rendering` 技能只在模型
  已决定出图后才会被加载，下沉等于门失效），而 matplotlib 的标签语言与字体写法可以下沉
  （写 matplotlib 代码前必然已加载 `python-analysis`）。守卫测试按同一准绳写：
  `DataAgentConfigTest#defaultSysPromptDoesNotRestateSkillLevelDetail` 断言常驻层不复述
  技能细节，`SharedSkillContentTest` 断言技能确实承载了这些细节。
- **resources 为唯一源**：出厂内容随构建产物走，jar 内外一致，且天然可被测试读取
  （`SharedSkillContentTest` 直接从 classpath 断言）。代价是运营不能再通过 git 提交修改
  根目录 `shared/`——改为改磁盘文件，由 seeder 的「手改优先」保证不被覆盖。
- **manifest 版本化而非每次强制覆盖**：既能修复 0 字节与漂移，又能保住运营手改和
  `MarketContributionService` 的审批产物；一次性采纳模式让老部署无需人工清理即可收敛。
  代价是多了一个需要解释的簿记文件与六种分支，用 `SharedWorkspaceSeederTest` 的 8 个用例
  把每条分支钉住。

## 被否决的替代方案

- **把全部细节塞进 `DEFAULT_AGENT_SYS_PROMPT`**：常驻 token 显著变长，且与 SKILL.md 重复，
  重复即漂移源。
- **只放技能、不动 `@Tool` 描述**：技能是按需加载的，模型不加载就等于没有硬门，
  ADR 0006 的问题会重演。
- **把「不主动生成图表」「全中文输出」也下沉到技能**：这两条门必须在模型做决定之前就在
  上下文里；下沉后 `chart-rendering` 只在已决定出图时才加载，门形同不存在。常驻层不是
  「越小越好」，而是「只放必须提前生效的门」。
- **删掉根目录 `shared/`，运行时只从 classpath 读**：`UserSandboxRegistry` 需要一个真实
  目录投影进容器，`LocalApprovalMarketplace` 需要可写落盘路径，两者都要求磁盘上的共享根。
- **仅用一次 git 提交把 0 字节文件改回来**：治标。双副本与「谁是源」的问题还在，
  下次仍会漂移，而且无法自愈其它部署的磁盘状态。
- **seeder 每次启动无条件覆盖出厂路径**：会摧毁运营手改与市场贡献产物。
- **把根目录 `shared/` 继续留在 git 里、用 CI 校验两份副本一致**：需要维护同步脚本与
  双向合并规则，成本高于「取消跟踪 + 单向物化」。

## 适用与失效条件

适用于当前 harness 行为：`@Tool` 描述随每轮请求原样下发、技能按需加载、
`workspace/AGENTS.md` 在首次 scaffold 时生成。若 harness 改为把技能全文常驻上下文，
或开始裁剪/摘要工具描述，分层取舍需重新评估；`AGENTS.md` 已存在的部署不会因常量变更而
自动刷新（scaffold 只写一次），需要用 `DATAAGENT_AGENT_SYS_PROMPT` 或删除该文件重建。
多副本部署若把 `${cwd}/shared/` 挂到共享卷，manifest 的并发写需要额外加锁，当前按单实例假设。
