# ADR 0021: 运行期动态 wren 连接档案（外部数据源组发布）

日期：2026-09-28
状态：已接受（specs/010 M4）

## 背景

外部数据源由用户在页面**运行期**配置（服务启动后才入库），其表在远程 MySQL 实例
（例：腾讯云 `bj-cdb-*.sql.tencentcdb.com`）。M3 的 wren 运行期链路只有一条启动期
生成的 profile（`WrenProfileHome` 从 `dataagent.dataset.datasource.url` 解析，指向
平台数据集库），`serve mcp` 全组共用——对外部源组，MDL 重写后的物理 SQL
（`test_data`.`active_user_stats`）被发到本地实例，运行期必报 1146（2026-09-28 实证：
远程 `test_data` 库三表存在、本地同库只有两张无关表）。同时发现 wrenai connector
`_apply_limit` 无条件尾部追加 `LIMIT n`（与 SQL 内显式 LIMIT 叠加成 1064）。

约束事实：
- 一个 wren 工程（= 一个知识库组）在 `serve mcp` 时只绑定**一条**连接（`--profile`）；
- MDL 重写后的物理 SQL 带 `schema.table` 限定，连接默认库无关紧要；
- wrenai `MySqlConnectionInfo.database` 必填但接受空串（实证），MySQL 协议允许
  不选库连接；
- `validate`/`build` 均不连库，表不可达只能在运行期暴露（M3 现状）。

## 决策

1. **连接目标在发布期解析**（`MdlPublishService#resolveWrenSource`）：全上传组 →
   默认 profile（M3 行为不变）；全表关联自同一 MySQL 外部源 → 专属 profile
   `ext-<datasourceId>`；其余（混合/多源/源删除/非 MySQL）→ **拒绝发布**并返回
   结构化中文错误。跨实例 JOIN 物理不可能，拒绝比静默产出坏快照诚实。
2. **发布期连通性预检**（`WrenSourceProber`）：发布前用目标连接按
   information_schema 验证组内每张物理表存在，失败即拒绝发布——把 1146 从运行期
   提前到发布期（发布本来就要用户显式操作，是承担「响亮失败」的正确位置）。
3. **profiles.yml 幂等全量重建**（`WrenProfileHome#ensureAll`）：文件内容 =
   默认 profile + DB 内每个 MySQL 外部源一条。单一事实源是数据库而非文件状态，
   启动 `ensure()` 与发布走同一渲染——重启不会抹掉已发布组的外部条目（读-改-写
   方案被否决：要解析自己写的 YAML，且多副本共享盘下有跨进程竞态；全量重建天然
   幂等，last-writer-wins 的内容仍一致）。无库名 URL 的 `database` 写空串。
4. **快照携带连接选择**：发布成功写 `published/wren-source.properties`
   （`profile=...`），`WrenInstanceRegistry#spawn` 读它选 `--profile`；缺失回落
   默认 profile（M3 存量快照零迁移）。profile 属于快照的一部分——DIRTY 组旧快照
   继续用旧连接作答，与「旧版本继续服务」语义一致。
5. **wren_run_sql 工具描述补 LIMIT 约束**：禁止 SQL 内显式 LIMIT/OFFSET，行数走
   limit 参数（wrenai 源码实证 `_apply_limit` 无条件追加）。

## 后果

- 正面：外部源组可发布可问数（用户场景闭环）；表不可达在发布期暴露并指明表名；
  旧组零迁移；profiles.yml 无孤儿条目（随 DB 收敛）。
- 负面/权衡：发布增加一次远程往返（外部源组）；外部源密码明文落 profiles.yml
  （与平台库现状一致，wren 亦不提供加密口）；共享盘多副本同时发布存在小竞态窗口
  （内容幂等，可接受）。
- 明确不做：PostgreSQL 外部源发布——MDL `data_source`、parse-types 方言与全局
  `typeCache`（无方言维度）需要按方言隔离改造，另立里程碑；跨实例 JOIN——物理
  不可能。
- 遗留观察：wren `serve mcp` 子进程在同一实例上并发调用两个工具时偶发死亡
  （MCP session terminated，2026-09-28 日志）——实例池 invalidate/重建自愈已兜底，
  是否需要调用级串行化待压测定案。

## 关联

- specs/010 M4；ADR 0018（双通道总纲）、ADR 0020（M3 运行期）
- wrenai 源码：`core/wren/src/wren/connector/mysql.py#_apply_limit`、
  `core/wren/src/wren/mcp_server.py`（DEFAULT_ROW_LIMIT/MAX_ROW_LIMIT）
- 诊断记录：`logs/LLM.log`（2026-09-28 会话，三错误叠加）
