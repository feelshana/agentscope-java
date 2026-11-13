# 当前任务：对话中的只读知识库浏览（spec137）

2026-11-13 Asia/Shanghai，liyufan_dev起点7173edfb，工作区起点干净。用户已浏览本地效果，最新授权停止本轮后端、提交/push个人分支并合入远程develop-2.0；66由用户更新部署。

## 范围

知识库选择按钮拆分书本查看/名称切换；右侧只读库概览→数据集预览/字段，复用当前QUERY接口与详情展示件，不扩大权限、不新增DDL/Agent执行/持久副本。工具详情互斥，保留输入/会话，按需加载和取消过期请求。

## 进度

已完成ChatPanel独立图标、ChatPage互斥懒加载侧栏、KnowledgeDataPanel与共用DatasetReadOnlyTables。原详情页继续显式注入编辑回调，侧栏只读。spec137/ARCHITECTURE第11章已同步，无新ADR/DDL/接口。React规范/shared-memory/Playwright用于本轮。

完整后端1160项0失败/错误、33条件跳过；前端三前缀各114项及build通过；最终Spotless/DocsConsistencyTest/package通过。真实本地样例、字段、原详情编辑取消、输入保留、侧栏互斥、切库、390px及模拟门户iframe通过；403/503/空库/挂起取消为故障注入。详见[验收报告](../test-reports/20261113-chat-knowledge-browser.md)。

5173既有Vite保留；本轮8080服务重启后样例读取恢复，运行忽略目录稳定JAR副本，后端类无变更。浏览器已留只读面板供审阅，无本轮新Agent问答、字段写入或66操作。

同日按用户反馈压紧数据集布局：库名单行、返回并入标题、说明默认一行可展开、页签/搜索同一行、表格自适应剩余高度和固定表头。真实商品预览在868px侧栏中表格约581px；390×700与模拟iframe通过。前端三前缀114项/build、后端门禁与package复验通过；服务未再重启，未增加接口/DDL。截图output/playwright/kb137-compact-*.png。

## 下一步

已停止本轮8080 Java进程36792，供用户在IDEA自行启动；保留既有5173。当前进行授权Git提交/推送/主线快进合并，实际提交号与结果以Git为准。此次spec136/137无新增DDL；共享数据源spec135若66尚未升级，仍需按既有升级指引补0146结构。流式生成期间交互、生产门户真人环境未实测。上一任务回看实现7173edfb一并推送，档案见archive/CURRENT_TASK-before-chat-kb-browser-20261113.md。
