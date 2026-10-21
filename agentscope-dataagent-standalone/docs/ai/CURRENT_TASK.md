# 当前任务与接手入口

更新：2026-10-21；分支 `boundary-lyf`。本文件随用户授权的 UI 提交交付，实际提交号以 Git HEAD 为准；开发起点为 `bd9d2b8a`。

## 当前：原型导航、业务术语、例行任务与更多（spec079）

用户在数据源UI之后追加授权：这三个页面、左侧栏和顶部“对话 > 知识库”路径框按既有原型改造。

- 已实现：原型侧栏/实际历史与账号、统一面包屑、窄屏导航抽屉及键盘焦点；业务术语表格/搜索/轻量编辑；独立更多页进入既有工作区；例行任务按ADR0082继续占位、无任务API。知识库卡片与聊天/建模主体未重做。
- 文件：AppShell/AppShell.css、ShellNavigation、SessionsSidebar、KnowledgeWorkspace、两个聊天头部、SemanticConfigPage、ScheduledTasksPage、MorePage、WorkspacePage、secondary-pages.css、Icon及main路由。保留下面spec078全部修改，DataSources.css移除被全局抽屉替代的旧窄屏隐藏规则。
- 本次实测：npm run build退出0（TypeScript/Vite，22.74秒），static产物更新；IAB隔离内存API桩验证术语CRUD/搜索/分页/切库旧响应/失败保留草稿/重试、导航/工作区入口、历史会话ID和批量选择、390窄屏/焦点、全屏建模侧栏隐藏；1280×800原型截图对照、2561×1398导航核对，view_image视觉检查；diff空白检查通过。[专项报告](../test-reports/20261021-prototype-navigation-and-secondary-pages.md)。
- 用户随后授权提交，本次提交检查：`mvn test`退出0，736项中710通过、26跳过、失败/错误0；`mvn package -DskipTests`退出0，生成新JAR与tar.gz，默认前端构建和Spotless门禁通过。JAR引用当前UI，私有dev/prod配置未包含。详见专项报告末尾补充。
- 未运行：真实后端/数据库/权限回归、真实任务执行、子路径部署；MDL接口未造桩，全屏建模仅核对侧栏边界。新JAR未启动或部署，既有服务不自动加载。
- 本轮提交包含spec078/079源码、配套文档及static；不推送。上层shared/.seed-manifest.json已有不相关修改保留，前后哈希一致；个人交接仍本地忽略，不入索引。
- 下一步：真实后端可用时联调术语、历史、工作区权限和数据源页；发布仍需用户指令。同事性能任务及原上线待办保留。

## 前一阶段：按既有原型改造数据源管理（实现完成，本轮一并提交）

用户已授权采用 `docs/previews` 原型的数据源管理页面（spec078/ADR0087），不扩大到整站UI或同事性能任务。

- 已实现：独立 `/configure/datasources`；列表、实际状态检测；轻量新建/配置、详情概况与按需库表字段；保留加密/采样、检测后保存和删除约束；窄屏与键盘适配。
- 主要文件：`DataSourcesPage`、`DataSourceFormModal`、`DataSourceDetailModal`、`DataSourceDialog`、`DataSources.css`、连接解析辅助；DatasetsPage入口、main路由及AppShell/SessionsSidebar的本页适配。旧 `DataSourceManagerModal` 移除，构建生成的static资源更新。
- 本次验证：`npm run build`（TypeScript/Vite）退出0；IAB用隔离内存API桩检查创建/编辑/失败/删除/旧响应/导航/390窄屏，原型与实现截图对照；`git diff --check`通过。详见[报告](../test-reports/20261021-data-source-management-ui.md)。
- 未运行：真实数据库/多租户后端回归、Maven测试/打包、真实Java服务与子路径部署验证；已有JAR不自动更新。没有操作原用户连接。
- 阶段结束时未提交；随后用户授权与spec079一并提交。历史验证细节保留在原报告，新增Maven检查见当前任务；个人交接仍本地忽略，没有推送或部署。
- 下一步：在真实后端可用时联调本页，用户另行决定发布；原上线待办继续保留，同事性能工作不接管。

## 历史：2026-10-20 整理收口（已完成，保留记录）

当时要求：先整理和清理，再将保留内容一次中文提交；不拆多次，不推送或部署。

本页随本次单次提交交付，Git最终HEAD标识本轮源码。整理已完成；本次Maven compile（跳过测试/npm）通过，Python5文件AST与MJS3文件语法检查、私有配置排除和差异空白检查通过。未重跑测试；Git Bash不可用，未新增Shell语法检查，不覆盖历史Linux验证。

## 本次收口范围

1. 保留真实故障修复及回归测试：Wren MCP客户端初始化期间临时使用应用ClassLoader并恢复；依赖预检检查MySQLdb；Linux启动日志统一logs。
2. 保留独立模拟BI工作台、宿主、loopback代理、Nginx片段、无密钥配置样例、配套测试和spec075/ADR0086。私有配置不入Git。
3. 保留spec076已有分段计时、按需profile-wren.py及测试，作为诊断能力；没有实施spec077性能方案。首问/沙箱/Wren优化明确由同事处理。
4. 保留application.yml上传50万行/1000万单元格上限，同步中英README、架构配置表及限制说明；其他大小/数量/并发限制不变。
5. 合并重复状态摘要，清理当前文档中的旧“云端未部署”等描述；历史失败、原测试证据和阶段哈希保留在专项报告/SESSION_LOG，不删除原始数据或回滚他人改动。

## 当前产品与云端事实

- 主功能：知识库/上传/外部MySQL-Doris/单库建模与显式发布/共享权限/问数/图表/Python已有分阶段真实验收。工具只改右侧详情，其他布局不重做；例行任务仅占位。
- 云端：应用、prod认证选项、模拟BI同域iframe到真实服务、六份红海文件导入、保存/发布/召回及真实SQL已走通；最大168893行，不能称50万边界通过。模型文件已安装，384维离线检查2.22秒，180秒下载阻断已解除。
- 查询耗时：诊断修改示例发布v9后，召回20.557秒/整轮25.522秒；另一新会话同题召回0.046秒/整轮5.498秒。不是每个新会话都重建；indexed还可能因成功标记缺失触发。默认工作区技能扫描glob→exec可在首轮准备阶段触发沙箱，仍按用户+Agent复用。
- 专用云测试库4a91c2fd-acfa-46a2-9dec-ba43be375bd0、v9及历史保留；原用户“测试”库fa000773-df29-4b80-8ab5-7963690d5df3未被本轮诊断修改。不要清理用户数据或重跑覆盖证据。
- 真实运行进程/端口不能沿用历史PID；如需操作必须重新核对身份。本次不启动、重启或部署服务。

## 已有验证（历史引用，非本次重跑）

- 模拟BI阶段：Maven732项707通过25跳过；Node初轮7/7、MIME契约补充后8/8；真实打包JAR冷启动MCP查询及浏览器权限/过期/切账号通过。
- 分段诊断：Java定向68项、Python11项；真实六表CLI及云端临时副本冷热对照通过。深度profile有明显开销，不作为生产时延。
- 已提交的状态/Wren恢复专项：真实MySQL持久化、重启追问及MySQL/Doris断连/冷却恢复已验证，详见各报告；不能当作当前合并版本或65生产SLO。
- 本次仅整理提交及必要静态/编译检查，不新增或重跑测试；具体本轮检查结果记录于SESSION_LOG和提交正文。

## 待续与职责

1. 等同事交付性能改动；核对时间范围限制实际实现。不要自行继续spec077，避免重复工作。
2. 建模非零CLI输出可能误标完成、批次结果通知拖延同批工具显示仍未修；与同事确认边界后独立处理。
3. 合并后冻结候选提交并按/dataagent/重新构建，记录源码/JAR/tar/镜像/外置配置对应；当前云制品不因本次提交自动更新。
4. 最终全流程回归、目标环境10人同库/多库混合负载与长稳、真实BI/HTTPS、升级回退与备份恢复、小范围灰度。
5. 用户决定跳过准确性规则定制；保留普通结果核对。Docker同事故障待完整orphan-cleanup WARN；现有模拟只能分类原因，未确认其现场根因。

## 证据入口

- [上线总账](../go-live-gap-review-20261012.md)、[单机发布](../single-host-release.md)。
- [模拟BI](../test-reports/20261019-bi-simulator.md)、[真实文件上传](../test-reports/20261019-honghai-upload.md)。
- [建模耗时](../test-reports/20261019-cloud-modeling-latency.md)、[分段计时](../test-reports/20261019-wren-phase-timing.md)、[待实施方案](../specs/077-query-memory-readiness.md)。
- 原始证据在仓库上层.runtime与output，私有配置/密钥/令牌不打印或提交；历史命令/制品哈希只对应其记录时版本。
