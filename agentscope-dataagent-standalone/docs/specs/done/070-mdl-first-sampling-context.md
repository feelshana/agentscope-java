# spec 070：MDL 优先的采样上下文

## 目标

避免同一份采样内容同时出现在列 description、`数据库中的结构化采样摘要` 和 `get_instructions` 中，减少问数上下文噪声。

## 行为

- 上传/关联表的采样继续参与初始列 description 和 MDL。
- `DatasetSampleDocuments` 只维护 数据库采样摘要和结构化刷新依据，不生成完整采样规则文件。
- 已存在的自动生成 `knowledge/rules/data-sampling.md` 在同步时删除。
- 建模与问数提示只保留采样使用边界，不注入 raw 采样正文。
- 用户手写的 `knowledge/rules/*.md` 保持不变。

## 验收

- 初始模型列 description 保留采样生成的字段说明。
- 新同步工程不存在 `knowledge/rules/data-sampling.md`。
- modeling-agent 和 data-agent 的系统上下文不包含 `数据库中的结构化采样摘要` 的样例值。
- 官方 `get_instructions` 只返回用户业务规则及其他非采样规则。
- 采样、MDL、发布和上下文相关测试通过。

