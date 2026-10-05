# 零售订单分析语义说明

## 数据表角色

- `ds_demo_customer` 是客户主数据，`customer_id` 唯一标识客户。
- `ds_demo_product` 是商品主数据，`product_id` 唯一标识商品。
- `ds_demo_order` 是订单事实表，`order_id` 唯一标识订单。
- `ds_demo_order_item` 是订单明细表，`order_id + line_no` 唯一标识一条明细。
- `ds_demo_refund` 是退款事实表，`refund_id` 唯一标识退款单。

字段注释和状态枚举已由数据库元数据提供，本说明不要求重复补充列描述。

## 表关系

1. `ds_demo_order.customer_id` 多对一关联 `ds_demo_customer.customer_id`。一个客户可以有多笔订单，一笔订单只属于一个客户。
2. `ds_demo_order_item.order_id` 多对一关联 `ds_demo_order.order_id`。一笔订单可以有多条明细。
3. `ds_demo_order_item.product_id` 多对一关联 `ds_demo_product.product_id`。一个商品可以出现在多条订单明细中。
4. `ds_demo_refund.order_id` 多对一关联 `ds_demo_order.order_id`。一笔订单可以产生多笔退款。

以上关联是业务逻辑关系，数据库没有声明物理外键。

## 业务术语

- 高价值客户：按有效订单口径计算，累计净销售额大于等于 10000 元的客户。也称“高净值客户”。
- 净销售额：有效订单的实付金额减去成功退款金额。退款归属到原订单的支付月份，不按退款发生月份归属。
- 客单价：有效订单实付金额合计除以有效订单数。

## 业务规则

### 有效订单

统计销售、订单数或客户贡献时，默认只保留同时满足以下条件的订单：

- `ds_demo_order.order_status = 'PAID'`；
- `ds_demo_order.deleted_at IS NULL`；
- `ds_demo_order.currency_code = 'CNY'`。

`CANCELLED`、`REFUNDED`、逻辑删除订单和非 CNY 订单不得计入有效订单指标。

### 有效退款

只有 `ds_demo_refund.refund_status = 'SUCCESS'` 的退款才能冲减净销售额。`REJECTED` 退款不得冲减。退款按原订单的 `paid_at` 所在月份归属。

### 区域名称

区域编码展示为中文：`EAST` 表示华东，`SOUTH` 表示华南，`NORTH` 表示华北。

## Cube 建议

建立 `有效订单分析` Cube，以 `ds_demo_order` 为基础模型：

- 指标“实付金额”使用 `SUM(paid_amount)`；
- 指标“订单数”使用 `DISTINCT_COUNT(order_id)`；
- 指标“平均客单价”使用 `AVG(paid_amount)`；
- 维度包括 `order_status` 和 `currency_code`；
- 时间维度“支付月份”使用 `paid_at`，粒度为 MONTH。

使用该 Cube 回答业务问题时仍须遵守“有效订单”业务规则。

## 复杂语义视图

建立语义视图 `customer_monthly_net_sales`，用于按客户和原订单支付月份分析净销售额。该视图应：

1. 先把 `ds_demo_refund` 中状态为 `SUCCESS` 的退款按 `order_id` 汇总，避免一笔订单多笔退款导致订单金额重复；
2. 把汇总结果与 `ds_demo_order` 按 `order_id` 左连接；
3. 把订单与 `ds_demo_customer` 按 `customer_id` 关联；
4. 只保留有效订单；
5. 输出支付月份、客户编号、客户名称、区域编码、有效订单数、实付金额、成功退款金额和净销售额；
6. 净销售额计算为 `SUM(paid_amount) - SUM(success_refund_amount)`。

## 需要人工处理的计算列

订单明细行金额定义为 `ds_demo_order_item.quantity * ds_demo_order_item.unit_price`。当前若没有安全的 Calculated Column 写入能力，请仅生成 `MANUAL_FIX` 提案，不要把它伪装成 Cube 或 View 并自动采纳。
