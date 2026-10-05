# 电商业务字典 & 指标口径文档
## 表说明
1. customers：客户主表，存储所有注册客户信息
2. orders：订单明细表，每一行代表一笔订单记录

## 字段枚举定义
### customers.customer_type
1 = 个人客户
2 = 企业客户

### customers.is_internal
0 = 正式客户，业务统计包含
1 = 内部测试账号，所有营收、客户统计**必须排除**

### orders.order_status
0：待支付
1：已支付【有效订单，计入营收】
2：已取消，不计营收
3：已退款，不计营收

### orders.is_deleted
0：正常记录
1：软删除，所有统计需要过滤 is_deleted=0

### orders.amount
单位：人民币元

## 核心业务指标口径（用于生成Cube）
1. 有效订单总营收
   口径：
- 订单状态 order_status =1
- is_deleted=0
- 排除内部测试客户（customers.is_internal=0）
  公式：sum(orders.amount)

2. 付费客户数
   口径：产生过有效已支付订单的唯一客户数量
   过滤条件同上
   公式：count(distinct orders.customer_id)

3. 月度有效营收
   按订单时间自然月聚合，口径同上，按月分组

## 业务术语同义词
"营收" = 有效订单金额
"付费用户" = 付费客户
4
