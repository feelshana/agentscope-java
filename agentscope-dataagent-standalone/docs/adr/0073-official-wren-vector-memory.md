# ADR 0073：使用官方 Wren 向量记忆后端

## 状态

Accepted

## 背景

问数知识库的 `knowledge/sql` 已由官方 Wren memory 管理，但运行环境未显式选择 memory 后端时可能回退到 grep。旧的 `.indexed-source` 标记还会使升级依赖后继续跳过索引，导致相似业务问题无法使用向量检索。

## 决策

1. 配置的 Wren CLI 环境安装并使用官方 `wrenai[memory-onnx]`，默认设置 `WREN_MEMORY_BACKEND=lancedb` 和 `WREN_EMBEDDING_BACKEND=onnx`。
2. 每个知识库项目继续以 `knowledge/**` 作为源文件，`.wren/memory` 作为该项目的派生索引；不迁移历史数据。
3. 发布项目在源文件或 memory 后端标记变化时执行官方 `wren memory index --no-seed`，并校验最终后端为 LanceDB。
4. 问数 Agent 对每个新问题优先调用一次官方 `recall_queries`；`wren_read_knowledge` 用于读取规则原文，不作为 `knowledge/sql` 的相似问题检索替代品。
5. 索引目录沿用现有 owner/group 项目路径，保持租户隔离。

## 后果

相似问答由 Wren 官方向量检索负责，平台不实现 embedding、向量库或相似度算法。首次索引会产生 ONNX 模型加载和 LanceDB 建索引开销；依赖不可用时发布/查询会明确失败，避免静默回退到 grep。
