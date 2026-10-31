# develop-2.0 主线整合验证（2026-10-31）

## 范围与来源
- 用户明确确认本地合并方案；没有推送、部署、执行数据库升级或清理原隔离资源。
- fetch基线：develop-2.0与wangchao_dev均为3bb64fc9d2d293cd0a269f60ad8eed2719b4917e；liyufan_dev为bc3bc14d9ca00ffcee50b996c370a51c1fce84bb；design/wren-official-capability-boundary为ea35983f06d9085905ec9ec59119fecb19793b40。
- 同事两笔已在主线；个人两笔以合并提交dd1fc811整合。旧design唯一新提交是20万行，按确认方案保留主线已有50万行/1000万单元格并统一Java缺省、前端备用和手册，保留来源祖先关系。
- 三份共享记录保留双方内容；两份旧CURRENT_TASK单独归档。业务源码自动合并后核对ChatPanel引导信号/发送标识和阅读保护/IME/复制等同时存在。生成资源不手工拼接，使用合并源码重新构建。

## 本轮验证
1. 前端 `npm.cmd test`：三种base（/、/dataagent/、/dify/dataagent/）各64项，零失败。新增策略请求失败可重试、旧后端404备用值及缓存覆盖。
2. `DATAAGENT_BASE_PATH=/dify/dataagent/ npm.cmd run build`：TypeScript与Vite通过。保留既有大包体积、ECharts静态/动态引用警告，本轮不重构拆包。
3. Maven定向命令：`mvn.cmd spotless:check resources:resources compiler:compile compiler:testCompile surefire:test -Dtest=DatasetUploadPolicyDefaultsTest,DatasetUploadAdmissionTest,DatasetUploadIntakeTest,DatasetImportLimitsTest,DatasetImportRollbackTest,DatasetServiceSourceCombinationTest,DemoDataServiceTest,OfficialWrenToolkitTest,ExternalDataSourceConnectionTest,OnboardingControllerTest,OnboardingServiceTest,DocsConsistencyTest -DfailIfNoTests=false`，共98项，零失败/错误/跳过。
4. 新增默认值/YAML契约验证（缺失upload段时策略不变）及部署覆盖验证；现有上传解析边界、失败回滚、源组合、示例只读、新手指引、Wren绑定缓存和权限测试一起通过。
5. `mvn.cmd resources:resources jar:jar spring-boot:repackage -DskipTests`通过，复用本轮已编译/测试的classes和生产前端，未重复前端安装/构建或运行无关测试。核对HTML引用文件存在，JAR中的HTML与每个引用资源逐字节一致。
6. Playwright合成夹具19133：模拟恢复问数第11步指引，复制按钮共存；退出指引后40px圆形回到最新、键盘Enter返回底部通过，无JS pageerror。夹具图表503为预设失败退路，不是线上错误。未操作用户19131真实会话、未重新发起真实建模/问数。证据在Git根忽略目录output/playwright/mainline-merge-20261031。
7. 首次Spotless因本次写入Java的CRLF失败，改为LF后通过；CLI多行参数首次语法失败后使用--filename运行成功。文档收尾后另复查DocsConsistencyTest、diff及合并祖先关系。

## 限制与后续
- 没有运行全量业务、真实LLM、Docker分析或线上验收；配置上限不等于本次实际压测过50万行/1000万单元格。
- 同事新增spec105及DATETIME修复涉及已有库升级和示例部署配置，后续发布按对应迁移脚本/部署手册核对；本轮未执行。内置示例默认关闭，原始Excel不入仓库。
- 本地原运行进程不代表已经加载此合并JAR；生产和原隔离后端均未重启。来源远端若随后更新，需要再次fetch核对。
