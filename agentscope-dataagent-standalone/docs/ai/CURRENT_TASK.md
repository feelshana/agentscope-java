# 当前任务 · 合并 liyufan_dev 工作台改版与口径页交互重设计

2026-11-26，分支 develop-2.0（合并已提交 54d6a425，未推送）。合并前的两条线：[口径资产 P0–P7](archive/CURRENT_TASK-caliber-p7-20261126.md)（已提交 0af2f85a）、[liyufan_dev 知识库工作台改版](archive/CURRENT_TASK-liyufan-workspace-20261126.md)（8d6ab688）。

## 合并

- 合并 origin/liyufan_dev（3 个提交，前端工作台、问数优化单题会话、资产验证快照）。冲突按"两边都保留"解决：`WrenProjectAccess.Project` 同时含口径轮/对话轮/口径库标志与 `questionId`；路径与提问限制两套都生效；口径页签、自动起草与草稿栏口径提示并入新工作台。
- 静态资源重新构建；spec 编号重复：本分支 spec149（口径资产）与 liyufan_dev 的 spec149（对话建模工作台）并存，索引均保留。

## 口径页重设计（spec153 已确认，P1 本地完成）

- 现状：口径页把表说明、逐行核查、问题验证、判定挤在一张表里。已出 4 张原型（总览、逐条核对、问题验证、发布检查）。
- [spec153](../specs/153-caliber-review-workbench.md)：按"起草 → 核对 → 验证 → 发布"拆页；验证不执行查询（修正原型中的结果数值）；P1 前端拆页与发布抽屉，P2 后端补原文片段、发布差异、条件对照、判定说明。P1 已实现：`CaliberOverview`/`CaliberReview`/`CaliberVerify`/`CaliberPublishDrawer`/`CaliberStepper`，发布数据带口径阻断；前端测试 186×3、tsc、构建通过，浏览器看过总览、核对、验证（全部通过的表）与抽屉。未验：有待核对/待验证行的表上的点击流程（核查与判定须用户本人点）。
- P1.5：实测发现“直接编辑”跳到只读工程页，改为核对页表单编辑（`CaliberRowForm` + `CaliberService#edit`，多取值须人勾选合并统计）；验证完成后的操作区改为结论 + 重新判定 + 去发布。
- 合并遗留已补：草稿栏口径未完成显示阻断并跳到发布检查。

## 待办

- 66 按新 requirements 重装 Wren 并回归；受限事实表 `query_cube` 时间维度门禁；键值类表指标名由平台补全；口径页"重新统计取值"按钮。
- 会员表默认指标"无，先问"时助手未先问（第一次违反，暂记）。
- 本机启动注意：JVM 需 `-Djdk.net.unixdomain.tmpdir=E:/jdk-tmp -Dfile.encoding=UTF-8`。

## 2026-11-26 · 数据同步源表筛选

在 develop-2.0 的 [spec148](../specs/148-configurable-table-sync.md) 增加按日/月的源表等值筛选。目标列由勾选决定，旧任务缺省保持原读取条件。隔离 MySQL 5.7→8.4、浏览器和完整构建已通过。66 由用户部署，升级前核对主线已有的 0156 迁移是否已执行，再用新目标表试查和试同步。

## 2026-11-27 · BI 入口等待画面

`codex/architecture-phase1` 已实现并本地联调。模拟 BI 图标打开新页后，免登入口和会话页共用首屏提示。浏览器已验证暂停 JS/CSS 下载时的实际绘制、真实账号免登、消息提示与重试。体验入口为 `http://localhost:18766/bi-sim/`。当前为本地修改，尚未提交或部署。

## 2026-11-28 · DataAgent 分析与模型视觉稿

已交付静态评审稿 [经营分析](../design/finesse-dataagent-analysis.png) 和 [数据模型](../design/finesse-dataagent-model.png)。指标与柱高由曜境零售 CSV 重算，模型依据对应样例业务规则。已检查静态画面、数据和文字对比度。等待视觉确认，再确定交互原型与现有路由的接入范围。
