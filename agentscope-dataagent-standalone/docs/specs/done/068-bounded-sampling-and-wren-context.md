# 068：有界采样与 Wren 样本复用

## 目标

外部表关联不再使用 COUNT(*)、逐列 DISTINCT；一次 LIMIT 1000 读取生成列样例，建模/问数复用官方工程资料。

## 行为

- 每表最多 1000 行、每列最多 20 个值、值最长 256 字符；长值标记截断，二进制跳过。保留样本内不同值计数、NULL 数、采样时间及来源，不能据此断言全表枚举。
- 采样关闭不读数据；失败记录中文状态，继续关联表结构；无自动重试和全表回退。
- 持久化摘要 JSON；raw/data-samples.md 提供建模资料，knowledge/rules/data-sampling.md 提供官方问数上下文。平台生成文件不可经 Agent 文件工具改写。
- 建模系统上下文注入最多 6000 字符采样资料及直接可见的通用约束；每次构造上下文重新核对当前授权项目，不用首次发送标记丢失恢复会话上下文。问数只注入规则，读取已发布资料。
- 列 description 仅保存简短样例提示；[enum] 只由有业务依据的建模流程生成。
- 外部 API rowCount 为 null，显示未统计；采样行数单独展示。历史外部记录同样返回未知，文件上传准确行数保留。
- MANAGE 可手动刷新外部样本；刷新失败保留旧成功摘要并返回错误；成功更新草稿、重新发布后进入问数。历史数据不自动补采样。

## 接口与存储

DatasetVO 增加 sampling（摘要对象，可空），rowCount 改可空。POST /api/datasets/{id}/sample-refresh 返回更新后的 DatasetVO，复用权限和资源锁。dataagent_dataset 增加 sample_summary_json LONGTEXT 可空列，以容纳宽表摘要。外部表内部历史 rowCount 不迁移、不继续更新，API按来源返回未知。

## 验收

测试空表、1001 行截断、重复/高基数字段、NULL、长文本、二进制、超时、关闭采样；关联链路不得 countRows。验证样本不生成枚举标签，平台文件写保护、建模摘要长度和知识库隔离、发布资料可通过官方规则读取。前端检查未知行数与样本状态。运行 mvn test、mvn package -DskipTests、frontend/npm run build；真实 LLM 遵循效果与数据库取消不作为单元测试已证明的结论。
