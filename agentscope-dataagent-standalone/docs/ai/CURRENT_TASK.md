# 当前任务：同步主线与个人分支，示例文件改为本地持久副本（2026-10-31）

## 用户授权与起点
- 用户确认66线上已验证可用；应用版本a85ccec6，Health UP、98819/2046行及datetime/只读账号、临时授权撤销已现场回传，BI可用由用户确认。
- 用户授权完成本地develop-2.0需要的pull/push，并确认最后切回liyufan_dev、同步主线，后续在个人分支开发。
- 起点本地develop=c903c321、origin/develop=a85ccec6，双方各一笔独有提交；个人本地/远端均bc3bc14d，没有独有新工作。已有部署共享记录WIP保留本次提交。

## 本轮方案与进度
- 本地两Excel已先恢复到模块data/demo-samples，SHA256与历史原字节一致；该目录被模块.gitignore和本机.git/info/exclude排除，切分支不受版本文件删除影响。
- 源码deploy/demo-samples中的两Excel已移除；保留SHA256SUMS、README、binary属性及restore-local.py，从011d06b历史按二进制恢复，不覆盖不同的现有文件。
- 本地在模块目录执行python deploy/demo-samples/restore-local.py即可准备，重复执行只校验；IDEA模块工作目录+DATAAGENT_DEMO_ENABLED=true可测示例上传。外部示例仍需本机数据库/只读账号的私有配置，未替用户初始化本机DB或启用IDEA。
- 66持久目录/data/dataagent/rehearsal-app/data/demo-samples及runtime.env、demo_data不改变；删除源码副本与切本地分支不影响线上，不因本轮重启/重部署或清理资源。
- 同步当前README/架构配置注释/spec105/ADR0122状态及桌面指引；无新业务流程，不新增spec或ADR，不改ADR正文。
- 本地持久样例及部署记录已提交97889a47；远端a85ccec6源码已自动合并，共享记录保留双方，远端原CURRENT_TASK归档archive/20261031-mainline-a85-checkpoint.md；最终验证后普通push，再切个人分支快进同步并推送。

## 验证与完成状态
- 本轮恢复工具：从历史恢复两文件原字节、重复运行不重写、已有不同文件时拒绝覆盖/不恢复其余文件，均通过；指南Bash块语法与桌面相同检查通过。
- 合并后DocsConsistencyTest再次5项通过；业务源码/前端/生成资源与origin的a85版本逐字节一致。最终分支/远端/忽略状态在push及切分支后以Git复核；不重跑无关LLM、部署或大规模业务测试。
- 旧线上部署详情归档archive/20261031-before-local-mainline-sync.md，历史未完成描述以本入口为准。最终提交号与远端同步状态见Git。

## 同事更新保留情况
- a85ccec6的分析取数完整性/摘要、归因默认单维度及问答确认与发布互斥等代码完整保留，未改写同事业务实现；运行代码已由用户线上验证，不扩大本轮回归范围。

## 2026-11-01 第一批体验与样式优化（wangchao_dev，未提交）
- 已做：主色#7560b5等基础规格、写死颜色转变量（画布文件保留色值）、最小字号12px、圆角收敛；修正选库/登录/归因层级文案；问数提示词要求结论先行；知识库卡片显示发布状态（GroupVO新增publishedVersion）；发布按钮说明原因；指引中隐藏跳过并发布；来源标签改kw-origin。
- 待用户看截图确认后，再做公共组件和重点页面。提示词与卡片状态需重启后端才生效。
- spec106语义建模工作台已实施，未提交：六个页签、模型页、SVG字段级关系图、术语并入、QUERY只读线上版本、/api/knowledge-bases/summaries。待重启后验收：线上字段说明、列表统计、QUERY路径、新手指引两条路线。
- 2026-11-01重启验收：线上字段、列表统计、卡片状态、QUERY只读与撤权、术语增删已通过。结论先行规则已移到QUERY_EFFICIENCY_RULES（application.yml的sys-prompt会覆盖默认提示词），需再重启后用真实问题验收；新手指引两条路线未走。
- 已知问题，暂缓：草稿改动经markMdlDirty拦截问数至重新发布。用户2026-11-01判断同时改动和问数几率小，先不修；放弃草稿后需点重新发布。
- newuser09走完指引，日志无错误、发布一次；用户认为#7560b5过暗，主色改#5b3df5并提高状态色；指引手势改为指向真实按钮，下一步也带手势。待重启确认。
