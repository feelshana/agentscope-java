# 首次上线数据库SQL交付验证（2026-10-28）

## 交付范围

基于 `codex/integrate-boundary-lyf-v2@b82bb7f4`，用户要求提供建库、平台完整建表、BI人员插入和管理员赋权SQL，供BI数据开发页面执行。本轮不改业务代码、不新增表字段、不生成Maven发布包、不操作公司环境；延续ADR0116身份与权限边界，不新增spec/ADR。

通用建库/赋权/核对SQL及操作指引进入项目文档；真实人员资料SQL只放用户桌面的本地交付目录，不进入Git或通用发布包。原Excel未修改，仅采用账号、中文名、公司和部门，不导入BI密码、手机号、角色或BI主键。

## SQL行为

- 默认库名dataagent_platform/data_agent，与当前application一致。前者执行现有0000完整37表DDL，后者先保持空库；桌面DDL仅增加明确的目标库限定，表定义与正式DDL相同。
- 人员SQL为单条INSERT，1484条VALUES，共525001字节；人员字符串用UTF-8十六进制表达式，避免SQL转义和连接编码差异。其内容仍是可恢复的真实人员资料，不是加密或脱敏。
- 新账号UUID由MySQL生成，角色user；每个密码均由现有Spring Security BCryptPasswordEncoder以默认cost10对独立随机值生成，生成时逐个执行matches验证，随机原文不保存。
- 重复账号通过唯一username更新display_name/company/department，不使用REPLACE，不修改已有ID、密码、角色、绑定、创建时间，也不删除Excel以外的账号或写授权表。
- 管理员SQL先查指定账号，再追加admin（空角色按默认user处理），已含admin时不重复追加；不创建目标账号，不改密码。执行后从BI重新进入更新令牌。
- 采用MySQL当前仍支持的VALUES(column) UPSERT语法，以兼容既有版本；8.0.20起的弃用提示不是本次执行失败。实际生产MySQL版本仍需核对。

## 本轮真实验证

在本机MySQL **8.0.41** 创建两个独立命名的临时数据库。只将交付SQL中的两个固定库名替换为本轮临时库；没有在现有业务库执行这些写入。

| 验证 | 结果 |
| --- | --- |
| 执行实际建库SQL | 两个库成功创建，UTF-8/InnoDB建表正常 |
| 执行完整平台DDL | 37表成功，上传库0表 |
| 执行1484人单条INSERT | 1484人成功，约0.296秒；四列逐条与Excel核对一致 |
| 新账号ID/密码/角色 | 1484个不同UUID、有效独立BCrypt哈希，全为user |
| 管理员SQL执行两次 | 角色仅user,admin，未重复追加 |
| 重复导入 | 约0.083秒；预置旧中文名更新为源资料，ID、已改密码、管理员角色、绑定、固定创建时间完全保留 |
| 名单外账号 | 独立合成账号仍保留，总1485，未因导入删除 |
| 执行核对SQL | 所有SELECT成功 |
| 当前完整应用ddl-auto=validate启动 | 独立18861实例健康UP，sql.init.mode=never，未靠自动建表补结构 |
| SQL导入普通用户BI登录 | 复用原平台ID，仅user角色 |
| SQL指定管理员后BI登录 | 复用原平台ID，包含admin角色 |

临时Java实例已停止，本轮自建的两个测试库已删除；已有IDEA8080及18766/18860演练服务保留。仅在本轮测试库短暂写入了真实人员资料，未再次导入或修改已有本地业务用户表。

补充验证：`mvn test -Dtest=DocsConsistencyTest` 5项通过，`git diff --check`通过。未改应用代码，未重跑全量Java、前端、问答或并发测试；未提交或推送本轮材料。

原始计数、耗时及构建文件校验值在忽略的output/production-database-validation-20261028.json与output/production-database-materials-20261028.json，启动日志在本轮独立output目录。报告不保存令牌、密码原文或人员明细。

## 边界

这是本机MySQL及当前编译应用的验收，不代表生产版本、账号权限、BI编辑器的单语句长度或多语句执行能力已验收。SQL导入不依赖应用先启动、Python运行环境或管理员JWT。生产密码入口仍禁用，不因这次SQL导入变更策略。大量导入已提前离线生成哈希，本次写库耗时不能与原API在线生成哈希的耗时直接比较，也不是容量结论。
