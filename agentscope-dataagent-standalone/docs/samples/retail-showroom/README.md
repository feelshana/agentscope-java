# 曜境零售旗舰样板

全部数据和品牌均为虚构演示，不代表真实公司；随机种子20261116。

已完成的[本地发布样板](http://127.0.0.1:5173/dashboards/a26cddc8-556f-4670-812d-c9ed439f0c03?view=published)，可用本地bob管理或alice查询账号访问。[真实验收与设计差异](../../test-reports/20261116-retail-showroom.md)。

`python generate.py`重建四份CSV及truth.json。金额源生成使用整数分；CSV以元输出两位小数。business-rules.md为提供给知识库的口径依据；dashboard-prompt.md为真实生成的提示词。

## 设计参考与能力边界

- [Tableau Superstore展示](https://public.tableau.com/app/search/vizzes/%23Superstore)：用于经营概览、结构与明细的信息分层参考；未复制其素材或声称同等完整产品能力。
- [帆软集团经营驾驶舱](https://app.fanruan.com/templates/20001007)：参考同比、区域、品牌、目标和门店经营的业务组织方式。
- 官方GenBI由Agent编写完整网页，图形布局自由度高；本产品实际提供ECharts、受控live查询、筛选和内部发布。无限自定义不等于已经具备拖拽、跨库、实时推送或移动端自动完美适配。

## 视觉规格

design-concept.png是本轮生成的完整参考；概念中的数值、日期、类别数为示意，最终必须替换成CSV/Wren实际结果。确定的实现差异：2026Q3、三渠道、四品类、12门店；趋势改为真实季度周/月线，不采用概念的三个演示点。相同元素保持深蓝/白/青绿/钴蓝和12px圆角；所有UI由代码与ECharts渲染，概念图不能作为最终页面。

首屏允许文案：曜境零售、全渠道经营驾驶舱、时间/区域/渠道筛选、刷新、净销售额、商品毛利率、成交订单、目标达成、收入趋势、渠道结构。下方区域经营表现、商品增长引擎、门店经营明细与指标口径。每个控件必须具有真实行为。

## 可复现流程

在应用根目录运行 `python docs/samples/retail-showroom/run_pipeline.py upload|model|publish-model|generate|adjust|publish`；使用已有本地bob测试账号，仅指向127.0.0.1:8080。运行状态和完整Agent事件只保存在忽略目录 `.runtime/showroom-retail/`，不保存令牌。脚本不会自动修改模型服务配置或发布到外部。

需依次确认各阶段成功后再运行下一阶段。发布是显式命令，生成Agent本身没有发布工具。建模或查询失败时检查事件，不通过直接改物理数据库或伪造结果绕过。
