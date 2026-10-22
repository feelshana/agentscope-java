# ADR 0082：采样结果以初始 MDL 列描述为唯一问数入口

- 状态：Accepted（2026-10-17）
- 关联：ADR 0080、spec 068；官方 enrich-context 采样写回规则。

## 决策

上传或关联表时收集的采样值继续用于初始字段描述和 MDL 生成。平台仅保留数据库中的结构化采样摘要用于内部刷新和审计，但不再把完整采样摘要写入 `knowledge/rules/`，也不通过问数侧 `get_instructions` 注入原始采样数据。

采样规则说明可以作为运行提示的一部分保留；列级业务含义、枚举、NULL 或哨兵值须在确认后写入 MDL 列 description。跨表默认过滤和业务约定仍写入 `knowledge/rules/`。

同步时删除历史自动生成的 `knowledge/rules/data-sampling.md`，不修改用户手写的其他规则文件，也不处理历史 MDL 内容。

## 后果

`describe_model` 返回初始 MDL 中已有的列描述；`get_instructions` 只返回真实业务规则。原始采样资料不会污染每轮问数上下文，但仍可用于后续受控刷新和审计。

