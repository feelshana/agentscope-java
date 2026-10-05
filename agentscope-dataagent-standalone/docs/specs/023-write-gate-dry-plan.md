# 023 - write_file 预检追加全工程 dry-plan 门（specs/022 后续）

## 背景

2026-10-04 建模事故复盘（bob 会话，知识库「本地文件」）：modeling-agent 凭记忆写出的两个 Cube
成员全部缺 `type` 字段，但 write_file 预检（gate① YAML 解析 + gate② `context validate --strict`）
双双放行；直到写 View 时预检才在 dry-plan 阶段报 `missing field 'type'`，agent 把红灯误诊为
「wren-core DATE 缺陷 / UNION ALL 限制」宣告环境死锁，错误结论又经 memory_save 固化进长期记忆。

实证根因：wren 官方 CLI 的 `context validate --strict` 不校验 Cube 成员必填字段（本机实测缺
type 时 exit 0 "Valid"，摘要只数 models/views/relationships）；`missing field 'type'` 这类
wren-core serde 错误只有 dry-plan（planner 严格反序列化 target/mdl.json）才暴露。平台预检的
覆盖面因此与发布链（local → validate → build → dryRun）不等价。

## 方案

`ModelingToolkit#validateProposal`（write_file / patch_file / HITL previewChange 共用的预检）
在 gate② 之后追加 gate③，全部对 scratch 工程副本执行：

1. `context build` —— 生成 scratch 副本的 `target/mdl.json`（dry-plan 的 `_require_mdl` 依赖它）。
2. `dry-plan --sql "SELECT 1" -d <dataSource>` —— 强制 planner 严格反序列化 manifest；
   缺 `type`、缺 `join_type` 等仅 serde 可见的错误在此暴露。

dry-plan 失败时的错误信息附引导语（面向 LLM，简体中文）：此类 serde 报错通常意味着某个
Cube/关系成员缺必填字段（如 `type`），应加载官方 enrich-context 技能对照 `references/cube_proposals`
模板修复——用真实红灯打断「环境缺陷」误诊循环。

错误信息按门区分来源：`context validate --strict` / `context build` / `dry-plan 物化预检`，
失败即拒写，workspace 不动，scratch 清理语义不变。

## 影响面

- `tools/data/ModelingToolkit.java`：`validateProposal` 追加 gate③；Javadoc 同步。
- `web/api/SemanticModelingController.java`：注释「gates ①+②」同步为「gates ①②③」（逻辑复用 previewChange，零改动）。
- `tools/data/ModelingToolkitTest.java`：FakeWrenCli 增加 `failDryPlan` 与 dry-plan 分支；既有成功用例补 gate③ 调用断言；新增「dry-plan 拦截缺 type 类错误」用例。
- 每次文件写入多两个子进程调用（build + dry-plan，工程为秒级），正确性优先。

## 验收（GWT）

- Given scratch 副本 dry-plan 报 `missing field 'type'`，When write_file，Then 拒写、workspace
  内容不变、错误含「工程校验未通过」与引导语、scratch 目录被清理。
- Given dry-plan 通过，When write_file，Then 依次执行 validate --strict → context build →
  dry-plan 后写入成功。
- Given `context build` 失败，When write_file，Then 拒写且错误信息标明 build 门。

## 排除项

- 不改 `MdlPublishService` 发布链（其 validate 已含 build + dryRunViews + dryRunCubes，覆盖面足够）。
- 不实现平台侧 YAML 成员字段校验（平台不复制 wren-core 的 serde 规则，避免双事实源——ADR 0035 精神）。
- 不动 agent 记忆管线（本轮已手工清理 workspace-modeling-agent 两个用户的记忆与 consolidation 状态）。

## 测试要求

`ModelingToolkitTest` 新增 dry-plan 门用例；`mvn test` 全绿；`mvn package -DskipTests` 通过。
