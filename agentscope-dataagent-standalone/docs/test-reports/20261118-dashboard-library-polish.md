# 仪表盘卡片权限、来源资料入口与搜索体验

日期：2026-11-18 Asia/Shanghai，codex/architecture-phase1。按用户截图直接优化，沿用spec144补充验收，无新ADR/DDL/权限规则。既有spec143/144/145/146及独立产品基准候选改动全部保留，不将候选设计视为整站迁移授权；未提交/推送/部署或启停IDEA。

## 实现

- 筛选：全部、我创建的、可管理、可使用。管理/使用按知识库MANAGE（canPublish）区分，标签置于草稿/发布状态之后；创建者只有QUERY也可继续编辑自己的看板，canEdit不被标签改变。
- 卡片：左侧两行知识库与创建人，右侧更新时间、创建日期上下对齐；知识库长名省略、悬停显示全名，按钮带轻量书本/方向图标。移除我创建的/共享给我/可编辑信息串，列表视图同步。
- 创建人：服务端仅对已授权列表批量读取用户显示名，回退账号，账户不存在显示已移除用户；内部creatorId使用JsonIgnore，HTTP返回结构保持平铺，只增加creatorName，不返回原始用户记录。编辑元数据后保留已有列表显示名。
- 来源入口：懒加载复用原KnowledgeDataPanel的说明、数据集、样例及字段浏览。右侧原生dialog提供焦点约束、遮罩关闭、Escape与焦点返回；权限失败沿原接口展示，不扩展QUERY/MANAGE。DatasetFieldInfo在原生dialog内挂载并限制左侧位置，避免弹层处于遮罩下；普通页面仍挂document.body。
- 搜索输入去掉内部outline/阴影，只保留外部圆角focus-within提示；未去除键盘可见焦点。

## 验证

| 验证 | 本轮结果 |
| --- | --- |
| 后端全unit | 1119项，失败/错误0，跳过1（Windows符号链接条件） |
| 前端3个相关文件 | 13项全部通过，根部署前缀；未重复三个前缀全套 |
| TypeScript/Vite生产构建 | 通过，正式static同步；既有大chunk提醒保留 |
| Chrome隔离API交互 | 四范围、创建人、自己创建但仅使用权限仍可编辑、搜索焦点、按需读库、样例/字段、字段Escape不关侧栏、关闭焦点返回、权限错误、元数据保存保留创建人、卡片/列表/390px通过；意外API/JS异常/非预期写入0 |

新增HTTP测试验证平铺JSON、显示名与账户移除回退、creatorId/凭证不泄漏、只查询可见创建人且RUN拒绝先于用户查询。既有资产/架构/控制器先选测31项通过，最终全unit包含新增HTTP测试。筛选测试明确区分canPublish与canEdit，避免把创建者的QUERY错标为MANAGE。

浏览器夹具.runtime/dashboard-library-polish-browser.js使用合成资产、资料、样例及PATCH响应，不访问真实用户业务资料。首次验证修正了夹具：React StrictMode可能重复请求，按需断言改为首次点击前零请求；样例rows应为数组而非对象。这些不是业务服务故障。最后一轮结果在.runtime/dashboard-library-polish-browser-final.log，错误/未知请求0。

四张截图output/playwright/dashboard-library-polish-{cards,drawer,list,mobile}.png均已实际查看；搜索无内层紫框，状态/权限同排，来源/创建人与两行日期左右对齐，右侧复用组件正常，窄屏无横溢。

## 使用与限制

刷新前端可看新布局；真实创建人名称需要重启IDEA加载新增列表投影，旧服务未更新时显示“创建人暂不可用”。无SQL迁移，不改生成任务或发布权限。未跑真实LLM/Wren生成、线上接口或完整clean verify发布门禁；本轮无需重新调用模型。文档/共享记录后另补DocsConsistencyTest与空白检查。

最终补记：最终构建/静态资源同步通过；DocsConsistencyTest、JPA集成及DashboardControllerTest共15项通过，失败/错误/跳过0，空白检查通过。临时dashboard-library-polish QA浏览器及本轮5175 Vite已关闭，用户服务/原预览保留。
