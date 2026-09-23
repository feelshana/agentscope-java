# ADR 0016: 知识库列表接口 N+1 查询优化

- 状态：已采纳
- 日期：2026-09-23

## 背景

`GET /api/dataset-groups` 接口有时不响应，导致前端知识库页面一直加载不出来。

## 问题根因

原实现中，`list` 端点为每个知识库调用 `listDatasets()` 获取数据集数量，该方法会：
1. 调用 `getGroup()` 验证权限（1 次 DB 查询）
2. 调用 `datasetRepository.findByGroupId()` 加载该组所有数据集实体（1 次 DB 查询，加载完整实体）
3. 在内存中过滤并取 `.size()`

若有 N 个知识库，则执行 1 + N*2 次 DB 查询，且加载完整实体只为计数，造成严重性能问题。

## 决策

1. **新增 `countByGroupId` 查询方法**：在 `DatasetRepository` 添加 `long countByGroupId(String groupId)`，使用 `COUNT(*)` 而非加载实体
2. **新增 `countDatasets` 服务方法**：在 `DatasetGroupService` 添加计数方法，保留权限校验
3. **更新 Controller 使用计数方法**：`list` 端点改用 `countDatasets()` 替代 `listDatasets().size()`
4. **前端添加超时保护**：`listGroups()` 添加 30 秒 AbortController 超时，避免无限等待

## 理由与权衡

- 性能：COUNT 查询比加载完整实体快数个数量级
- 可靠性：前端超时防止页面永久挂起
- 代价：无，COUNT 查询是标准优化手段

## 影响面

- 后端：`DatasetRepository.java`（+1 方法）、`DatasetGroupService.java`（+1 方法）、`DatasetGroupController.java`（list 端点优化）
- 前端：`api/datasets.ts`（listGroups 添加超时）
