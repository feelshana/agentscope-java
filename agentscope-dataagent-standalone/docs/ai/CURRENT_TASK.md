# 当前任务：develop-2.0 主线整合（2026-10-31 Asia/Shanghai）

## 授权与基线
- 用户确认在本地develop-2.0合入liyufan_dev及旧design分支，冲突方案已确认；不push、不部署、不初始化数据库、不清理已有服务资源。
- fetch核对：origin/develop-2.0与origin/wangchao_dev均为3bb64fc9，origin/liyufan_dev为bc3bc14d，origin/design/wren-official-capability-boundary为ea35983f。
- 本轮起点工作区干净；当前已切换develop-2.0并跟踪origin/develop-2.0。合并验证/提交状态以Git及后续记录为准。

## 整合方案与进度
- liyufan分支两笔提交：设计/测速/部署资料及八项体验小修；同事主线的新手指引、内置示例、建模绑定缓存与DATETIME修复一并保留。
- 业务源码自动合并；双方CURRENT_TASK分别归档为archive/20261031-before-mainline-merge-{wangchao,liyufan}.md；DECISIONS和SESSION_LOG保留双方记录。
- 前端生成资源由合并后的源码按/dify/dataagent/重建，不选择任一旧包覆盖。
- 旧design唯一新增ea35983f原意将10万行扩大到20万；当前主线YAML已有50万行/1000万单元格。按用户确认保留更高现有效果，将Java默认/前端备用/文档统一为50万行/1000万单元格。
- 当前阶段：处理liyufan合并与生产构建；旧design整合及最终相关验证待完成。

## 验证与待续
- 本轮验证结果待执行后补记；历史测试不作为本轮合并通过证明。
- spec105真实验收、示例部署文件/只读账号配置及数据库升级仍属于后续部署事项；本轮不实际执行。示例默认关闭，用户原始Excel不入仓库。
- 八项UI真实BI剪贴板/中文输入法现场验收及线上音乐建模测速对照待用户继续；历史实测见test-reports/20261031-launch-ux-polish.md和20261031-modeling-speed-comparison.md。
- 普通修改只验证涉及内容，不开展无关重构、大规模压测或真实LLM费用测试；保留本地原隔离服务及数据。
