# 当前任务：知识库与更多设计提案（2026-11-01 Asia/Shanghai）

## 授权与基线
- 用户要求先给更多/知识库方案图，顺问历史圆点；未授权正式改造或修复未读。
- liyufan_dev@5b1079a6、暂存为空。既有源码/静态包/文档WIP保留，无提交/push/切分支/部署/DB。
- 仅预览/代码核对，无新spec/ADR，不改ARCHITECTURE当前实现。

## 本轮完成
- docs/previews/knowledge-more-20261101：Image Gen探索、两页HTML、复用原品牌/SVG、卡片/列表/无数据/窄屏与浏览器截图。
- 推荐紧凑知识库卡片、数量/创建元信息/主次操作；更多帮助独立+管理员分隔线列表，未增加能力/指标。
- 19138 loopback白名单无API预览，业务按钮仅提示。入口index.html#knowledge/#more。评审顶栏/无数据按钮不进正式界面。
- 圆点：SessionRow使用unread&&!active；后台lastActivityMs>lastReadAt。主侧栏只导航/chat，ChatPanel未调markRead，调用在旧SessionTranscript；读过可能仍出现，未修复。

## 验证
- Playwright11项：搜索/无结果/列表/无数据/更多/模拟入口/认可SVG/两页390px等通过；JS语法通过。
- 已view_image比较生成探索与浏览器实图；纠正生成图品牌/图标漂移，最终评审以HTML及03/06实图为准。
- 文档测试与最终检查见SESSION_LOG；无真实DB/LLM/66测试或业务静态包构建。

## 下一步
- 等用户选择后改正式DatasetsPage/MorePage；已读修复另列候选。
- 上轮回答工具条完成，归档archive/20261101-before-knowledge-more-design.md与专项报告。
- 全站其余A/B候选仍待选，不由本轮推定批准。
