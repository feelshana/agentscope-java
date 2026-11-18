# 看板查看页顶栏优化

2026-11-17 Asia/Shanghai，codex/architecture-phase1，沿用spec144；仅前端布局/文字及静态构建，不提交或部署。

## 结果

- 左侧明确“返回列表”导航，标题与状态集中，描述旁“编辑信息”入口修改名称/描述；无描述可编辑者显示用途提示，只读者不显示编辑入口。
- 右侧访问权限为次操作，查看态“编辑看板”为主操作，编辑态保留查看已发布版/显式发布及原保存进度；移除查看态重复权限文案和无标签铅笔按钮。
- 桌面两端布局，查看页顶栏与内容同宽对齐；长标题省略、长描述两行，窄屏操作换行。没有修改后端接口、ACL、查询、发布与预览隔离。

## 本轮验证

前端相关dashboards.test.mjs、dashboard-library-management.test.mjs默认前缀12项全部通过，TypeScript/Vite构建通过并同步静态资源；git diff --check通过。无后端业务改动，不重复跑全单元/真实LLM，共享记录后DocsConsistencyTest 5项通过，失败/错误/跳过0。

真实Chrome隔离API夹具验证返回列表、信息编辑后仍保留已发布模式与原预览HTML、编辑/已发布切换、访问权限弹窗、只读入口、空描述、发布弹窗取消；仅一次预期元数据PATCH，无发布/查询/其他写入。使用明确合成HTML，不代表真实业务数据。最终未预期请求/写入/JS及console错误均0。

2560宽时header-frame与canvas左缘均510px；850/390/320页面与顶栏均无横溢，覆盖80字标题与500字描述。7张图位于output/playwright/dashboard-header（published-desktop、edit-desktop、viewer-desktop、published-wide、published-850/390/320），已检查桌面、编辑态及两种窄屏。初始夹具误在所有子框架写sessionStorage且错误检查可信外层srcdoc；改为仅顶层初始化、直接断言内层演示内容并在新QA浏览器中复验通过，未更改产品CSP/沙箱。

日志：.runtime/dashboard-header-{tests,build,browser,docs}.log。用户IDEA/已有服务未接管，未操作66、真实资产或执行模型；本轮刷新前端可体验。完整clean verify未运行，合并发布前需补齐。
