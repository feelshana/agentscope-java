# 66已有BI任务：报表/仪表盘字段升级

2026-11-05，ADR0139/spec119。本次用户授权不做线上备份；仅追加目标字段，不删除表/记录/进度，不改源库。
目标：bi_report 23列、bi_dashboard 39列，其他五表不变。
源表配置大字段不读；目标原id/name/org_id顺序保留，追加列可空以兼容已有数据。

## 1. 更新代码之前暂停
在66当前用户执行，使用旧版已有入口，不需要宿主安装Python：
~~~bash
(
  set -e
  cd "$HOME/dataagent-source/agentscope-java/agentscope-dataagent-standalone"
  bash docker/bi-sync.sh pause
  bash docker/bi-sync.sh status
)
~~~
必须看到paused=true、running=false。若仍running=true，等当前任务协作退出后重查status。
保留原02:00定时任务；暂停期间即使触发也不写数据。禁止先更新源码再暂停：同步容器挂载宿主源码。

## 2. 更新应用与源码
~~~bash
bash "$HOME/dataagent-source/agentscope-java/agentscope-dataagent-standalone/docker/deploy-formal.sh"
~~~
沿用日常发布指引的网络重试参数。发布只构建/替换应用，**不会自动升级BI目标表**。
失败保持暂停，先处理发布报错。不要重新prepare、init或改owner。
当前正式runtime.env（包括搜索Key）保留，不从旧演练目录覆盖。

## 3. 加列、补齐、恢复
仅在发布成功且仍paused=true/running=false后执行：
~~~bash
(
  set -e
  cd "$HOME/dataagent-source/agentscope-java/agentscope-dataagent-standalone"
  bash docker/bi-sync.sh check
  bash docker/bi-sync.sh migrate
  bash docker/bi-sync.sh refresh-basics
  bash docker/bi-sync.sh status
  bash docker/bi-sync.sh resume
  bash docker/bi-sync.sh status
)
~~~
migrate检查任务归属/源指纹/原运行锁、暂停状态、全部源字段类型及目标结构，才追加两表字段。
refresh-basics先读齐五张基础表再同事务替换，失败回滚；成功后核对源/目标行数，设置asset_fields_ready=true。
该步骤不读取日志数据，不改变success_until、日志水位/项目进度/账本；不要求日志历史重新对账。
最终status应为paused=false、running=false、schema_version=3-assets、asset_fields_ready=true；
success_until等日志截止日应与升级前相同。
若需要立即补到昨天，再手动执行一次正常sync，否则等待原02:00任务；不重新安装cron。

任一步失败，set -e停止后续操作，不继续resume。
DDL有隐式提交；一张表成功另一张失败时保持暂停，排除错误后重跑migrate、refresh-basics，再resume。
异常字段/类型/非本任务表拒绝，不自动DROP或重置水位。
DDL之后不能直接切回只识别39列的旧脚本；保留新脚本并修复升级。

## 4. 知识库由用户重新创建
2026-11-05用户明确：表结构与数据补齐后，在线上另建知识库、关联现有数据源的七张表并重新建模；旧知识库由用户按需删除。
本次不修改应用字段刷新逻辑、不自动创建/删除知识库。删除外部表关联知识库不会删除这七张数据库业务表，也不会停止同步任务。
新库上传本目录业务口径.md的最新版本，确认报表23/仪表盘39字段，建模并发布；公共使用范围由用户自行设置。
