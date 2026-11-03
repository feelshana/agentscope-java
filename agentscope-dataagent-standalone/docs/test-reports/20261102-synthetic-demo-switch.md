# 虚构中文姓名及新手示例入口切换

日期：2026-11-02。liyufan_dev@19fcdbde起点，未提交/push/部署。

## 行为

- 两份新示例固定为演示订单.xlsx（48000）和演示客户.xlsx（2000）。客户1915个不同中文姓名，允许重名，主键customer_id保持；原订单文件SHA256未变。
- 新手引导仍复用现有上传/建模/确认/发布。无只读密码也能取得文件，不自动上传业务口径或管理员答案。
- 本地data/demo-samples安装新文件和清单；旧文件保留，旧清单备份target/local-demo-switch-20261102/backup。旧文件名和任意文件不能从样例API读取。
- 新外部示例采用独立demo_commerce_v1及builtin-demo-commerce-v1，不覆盖旧表。旧JDBC URL不被冒充新示例；系统源共享仅限当前标识、新库和两张声明表。
- 恢复脚本默认从仓库synthetic-v1复制合成数据，校验一致才写入；不再自动获取历史真实资料。

## 实际验证

- DemoDataServiceTest、DemoSampleFilesTest、SyntheticDemoMysqlTest、ExternalDataSourceConnectionTest、DatasetServiceSourceCombinationTest、OnboardingServiceTest、OnboardingControllerTest、DocsConsistencyTest共50项通过，无跳过。
- DemoSampleFilesTest通过真实样例Controller取文件，再用生产ExcelParser完整解析48000/2000行、DATETIME、中文姓名/重名、唯一关联键与净销售额9640810.45，旧文件请求拒绝。
- 独立新建mysql:8.4.5容器dataagent-synthetic-seed-20261102，仅127.0.0.1随机端口，不挂载已有数据库。实际服务首次导入成功，重复准备行数不变，姓名/净销售额准确；只读账号不能DELETE或SELECT旧库，旧库哨兵数据仍为7。凭据仅忽略target私有文件，不入文档。
- 恢复工具临时目录首次复制、重复执行和修改文件保护通过。
- 客户Excel只修改姓名、保留原表/日期/格式；Artifact Tool渲染目视通过；业务数值未改变，桌面ZIP与参考材料已同步。
- npm67项×3base、TypeScript及/dify/dataagent/生产构建通过，保留既有大包/ECharts提示；新静态包index-D4SifvFe/ontology-BCgKVD1d，CSS不变。
- 最终Spotless/文档检查及专属测试容器停止结果见SESSION_LOG。

## 限制与用户下一步

没有操作用户运行中的IDEA/浏览器、现有MySQL或生产66；没有真实LLM建模/发布/问答。用户需停止并重启已配置DATAAGENT_DEMO_ENABLED=true的DataAgentApp(1)，刷新前端，用新建知识库或重置引导进入上传弹窗，确认两新文件后点击使用示例文件。已有旧知识库不会自动换成新数据。

外部示例路线若显式配置旧demo_data URL需另改新库与新只读账号；当前用户选的文件上传路线不依赖该配置。生产未切换，旧历史数据仍需后续单独退役，不能声称本地替换删除了所有历史资料。

## 用户本地试用后的日志诊断（2026-11-02）

- 本次只读核对用户IDEA运行日志，未操作页面、重跑建模或修改建模流程。实际Java工作目录为仓库根，证据为根目录logs/execution-timing.log及LLM-modeling.log，不能用模块内旧日志替代。
- 首轮建模trace 3f798d9d-383f-4dbc-a44f-54d1904dcbb4：北京时间17:42:42至17:44:38，115458ms，10次模型调用、20次工具调用；截图101秒/19步为中途状态，步数按前端工具帧计数，并非模型轮数。
- model.end合计98625ms；其中两次45590ms、36474ms，合计82064ms。7次wren_cli工具耗时合计15403ms；全表主键/关联/时间合并探查5478ms（含草稿首次自动build），validate 686ms，memory index 7106ms。工具存在并行，不能把所有工具时长简单相加当墙钟总耗时；模型调用耗时也不等于纯推理耗时。
- 实际探查确认48000订单、2000客户、订单编号及客户编号唯一无空值、1800客户有订单、订单无孤立客户键；基础结构validate通过。未据此宣称发布及各复杂问答正确率已验收。
- 发现技能挂载加载失败后使用skills list/get、尝试未开放的--reference参数、再get --full；另有无业务文档时写knowledge/rules被现有门禁拒绝。均属于本次多余交互候选优化点，尚未修改代码，不能推算消除后的具体提速。
- 无文档路线仍可建模与问数；实际已发起销售额口径确认。明确字段的净销售额、毛利额、渠道/品类/客户地区汇总可作为首发演示；泛称销售额、客单价、退款率、复购率及利润口径需问题明确或用户确认，不能假称未上传业务口径文件仍自动掌握预设定义。
- 建议保持两Excel无文档引导，先让推荐问题明确指标/日期/定义，并治理上述交互；不因本轮115秒直接缩减5万行或预置已建好的语义模型。以上为建议，未获实施授权。
