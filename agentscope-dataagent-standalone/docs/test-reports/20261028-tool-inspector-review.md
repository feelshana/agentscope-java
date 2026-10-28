# 2026-10-28 问答工具详情真实调用验收与展示调整

## 范围与环境
在现有IDEA 8080服务、当前激活模型、本地Docker沙箱与官方Wren链路上发起7个独立会话，共17次真实工具调用、8种工具。使用已有“测试本地数据源”中的合成订单，Python/图表用题面内置合成数据；未查询BI人员资料或密码字段，未修改业务文档、模型或发布设置。真实会话与产物保留，未重启IDEA或清理现有容器。不是用夹具冒充LLM实测。

本轮属于现有详情组件验收和局部修正，不改变工具协议、权限、查询路径、文件归属或样例保存，不新增spec/ADR。起点a9ccfdc2及本轮之前的未提交修改均保留，未提交或推送。

## 真实场景
| 场景 | 会话ID | 工具 | 总耗时 | 返回/检查 |
|---|---|---|---|---|
| 订单状态汇总 | `1d1db56a-0b5f-4345-8767-e230455384cf` | recall_queries, list_models, describe_model, run_sql | 31.37s | 4行3列，与回执数值一致 |
| Python计算与文件 | `8424ef8d-f77a-4f61-a43b-a3e885223900` | run_python | 14.49s | 3个文件；CSV下载6行、合计916；PNG实际加载 |
| 交互柱状图 | `6c2947ee-004e-4b8e-99c4-4d8b08ba9ebe` | render_chart | 4.37s | 真实chartId载入canvas，4行输入数据 |
| Python预期异常及恢复 | `1725b772-2503-4177-ad22-fe022012db11` | run_python, run_python | 11.55s | 第一次exit=1，第二次exit=0输出6；最终回答正常完成 |
| 宽表明细 | `7606acf4-8c93-43ca-ae57-9bdb78c187ce` | describe_model, recall_queries, run_sql | 32.56s | 66行6列；默认50行，可展开66行并横向滚动 |
| 业务规则及空查询 | `eab302fd-4059-4da9-adb5-19e532ed1104` | get_instructions, list_models, describe_model, recall_queries, run_sql | 45.38s | get_instructions及SQL 0行；明确空结果不等于指标0 |
| 技能文件读取 | `97bac891-7608-4892-a44b-1af44e8401ed` | load_skill_through_path | 9.08s | 实际加载示例技能；框架内部调用继续隐藏 |

共7个会话均收到done，未出现SSE中断；其中1次Python调用故意触发ValueError用于验收，并非业务故障。耗时仅是本机本轮样本，不作为容量/上线SLA。会话URL为本地`http://127.0.0.1:8080/chat?session=上述ID`，需对应账号登录。

## 发现与修正
1. **已复现状态错误**：Python退出码1的调用在轨迹中仍显示成功勾号，右侧却正确显示失败。ToolCallBlock改用与ToolInspector相同的inspectResult，Python/官方回执/技能错误等同一规则解释；不把缺回执假设成功。补2个组件渲染回归，覆盖失败和恢复/缺回执。
2. **模型结构太冗长**：每字段套“名称/类型/说明”多层列表，增加滚动。改为模型名、紧凑字段表；字段额外属性、关系及其他信息可展开，原始输入/返回仍保留。目录改成模型名称、字段数、描述的列表。实际4个模型、orders 7字段与2条关系均可查看。
3. **业务规则与图表输入**：get_instructions以Markdown阅读并可复制全文，长规则分段展开；render_chart输入用原始数据表显示，避免每个数字变成嵌套列表，原始配置仍可查看。
4. **表格与文件操作**：真实数值列右对齐，不改变小数、标识或NULL原值；宽表保持区域内滚动。Python标准输出保留等宽列对齐，可键盘/横向滚动。下载按钮提高对比度，调用轨迹与详情使用相同工具图标。
5. **截图后校正**：390px下DATETIME/DECIMAL曾在类型列中被拆行，已减少该列内边距，并在桌面/390px重新验证常见类型均为单行。

## 展示与交互验收
- 实际SQL：4行表格、原始数值855652.75等保留、2个数值列对齐、输入/原文可读。放大进入dialog，Escape回到侧栏。
- 模型目录4项；字段7行，展开关系显示orders_customers等原始信息；原始内容切换到输入可看到orders参数。
- Python成功：stdout包含916，3个产物可见，真实图片naturalWidth大于0；首次基线已实际点击下载CSV，读回6行销售额合计916。错误：轨迹失败与详情失败一致，预期异常及代码/原始回执保留；后续成功调用仍显示成功。
- 图表：真实服务chartId加载canvas；输入显示4行2列。未修改ECharts生成逻辑。
- 宽表：默认50行，显示更多后66行；实际键盘ArrowRight可横向滚动。未把本次66行称为全库总量。
- 空查询：0行提示明确，不当作金额0；规则Markdown包含原有GMV等口径，未改写规则。
- 390px详情为全屏dialog，模型表不扩大body宽度，Escape可关闭；默认桌面侧栏及放大阅读分别检查。
- 原有加载技能工具在ChatPanel的HIDDEN_TOOL_PATTERNS中有意隐藏，真实读取已发生，但不为其增加外部入口。初次自动化错误地等待该按钮导致超时，确认原有隐藏策略后按预期重新验收；不是产品漏显示故障。

## 自动化与证据
- 前端`npm test`：根路径、/dataagent/、/dify/dataagent/各48项通过。新增2项组件状态回归；测试SSR内联React依赖，修复临时目录下外置React无法解析的测试打包问题。
- 根路径与/dify/dataagent/构建通过，最后恢复根构建。最终字段列内边距小调整后又运行根构建与资源复制；没有改路由或前缀逻辑。
- 本轮没有后端业务改动，不重跑Java全量；Spotless/DocsConsistencyTest另行运行。
- IAB完成基线查询/Python预览及CSV实际下载后发生浏览器接口兼容错误；按工具指引尝试恢复未果，改用独立Chromium继续真实服务验收。IAB临时视口已复原，其专用标签默认随回合结束关闭；未声称它是产品故障。独立Chromium关闭情况以交接为准。
- 原始事件、会话ID、执行耗时及截图在本地忽略的`output/tool-review-20261028/`；报告不包含Token、模型Key、数据库密码或真实人员名单。主要截图：model-schema-after.png、model-directory-after.png、python-error-before/after.png、python-after.png、chart-after.png、chart-input-after.png、rules-after.png、model-mobile-after.png。

## 未覆盖与其他观察
- 本机运行环境没有DEEPSEEK_API_KEY，不能声称完成真实联网搜索；保留现有Markdown/原文展示，仅补中文标题。待配置后另验来源链接与搜索异常。
- 本轮未操作建模写工具、发布或删除文件；未覆盖所有可能的用户自定义工具及极端外部返回。已有解析回归覆盖转义、缺回执、截断、NULL/false/0、附件等边界，不能等同全部线上工具验收。
- 技能读取实际加载example-skill示例模板，文件未包含完整的精度/空值/日期分析方法论；助手也明确说明未提及。本轮只检查工具展示，不擅自新增分析技能或改变问答方法。
- 本轮检查结果展示与工具回执一致性，不替代110题正确率、并发或长稳回归。

最终收尾：Spotless检查及DocsConsistencyTest 5项通过（0失败/0错误），git diff --check通过；独立Chromium专用会话已关闭，IDEA/Wren与7个真实测试会话及产物保留。HTTP核对8080首页与当前根构建静态产物一致，刷新即可加载本轮前端；Java服务未重启。工作区保持未提交。
