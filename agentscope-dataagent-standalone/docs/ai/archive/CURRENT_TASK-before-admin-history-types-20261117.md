# 当前任务：仪表盘额度与发布整合

2026-11-17 Asia/Shanghai。用户授权提交全部本地修改，验证后整合liyufan_dev和实际主线develop-2.0并推送，由用户部署66。整合工作区D:/da-r1117；原开发目录codex/architecture-phase1。本轮沿用spec141/142、ADR0149及架构ADR0144，不新增spec/ADR。

## 已完成

- 本地功能提交7866772a，包含仪表盘、权限/完整删除、样板、长输出与取消修复；原主线876d899d的3个独立提交保留。
- 看板默认输出上限32768，独立环境变量可调，不覆盖全局模型连接；16384实际输出14180/13817余量偏小，32768当前模型探针200、40405ms、12468tokens，HTML逐字一致。
- 合并修正DatasetService构造器同时注入共享源授权和删除清理，补Spring装配行为测试；管理员回看改用迁移后的runtime.session。
- 共享源身份判断依赖runtime.auth.UserIdentity，由原UserStore默认方法适配，保持原角色/用户数据；架构例外清单未放宽，旧纯mock夹具改执行真实默认方法。
- Java17完整clean verify成功04:46：后端1164项，失败/错误0，条件跳过23；前端27文件三个前缀各129项；格式、TypeScript/Vite和JAR/tar.gz打包通过。
- 报告校验成功：必需JPA/H2/市场集成4/1/7项无跳过。core Java17专项43、Python工具15及报告脚本6项通过。
- 23条件跳过包含真实外部环境未启用及Windows不能创建symlink，不冒充全通过；此前真实Agent/浏览器范围见原报告。
- 最终报告docs/test-reports/20261117-dashboard-release-integration.md；部署指引docs/guides/66-dashboard-upgrade.md。

## 当前进度与下一步

- 整合提交bcc3d26af909534c935c8f9caa08c7446adb2ce6已合入并原子推送origin/liyufan_dev与origin/develop-2.0；ls-remote两支一致。最终文档5项通过、空白检查通过。后续仅本次发布记录补记，不改变已验证代码；准备同步原开发目录至该整合结果。
- 66共享数据源旧迁移是否执行未知；仪表盘本身不新增DDL，不改七表/六表cron，不初始化用户或替换密钥。
- 本地样板的知识库/用户/已发布资产不随Git迁移，线上需单独导入建模生成发布，勿直接复制含本机ID的asset.json。
- 本轮未操作66、未停止或接管用户IDEA/Vite。代码变更需重启服务才能加载；部署由用户执行deploy-formal.sh并现场验收。
- GitHub Actions实际运行结果未核实，不以本地通过声称远端CI已绿。
