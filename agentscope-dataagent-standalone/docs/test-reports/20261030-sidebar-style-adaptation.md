# 2026-10-30 侧栏颜色合并适配纠正

用户反馈66侧栏仍像旧样式。实际fetch后同事分支最新仍为1f34ffc5，`git merge-base --is-ancestor`确认其已是本地d1c762ff的祖先，无新提交漏合；用户回传容器`/opt/dataagent/app/source-revision`，字符辨识对应d1c762ff40abc0b4d8ff9be1ee804c7d389159f6。现场命令由用户执行，助手未连接66。

## 根因与范围

同事1f34ffc5在SessionsSidebar的旧S样式对象中调整brandTag、listHeaderTitle、groupLabel、rowTitle颜色，并修改global.css的通用导航文字色。整合分支保留了原来app-rail结构与AppShell.css，这几处实际DOM使用rail-brand-tag、rail-history-label、rail-history-day、rail-session-open、rail-nav-item类；旧S对象/da-navitem并不控制这些文字。Git合并成功与功能测试通过，不能证明对应的视觉变化已适配到实际DOM。

修正AppShell.css五处实际颜色，次要文字对齐--da-text-3，正文与普通导航对齐--da-text；添加维护注释明确展开侧栏的样式入口。不更改侧栏宽度、白底新建对话、更多页导航、中文资料与公司、管理员入口或收起按钮。两分支原有侧栏布局不同，本次同事提交的增量是文字对比度，不是整体布局替换。

## 验证

- Java1008项，0失败/0错误/32条件跳过；前端三路径各57项、生产前缀TypeScript/Vite构建、Spotless/package通过。
- Playwright隔离API挂载真实AppShell/SessionsSidebar，逐项检查五个实际DOM节点的计算颜色；模拟管理员确认更多页大模型/公共库入口、中文名和公司保留、收起展开可用，390宽度无横向溢出。首次夹具因检查展开按钮早于页面挂载而超时，修正等待后通过，未改产品逻辑。
- 在同一DOM临时加载HEAD原AppShell.css复现旧色，再移除覆盖层验新色：历史会话标题由rgb(148,155,171)改为rgb(107,114,128)。新旧侧栏和桌面/手机截图在output/database-handbook-sidebar-20261030，人工查看。
- 同批扩充docs/guides/user-admin.md为66数据库日常命令手册，新增guides/README.md专题索引；十个Bash代码块语法检查通过，命令与现有user-admin.sh/Compose配置核对。脚本真实MySQL20组为上一轮报告的历史结果，本轮未重新操作数据库或测试手工资料SQL的生产执行。

修复与文档当前仅本地修改，尚未提交/push/部署。66仍是d1c762ff，后续需用户提交push、拉取构建新镜像并upgrade后才加载新颜色。未新增迁移、未导入人员、未改变应用问数或认证逻辑。
