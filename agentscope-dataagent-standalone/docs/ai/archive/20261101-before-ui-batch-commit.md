# 当前任务：数据集工作台实施（2026-11-01 Asia/Shanghai）

## 授权与基线
- 用户认可数据集页方案图，授权局部实施。
- liyufan_dev@5b1079a6，暂存为空，保留所有既有WIP；无提交、push、切分支、部署或真实资源写入。
- 局部前端，无新spec/ADR，ARCHITECTURE第11章同步。

## 已完成
- DatasetGroupPage.tsx/独立DatasetGroupPage.css按图实现页头书册/名称/元信息、成员+设置+紫色提问、单条模型状态带和工具栏。
- 编辑信息、事实表时间范围收进设置菜单，空说明可补充；原弹窗、单库路由、API与权限边界保持。
- 五列表格来源类型/路径两行、80px行高、数字居中、直接查看入口；版本/零行/未知行数保留。
- dw-source隔离kw-source空库卡片样式，原空库上传/关联入口保持。
- 上传失败、不确定、模型待检查等恢复逻辑与准备中禁用不变。
- 提案README更新认可/实施状态，报告docs/test-reports/20261101-dataset-workspace-ui.md。

## 本轮验证
- npm test64项×3base、tsc、最终/dify/dataagent/生产构建通过。
- 最终index-Dc0uOz-q.js/index-BqA6_klR.css/ontology-C8YLhVBW.js，既有大包/ECharts提示保留。
- 真实现有库只读UI10项通过，四表字段7/7/8/6、编辑/上传仅打开取消，未写真实数据。
- 实际Vite+dataset/group/source API全部拦截28项通过：设置/说明PUT/搜索/原路由/权限/状态/0行/派生/版本/删除确认/上传恢复/空及失败。
- native confirm导致CLI先报modal；继续完成后独立读取页面结果核实28项；唯一模拟写为qa说明PUT。
- 已view_image比较认可图及最终1663×946真实截图，检查字号/留白/状态/五列/行高/图标与390窄屏。
- 初轮字小/表格偏高已修；原侧栏/真实路径/面包屑保持，提案水印不进入正式产品。
- 截图docs/test-reports/assets/dataset-workspace-20261101/real-*；fixture-*为模拟场景，脚本在忽略output/playwright/dataset-workspace-20261101。
- 文档门禁/Git收尾见SESSION_LOG。

## 下一步与限制
- 用户可刷新5173知识库详情验收，不重启用户服务。
- 原有本地upload-status GET返回400，catch忽略；已记录未改后端，真实导入/建模/LLM/66未重测。
- 本轮提案检查点归档archive/20261101-before-dataset-workspace-implementation.md；前批四列卡片继续保留。
