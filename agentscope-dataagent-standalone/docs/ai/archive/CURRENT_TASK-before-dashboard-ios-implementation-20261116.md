# 当前任务：仪表盘 iOS 界面 HTML 预览

2026-11-16 Asia/Shanghai；codex/architecture-phase1。用户希望先比较HTML设计与直接修改；本轮选择独立交互设计稿，待评审，不代表获批正式改版。无新spec/ADR，不提交/push/部署。

## 本轮交付

- docs/design/dashboard-ios-preview-20261116.html、同名.js、同名.md；概念图和列表/创作页截图同目录。
- 本地 http://127.0.0.1:8890/dashboard-ios-preview-20261116.html ，已请求在Codex右侧预览，独立Python只读服务器。
- 沿用知识库#f2f2f7/顶部淡光/22px白卡/柔影/悬停上浮/按压缩放；列表、新建、创作、查看、发布确认及手机切换。
- 三卡为演示状态，共用已有零售合成数据汇总；不调用API、不执行真实生成/发布、不修改正式React/API/权限。
- 根据CSS预先提取令牌与组件系统；内置Image Gen概念参考，Chrome截图及view_image对照；意图差异/首屏文案差异见设计说明。

## 本轮验证

- Playwright CLI（IAB工具未提供）真实Chrome独立页签：搜索/分类、创建、按月修改、无修改发布禁用、修改后启用、发布确认/取消、收起/展开、查看/编辑、3次重载通过。
- 1488/390/320无根页面横溢出，390内预览310/310；JS语法通过；最终pageerror/console warning/error均0，API请求0。
- 月度汇总149.104696/159.089532/166.518452万元，与已有CSV一致；未假称实时查询。周末组只有一天，已标口径。
- DocsConsistencyTest最终5项通过，失败/错误/跳过0，空白检查通过；未运行正式前端构建或真实Agent。

## 上一阶段保留

- 零售旗舰样板已真实本地发布，详见archive/CURRENT_TASK-before-dashboard-ios-preview-20261116.md与docs/test-reports/20261116-retail-showroom.md。
- 正式样板 http://127.0.0.1:5173/dashboards/a26cddc8-556f-4670-812d-c9ed439f0c03?view=published ，库1b339def-deeb-4188-84b6-5bda188c7fb9，bob管理/alice QUERY，非全员公开。
- 正式修订53c510f6902b00211c1c6466b8afc8e7e8aaac370512f2ba5c22c1383e174c2b，8080/5173服务保留。
- 保留全部既有未提交GenBI源码/文档/设计/构建资源，未覆盖或回滚。

## 下一步

- 用户先体验HTML并反馈，再明确正式应用的视觉范围；不依据此设计稿自动改正式页面。
- 生产发布前仍需完整验证、版本/访问范围和线上环境验收；未操作66。
