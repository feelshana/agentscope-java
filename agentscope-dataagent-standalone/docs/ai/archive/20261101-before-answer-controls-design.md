# 当前任务：推荐图标方案实施（2026-11-01 Asia/Shanghai）

## 授权与基线
- 用户认可图标提案并明确使用推荐组合：默认线性、选中实心。仅这批图标已授权，全流程A1–A4/B1–B2仍待选择。
- liyufan_dev@5b1079a61be0d7a5b1ffc1975801e75d458f8c16；保留已有品牌/侧栏/术语/例行任务WIP，暂存区为空。未提交/push/切分支/部署/DB。
- 局部视觉小优化，不新增spec/ADR；架构第11章补图标当前实现。

## 完成
- ProductIconPaths.tsx使用认可SVG转静态JSX：书册、模块、文字与定义、日历、更多、数据库、表格、撰写8类，线性/实心两种。
- Icon新增variant，默认outline；SessionsSidebar展开/收起按真实当前路由选择solid；导航20px。数据源管理页两态均选知识库。
- terms/calendar独立命名，不把全局settings/clock的设置/耗时语义替换。内容区域沿用线性及原尺寸；不改变卡片布局。
- 全流程原型docs/previews/ui-review-20261031/review.js同步新图形与选中规则；19134刷新可见。
- /dify/dataagent/生产构建已更新static，未重启任何业务服务。

## 验证
- TypeScript通过；npm test 64项×3base全部通过；正确部署前缀npm run build通过。保留既有大包/ECharts提示。
- 当前无内置浏览器控制工具，按Playwright技能启动独立浏览器与19136只读fixture，使用实际生产构建+固定API示例；不连数据库/生产。
- 1280×720验证5导航每次仅当前solid、全部20px；数据源页收起态仍高亮知识库；390×844手机导航打开/Escape关闭通过。
- 已view_image对照认可图及实际截图，图形/轮廓孔洞/线宽尺寸/颜色/新建图标/手机裁切均核对；沿用已有布局为本轮明确边界。
- 原型静态检查通过；DocsConsistencyTest 5项及git diff --check通过，暂存区为空。详见docs/test-reports/20261101-product-icons.md。不跑无关LLM/DB/Java全量。
- 详细代码范围、截图与限制见该专项报告；本轮视觉检查不代表真实业务端到端验收。

## 下一步
- 用户刷新本地前端/19134原型查看；后端静态方式需加载新构建，未擅自启动/重启。
- 需要提交时再按用户明确授权处理；已有生产WIP不是本轮全部成果。
- 全流程其他改造仍待选择，见docs/design/20261031-interface-review.md。

## 历史
- 完整评审与图标提案归档：archive/20261101-before-icon-implementation.md。
- 上轮品牌实际编辑/批量删除等业务交互尚待独立验收；本轮只走导航，不冒充这些验收。
- 测试服务19136 PID29184为本轮创建，已在收尾核对命令行后停止，独立浏览器已关闭；其脚本/检查日志在Git根output/icon-adoption-20261101。既有19134等服务保留。
