# 本机服务实测：语义建模与问数

沙箱就绪后的续测已完成，最新结果见 [2026-10-07 问数与 Python 实测](2026-10-07-sandbox-e2e-verification.md)：12 题完成，11 题符合原基准，1 题口径歧义；明确口径后补测通过，顺序执行的 Python 和产物下载通过。下文保留首轮环境阻断情况。

测试日期：2026-10-06 至 2026-10-07（跨午夜）。服务：http://localhost:8080。仅新增隔离测试知识库、测试会话与验证产物，没有修改业务代码或现有业务数据。

## 结论

语义建模、HITL、MDL 校验及发布已经真实跑通；真实发布模型经 Wren SDK 连接同一 MySQL 的 7 项固定逻辑 SQL 检查全部通过。对话问数的 12 道题在进入工具调用之前被 Docker 沙箱环境阻断，因此不能据此评价 Text2SQL 准确率，也不能声称 Python 分析已经跑通。这里的 SDK 检查是诊断实验，没有向生产系统增加物理 SQL 回退。

测试知识库：`wren-gap-live-20261006-233059`，ID：`73b26360-df5c-497e-9137-bdfe7f50df67`。所属测试账号：bob。测试资产保留，便于复核；发布版本为 v8。

## 已执行的服务验证

| 项目 | 结果 | 证据/解释 |
|---|---|---|
| 服务健康与登录 | PASS | health 返回 UP；bob/alice 登录成功 |
| CSV 上传与知识文档 | PASS | 7 张表，10042 行；业务口径文档成功上传 |
| 建模对话和写入确认 | PASS | 真实 SSE 对话；7 次测试确认，只针对本测试知识库 |
| 模型生成及发布 | PASS | 7 个模型、2 个 View、2 个 Cube；校验 ok=true，发布 v8 |
| 关系确认与发布一致性 | FAIL | 数据库有 2 条 CONFIRMED；YAML 与发布可视化模型只有 order_customer 1 条 |
| MDL 可视化数据 API | PASS | 实际读取 mdl/view；未进行浏览器页面渲染验收 |
| 固定 SQL 业务数值 | PASS 7/7 | 实际发布快照 + Wren SDK + MySQL；不是 LLM 问数测试 |
| 跨用户读取隔离 | PASS 3/3 | alice 读取 bob 测试知识库、MDL、工作区文件均返回 404 |
| 无效 YAML / 路径越界预览 | PASS | 拒绝；预览不改变工作区 |
| 平台文件的预览权限一致性 | FAIL | 有效 wren_project.yml 的原文预览返回 ok=true；没有实际写入平台文件 |
| 编码导入保真 | FAIL | CSV 中 000123 推断为 BIGINT，数据集预览已变为 123 |
| 对话问数 | BLOCKED 12/12 | Docker Linux 引擎不可用，工具调用尚未发生 |
| Python 分析链路 | 未验证成功 | 依赖同一 Docker 沙箱，不能以固定 SQL 结果代替 Python 验收 |

固定 SQL 的预期与实际一致：有效营收 1050 元、6 笔订单/2 位有效客户、客户营收李四 700 元/张三 350 元、净营收 950 元、10005 个事件合计 100.05 元、2 个孤儿客户引用、商品编码 P2 重复 2 次。数值比较使用 Decimal 容差，避免将 MySQL DECIMAL 与 Python float 的表示差异误判为业务错误。

## 运行中发现的问题与优化顺序

1. **先恢复问数沙箱，并增加就绪检查。** 实际错误为 `docker run failed (exit=127)`，找不到 `//./pipe/dockerDesktopLinuxEngine`。已启动本机安装的 Docker Desktop，但后续 30 次检查仍未获得可用的 Docker Server，`docker info` 超时。建议在服务健康中单独暴露 sandbox readiness，在聊天前返回可操作的环境提示，避免健康 UP 却问数全部失败。没有执行 Docker 重置、WSL 修改或机器重启。
2. **修复关系确认后的 YAML 合并一致性。** 退款→订单与订单→客户都被确认，但最终只保留订单→客户。结合初始 `relationships: []` 和 MdlSuggestionService#mergeRelationships，疑似第一次直接追加条目形成非法 YAML，后一次解析失败后重写仅保留当前关系。此根因来自源码和实际产物交叉分析，未通过 JVM 调试逐步证实。应对空数组形式、批量确认、重复确认、失败回滚补回归测试，并校验确认结果与最终工作区一致；以 YAML 为事实源，不能只凭数据库 CONFIRMED 状态宣布成功。
3. **上传前增加类型确认与编码字段保护。** code 的语义描述已经注明定长字符串，但物理类型仍是 BIGINT。应允许上传时显式指定 VARCHAR，并识别前导零、超长数字 ID 等；此问题发生在导入阶段，后续 MDL/Python 无法还原已经丢失的零。
4. **统一预览与实际写入的路径权限校验。** ModelingToolkit#writeFile 调用 MdlWorkspaceService#resolveWritable，实际写工具有平台文件禁写保护；预览接口没有同等拒绝。当前证据属于预览契约缺陷，不是已证实的任意写入漏洞。先前用不完整平台 YAML 得到拒绝仅证明内容校验，不能证明路径保护。
5. **明确 View 依赖能力并将错误前移。** 建模助手首次创建净营收 View 时引用 valid_order，dry-plan 返回找不到该表；助手改为基础逻辑模型 JOIN + CTE 后成功。当前链路能自修复，但应在建模上下文说明 View 引用限制，并在预览显示依赖与可执行性，降低反复确认成本。

## 原始证据与复跑

证据位于 [wren-gap](wren-gap/README.md)：`live-state.json`、`live-events.jsonl`、`live-modeling-sse.jsonl`、`live-mdl-validation.json`、`live-mdl-publish.json`、`live-extra-results.json`、`live-isolation-results.json`、`live-write-gate-results.json`、`live-published-wren-results.json`、`live-docker-status.json`，以及各题的 `live-Qxx-sse.jsonl`。不保存登录令牌。

启动 Docker 后另发起了 Python 分析请求，但没有收到首个 SSE 事件；在引擎检查持续超时后停止该测试客户端，不把它记为成功或数值错误。

Docker Server 与项目沙箱镜像就绪后，可在本测试知识库继续验证：

```powershell
python docs/validation/wren-gap/start_live.py questions
python docs/validation/wren-gap/start_live.py python
D:/workspace/wren-probe/.venv/Scripts/python.exe docs/validation/wren-gap/start_published_wren.py
```

questions/python 会追加会话事件，复核时应区分初次环境失败与后续重跑。测试数据只包含人为构造的验证样本。此前的 [项目差距分析](2026-10-06-wren-gap-review.md) 中“服务未启动”的描述属于上一轮检查，本报告记录启动后的实际结果。官方侧先前执行为 Wren CLI/SDK 基线，并未运行同配置的官方 LLM agent 对话，所以仍不能给出“本项目 vs 官方 agent”的对话准确率差值。
