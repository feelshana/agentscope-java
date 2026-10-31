# 当前任务：develop-2.0 主线整合（2026-10-31 Asia/Shanghai）

## 授权与基线
- 用户确认在本地develop-2.0合入liyufan_dev及旧design分支，冲突方案已确认；不push、不部署、不初始化数据库、不清理已有服务资源。
- fetch核对：origin/develop-2.0与origin/wangchao_dev均为3bb64fc9，origin/liyufan_dev为bc3bc14d，origin/design/wren-official-capability-boundary为ea35983f。
- 本轮起点工作区干净；当前已切换develop-2.0并跟踪origin/develop-2.0。合并验证/提交状态以Git及后续记录为准。

## 已完成整合与提交状态
- liyufan分支两笔提交：设计/测速/部署资料及八项体验小修；同事主线的新手指引、内置示例、建模绑定缓存与DATETIME修复一并保留。
- 业务源码自动合并；双方CURRENT_TASK分别归档为archive/20261031-before-mainline-merge-{wangchao,liyufan}.md；DECISIONS和SESSION_LOG保留双方记录。
- 前端生成资源由合并后的源码按/dify/dataagent/重建，不选择任一旧包覆盖。
- 旧design唯一新增ea35983f原意将10万行扩大到20万；当前主线YAML已有50万行/1000万单元格。按用户确认保留更高现有效果，将Java默认/前端备用/文档统一为50万行/1000万单元格。
- liyufan已合并为dd1fc811；旧design冲突已按确认方案处理，本次收尾为合并提交。两次合并均保留来源提交历史，不以cherry-pick替代。最终提交号、祖先关系与工作区状态见Git。

## 验证与后续
- 本轮：前端三路径各64项、TypeScript及生产前缀构建通过；后端相关98项（含文档5项）与Spotless通过，资源复制/JAR封装通过，HTML及JAR资源逐项一致。新增默认值与YAML契约、部署覆盖及前端策略重试/备用回归。
- Playwright仅在原合成19133夹具核对：问数步骤指引、复制按钮共存、40px圆形按钮与键盘回到最新，无pageerror；预期图表503用于失败退路。未操作用户19131真实会话。CLI多行参数首次解析失败后改用--filename运行，最终通过。
- 收尾DocsConsistencyTest再次5项通过，diff检查无误。
- 本次未运行无关全量、真实LLM/线上业务验收或数据库升级；详细命令和边界见../test-reports/20261031-develop-mainline-integration.md。
- spec105真实验收、示例部署文件/只读账号配置及数据库升级仍属于后续部署事项；本轮不实际执行。示例默认关闭，用户原始Excel不入仓库。
- 八项UI真实BI剪贴板/中文输入法现场验收及线上音乐建模测速对照待用户继续；历史实测见test-reports/20261031-launch-ux-polish.md和20261031-modeling-speed-comparison.md。
- 普通修改只验证涉及内容，不开展无关重构、大规模压测或真实LLM费用测试；保留本地原隔离服务及数据。
