# 回答工具条与输入区实施验证｜2026-11-01 Asia/Shanghai

## 授权与范围

用户认可两张方案图并要求实施，补充保留新手友好的反馈说明。基线 `liyufan_dev@5b1079a6` 加已有品牌/图标/术语等WIP；保留原修改，不提交、push、切分支、部署或操作数据库。局部视觉及交互小修，不新增spec/ADR；全流程其余A/B候选仍未授权。

认可图：[工具条](../previews/answer-controls-20261101/01-answer-footer-v2.png)、[发送/停止](../previews/answer-controls-20261101/02-composer-states-v2.png)。本轮使用前端设计、React最佳实践与Playwright技能，未引入运行依赖。

## 实施

- `AnswerFeedback`通过copyAction槽位在同一行放复制、分隔、正确/不正确与“评价说明”。按钮仅图标，保留aria-label/aria-pressed、可见键盘焦点和悬停说明。复制不随评价busy禁用。
- 新手说明始终有可发现的“ⓘ 评价说明”入口；鼠标悬停、键盘聚焦或点击可展开。分析类保留原句“反馈可选 · 分析类回答只记录反馈，不保存为问答样例。”普通问数保留确认正确后保存成功查询为参考样例的说明。支持再次点击、外部点击、失焦及Escape关闭；手机点击区至少44px，气泡对齐工具条并限制宽度。
- 真实服务端反馈结果、参考样例保存失败、错误与重试继续直接显示。提交失败的重试会重发原UP/DOWN，修正原来仅重新GET、没有重提失败评价的情况；不改变反馈API或样例资格。
- `CopyAnswerButton`保留完整回答复制及2.5秒成功状态；成功原位换勾号，失败直接显示手动复制说明。复制失败在工具条下独立一行，避免手机图标错行。
- `ChatPanel`复用两处原输入区，移除Enter提示文字；键盘、IME与停止执行原逻辑保持。发送为上箭头，生成中切停止方块；36px石墨视觉圆、44px按钮点击区，禁用为浅灰，状态切换不跳位。输入区16px圆角、细边，去掉外层聚焦光晕。
- 原型19134同步图标工具条、评价说明入口、无键盘提示与发送/停止状态，仍为离线模拟，不代替生产验证。

## 验证方法及结果

当前会话没有内置浏览器/CUA控制接口，按已读取Playwright技能的备用方式启动独立浏览器。19137仅本轮loopback测试服务，读取实际生产构建并提供固定API/SSE示例；不连接真实数据库/模型，不读取用户登录态。剪贴板在该测试浏览器中模拟writeText成功/拒绝，不读取或写入用户系统剪贴板。

脚本、起点文件备份及原始输出位于Git根忽略的 `output/answer-controls-20261101/`。`browser-check.log`记录19项通过：

- 分析提示全文可展开，键盘聚焦/ESC关闭；普通问数显示不同提示。
- 复制收到完整回答，成功换图标；模拟拒绝显示手动复制退路。
- 正确选中状态；不正确POST失败后重试，服务端统计确有两次POST且保留DOWN；参考样例保存失败仍以警告显示。
- 评价读取中禁用评价但复制可用。
- 手机气泡没有越界，说明点击区≥44px。
- 首页与对话均无Enter常驻提示；空输入禁用发送；中文composition中Enter不发请求，Shift+Enter仍换行。
- 发送/停止按钮切换正常；一轮模拟SSE确实被停止（streams=1、aborted=1）。

后续CSS修正了手机复制失败时的换行；`capture-final.log`复核4个操作按钮y相同，390px下scrollWidth=clientWidth=390。截图禁用有限时长进入动画以捕获最终静态状态，不更改产品动效。

实际截图：[默认工具条](assets/answer-controls-20261101/answer-desktop.png)、[分析说明](assets/answer-controls-20261101/feedback-help-desktop.png)、[手机说明](assets/answer-controls-20261101/feedback-help-mobile.png)、[手机复制失败](assets/answer-controls-20261101/copy-error-mobile.png)、[首页可发送](assets/answer-controls-20261101/home-ready.png)、[生成中停止](assets/answer-controls-20261101/generating-stop.png)。

自动检查：TypeScript通过；npm test 64项在`/`、`/dataagent/`、`/dify/dataagent/`共3轮全部通过。最终正确前缀`npm run build`通过，产物index-D20NPjKs.js / index-BB41b0Nc.css / ontology-SIywkjvf.js；保留既有大包及ECharts导入提示。原型静态检查通过。DocsConsistencyTest 5项、git diff --check通过，暂存区为空。

## 对图核对与限制

已用view_image在同一轮查看两张认可图与最新实际截图，核对六点：工具顺序/左对齐；复制/评价的18px图形和选中紫；36px发送/停止圆及原位切换；细边/16px圆角与无光晕；两处无Enter提示；手机44px点击区及失败布局。此次局部组件按认可图和用户新增说明要求实施并完成相关视觉核对。

明确偏差/边界：新增可见“评价说明”是用户本轮补充；原图外框是图板分组，实际回答不套卡片。保留现有首页问候、新手任务及知识库/分析权限条件，不套用图中示例文案；未选库时不显示分析入口。图稿为局部放大，实际在1280×720和390×844复核，非将图稿整张缩放铺屏。未执行真实业务Agent/反馈落库/参考样例写入、公司BI iframe或线上验收，相关行为仅验证接口契约下的前端状态。

源码及静态包已更新，未擅自重启用户业务服务；本批仍未暂存/提交。

收尾：19134脚本HTTP200，包含新评价说明且已无原键盘提示。已关闭本轮独立浏览器并核对命令行后停止自建fixture PID65952；原有服务保留。
