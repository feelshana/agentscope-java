# 曜境零售：全渠道经营口径（合成演示数据）

此品牌、门店和交易均为可复现的虚构数据，不代表真实企业业绩。覆盖2025-04-01至2026-09-30；默认展示2026年第三季度，对比2025年同期。

retail_orders 一行是一笔订单，只含一个商品SKU；order_id唯一，order_date为自然日。store_id关联retail_stores.store_id（多对一），product_id关联retail_products.product_id（多对一）。quantity为下单件数。

金额均为人民币元，精确至分。gross_sales为原价商品金额，discount_amount为优惠额，refund_amount为退款额；net_sales = gross_sales-discount_amount-refund_amount。net_cost为退款调整后的商品净成本；net_profit=net_sales-net_cost（这里是商品毛利，不含人工租金营销费用，不得称为净利润）。

净销售额=SUM(net_sales)。商品毛利=SUM(net_profit)。毛利率=SUM(net_profit)/SUM(net_sales)，分母为0时无值。成交订单=COUNT(order_id)且net_sales>0，订单总数另行标注；客单价=净销售额/成交订单。退款率=退款额/优惠后商品金额，即SUM(refund_amount)/SUM(gross_sales-discount_amount)。同比为同一自然日期区间向前一年，不能拿上季度当同比。复购会员销售占比用customer_segment='复购会员'的净销售额除总净销售额，不代表客户留存率。

渠道为线下门店、官方商城、内容电商。门店属于华东、华南、华北、西南、华中、西北六区域，共12门店。商品有户外装备、运动服饰、日常生活、专业配件四品类，共16SKU。

retail_targets粒度为月初日期target_month+store_id+channel联合唯一；sales_target是门店该月该渠道目标。store_id多对一关联门店。目标与交易须分别聚合再比较，严禁把月目标直接连接每条订单再求和。目标达成率=筛选范围实际净销售额/同范围销售目标。时间选择以完整自然月/季度为主；本版不展示日级目标达成率，不擅自按天平摊月目标。

经营故事：华东体量领先，华南稳健；西南增长和目标达成较好但折扣较高；华北、西北存在目标缺口；内容电商增长较快但折扣和退款压力较高。每个洞察应从实际SQL结果判断，不把故事文本当作查询结果。

不生成问答对、View、加工表或Cube；建立基础模型、维度关系、字段说明和本文件明确的统计规则即可。
