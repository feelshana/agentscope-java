# 推荐图标方案实施｜2026-11-01 Asia/Shanghai

## 范围

用户明确认可图标设计并要求使用推荐方案：默认线性，选中导航实心。本轮只实现这批图标，不将之前全流程评审的A1–A4/B1–B2视为已批准。保留 `liyufan_dev@5b1079a6` 上已有品牌/侧栏/术语等未提交修改，没有提交、推送、部署或数据库操作。

设计源：[图标家族](../previews/icons-20261101/01-icon-families.png)、[页面对照](../previews/icons-20261101/02-in-context.png)。使用其原SVG几何，不重新解释或生成图形。

## 实现

- `ProductIconPaths.tsx`：8类图标的线性/实心静态JSX，currentColor、24px viewBox，无第三方图标依赖、字体或网络资源。
- `Icon.tsx`新增可选variant，默认outline。复用book/model/database/table/compose/moreHorizontal名称，新增terms/calendar语义；原settings和clock继续表示设置、时间，不全局改成术语或任务。
- `SessionsSidebar`：根据原导航路由选择实心；展开/收起使用相同判断，数据源管理页均保持知识库高亮；收起态保留文字提示并补aria-current。
- `AppShell.css`导航20px；`global.css`仅为新图标增加线宽/实心样式；内容区现有尺寸与标签保留。知识库卡片、列表、数据源、数据集等复用调用同步显示新线性图标。
- 全流程HTML评审原型同步相同路径与选中规则。生产构建保持`/dify/dataagent/`。

## 浏览器与对图

本轮工具列表没有内置浏览器/CUA控制接口，按已读取的Playwright技能备用方式启动隔离浏览器，未复用用户登录浏览器或访问生产页面。独立loopback服务19136提供实际生产构建，API为固定只读示例，不验证真实数据库/模型或权限。脚本/日志在Git根忽略的`output/icon-adoption-20261101/`。

- 1280×720桌面逐项切换知识库/建模/术语/任务/更多：每次仅当前项为实心，其余线性，全部20px，运行时断言通过。
- 进入数据源管理并收起侧栏，知识库仍为选中项；截图中图形、负空间、颜色正常。
- 390×844打开手机导航，图标正常；Escape关闭并恢复桌面尺寸。
- 截图：[知识库](assets/icons-20261101/knowledge-desktop.png)、[收起态](assets/icons-20261101/collapsed-desktop.png)、[手机导航](assets/icons-20261101/mobile-navigation.png)。均为实际编译页面+模拟数据截图。
- 图像工具重新查看认可场景图与实际桌面/手机截图后核对：书册/模块/文字/日历隐喻一致；轮廓和实心孔洞一致；1.65线宽、20px导航一致；默认灰/选中紫一致；新建对话中的白色线性图标清楚；手机没有图标裁切。图标实施已对照认可设计验证。
- 明确范围差异：认可图板同时展示A/B两种风格，本轮使用推荐组合；沿用已有侧栏选中背景、卡片容器、字号和布局，不把图板的简化页面布局应用为新的全站样式。界面业务文案/路由/权限不改，示例业务文本仅存在于验收fixture。

工具检查中曾因PowerShell多行参数和选择器匹配到面包屑而中断；改用脚本文件和侧栏作用域后完成，无对应产品逻辑缺陷。

## 自动检查

- TypeScript `tsc --noEmit -p frontend/tsconfig.json`通过。
- `npm test`：64项×`/`、`/dataagent/`、`/dify/dataagent/`全部通过，0失败。
- `DATAAGENT_BASE_PATH=/dify/dataagent/ npm run build`通过；生成index-BmE8ddt9.js、index-CPDT9pvV.css、ontology-Btl5I3CD.js，资源前缀正确。仍有既有ECharts静动态导入与大包提示。
- 原型`check-static.cjs`通过。
- DocsConsistencyTest 5项通过；git diff --check通过（仅CRLF归一化提示）；暂存区为空。未运行无关Java全量/真实LLM/DB或66操作。

可刷新原型19134查看；本地业务前端刷新后加载新源码，后端静态页需使用新构建。没有替用户重启业务服务。

收尾：19134原型脚本HTTP 200且包含新图标。已关闭本轮独立浏览器并核对命令行后停止自建fixture PID29184；未停止既有服务。
