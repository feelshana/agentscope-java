# spec132：构建校验执行与发布协调分离

## 背景与授权

用户在本地检查点46facb45后要求继续；按ADR0144逐次抽取职责。本轮只移出已构建工程的SQL资产/Cube检查及CLI诊断解析，完成回归后独立本地提交，不push、合并或部署。不引入新业务/长期边界，无新ADR。

## 职责与调用边界

- MdlBuildChecks为dataset内包级协作对象，依赖WrenProperties、WrenCli、WrenProfileHome、ObjectMapper。接收协调器已选定的工程目录、Snapshot和外部源；不重新选源或赋予权限、不拿发布锁/工作区锁。
- 迁移dryRunSqlObjects、dryRunViews、dryRunDerivedModels、derivedModelsOf、dryRunCubes和parseWrenOutput及摘要模式；保留原命令参数、顺序、错误来源/文案、异常传播和临时连接文件finally清理。
- MdlPublishService仍拥有validate/publish/prepare的编排、来源选择/预检、播种/构建、锁顺序、工作区/临时工程、快照替换、版本写入与事件。构造器签名、HTTP/DTO/JSON保持；两处原检查调用位置和先后次序不改。
- 单题/批量dry-plan、确认问答失效与文件撤销仍在原协调流程，避免同时迁移状态写入；不顺便合并重复build步骤、改诊断规则或修复未确认的历史行为。

## 验收

1. 所有原发布/资产/问答/共享权限与装配断言保留；View/ref_sql先检查、再Cube、最后发布问题检查的顺序不变。
2. 新组件独立测试覆盖诊断、空输入、显式连接/引号转义、失败继续收集、Cube全量度量/空度量跳过、连接准备失败与异常时临时文件清理；无真实数据库/CLI进程。
3. 字节码规则约束校验组件不依赖发布/读预览/播种、平台仓库、事务、锁或事件；只允许现有校验CLI命令与临时连接IO，不接受调用方绕过协调器的外部入口。
4. 源码对照仅改变检查调用委托及传入selection.external，保留其余方法体；执行重点与完整后端（不含npm目标）、格式/文档；前端未变，不重复构建。合并/发布前仍完整clean verify。
5. 更新架构/边界和共享记忆，真实条件跳过、服务/远端未验收明确记录；独立本地提交后工作区干净。

## 本地验收结果

重点97项及干净完整后端1100项（21条件跳过）通过，负向变异和方法等价对照通过。结果与保留价值见[报告](../test-reports/20261112-mdl-build-checks-extraction.md)。本轮独立本地提交，新ID以git log核实；远端/真实环境和发布验收仍待后续，暂留当前规格区。
