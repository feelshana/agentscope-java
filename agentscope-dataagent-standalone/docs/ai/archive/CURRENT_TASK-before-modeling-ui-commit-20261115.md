# 当前任务：衍生列操作与助手输入区对齐

2026-11-15 Asia/Shanghai；codex/architecture-phase1，基线d0f8cad5。小范围布局修正，沿用spec140，无ADR，无后端/DB/Agent/权限变更。

## 已完成

- 新增衍生列按钮移至衍生列分组标题右侧；独立于原生summary，折叠时仍可操作，保留原权限、忙碌禁用及弹窗。
- 助手composer改为垂直居中，空输入/有内容/多行输入均保留原发送行为和尺寸。
- SemanticWorkbench.tsx/css、ModelingChatPanel.tsx及spec140/报告/共享记忆同步。纯布局不涉及架构边界，不增加ARCHITECTURE内容。
- 既有各页UI和共享AI图标修改全部保留；未提交、push或部署。旧CURRENT归档before-layout-nits-20261115.md。

## 验证

- 相关四文件ROOT前端17项通过，失败/跳过0。
- 真实IAB目标知识库1920/1440：标题操作同行，展开/收起及从收起状态打开原新增弹窗通过；取消后仍收起。按钮不是summary子元素。
- 空输入禁用与三行输入可用，通过DOM测量输入框/发送按钮中心差0px；1440标题/新增按钮中心差0px，无body横溢出。
- 未点击保存/发送，没有真实建模写入。数据仅覆盖衍生列为空；未新增真实衍生列或跑LLM/SSE。
- 1440三栏时关系图工具栏原有挤压换行仍可见，本次未扩大修改范围；不宣称全工作台响应式验收。
- 最终构建和DocsConsistencyTest结果见报告补记；未跑完整clean verify。

## 状态与下一步

- 用户原Vite5173/IDEA8080及Chrome保留；独立IAB页已关闭，视口覆盖重置，输入清空、原分组展开状态恢复。
- 外部设计稿与基线文件保留。刷新页面即可查看；其他既有未提交修改来源见前轮归档。

最终验证：TypeScript/Vite与DocsConsistencyTest 5项通过；空白检查通过，保留既有包体积提示。
