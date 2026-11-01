# 当前任务：数据集页面设计提案（2026-11-01 Asia/Shanghai）

## 授权与基线
- 用户要求数据集页优化并先给方案图；本轮不实施正式页面。
- liyufan_dev@5b1079a6，暂存为空，保留所有既有UI WIP；无提交、push、部署或数据库操作。
- 纯视觉评审，无新spec/ADR或架构变更。

## 已完成
- 已核对用户真实截图与DatasetGroupPage现有头部、权限、状态、表格、上传/关联动作。
- 使用frontend-app-builder/imagegen技能生成一张完整桌面方案，已view_image核对。
- docs/previews/dataset-workspace-20261101/01-datasets-overview.png；README记录设计/权限边界，prompt.md保留完整提示词。
- 建议收拢页头低频管理项、压缩模型提示、来源合并两行、约80px表格行、数值居中、直接查看入口。
- 外部表未统计保持原值，数据仍为orders/products/customers/order_items和字段7/7/8/6，无虚构计数或实时状态。
- 查明kw-source标签与空库导入卡片同名CSS覆盖，后续获准实施需隔离；本轮未修生产样式。

## 验证与下一步
- 本轮仅图稿和记录，未运行前端编译/业务测试；文档检查结果追加SESSION_LOG。
- 待用户认可后再局部实施DatasetGroupPage，保留QUERY/MANAGE、准备中禁用、来源约束、现有上传状态与删除确认。
- 上一批知识库四列卡片已实施；完整检查点归档archive/20261101-before-dataset-workspace-design.md，历史验证不当作本轮新验收。
