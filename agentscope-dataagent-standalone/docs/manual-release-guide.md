# DataAgent 发布说明入口

更新：2026-10-17。原手册来自旧分支，其“只在改前端时构建”“配置随包”“生产仅ddl-auto=update”等说明已与当前Maven/发布包不一致；为避免双份步骤相互矛盾，统一使用下面的当前文档。旧内容可从Git历史查阅。

- [Linux单机发布、Maven打包、conf/bin/lib、start/stop与升级回退](single-host-release.md)
- [BI同域/dataagent/部署、接口和Nginx流式代理](integration/bi-login.md)
- [权限、上传及单进程容量预算](platform-concurrency-and-release.md)
- [当前上线待办与风险](go-live-gap-review-20261012.md)

私有dev/prod配置外置且不入Git/包。普通Maven构建默认重建前端；/dataagent/部署在构建前设置DATAAGENT_BASE_PATH。生产样例使用ddl-auto=validate并关闭演示SQL，先由DBA创建两个库和所需schema/迁移；开发update与生产迁移不要混为一谈。具体命令及配置以单机发布说明为准。
