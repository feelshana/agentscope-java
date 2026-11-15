# 当前任务：三页建模优化设计落地

2026-11-15 Asia/Shanghai；codex/architecture-phase1。用户已确认三页HTML，要求先提交本地现状再实施；基线提交 d0f8cad5（未push）。本轮按spec140改React，不继续改建模对话框。

## 已实现

- 问数优化：紧凑页头与原四步说明，问题列表/状态筛选/批量处理，新增问题输入层，MDL/规则查看层，当前题目四阶段；修正反馈按需展开，原Agent请求/口径确认/SQL核对/推荐/试问入口保留。
- 公共视图：规划默认展开可收起，自动整理仍标建设中；手动起草输入层，左侧资产列表，右侧用途核查/工程文件/问题验证。核查后进入验证；必选时间列、未保存文件与人工判定门禁保留。
- 资料：数据表与文档分区，字段/数据管理沿用原回调；文档新增/替换/删除沿用原API。名称或阅读打开只读正文侧栏，点击才读取，支持Markdown、Word提取正文、目录/查找/字号/原始文本、失败/重试/空态。
- 阅读关闭/切库忽略旧响应；复用现有MANAGE正文接口，不加载外部图片、不执行源HTML，不新增持久存储、依赖或解析器。
- 全屏与并排使用容器宽度适配；不新增全局主题切换。前端页面与ARCHITECTURE第14章已同步，无后端/API/DB/配置/权限/Agent提示/发布链路改变，无ADR/SQL迁移。

## 当前验证

- 相关5个前端文件×3前缀：63项通过；TypeScript/Vite构建通过，静态资源已更新；DocsConsistencyTest 5项通过。既有主包体积警告仍在，未新增包。
- 独立Playwright加载真实React组件、使用明确接口夹具：23项主流程检查通过，另验证MDL/规则、首次发布门禁、忙碌、取消保留输入、列表失败重试。
- 已核验文档替换/删除请求、数据入口回调；1440/850/390视口和桌面620px并排面板无页面横向溢出，检查截图和文档阅读焦点。
- 未调用真实LLM/Wren、未执行SQL/发布、未更改用户业务数据；组件夹具验收不等同真实后端全链路验收。报告 docs/test-reports/20261115-modeling-optimization-pages.md。

## 文件与状态

- spec140、ModelingMethodFlow/ModelingMaterials/BusinessDocumentReader/展示辅助函数/局部CSS与3个既有面板、SemanticModelingPage；新增文档展示回归。
- 本轮实施未提交、未push/合并/部署；用户原有5173 Vite/8080 IDEA服务保留，仅关闭本任务浏览器。临时验收入口已移除，截图/日志收在忽略的.runtime/modeling-tabs-implementation。
- 本轮外部修改 docs/design/semantic-modeling-20261113-full.html、未跟踪ui-baseline.md保留，不作为本轮成果，不覆盖。
- 旧设计任务检查点见 archive/CURRENT_TASK-before-modeling-tabs-implementation-20261115.md；更早p1/对话设计见相应archive与spec139。

## 下一步

用户刷新建模页三个页签后用真实资料复核；详细范围和限制见本轮报告。无自动提交新实施或部署授权。
