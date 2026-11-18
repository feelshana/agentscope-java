# 当前任务 产品基准候选与工程现状盘点

2026-11-18 Asia/Shanghai，codex/architecture-phase1。用户已确认协作方向并授权开始，实物由Codex选择。当前交付为独立React样板及可携带HTML，尚未确认全产品风格或授权批量迁移。前序任务完整保存在[上一检查点](CURRENT_TASK-before-product-baseline-20261118.md)。

## 已完成

- 独立入口frontend/design-baseline.html、src/design-baseline/及专用Vite配置；复用既有全局CSS、品牌、图标和ECharts，无新依赖。
- 知识库列表/创建/详情、数据源、问数、建模、仪表盘、设置和可浏览规范；加载/空/错/只读及桌面/窄屏样板。
- npm run baseline:build生成docs/design/product-baseline/review.html，脚本和样式内嵌；CSP禁止外部连接；中间构建改写target，不覆盖正式static或加入产品路由。
- 使用与设计规则（仅本地产品基准目录，不随Git发布）、工程盘点（仅本地产品基准目录，不随Git发布）、验证记录（仅本地产品基准目录，不随Git发布）、生成参考与主要场景截图已保存；AGENTS文档地图、ARCH前端结构已增加候选入口。
- 工程优先建议：统一theme.ts/global.css主题来源、归并公共页面模式、测量首屏加载成本；仅建议，未修改正式主题或重构业务。

## 本次验证

- TypeScript及样板构建通过；单文件脚本语法与外部资源隔离结构检查通过。
- 正式前端隔离输出构建通过，写target/product-baseline-production-check，原static保留。
- ArchitectureDependencyTest、SharedOfficialWrenAccessTest、MdlPublicationTransactionTest、DocsConsistencyTest共20项在Java17.0.14通过，失败/错误/跳过0；首次Java21复测同样通过。
- 前端conversation-polish、semantic-workbench、auth-session三个文件25项，在三个部署前缀各通过。
- 内置浏览器本地HTTP检查搜索/筛选/创建/只读/重试、图表明细/时间、建模更新/显式演示发布、设置反馈和对话框焦点；六场景320/390/850无整页横溢，1440及参考原生1487×1058补验。发现的隐藏表头横溢已修复。
- 最终共享记录后的DocsConsistencyTest 5项通过，失败/错误/跳过0；git diff --check通过。问数输入区补为固定可见，长回答独立滚动，桌面/390窄屏及只读状态补验通过，最终样板重建通过。

## 适用边界

样板全部使用合成数据和内存状态；任意问数输入不触发真实计算，连接/草稿/发布为演示。内置浏览器禁止file协议，未绕过；单文件双击体验待用户确认，HTTP预览与结构检查不冒充file实测。临时site目录的清理由自动审批策略拒绝，已保留并局部忽略，最终HTML不依赖它。

未运行完整clean verify、真实LLM/Wren/业务数据库或生产恢复/容量验收。未提交、推送、合并或部署；原有spec143/144/145及其他未提交工作保留，不接管IDEA/已有Vite。基准预览另开127.0.0.1:5186。

## 下一步

先由用户体验候选，明确导航/密度/列表/详情/工作台取舍；再选一个真实页面做主题统一和公共组件试点。确认和验证通过后逐类迁移，不把候选规范直接宣称为已批准标准。前序spec146看板生成恢复及spec143/144/145可靠性、历史/资料管理仍待真实体验，继续时读取归档与对应spec。
