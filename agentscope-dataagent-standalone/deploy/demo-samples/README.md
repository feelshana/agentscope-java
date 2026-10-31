# 新手示例文件：本地与服务器各自持久保存

66已部署并由用户确认线上可用。两份Excel曾在提交 `011d06b97fb6ebcbfd42f87213864f65e4779259` 中临时通过GitHub传输，随后从当前源码树删除；Git历史仍保留原字节。当前目录保留恢复脚本、校验清单和二进制属性。

## 本地开发

在项目模块目录执行（Python 3，无额外依赖）：

```bash
python deploy/demo-samples/restore-local.py
```

脚本从上述历史提交按二进制读取两份原文件，验证SHA256后写入模块 `data/demo-samples/`。该目录被Git忽略，切换develop-2.0/liyufan_dev和正常pull不会删除本地文件。已存在且校验相同则直接通过；不同则停止且不覆盖，不需要每次手工复制。脚本同时保存SHA256SUMS。

IDEA后端Working directory设为项目模块目录，在本地运行配置设置 `DATAAGENT_DEMO_ENABLED=true`；默认samples-dir即data/demo-samples。工作目录不同的启动方式可设置 `DATAAGENT_DEMO_SAMPLES_DIR` 为本机绝对目录。

这就可以测试内置Excel上传。外部示例数据源还需要本机demo_data与独立只读账号：`DATAAGENT_DEMO_JDBC_URL` 指向本机MySQL，`DATAAGENT_DEMO_USERNAME` / `DATAAGENT_DEMO_PASSWORD` 放IDEA环境变量或本地忽略配置。不开启时功能保持默认关闭。不能将66私有凭据提交到仓库。

恢复脚本需要本地Git历史包含011d06b；缺少历史（如浅克隆）时会明确停止，先获取完整主线历史或将原文件复制到目标目录再校验。Windows不要用旧PowerShell的文本重定向导出xlsx，脚本始终按原始字节写入。

## 66及后续更新

66持久目录固定为 `/data/dataagent/rehearsal-app/data/demo-samples`，runtime.env中的 `DATAAGENT_DEMO_SAMPLES_DIR` 指向这里。两份Excel和SHA256SUMS已在该目录校验通过；示例源已导入并经demo_reader验证。正常源码更新只删除源码目录受Git管理的副本，现有upgrade保留服务器持久目录与配置，不需要重新复制、导入或重启仅为删除仓库副本。

新服务器仍需单独恢复/提供文件并配置示例库，不把既有66准备完成理解为其他环境自动就绪。详见[66更新指引](../../docs/guides/66-update-operations.md)。
