# 新手通用示例：虚构订单与客户

当前示例为完全合成的48000笔订单和2000名虚构中文姓名客户，保留重名，以customer_id关联。源码素材在synthetic-v1/，不含真实人员/业务资料或凭据，可以随Git分发。旧报表访问/用户列表仅为历史材料，不再由示例API提供，恢复脚本也不再从历史提取它们。

## 本地开发

在模块目录执行一次：

```bash
python deploy/demo-samples/restore-local.py
```

默认复制并校验到被Git忽略的data/demo-samples。相同文件直接通过，内容或清单不一致则停止，不覆盖。旧环境清单不同可选独立目录，例如`--directory data/demo-samples-commerce-v1`，并同步设置DATAAGENT_DEMO_SAMPLES_DIR；不要删除或覆盖旧资源来绕过检查。

本轮用户本机的data/demo-samples已经安装新文件，原文件保留、原清单备份在忽略的target/local-demo-switch-20261102/backup，无需再手工复制。IDEA环境变量DATAAGENT_DEMO_ENABLED=true，DATAAGENT_DEMO_SAMPLES_DIR指向该目录。重启后端、刷新前端，在新手引导上传弹窗点“使用示例文件”，应看到演示订单.xlsx、演示客户.xlsx。

文件上传不需要示例数据库或只读密码。业务口径可从synthetic-v1/业务口径.md在知识库资料中手动补充；不会自动上传管理员参考答案。

## 外部示例源

同一批Excel可由DemoDataService准备为独立demo_commerce_v1库，表demo_orders/demo_customers，系统源builtin-demo-commerce-v1。默认JDBC为jdbc:mysql://localhost:3306/demo_commerce_v1，默认只读账号demo_commerce_reader，需要本地DATAAGENT_DEMO_PASSWORD；启用时数据集库连接还需准备该独立库的权限，或由DBA提前准备。仅空表导入、数量不完整则停止；重复启动不追加。

显式配置旧demo_data的环境不会被自动改指向或迁移，旧源不会当作新示例展示。旧库、旧知识库、账号与历史文件不自动删除；正式退役另行处理。

## 66切换（本轮未执行）

现有66持久目录仍可能是旧素材。本次仅本地试用，不应直接沿用旧升级指南假定换镜像就换了数据。将来需将synthetic-v1两Excel及清单复制到新的持久目录、修改DATAAGENT_DEMO_SAMPLES_DIR，以及需要外部示例时配置新库URL/只读账号；验证两条引导路线后再决定旧数据公开范围和旧库退役。不重建平台库、不重复导用户。
