# ADR0108：YAML 写入语法检查、models 目录文件限制、年份规则提级与 wren_cli 分段计时

## 状态

已接受，2026-10-20。补充 ADR0105/0106。

## 背景

上传库重新建模共用 4 分半钟，日志显示以下问题：

- Agent 在字段描述中写入未进块格式的 `[enum]`/`[null]` 行，`context validate --strict` 连续 3 次因 YAML 解析失败报错。Agent 随后创建 `metadata.new.yml`、`metadata.tmp.yml` 等副本绕行，第 2、3 轮耗时约 2 分钟。
- 第 1 轮 `wren_cli` 单次调用 13–31 秒，但计时日志只有调用总时长，无法区分等待工程锁、绑定连接和 CLI 执行。MySQL 进程列表正常，没有残留查询。
- 问数"缺少日期先询问"的最高优先级段落使 Agent 对已写明月日的问题仍追问年份。

## 决策

- **YAML 写入语法检查。** `WrenProjectFilesystem` 对 `.yml`/`.yaml` 的 `write_file`、`edit_file` 及确认预览做 SnakeYAML 语法解析。不合法时不保存（edit 恢复原内容），返回行号、列号和"多行描述使用 `description: |` 块格式"的修正提示。只检查语法，不做 Wren 语义校验。
- **models 目录文件限制。** `WrenProjectAccess#path` 只允许写入 `models/<模型名>/metadata.yml` 和官方 `ref_sql.sql`（wren context v2–v5 布局），拒绝副本和临时文件。
- **年份规则提级。** 问数最高优先级段落写明：已写明月日或相对日期即视为已提供日期，未写年份按当年理解，不再确认；指标口径先按已发布的规则和召回示例理解，规则未定义且确有实质歧义时才询问。
- **wren_cli 分段计时。** `OfficialWrenToolkit` 在 `execution-timing.log` 中分别记录 `wren_cli.lock_wait`、`wren_cli.bind_profile`、`wren_cli.execute`；超时记为 TIMEOUT。

## 后果

YAML 格式错误在写入时即被发现，不再拖到 validate 阶段，也不会留下损坏的文件。下次出现慢调用，可以直接看出时间花在等锁、绑定连接还是执行上。旧的扁平 `models/x.yml` 写法不再被接受，相关测试已改为官方目录布局。
