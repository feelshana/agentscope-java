# 新建看板表单优化验证

2026-11-17 Asia/Shanghai；codex/architecture-phase1，沿用spec144，保留全部既有未提交工作。本轮按用户截图实现，不提交/部署，不接管IDEA。

## 实际行为

- DashboardKnowledgePicker替代原生select，使用图标、名称、已发布/数据集提示及选中标记，搜索名称与说明；限定QUERY且已发布、未清理的知识库。弹层在原生dialog内，避免顶层遮挡；支持上下键、Enter、Escape仅关闭列表、Tab自然转入后续控件、点击外部及无结果。
- 名称标签右侧“使用知识库名称”按钮显式替换名称，填入后仍可编辑；换知识库不覆盖已填名称。复用80字限制，避免截断代理对，过长提示已截取。
- 继续允许同名，按独立UUID区分，不加编号/唯一约束；仅根据当前可见看板给出同名非阻断提示，不扫描其他用户资产。无源时选择器、快捷填名和创建按钮禁用。
- 知识库和名称显示必填星号及无障碍必填属性，名称有原生required；描述选填、500字计数，重开重置表单。描述随POST一次保存，不追加第二次PATCH、模型调用或首轮自动发送。
- 创建服务四参数入口校验名称/描述后再生成工程，非法参数不留资产目录；三参数内部兼容入口保留原未命名回退，旧HTTP客户端不传description兼容空描述。创建仍复用既有权限及已发布模型检查。

## 验证

Java17相关DashboardServiceTest、DashboardControllerTest、DashboardTurnPreparationTest、DashboardDeletionServiceTest、ArchitectureDependencyTest：30项失败/错误0，1个Windows符号链接能力不足条件跳过。新增用例验证描述持久化/重启、同名独立ID、无效字段不残留及WebFlux POST字段传递。全unit1104项，失败/错误0，同一条件跳过1项，BUILD SUCCESS；共享记录后DocsConsistencyTest 5项通过，失败/错误/跳过0，空白检查通过。

前端默认部署前缀dashboards.test.mjs与dashboard-library-management.test.mjs共12项通过，新增名称填入长度/控制字符/Unicode边界。TypeScript/Vite最终构建成功，静态资源同步，保留既有大包提示。

Chrome + Playwright使用20条隔离知识库数据，其中17条满足条件：名称/说明搜索、未授权/未发布/清理排除、无匹配Enter不提交、Escape保留表单、上下键/Enter选择、Tab保持自然焦点、换库不覆盖名称、长名截取、同名提示、选填描述、一次创建导航、取消不写及无可用库禁用均通过。只允许一次预期POST（同名且含描述），无意外API/写入/JS错误；不创建真实业务资产。最初脚本全页option计数混入工具栏原生选项，改为目标listbox后通过；Chrome自动聚焦滚动列表细节已以tabIndex=-1修正并复验。

390/320原生弹窗无横溢，5张截图保留在output/playwright/dashboard-create（form-desktop、search-desktop、search-390、search-320、no-sources），已检查表单、展开选择器及两种窄屏。日志在.runtime/dashboard-create-{backend,unit,frontend,build-final,browser,docs}.log。

## 边界

未操作线上或实际用户资产，未执行真实Wren/LLM；新描述参数需用户重启IDEA后端加载。无DDL/新ADR/全局命名规则变更。未执行完整clean verify，发布合并前须补齐。
