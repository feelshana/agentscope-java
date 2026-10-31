# 当前任务：66升级与示例部署完成，待BI页面验收（2026-10-31 Asia/Shanghai）

## 线上已确认（用户现场回传，非助手远程执行）
- 66已由develop-2.0拉取011d06b，之后按建议拉取同事a85ccec61a3fa05717d506c1afe60aa4e1c31d25并重建。
- 用户截图确认a85ccec6构建、镜像静态预检、upgrade完成，容器Healthy，脚本报告应用镜像更新通过，健康接口UP。
- 应用容器实际source-revision等于a85ccec61a3fa05717d506c1afe60aa4e1c31d25，已通过Python读取核对，不仅是源码目录版本。
- 平台用户表onboarding_tour varchar(200)、onboarding_reset_at datetime(6)已添加，均允许NULL；不再执行0105。不重建平台库或导用户。
- 两份Excel及SHA256SUMS已复制至/data/dataagent/rehearsal-app/data/demo-samples，源/目标两轮校验OK，长期配置指向持久目录。
- 66 Git1.8将xlsx误判文本产生M，用户追加.git/info/attributes中仅针对示例目录的binary规则后工作区干净；未reset/clean。
- 示例库及demo_reader已由管理员创建，密码在66随机生成且只存私有runtime.env，旧配置备份保留；用户回传权限USAGE+demo_data.* SELECT。
- 新版启动后，用户用应用容器的demo_reader连接查询：report_visits=98819行、platform_users=2046行，visit_time=datetime，权限仍仅SELECT，检查通过。用户文件首表数量一致（用户Excel共4表，仅导第一表）。

## 当前必须继续的事项
- 临时授权已撤销，用户截图确认dataagent_dataset只剩USAGE及原data_agent.* ALL，demo_reader仍为USAGE及demo_data.* SELECT。没有剩余demo_data导入授权待处理，不重复REVOKE。
- 实际访问日期范围已由SQL回传：2026-09-20 16:35:00至2026-10-07 16:31:00；BI关联时选择覆盖首尾完整日的时间窗口，按完整窗口统计访问记录应为98819。示例源继续使用独立demo_reader；不需因此重启。后续重启时，受限上传账号尝试自动准备示例库可能有权限警告，现有catch会继续用预建的只读源验证连接。
- 再通过BI刷新核对新手示例上传/示例源可选与实际引导；健康、行数验证不等于全部真实建模/问答已验收。
- 计划66实际文件读取/业务入口确认后再删仓库两Excel副本；目前未删。持久目录保留，Git历史仍含文件。

## 本地Git与修改边界
- 本地develop-2.0包含c903c321（示例xlsx binary兼容修复），上次fetch的origin/develop-2.0为a85ccec6，二者由011d06b分叉；本地有共享记录WIP。未合并同事这次新增，不擅自切分支/覆盖记录。
- c903c321上次未push；不可误称已经在66，66当前通过本地info/attributes规则兼容。
- 之前两次合并dd1fc811/b6105aa1及示例提交011d06b已由用户push，66使用a85ccec6后包含这些基础历史。
- 旧CURRENT_TASK归档archive/20261031-before-66-deployed-verification.md；后续若要本地同步主线，保留现有共享WIP并先处理文档冲突。
- 当前只更新共享事实记录，无自动提交/push，无助手远程SQL/部署或清理既有本地服务；操作均由用户现场执行。

## 验证边界
- a85ccec6是同事分析取数/问答确认发布互斥修复，助手只审阅差异；相关测试依据同事提交说明与66现有构建流程，不声称助手本地重测该提交。
- 旧主线整合本地定向98项及前端三路径各64项等结果见20261031-develop-mainline-integration报告；不拿它替代本次新增的验证。
- 真实LLM全流程、业务准确率、BI剪贴板/输入法与线上完整建模测速仍待用户验收；临时权限撤销已完成，最近下一步为BI入口验收。
