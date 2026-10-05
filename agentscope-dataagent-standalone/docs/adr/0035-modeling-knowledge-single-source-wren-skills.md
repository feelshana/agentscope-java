# ADR 0035: 建模知识单一事实源上移 wren 官方 skills —— 干净 HarnessAgent 复用官方指引

- 状态：已采纳
- 日期：2026-10-04
- 证据：`logs/LLM-modeling.log`（Cube「报表访问分析/点击详情分析」试跑反复报 `Invalid manifest JSON: missing field 'type' at line 331 column 9`，agent 三次获取官方指引失败后凭记忆写出缺 type 的 Cube，L5010–L5480）；本机实测 `wren skills get enrich-context --full` 一次返回 46726 字符（SKILL.md + `cube_proposals` 参考 + `gap_catalog` 参考，其中 Cube YAML 模板带 `type`、`time_dimensions` 复数数组，与黄金组成功发布格式一致）；`wren/skills_content/`（generate-mdl 11KB、enrich-context 27KB+references 19KB、usage 16KB、onboarding 9KB）；agentscope 框架源码 `SkillToolFactory`（`load_skill_through_path` 工具，支持 `SKILL.md` 与 `references/*` 资源及磁盘回退）、`HarnessAgentBuilderSupport#composeSkillRepositories`（四层 skill 仓库装配）、`AgentSkillPromptProvider`（常驻注入技能名+描述）
- 关系：延续 ADR 0033（YAML-first，官方 CLI 为确定性校验器）与 ADR 0031（原生 HITL）；修正 ADR 0033 落地时把建模流程写进 `MODELING_SCRIPT` 的做法；兑现 ADR 0007 的分层准绳并吸取 ADR 0008「技能没被加载」教训

## 背景

一次真实建模会话因 Cube YAML 缺 `type` 字段陷入全链路死锁（validate/dry-plan/cube 试跑/View 落盘全部连坐失败），agent 把根因误诊为「wren-core 的 DATE 序列化缺陷」。独立取证还原的因果链是：

1. **官方指引自足但没被拿到。** 官方 `enrich-context` 技能的 `cube_proposals` 参考文档包含完整的 Cube YAML 模板（成员带 `type`、`time_dimensions` 复数、查重守卫、validate→cube query 两级校验流程），与平台黄金组成功发布的格式完全一致。agent 拿到了 `enrich-context` 主文档（`wren_skills_get enrich-context`），但取参考文档时用了 `--script references/cube_proposals`——CLI 的 `--script` 只认 `scripts/` 目录下的可执行脚本，references 文档必须用 `--full`。失败后 agent 未换参数重试，转投 harness 原生 `load_skill_through_path`（wren CLI 内置技能不在 harness 技能注册表里，两套体系无桥接）再次失败，于是「回忆记忆中已验证过的 Cube 结构」凭记忆写——记忆里的格式恰好是 WrenAI 云端格式，本地 wren-core serde 不认。
2. **平台工具描述失真放大了误导。** `ModelingToolkit` 的 `wren_context_instructions` 描述承诺「wren 对『如何编辑本工程』的权威指引（目录布局、字段约定、校验要求）」，而 CLI 该命令的真实行为是打印 `knowledge/rules/` 业务规则；`MODELING_SCRIPT` 硬约束第 4 条还把它定为「字段写法不确定时的首选」。agent 对工具的信任完全建立在描述上，假承诺把它引向了不可能有答案的地方。
3. **官方体系内部也有裂缝。** `generate-mdl` 剧本只覆盖 models/views/relationships，Cube 模板唯一在 `enrich-context` 的 references 里；agent 即使照官方主剧本走，到 Cube 环节也会断链。

同时确认的框架能力事实（读 agentscope-core / agentscope-harness 源码）：

- core 的 `SkillToolFactory` 生成 `load_skill_through_path` 工具：按 `skillId` + 资源路径（`SKILL.md` 或 `references/xxx.md`）返回技能内容，含磁盘回退；`AgentSkillPromptProvider` 把每个技能的「名字 + 描述」常驻注入 system prompt，全文按需加载。
- harness 的 `HarnessAgentBuilderSupport#composeSkillRepositories` 按四层装配技能仓库：project-global skills 目录 → builder 级 `skillRepositories(...)`（用户/装配层提供）→ workspace `skills/` 目录 → 按用户的 `WorkspaceSkillRepository`；`SkillFilter.only(...)` 可做白名单。
- 平台已有声明式载体 `SkillRepositoryConfigEntry` + `SkillRepositorySupport`（agentscope.json 消费），且 `DataAgentConfig` 对 modeling-agent 走编程式 `configureAgent` 装配，具备直接挂仓库的条件。

这与 Claude Code 消费 SKILL.md 的机制同构。也就是说：**官方 wren skills 本来就是为「任何 agent」设计的标准投递物，harness 原生就能消费**——平台只需要把两者接上，而不需要自己维护一份建模剧本。

## 决策

1. **建模领域知识的单一事实源 = wren 官方 skills，平台零维护。** 建模流程（generate-mdl 的 Phase 序列）、YAML 结构约定、Cube 模板（enrich-context `cube_proposals`）、CLI 用法与排错（usage）、工程初始化（onboarding）全部以官方 SKILL.md + references 为准，随 wrenai pip 包版本演进。`MODELING_SCRIPT` 中与之重复的建模流程、资产落点格式指导全部删除。
2. **官方 skills 经 harness 原生技能仓库挂载到 modeling-agent。** 装配期在 `DataAgentConfig` 的 modeling-agent `configureAgent` 块中，用 `FileSystemSkillRepository` 显式注册 wren skills 目录（builder 级 Layer 2），并以 `SkillFilter` 白名单只放建模相关技能（generate-mdl、enrich-context、usage、onboarding；`dlt-connector` 是 SaaS 接入技能，不放）。agent 通过常驻的 available_skills 清单感知技能存在，经 `load_skill_through_path` 按需取全文与 references——机制与 Claude Code 同构，不再依赖 agent 记得调 `wren_skills_get` 或用对参数。
3. **技能目录来源：从已安装 wrenai 包的 `skills_content` 目录解析，fail-soft 降级。** 启动装配时按 `WrenProperties` 解析的可执行文件定位 pip 包内 `skills_content`（保证技能版本与本地 wren-core 行为严格一致）；解析失败仅记 warn 并跳过注册，建模 agent 退回 `wren_skills_get` 通道（描述已修正，见决策 5），应用照常启动。
4. **`MODELING_SCRIPT` 瘦身为纯协议层。** 保留且只保留平台特有协议：HITL 卡片纪律（写操作被暂停，采用/修改/拒绝）、写工具纪律（只经 write_file/patch_file 落文件、改前先 read_file）、列名不猜（只引用 list_modeling_state/read_file 实际存在的列）、播种约定（`models/` 由平台播种，只追加不重建——官方 generate-mdl 的 schema 探查 Phase 在平台不适用）、发布出口（引导用户到「语义建模」页，对话内不发布）、资产描述与输出用简体中文、过程汇报纪律（specs/021）。官方优先条款改为：建模流程与字段写法不确定时先加载 `generate-mdl` / `enrich-context` 技能照做。
5. **工具描述与真实行为对齐（治本配套）。** `wren_skills_get` 描述明确「references 参考文档用 full=true，script 仅用于可执行脚本」；`wren_context_instructions` 描述改为真实行为（输出 knowledge/rules 业务规则，供问数与 enrich 流程消费，不是工程编辑指引）；`wren_skills_list` 描述补充各官方技能的一句话用途，让模型在首次选择时就能选对。

## 理由与权衡

- **为什么「干净的 HarnessAgent」成立**：官方 skills 是标准 SKILL.md 格式（frontmatter name/description），harness 技能机制与之同构；挂载后 available_skills 常驻只占数百 token（四条名字+描述），全文按需加载，完全符合 ADR 0007 的「门常驻、手册按需」准绳。建模能力的正确性从「平台复述官方知识」变成「直接执行官方知识」，wrenai 升级即免费获得剧本更新，无同步成本。
- **协议层为什么不能删**：官方剧本假设 agent 能直接探查数据库、直接写文件、没有发布闸门。平台的播种约定、HITL 确认、租户边界、发布出口是官方不知道的，必须常驻。本次事故的教训不是「协议层多余」，而是「协议层越权复述了建模知识」——瘦身是把归属还给事实源。
- **英文透传的取舍**：官方 SKILL.md 是英文，不翻译不改写（改写即制造双事实源，ADR 0007 的教训）。协议层约束的是平台产出（资产文件内容、对话输出）用简体中文；流程知识的语言与正确性相比是次要矛盾。
- **为什么不用 GitSkillRepository 直指 GitHub**：技能版本必须与本地 wren-core 的 serde 行为匹配（本次「云端格式不被本地接受」正是版本错配的写照）；运行时拉外网还引入部署依赖。本地包内解析是唯一能保证「剧本与引擎同版本」的来源。
- **为什么不是只修工具描述**：描述修正能消除误导，但「模型是否调用、是否用对参数」仍是单点故障——ADR 0008 已实证过技能没被加载时门形同虚设。原生挂载让官方指引进入 available_skills 常驻清单，模型在规划阶段就能看到它，取用路径从「记得调对 CLI」降为「按 harness 通用机制加载」。
- **代价**：装配层多一段包路径解析与 fail-soft 逻辑；官方技能全文较大（enrich-context 全套约 46KB），依赖模型按需加载而非一次全读——这正是 harness 技能机制的常规工作方式（ADR 0007 分层的既定代价）；协议层与官方剧本的边界需要用守卫测试钉住（协议层断言不含 YAML 格式指导，技能断言确实承载）。

## 被否决的替代方案

- **只修 `@Tool` 描述与 `MODELING_SCRIPT` 文案**：消除本次的直接误导，但取用通道的单点故障仍在；agent 忘调、调错参数时同样坠入凭记忆的深渊（本次 L5451 的 `--script` 误用即实例）。
- **装配期预取官方模板全文内联 system prompt**：常驻 token 暴涨（官方全套 46KB+），且与按需层彻底重复——重复即漂移源，等价于取消 ADR 0007 分层。
- **把官方 skills 翻译/改写成平台中文版再播种**：制造第二事实源，官方升级即漂移；翻译质量还引入新的正确性风险。
- **`GitSkillRepository` 直指 WrenAI GitHub 仓库**：技能与本地引擎版本可能错配（本事故的格式冲突恰是版本间差异），且运行时依赖外网。
- **维持现状，仅在报错信息里提示「参考官方 skill」**：治标。报错发生在错误已经写盘之后，且本次 agent 在反复报错中并没有自行恢复到官方剧本。

## 适用与失效条件

依赖三个当前事实：wrenai pip 包携带 `skills_content` 目录且结构为「技能目录 + SKILL.md + references/ + scripts/」；harness 技能装配 API（`skillRepositories` / `SkillFilter` / `load_skill_through_path`）行为稳定；官方 SKILL.md frontmatter 可被 `MarkdownSkillParser` 解析。若 wrenai 未来改变技能投递结构（如内置进引擎或改为服务端下发），决策 3 的解析路径需随版本更新，降级路径（wren_skills_get 通道）兜底；若 harness 裁剪技能机制，需回退到预取内联方案并重新评估常驻成本。官方剧本与平台协议出现新的冲突点（如官方建议的行为与 HITL、租户隔离矛盾）时，按 ADR 0007 准绳裁决：需要提前生效的写进协议层，其余信任官方剧本并在此 ADR 追加记录。
