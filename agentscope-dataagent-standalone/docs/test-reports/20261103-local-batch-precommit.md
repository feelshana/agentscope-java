# 演示数据、正式库与BI同步批次提交检查

2026-11-03 Asia/Shanghai；用户授权整理全部当前修改并完成个人分支本地提交。起点liyufan_dev@19fcdbde，不push、不合并主线、不操作66。

## 提交范围

- 48000订单/2000虚构中文姓名客户、新手示例接入与独立版本源，保留数据生成、参考答案及校验材料。公开模型只使用演示文件及业务口径，管理员参考资料不自动上传。
- 正式外置两库预检、独立候选与显式接替/回退、交互私有配置；BI固定七表39列、既有项目索引分段、事务与进度/核验、每日任务和可恢复停用。
- 更多的全局管理员只读同步状态页、权限/安全投影和失败提示；知识库卡片悬停及无业务说明留白，保留现有布局与菜单编辑。
- 对应spec/ADR/架构、操作指引、共享记录、测试、正式静态包；三张隔离UI证据归档docs/test-reports/assets/bi-sync-20261103。
- 排除私有配置/真实旧Excel、运行数据、target、浏览器快照日志和重复ZIP，原文件保留不删除。Excel显式binary，校验控制的虚构交付包保留原始字节，避免Git换行转换破坏SHA清单。

## 本次提交前执行

- Maven spotless:check及全量test通过：1048项、Failures=0、Errors=0，33项条件测试跳过。命令使用skip.npm/skip.installnodenpm以复用最终前端静态包；不重新开启真实LLM、Wren或Docker条件测试。
- Maven package -DskipTests通过，生成JAR及分发tar；不代表运行服务已加载或生产镜像已构建。
- npm test在三种base下均70项通过；最后一次npm run build已通过TypeScript/Vite，产物index-BY6EfkuK/index-xeF0o08x/ontology-m__ypegp。沿用既有ECharts导入/大包警告，不扩展无关重构。
- 真实隔离MySQL15项、Linux准备14项、Compose与新示例导入/解析/只读隔离的前序实测见本批专项报告，本次不重复启动数据库或真实模型。正式8.0.36/66业务流程未代验。
- 暂存检查含示例Excel及完整参考包SHA、无私有/临时路径、密钥/令牌/带凭据URL扫描，最终结果见SESSION_LOG和Git提交。DocsConsistencyTest及diff检查在共享记录更新后再执行。

没有正式平台再初始化、真实同步、cron安装、服务重启或数据库清理。实际发布与66验收仍按主指引逐阶段完成。
