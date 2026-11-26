# 当前任务 · 合并 liyufan_dev 工作台改版与口径页交互重设计

2026-11-26，分支 develop-2.0（合并未提交）。合并前的两条线：[口径资产 P0–P7](archive/CURRENT_TASK-caliber-p7-20261126.md)（已提交 0af2f85a）、[liyufan_dev 知识库工作台改版](archive/CURRENT_TASK-liyufan-workspace-20261126.md)（8d6ab688）。

## 合并

- 合并 origin/liyufan_dev（3 个提交，前端工作台、问数优化单题会话、资产验证快照）。冲突按"两边都保留"解决：`WrenProjectAccess.Project` 同时含口径轮/对话轮/口径库标志与 `questionId`；路径与提问限制两套都生效；口径页签、自动起草与草稿栏口径提示并入新工作台。
- 静态资源重新构建；spec 编号重复：本分支 spec149（口径资产）与 liyufan_dev 的 spec149（对话建模工作台）并存，索引均保留。

## 口径页重设计（待确认）

- 现状：口径页把表说明、逐行核查、问题验证、判定挤在一张表里。用户要求参照主流产品先出原型，再定方案。

## 待办

- 66 按新 requirements 重装 Wren 并回归；受限事实表 `query_cube` 时间维度门禁；键值类表指标名由平台补全；口径页"重新统计取值"按钮。
- 会员表默认指标"无，先问"时助手未先问（第一次违反，暂记）。
- 本机启动注意：JVM 需 `-Djdk.net.unixdomain.tmpdir=E:/jdk-tmp -Dfile.encoding=UTF-8`。
