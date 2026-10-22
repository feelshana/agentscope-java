# ADR0105：建模上下文注入编译状态、CLI 日期输出说明与问数排名粒度

## 状态

已接受，2026-10-20。补充 ADR0104。

## 背景

ADR0104 生效后，上传库实测仍有以下问题：

- 提示词已写"首次查询前先 context build"，Agent 仍先查询，因 target/mdl.json 不存在而失败。
- `wren query -o json` 经 pandas `to_json` 默认把日期时间输出为毫秒时间戳。Agent 拿到时间戳后，又用 DATE_FORMAT 重复查询了一次。
- 问数时按"工作区、负责人、部门"分组做负责人排名，同一负责人被拆成两行。图表和回答中 sunhai 的零访问项目数写成 12，实际是 13。
- 表格列名与 SQL 含义不符；Agent 执行了新查询，却声称"没有重新统计"。

## 决策

- `WrenProjectContextMiddleware#buildState` 按 target/mdl.json 是否存在，在运行上下文中如实写入"已编译 / 尚未编译"。尚未编译时，明确要求先执行 context build。用确定的状态信息替代通用提示。
- `wren_cli` 工具描述说明 query 的 table/json/csv 输出格式：查询日期时间用默认 table 输出；确需 json 时在 SQL 中用 DATE_FORMAT 转换，不为换算格式重复查询。
- 问数规则：
  - 按主体排名或计数时，GROUP BY 只放该主体，附加属性用聚合函数带出；
  - 表格列名和图表标题与 SQL 字段含义一致；
  - 回答中描述的执行过程与实际工具调用一致。

## 后果

以上均为上下文与提示约束，不新增工具拦截或计数。编译状态只反映生成上下文时的文件状态，文件修改后的重新编译仍由 Agent 判断。
