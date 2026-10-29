# 当前任务：容器部署全流程回归与同机对照（2026-10-29 Asia/Shanghai）

## 授权、基线和边界
- 用户要求本地及个人云8.137.156.84复刻66方案，验全流程、正确性、速度、性能、并发；明确允许测试专用tar包与SSH隔离资源。正式66包仍用户构建，不操作66。
- 分支codex/integrate-boundary-lyf-v2@b82bb7f4加既有工作区；未切分支/提交/推送，保留所有既有修改与旧服务。正式业务Java/前端未改。
- 正式变更含运维controller sandbox实例/镜像显式覆盖（默认不变）、Docker测试、部署文档和本轮报告。spec098/ADR0117沿用；实验Java仅Git忽略output。
- 此前环境阶段见archive/20261029-environment-complete-before-ab.md；重复中途检查点保留在本地Git忽略的output/precommit-cleanup-20261029，不随源码交付。以下取代其中过时状态。

## 已完成的实测
- 隔离冻结1338源码文件，Maven verify Java946/0失败/0错误/28跳过；正确DATAAGENT_BASE_PATH=/dify/dataagent/重打包，JAR85889dad…，tar dbc24001…，无私有配置/人员资料。
- 云Ubuntu24.04.5/4CPU/约15GiB；两模式同镜像提取Java17.0.21/Python3.12.16/Wren0.15及库、同JAR/模型入口/数据/语义源/资源2CPU6GiB，独立分析沙箱1CPU1GiB，idle60s。不同Docker存储Id不同但11层DiffID一致、传输归档SHA一致。
- 云本轮/data/dataagent-ab-20261029-0153，两专库da_ab_20261029、da_ab_20261029_data及限两库账号；DB代理172.17.0.1:23306。旧Java15160/307708及MySQL/Nginx保留，不改全局库/监听。
- 云原生/容器各110题全部完整返回；p50=11.862/11.879s，p95=21.424/24.572s。工具路径不同，不能视为精确Docker开销。各39文件可下载且越权拒绝；完整返回不等于答案正确。
- 内容错误已核对SQL/原文：02-10原生错日期范围及CSV，08-10全员擅加在职，08-09规则出处夸大，08-05字段口径冲突；单位/拆查询/比例自动匹配差异不可一律判错。不修改测试规则刷分。
- 本地原生/磁盘容器各11场景；新上传→文档→Auto-pilot→校验发布→权限→SQL/Python/CSV/中文PNG闭环通过；云原生27/27、容器26/26（附件数差异）。外部两表+7天时间门禁缺日期/合法日期/越窗正反例通过。
- 模拟BI固定链接新页iframe/Local Storage登录：管理员/新user、QUERY只读详情/预览/字段/技术信息、管理按钮隐藏和写API拒绝通过。不是公司真实BI现场。
- 上传6边界含500001行拒绝，49注册/49物理无孤儿；本机真实stop/replace历史/反馈/3附件SHA/旧CSV恢复通过，停止至UP17.194s。
- 本地/tmp实为1.6GiB tmpfs触发附件2GiB可用保护，已迁磁盘并仅清本轮旧tmpfs；不降低保护。原生提取MariaDB插件路径错误已局部修正。两者属测试准备，非Docker业务必然故障。
- 原版本同库5用户通过，原生10用户6/10、独立有界15用户0/15；容器有界10用户0/10。不能宣称正式版本10–15稳定。线程/MCP现场支持共享boundedElastic嵌套阻塞，未OOM，MySQL空闲。
- 独立同依赖Echo MCP：同步0/2、异步2/2。线程池80实验仍soak超时；仅Registry异步实验纯15过、mixed15失败，现场发现Toolkit注册也block同池。

## 本轮收口与具体后续（09:49检查点）
- output/container-ab-20261029/diagnostic-async2只改两个class：Registry用框架异步MCP，Middleware组合注册Mono替代block；其余203库/类/前端相同，正式Java不变。SHA764e6f7f…，可审阅diagnostic-only.patch，不是正式修复。
- async2默认线程池同预算：原生与容器各131/131技术完成，主数值分别131/131与127/131；各5轮15用户混合（其中5个Python）均无技术失败，仅短批次非长稳。原生34/34 CSV正确，容器32/34 CSV正确、34/34 PNG存在。
- 容器4个数值失败已追踪：2次SQL正确但模型Python手写金额错，1次仅答案转述错，1次连接后COUNT(*)数错；不是Python算术或Docker误差。load/numeric-error-trace.json有工具输入输出。
- 容器async2另11场景11/11完成、5库10用户并发10/10完成（全文仍须单独评分）。真实stop/replace经Nginx前缀后历史/反馈/2附件SHA保持，restore_artifact+Python核对旧CSV=1000，停止至UP31.105s，续问14.929s。
- 本轮云业务容器已停保留(exit143/noOOM/Restart0)，native实验unit全部停，DB代理/metrics/专属Nginx已停，隧道54208/3232已关闭。云原Java15160/307708仍在；本地测试容器停，原18766/18860及其他Docker应用保留。包/两库/目录/镜像/证据不删除。
- 输出ROOT为仓库根output/container-ab-20261029；remote.py用已有SSH资料且字节stdin防CRLF，私有env不打印。load_suite/run_bank/harness复现实测，AB_CHAT_MAX_SECONDS=120。
- 本轮已完成上述对照与收尾，报告见docs/test-reports/20261029-container-runtime-ab-regression.md。后续应将两处实验改动作为正式bug修复任务，补调度/空范围/错误/取消/排队撤权回归，再重跑发布包；当前不自动扩展业务改造。
- 最终Spotless/前端构建和48测试/DocsConsistencyTest5已通过，git diff --check无错误；共享记录最终增量再复核DocsConsistencyTest。不自动提交/推送。
- 生产66旧内核/真实公司BI代理/真实公司模型/两生产库、多小时长稳、持续到达及模型故障注入仍未验；短15用户批次不能承诺容量。实验最终是否合入正式代码待后续业务修复任务。
