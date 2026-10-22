# 业务文档显著入口与上传反馈验证
关联：ADR 0051、spec 042。日期：2026-10-07。

## 修改
业务文档引导置于建模主区顶部，建议先上传，再提出问题；无文档仍能对话。显示上传中、成功文件名、失败原因及已保存内容预览。刷新依据服务端资料恢复状态；文档保存与异步口径分析分开展示。初始读取较晚返回时不会覆盖刚上传的文档，切换知识库会重置资料和请求反馈。
顶部“查看语义模型”使用突出边框、图标及模型/Cube/视图/MDL 范围说明。输入框旁重复上传按钮已移除。

## 验证结果
- mvn package -DskipTests：成功，含 npm run build（TypeScript/Vite）和 Spotless 检查。
- git diff --check：通过。
- Edge 浏览器 13 项检查全通过，见 document-entry/browser-checks.json。
- 使用验证知识库 2938f8c8-6fee-44aa-a700-6aa8c9769c98，通过原生文件选择控件实际上传 TXT，读取服务器资料证明成功保存；模拟网络延迟检查上传中及防重复按钮。
- 刷新确认已有文档恢复；上传损坏 DOCX 验证真实失败反馈和服务器原资料保留；模型入口跳转与返回保持反馈。成功运行末尾还原该次运行前的文档内容。
- 本轮为前端改动，未修改后端接口或业务规则，未重复运行已通过的后端全量测试。

## 截图
- document-entry/uploading.png
- document-entry/upload-success.png
- document-entry/upload-failure.png

验证服务 localhost:8085 已更新为此次构建。测试上传只针对已有开发验证库，不修改用户截图中的订单库。
