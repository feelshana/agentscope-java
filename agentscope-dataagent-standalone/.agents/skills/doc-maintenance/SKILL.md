---
name: doc-maintenance
description: 定期清理项目文档，防止规则与说明膨胀或互相矛盾。用户说"整理文档""清理文档"或一个里程碑结束时使用；Codex 与 Claude 均可按本文件执行。
---

# 文档定期清理

目标：让 AGENTS.md 与 ARCHITECTURE_zh.md 只描述当前有效的规则与实现，历史留在 ADR、`specs/done/` 与 `docs/archive/`。只改文档，不改代码行为。

## 步骤

1. **读现状**：AGENTS.md、ARCHITECTURE_zh.md、`docs/adr/README.md`、`docs/specs/README.md`、`docs/ai/CURRENT_TASK.md`，以及 `git log` 中自上次清理以来的变更。
2. **AGENTS.md**：删除已失效或与代码不符的规则；把逐条追加的细节合并进对应小节；易变的数值删去，改为指向代码常量。保持 ≤ 150 行。
3. **ARCHITECTURE_zh.md**：按代码核对受影响章节；"已退出主流程"的内容移入 `docs/archive/architecture-legacy.md` 并留一行指针；单章 ≤ 250 行。当前待办：第 5.6 节（发布流）待对照代码压缩。
4. **ADR**：
   - 找出与当前代码或 AGENTS 规则冲突的旧 ADR，只把其状态行改为"已被 ADR-xxxx 取代（日期）"或"所述功能已停用"，不改正文；
   - 更新 `docs/adr/README.md` 的"当前有效主线"表和全量索引（新增 ADR 补行）。
5. **spec**：已实施并验收的 spec 移入 `docs/specs/done/`，更新 `docs/specs/README.md`；用 `git mv` 保留历史，并修正指向它的相对链接。
6. **共享记忆**：`CURRENT_TASK.md` 只留当前任务（≤ 80 行），旧检查点追加到 `docs/ai/archive/` 下按日期命名的文件；`SESSION_LOG.md` 超过约 400 行时，把较早部分移入 `docs/ai/archive/`。
7. **历史资料**：不再被当前文档引用、只具追溯价值的文档移入 `docs/archive/`；只有空文件或明显的临时文件才删除，删除前在回复中列出。
8. **验证**：运行 `mvn test -Dtest=DocsConsistencyTest`（链接、索引、状态、规模上限），再运行完整验证命令；在回复中列出移动、删除和改写的文件。

## 不做的事

- 不修改 ADR 决策正文，不修改官方 Wren 技能文件。
- 不为了通过规模检查而提高上限。
- 不把建议或未验证的内容写成已实施。
