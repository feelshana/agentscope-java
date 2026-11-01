# 知识库与更多：第二轮设计提案

2026-11-01，状态：**待用户认可，未改造正式业务页面**。

入口：`index.html#knowledge` / `index.html#more`；用 `python serve.py` 在本机 19138 预览，或直接打开 HTML。所有数据为示例、业务按钮仅提示，无网络业务调用。

## 推荐方向

- 知识库：保留卡片/列表、搜索、现有排序与入口；缩小图标和卡片高度，取消图标紫色底块。名称为主、数据集数量其次，创建者/创建日期弱化为元信息。权限用“可管理”描述，避免被理解为管理按钮。已有数据卡片的提问动作统一紫色，查看数据为次操作；无数据状态显示添加数据，不引导直接提问。一页时不展示不可用的翻页控件。
- 更多：有限宽度内容区；帮助入口单独突出。两项管理员工具合并为细分隔线列表，整行可点，箭头位置和文字统一。普通用户仍只看帮助；管理员工具不越权展示。
- 品牌、功能图标与已认可的设计保持一致；不是新一轮品牌重做。底部“查看无数据状态”与右上页签属于评审工具，正式页面不加入。
- 未增加发布/同步状态、质量分数、收藏、统计指标或新后台能力；上线前局部前端可完成，仍需认可后实施。

## 文件与验证

- `01-more.png` / `02-knowledge.png`：Image Gen视觉探索，保留供参考。
- `03-knowledge-desktop.png` / `06-more-desktop.png`：最终HTML浏览器实图，正式评审以这些和HTML为准。
- `04-knowledge-list.png` / `05-knowledge-empty.png`：列表与无数据状态。
- `07-more-mobile.png` / `08-knowledge-mobile.png`：390px窄屏。
- 原品牌SVG来自frontend/src/assets；图标直接来自上一轮认可SVG，没有栅格临摹。Image Gen中的品牌/图标漂移在HTML中纠正，列表边框收敛为分隔线，字号按真实应用比例落地；这些是本提案的明确修正，不代表用户已经认可。
- 独立Playwright验证11项：两卡片、认可SVG、搜索/无结果、切换列表、无数据预览、页面切换、管理员说明、业务动作仅模拟、两页390px不横向溢出。未接DB/LLM/66，未跑业务构建或无关后端全量测试。

## 会话圆点核对

SessionsSidebar#SessionRow由 `entry.unread && !active` 添加is-unread，AppShell.css画4px紫点。SessionReadStateStore#isUnread使用 `lastActivityMs > lastReadAt`，没有已读记录也算未读；不是仅以新助手回答为依据。主侧栏openSession只导航/chat，ChatPanel未调用markRead，现有调用在SessionTranscript旧历史组件，因此读过后标记可能仍存在。本轮仅报告事实，没有修改此行为。

候选：在实际聊天历史加载成功/阅读到底部时更新当前用户已读水位并同步列表，圆点增加“有未读更新”可访问说明；只在真正需要时显示。需要后续独立确认与行为验证。

## 参考

参考其信息组织而非复制视觉：https://www.notion.com/help/search （资源检索），https://www.intercom.com/help/en/articles/15432088-your-workspace-settings （设置分区）。本设计保持当前产品能力及权限边界。
