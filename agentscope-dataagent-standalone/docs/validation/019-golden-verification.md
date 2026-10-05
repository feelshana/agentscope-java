# specs/019 YAML-first 语义建模黄金用例 E2E 验证记录

> 执行日期：2026-10-04。对应 specs/019 验收标准 #1（黄金用例）、#2/#3/#4/#8（单测覆盖，见 ModelingToolkitTest / SemanticModelingControllerTest）、#6/#7（本记录 E2E 走查）。

## 1. 数据与口径（黄金基准）

本地 MySQL `127.0.0.1:3306/test_data`：

- `customers`（3 行）：customer_id 1 张三（is_internal=0）、2 XX科技（0）、999 测试账号A（**1 内部**）
- `orders`（5 行）：1001（customer 1，2025-01，99.00，已支付）、1002（customer 2，2025-02，2990.00，已支付）、1003（customer **999**，2025-02，10.00，已支付）、1004（已取消）、1005（已退款）

口径来源：`docs/order.md`（有效订单 = order_status=1 且 is_deleted=0 且 customers.is_internal=0）。

独立 SQL 手工对账基准（直连 MySQL，不经平台）：

| 指标 | 基准 |
| --- | --- |
| total_valid_revenue | 3089.00（99 + 2990） |
| paid_customer_count | 2 |
| 月度营收（2025-01/02/03） | 99.00 / 2990.00 / 0 |

## 2. E2E 链路执行记录

服务以最新构建 jar 启动（8082），`dataagent.wren.executable` 指向 venv wren.exe（wrenai 0.15.0）。

1. 登录 → 建知识库 m6-gold2 → 注册外部 MySQL 数据源（test_data）→ `POST /api/dataset-groups/{id}/associate` 关联 orders/customers 两表 → **自动播种 + 基线发布**（ associates 响应含列级中文描述，mdlState=DIRTY v1）。
2. 上传 `docs/order.md` 知识文档（PUT /knowledge）。
3. 建模对话 `POST /api/agents/modeling-agent/chat/stream`（sessionKey=`modeling-{groupId}`），探针解析 SSE 流，对每张 `hitl_request` 卡自动 `POST /chat/confirm`（confirmed=true，不传 toolInput）。第一轮 6 轮确认，第二轮纠错迭代 5 轮确认，共 11 张卡全部走现有 HITL 通道。

### 第一轮建模（官方五拍完整走通）

| 轮 | 卡 | 结果 |
| --- | --- | --- |
| 1 | decide_relation CONFIRM（orders.customer_id → customers.customer_id MANY_TO_ONE） | 候选确认，写 relationships 候选队列 |
| 2 | write_file cubes/order_stats/metadata.yml（v1） | **闸②拒绝**：relationships.yml 仍为空列表 `[]`，`context validate --strict` 对工程副本失败，真实工作区零触碰 |
| 3 | write_file relationships.yml（agent 自查后修复结构） | 预检通过写入 |
| 4 | write_file cubes/order_stats/metadata.yml（v2） | 预检通过写入 |
| 5 | write_file knowledge/rules/general.md（枚举/默认过滤/同义词，45 行） | 预检通过写入 |
| — | validate_mdl（agent 主动收尾） | 「MDL 校验结果：通过」 |

发布 `POST /mdl/publish`：ok=true，PUBLISHED v2（reconcile → staging → validate --strict → build 2 models → 逐视图 dry-run（无视图）→ 逐 cube `cube query --sql-only` → 原子快照）。

### 首次问数对账（暴露口径缺口）

data-agent 自动调用 `wren_query_cube`（确定性 SQL 转译，不经 LLM 规则理解）：

| 指标 | 实测 | 基准 | 差异 |
| --- | --- | --- | --- |
| total_valid_revenue | 3099.0 | 3089.00 | +10（内部订单 1003 未排除） |
| paid_customer_count | 3 | 2 | +1（内部账号 999） |
| 月度 | 99 / 3000 / 0 | 99 / 2990 / 0 | 2 月差 10 |

根因：cube 度量只固化了单表条件（order_status/is_deleted），内部客户排除留在 knowledge/rules 默认过滤——**cube 确定性查询不读规则**，跨表口径必须固化进语义层。

### 第二轮纠错迭代（对账驱动，官方五拍第二圈）

| 轮 | 卡 | 结果 |
| --- | --- | --- |
| 1 | patch_file models/orders（+customers 对象列 + customers_is_internal 投影列，relationship 写在 properties 内） | 写入后 **wren_dry_plan 拦截**：`invalid relationship chain`，agent 读文件+查 usage 剧本自查 |
| 2 | patch_file models/orders（relationship 提为顶层字段） | dry-plan 通过，投影列正确展开进 WITH 子句 |
| 3 | patch_file cubes/order_stats（两度量 CASE 补 `customers_is_internal = 0`） | 预检通过 |
| 4 | patch_file cubes/order_stats（description 同步「已固化」） | 预检通过 |
| 5 | patch_file knowledge/rules/general.md（默认过滤改为「已由 cube 度量固化」） | 预检通过 → validate_mdl 通过 → `wren_cube_query --sql-only` 编译出修正后 SQL |

重新发布：PUBLISHED v3。

### 终局问数对账（全部命中）

| 指标 | 问数实测 | 基准 | 结论 |
| --- | --- | --- | --- |
| total_valid_revenue | **3089.0** | 3089.00 | 一致 |
| paid_customer_count | **2** | 2 | 一致 |
| 月度营收 | **99.0 / 2990.0 / 0.0** | 99 / 2990 / 0 | 一致 |

问数过程 data-agent 另行调用 render_chart 输出月度趋势图（ECharts）。

### M5 preview 端点补充验证（POST /modeling/workspace/preview）

- patch 缺失锚点 → `ok=false`，error「original 在文件中不存在」，同时返回 oldContent（卡片可展示现状）；
- write_file 坏 YAML → `ok=false`，error「error: YAML 解析失败」（闸①快速失败）；
- 合法 patch → `ok=true`，oldContent/newContent 双内容返回（diff 数据源），工作区不落盘；
- GET /modeling/workspace/file?path=relationships.yml → 返回 172 字符文件内容。

## 3. 验收标准对照

| # | 标准 | 结论 |
| --- | --- | --- |
| 1 | 空组 + orders/customers 上传 → 播种 → agent 按 order.md 推进逐卡确认 → 发布 → 黄金值一致 | ✅（本记录 §2） |
| 2 | 未确认不落盘 / 确认后写入且 validate 通过 | ✅（ ModelingToolkitTest 三重闸用例 + §2 闸②拒绝/通过时间线） |
| 3 | 非法 YAML / 越界路径拒绝且文件不变 | ✅（单测 + preview 端点复验） |
| 4 | 坏 cube 表达式被预检或发布拦截 | ✅（MdlPublishServiceTest + 发布链 cube --sql-only） |
| 5 | 新数据集对账不重写既有文件 | ✅（MdlSeederTest append-only） |
| 6 | 确认关系 → relationships.yml → 发布后跨表 JOIN | ✅（customers_is_internal 投影列跨表排除生效，3099→3089） |
| 7 | knowledge/rules 注入 [KNOWLEDGE_BASE_OVERVIEW] | ✅（M4 单测 readKnowledgeRules + 问数会话知识注入） |
| 8 | 他组路径租户拒绝 | ✅（SemanticModelingControllerTest foreignGroup 404 全端点） |

## 4. 复跑要点

- wren CLI（Windows）：`pip install 'wrenai[mysql,mcp]' 'mcp<2'` 后将 `dataagent.wren.executable` 指向 venv `wren.exe` 绝对路径（`DATAAGENT_WREN_EXECUTABLE` 经 Spring relaxed binding 从环境变量注入；PowerShell 会话内 `$env:` 赋值启动 java 时若经工具层传输可能失效，建议直接使用命令行参数 `--dataagent.wren.executable=...`）。
- 工作区产物：`~/.agentscope/dataagent/mdl/<groupId>/workspace/`（官方 schema_version 5 布局）；发布快照 `published/`。
- 黄金证据 trace（模型时间线全文）：会话 target/ 下 m6-modeling-trace.txt、m6-fix-trace.txt、m6-query-trace.txt、m6-query2-trace.txt（构建产物目录，不入库）。
