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
- 当前阶段：先提交本地样例与已验证部署记录，再合并远端a85ccec6，保留双方记录，验证后普通push；然后切个人分支快进合入主线并同步其远端。

## 验证与完成状态
- 本轮恢复工具：从历史恢复两文件原字节、重复运行不重写、已有不同文件时拒绝覆盖/不恢复其余文件，均通过；指南Bash块语法与桌面相同检查通过。
- 文档一致性及最终分支/远端/忽略状态待收尾；不重跑无关LLM、部署或大规模业务测试，应用沿用a85已上线验证代码。
- 旧线上部署详情归档archive/20261031-before-local-mainline-sync.md，历史未完成描述以本入口为准。最终提交号与远端同步状态见Git。
