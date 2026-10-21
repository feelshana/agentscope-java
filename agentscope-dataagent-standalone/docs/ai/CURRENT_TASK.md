# 当前任务与接手入口

更新：2026-10-21；分支 `boundary-lyf`，开发起点 `f11ce17c`。用户已要求提交当前代码与文档，本页随本次单次提交交付，最终提交号以Git为准；不推送或部署。

## 当前：前端测试入口与文档状态校准（用户已授权）

- 用户从优化清单选择第4、7项。本轮只修前端测试设施/发布门禁和文档，不改业务功能、同事性能/时间门禁或目录布局；同事附件不是本项目已采纳规则。
- 已复现旧入口16通过/2文件加载失败、11项未执行。移除data URL/手写依赖替换，改用已有Vite编译真实TS模块，再用Node执行；`npm test`覆盖根路径与/dataagent/，不加载私有env、不写应用static，无新增依赖。
- 已更新sessionStorage认证夹具，覆盖前缀/Request/凭据/登录选项；新增SSE错误及元数据回归，保留工具解析。两种前缀各35项通过（35个独立用例、70次执行，失败/跳过0）。
- Maven在npm-build之前运行npm-test，-DskipTests只跳过Java；临时失败用例已验证能阻止Maven继续，已移除。
- 校准上线清单/总账的模型管理及旧测试状态；README/ARCHITECTURE/AGENTS同步验证命令，TODO只导航。历史失败与成绩保留在SESSION_LOG及原报告，不当作本轮验证。
- 本轮mvn test退出0：Java759项，733通过/26跳过，失败/错误0；默认前端测试/构建及Spotless通过。mvn package -DskipTests退出0，前端门禁仍执行，Java测试跳过。12份文档157个本地链接及差异空白检查通过；受控前缀负例检出3项失败。详见[本轮报告](../test-reports/20261021-frontend-regression-and-docs.md)。
- 本轮测试/文档工作完成，非clean根路径制品已重建但未启动部署，static无差异。没有重跑真实浏览器/BI/公司模型/Wren全流程或负载恢复测试，未操作既有服务；开发阶段的未提交状态后续由用户提交指令收口，历史报告保留原阶段含义。
- 文件：frontend/package.json、tests三文件和run.mjs、pom.xml、验证与发布文档、共享记忆及本轮报告；既有深度分析规划仍保留。上层shared/.seed-manifest.json原修改保留，个人LOCAL_HANDOFF_LYF.md继续忽略、不入Git。

## 后续规划：明确暂缓

- [配置页面化](../plans/platform-settings-backlog.md)：用户明确先不做，未排期、非上线阻断。
- [深度分析与HTML报告](../plans/optional-deep-analysis-proposal.md)：用户与同事再评估，未排期、未实施；此前调研仅为建议，不能因泛称“继续”启动开发。

## 已交付代码与未完成的发布工作

- f11ce17c为用户要求的spec080管理员模型管理/手动热切换及上线材料一次提交，基线也包含此前已提交的spec078/079 UI；没有推送部署。保存/测试/显式应用分开，新轮生效、在途轮固定，无自动公网回退。实现和历史验证见[模型服务报告](../test-reports/20261021-admin-model-services.md)。
- 公司入口仍需确认Bearer签名合同/有效期、完整URL、SSE/tool_calls及公网允许范围，未调用真实凭据；生产需0088表及模型配置独立主密钥，配套平台库备份。
- 同事负责首问/沙箱/Wren性能，spec077仅建议；其附件描述工具装配、时间门禁和Cube/View边界，不等于代码已合并。交付后核对框架依赖、迁移、权限和业务回归，避免重复实现。
- 建模聊天块非零退出可能误显示完成、同批工具结束通知延后仍待对齐/复验；右侧详情已有退出码解析，不能笼统说所有页面未识别。偶发短回答、Windows快照及Docker orphan属于历史待复现项。
- 65系统/架构/实际配额尚未确认，整机32CPU/252GiB不是应用预算。BI在另一服务器，浏览器仍走同源/dataagent/；前端随JAR部署，不启动Vite。
- 待同事合并后冻结候选，补完整首次schema；按实际前缀clean构建并记录源码/JAR/tar/镜像/配置对应。公司模型/真实BI与UI/权限、目标配额混合负载/长稳、升级回退和双库/持久文件恢复仍须验收。
- 云端历史模拟BI、六份文件上传及Wren召回已有实测，最大168893行，不等于50万边界或65验收；当前源码/制品不会自动更新云端。已丢失的旧Agent状态不自动恢复。
- 例行任务只占位、旧API关闭；准确性规则定制仍按用户决定跳过，保留常规结果核对。不清理原用户数据/会话/云资源。

## 导航与历史证据

- [上线风险与待办总账](../go-live-gap-review-20261012.md)、[执行清单](../release-checklist.md)、[单机手册](../single-host-release.md)、[65准备](../65-server-preparation.md)。
- [长期背景](PROJECT_CONTEXT.md)、[决定及暂缓项](DECISIONS.md)、[历史checkpoint](SESSION_LOG.md)。本页收敛当前事项，10.17—10.21历史过程仍在这些记录和对应专项报告中，不改写历史成绩。
- [UI导航验收](../test-reports/20261021-prototype-navigation-and-secondary-pages.md)、[数据源UI](../test-reports/20261021-data-source-management-ui.md)。
- [模拟BI](../test-reports/20261019-bi-simulator.md)、[真实文件上传](../test-reports/20261019-honghai-upload.md)、[建模耗时](../test-reports/20261019-cloud-modeling-latency.md)、[分段计时](../test-reports/20261019-wren-phase-timing.md)。
