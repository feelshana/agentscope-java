# GenBI 对话仪表盘试用版验收

日期：2026-11-16 Asia/Shanghai。分支 `codex/architecture-phase1`；未提交、未推送、未部署66。
范围：spec141 / ADR0149 的内部live整张看板闭环。

## 本地运行

- 前端 `http://127.0.0.1:5173/dashboards`；后端8080。使用既有本地MySQL与已发布的“电商销售-问答视觉验收”库，测试账号bob。
- 样板 `/dashboards/4bfe16d5-d7b5-415c-b371-fdbd12920bf6`。第一版已通过管理者按钮发布，第二版为已校验、未发布的修改草稿，便于对照。
- 实际安装的Wren为0.15.0；技能从同安装环境的 `skills_content/genbi` 与 `usage` 加载。没有修改官方技能，没有开启任意shell或外部部署。

## 真实 Agent / Wren

1. 自然语言请求生成电商经营概览。日志确认加载官方两项技能，调用GenBI build、官方MCP模型描述和查询、原生write_file、GenBI register/verify；约57.7秒完成，HTML约23KB。
2. 数据1500笔订单，覆盖2025-01-01至2025-09-28；最近30天为2025-08-30至2025-09-28，共182笔、有效158笔、客户121人。模型没有金额字段，Agent明确说明，未生成假销售额/客单价。
3. 浏览器预览显示指标、折线、环形图和区域排名。选择华东后显示27笔、有效20笔、客户16人；与查询结果一致。
4. 管理者显式发布成功。继续对话要求增加横向条形图、保留表格；同一会话原生read_file/edit_file更新并verify，约55.2秒完成。草稿摘要变化，已发布摘要及HTML仍为第一版。
5. 第二轮首次调用实际模型服务返回503，没有自动切换模型；重试同一服务后成功。页面对503显示简短可重试提示，保留输入内容；未把外部服务失败记录成代码或查询成功。
6. 重启服务后，资产、发布指针和对话历史恢复；历史读取37条原生记录。修正了KB命名空间遗漏，不另建聊天存储。

## HTTP 与隔离

- 最终真实HTTP：无此私有知识库权限的alice查看返回404；写SQL返回400；只读官方查询200，标准桥返回 `columns:["total"], rows:[[1500]]`。
- Boot4 HTTP使用Jackson3，不能直接序列化Jackson2 JsonNode；接口明确转换为JSON原生Map/List，并把不同官方MCP版本的对象行规范为桥约定的位置行。
- 生成HTML在内层 `sandbox=allow-scripts` 的opaque srcdoc运行；可信外层CSP限制子框架导航。实际临时夹具确认无法读宿主DOM/Cookie，fetch外联被阻止，外部跳转被屏蔽。夹具验证后删除，仅保留业务样板。
- 主页面与生成页面在390×844均无横向页面溢出；移动端有导航入口，工作台上下排列。测试后恢复默认视口。
- 测试范围仅覆盖当前浏览器、此样板与所述探针，不能宣称验证了所有浏览器或所有生成网页。

## 自动验证

- `python scripts/check.py backend --unit`：1065项通过，失败/错误/跳过0（当时工作区）；后续HTTP状态与表格规范化变更又运行相关专项，均通过。
- 专项：DashboardServiceTest、DashboardQueryServiceTest、DashboardControllerTest、DashboardTurnPreparationTest、SessionTranscriptNamespaceTest、DataAgentConfigTest、ArchitectureDependencyTest、DocsConsistencyTest；覆盖持久化、不可变发布、期望版本、跨用户/选库、排队撤权、路径/命令、官方失败、日期门禁及历史恢复。
- `python scripts/check.py frontend --files dashboards.test.mjs`：3项通过（消息来源/旧通道拒绝，最终回答恢复，503提示）。
- `npm run build`：TypeScript/Vite通过。工作台和预览分别延迟加载；约13KB工作台JS、约1MB原始ECharts预览JS（gzip约338KB）只在实际打开预览时加载。既有主包大块告警仍存在，本轮未改登录性能方案。
- 格式与文档门禁通过。日常范围未运行合并/发布要求的全量clean verify，也未验66和多实例部署。

## 实际限制

本版为生成整张网页，尚无逐图钉入、拖拽、逐图版本或跨库看板。采用内部live桥而非官方浏览器WASM/snapshot；官方verify是结构和密钥扫描，不等于浏览器执行验收。单进程资产锁、文件版本持久化，旧修订暂不回收；模型发布更新后，旧草稿需新建工程重新生成。外部模型服务的可用性与生成耗时不由本功能保证。
