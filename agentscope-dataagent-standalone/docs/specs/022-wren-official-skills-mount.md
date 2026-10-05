# spec 022: 建模知识上移 wren 官方 skills——原生技能仓库挂载与 MODELING_SCRIPT 协议层瘦身

## 背景与目标

ADR 0035 已拍板：建模领域知识（流程、YAML 结构、Cube 模板、校验清单）的单一事实源是 wren 官方 skills（generate-mdl / enrich-context / usage / onboarding），随 wrenai pip 包版本演进，平台零维护。本 spec 落地其工程部分：把官方 skills 经 harness 原生技能仓库挂到 modeling-agent（与 Claude Code 消费 SKILL.md 同构），同步把 `MODELING_SCRIPT` 瘦身为纯协议层、修正三个失真的工具描述。受益者：建模 agent（指引可靠入上下文，消除本次 cube 缺 type 死锁的取用单点故障）与平台维护者（官方升级即免费获得剧本更新）。

## 方案概述

1. **技能目录解析（新类 `dataset/WrenSkillsLocator`）**：输入 `WrenProperties`，产出官方 skills 目录 `Optional<Path>`。优先级：显式配置 `dataagent.wren.skills-dir` > 从 `resolveExecutable()` 路径推断。推断候选（命中首个含 SKILL.md 子目录的）：`<exeDir>/site-packages/wren/skills_content`（Windows user site）、`<exeDir>/Lib/site-packages/wren/skills_content`（Windows venv）、`<exeDir>/../lib/python3.*/site-packages/wren/skills_content`（Unix glob）、`<exeDir>/../lib/site-packages/wren/skills_content`（Unix venv）。目录必须存在且含至少一个 `SKILL.md`，否则 empty。
2. **装配挂载（`DataAgentConfig`）**：modeling-agent 的 `configureAgent` 块中，locator 命中则 `b.skillRepository(new FileSystemSkillRepository(dir))` + `b.skillFilter(SkillFilter.only("generate-mdl", "enrich-context", "usage", "onboarding"))`（dlt-connector 是 SaaS 接入技能不放）；未命中仅 log.warn 跳过，应用照常启动（fail-soft，退回 `wren_skills_get` 通道）。
3. **`MODELING_SCRIPT` 瘦身**：删除建模流程五拍细节、资产落点格式指导（DISTINCT_COUNT/CASE 口径示例、cubes/views/knowledge 路由表）；保留并只保留协议层：角色与发布出口、硬约束（只增不改 / 写工具纪律 / 列名不猜 / 播种约定 models/ 只追加不重建 / HITL / specs/021 汇报纪律）、官方剧本指引（改为「建模流程与字段写法先 load_skill_through_path 加载 generate-mdl / enrich-context / usage 技能」）、validate_mdl 收尾节奏、简体中文。
4. **工具描述对齐真实行为（`ModelingToolkit`）**：`wren_skills_list` 补各技能一句话用途；`wren_skills_get` 明确「references 参考文档用 full=true，script 仅用于可执行脚本」；`wren_context_instructions` 改为「输出 knowledge/rules 业务规则，非工程编辑指引」。

涉及主流程变更：同步 `ARCHITECTURE_zh.md` 5.6/4.6 建模装配与技能段、README 配置表新增 `dataagent.wren.skills-dir`。

## 影响面

- 后端：新 `dataset/WrenSkillsLocator`；`WrenProperties` 加 `skillsDir` 配置项与访问器；`DataAgentConfig` modeling-agent 装配块 + `MODELING_SCRIPT` 常量；`ModelingToolkit` 三个 `@Tool` 描述。
- 前端：无。
- 数据：无。
- 文档：ARCHITECTURE_zh.md（建模 agent 装配与技能段）、README 配置表。

## 验收标准（Given-When-Then，可测试）

1. Given wrenai 已安装（skills_content 存在），When 应用启动，Then 启动日志出现技能目录挂载 info，modeling-agent 的 system prompt 含 generate-mdl / enrich-context / usage / onboarding 四技能的 available_skills 条目。
2. Given `dataagent.wren.skills-dir` 显式配置，When locator 解析，Then 使用显式路径（存在性校验失败时 warn 并回落推断）。
3. Given wrenai 未安装或目录不含 SKILL.md，When 应用启动，Then 仅 warn 不抛异常，modeling-agent 正常构建，`wren_skills_get` 仍可用。
4. Given 瘦身后的 `MODELING_SCRIPT`，When 守卫测试运行，Then 含播种约定/HITL/技能指引/中文/validate_mdl 断言，且不含 DISTINCT_COUNT、资产落点格式指导与 retired 工具名。
5. Given `WrenSkillsLocator`，When 单测构造各平台伪路径结构，Then 命中对应候选规则；无候选命中时返回 empty。
6. Given 三个工具的 `@Tool` 描述，When 守卫测试运行，Then `wren_skills_get` 描述含「full=true」与「script 仅用于」表述、`wren_context_instructions` 描述含「业务规则」且不含「权威指引」。

## 不做的事（明确排除项）

- 不翻译、不复制、不改写官方 SKILL.md 内容到平台仓库（英文透传，双事实源禁止）。
- 不给 data-agent（问数）挂技能仓库；不动 `wren_skills_get`/`wren_skills_list` 的 CLI 参数行为（只改描述文案）。
- 不实现 GitSkillRepository/远端技能源；不做技能热更新（跟随应用重启）。
- 不处理官方技能与平台协议的新冲突（HITL/租户已由协议层与 middleware 强制，与剧本文字无关）。

## 测试要求

- 新建 `WrenSkillsLocatorTest`：显式配置优先且存在性校验、Windows user-site 推断、Windows venv 推断、Unix venv 推断、无命中返回 empty、目录无 SKILL.md 返回 empty（`@TempDir` 造结构）。
- `ModelingToolkitTest#modelingScriptGuidesTheYamlFirstFlow` 更新：删除 DISTINCT_COUNT/wren_context_instructions 断言，新增播种约定、技能名（generate-mdl/enrich-context）、load_skill_through_path 断言；retired 工具断言保留。
- 新增描述守卫断言（并入 ModelingToolkitTest 或新建小测试类）：三个工具描述文案关键 token。

## 关联

- ADR：docs/adr/0035-modeling-knowledge-single-source-wren-skills.md
- 前序：specs/019（YAML-first）、specs/021（任务行化汇报纪律）
