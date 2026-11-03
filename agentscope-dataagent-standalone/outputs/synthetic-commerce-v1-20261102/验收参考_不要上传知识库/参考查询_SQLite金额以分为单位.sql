-- Only for the independent oracle; Excel amounts are yuan, so remove /100 when adapting to an uploaded model.

-- Q01 2026年5月1日至10月31日一共有多少笔订单、多少名注册客户？
SELECT (SELECT COUNT(*) FROM demo_orders) AS orders,(SELECT COUNT(*) FROM demo_customers) AS customers;

-- Q02 2026年10月实付GMV、退款金额、净销售额和毛利额各是多少？
SELECT COUNT(*) AS order_count,SUM(paid_amount)/100.0 AS paid_gmv,SUM(refund_amount)/100.0 AS refunds,SUM(net_revenue)/100.0 AS net_revenue,SUM(gross_profit)/100.0 AS gross_profit,SUM(gross_profit)*1.0/NULLIF(SUM(net_revenue),0) AS gross_margin,SUM(CASE WHEN net_revenue>0 THEN 1 ELSE 0 END) AS net_order_count FROM demo_orders WHERE order_time >= '2026-10-01' AND order_time < '2026-11-01';

-- Q03 按月画出2026年5月至10月的净销售额和毛利额趋势。
SELECT SUBSTR(order_time,1,7) AS month,COUNT(*) AS order_count,SUM(paid_amount)/100.0 AS paid_gmv,SUM(refund_amount)/100.0 AS refunds,SUM(net_revenue)/100.0 AS net_revenue,SUM(gross_profit)/100.0 AS gross_profit,SUM(gross_profit)*1.0/NULLIF(SUM(net_revenue),0) AS gross_margin,SUM(CASE WHEN net_revenue>0 THEN 1 ELSE 0 END) AS net_order_count FROM demo_orders GROUP BY 1 ORDER BY 1;

-- Q04 2026年10月每天有多少笔订单？画折线图。
SELECT SUBSTR(order_time,1,10) AS day,COUNT(*) AS orders FROM demo_orders WHERE order_time >= '2026-10-01' AND order_time < '2026-11-01' GROUP BY 1 ORDER BY 1;

-- Q05 2026年10月各销售渠道的净销售额排名和占比是多少？
SELECT sales_channel,SUM(net_revenue)/100.0 AS net_revenue,SUM(net_revenue)*1.0/(SELECT SUM(net_revenue) FROM demo_orders WHERE order_time >= '2026-10-01' AND order_time < '2026-11-01') AS share FROM demo_orders WHERE order_time >= '2026-10-01' AND order_time < '2026-11-01' GROUP BY 1 ORDER BY net_revenue DESC;

-- Q06 2026年10月各客户地区的净销售额、成交客户数和毛利率是多少？
SELECT c.region,SUM(o.net_revenue)/100.0 AS net_revenue,COUNT(DISTINCT CASE WHEN o.net_revenue>0 THEN o.customer_id END) AS buyers,SUM(o.gross_profit)*1.0/NULLIF(SUM(o.net_revenue),0) AS gross_margin FROM demo_orders o JOIN demo_customers c ON o.customer_id=c.customer_id WHERE order_time >= '2026-10-01' AND order_time < '2026-11-01' GROUP BY 1 ORDER BY net_revenue DESC;

-- Q07 2026年10月普通会员、银卡会员和金卡会员分别贡献了多少净销售额？
SELECT c.member_level,SUM(o.net_revenue)/100.0 AS net_revenue FROM demo_orders o JOIN demo_customers c ON o.customer_id=c.customer_id WHERE order_time >= '2026-10-01' AND order_time < '2026-11-01' GROUP BY 1 ORDER BY net_revenue DESC;

-- Q08 2026年10月净销售额最高的5款商品是什么？
SELECT product_id,product_name,SUM(net_revenue)/100.0 AS net_revenue FROM demo_orders WHERE order_time >= '2026-10-01' AND order_time < '2026-11-01' GROUP BY 1,2 ORDER BY net_revenue DESC,product_id LIMIT 5;

-- Q09 2026年10月订单数、成交订单数和净成交订单数分别是多少？
SELECT COUNT(*) AS all_orders,SUM(CASE WHEN paid_amount>0 THEN 1 ELSE 0 END) AS paid_orders,SUM(CASE WHEN net_revenue>0 THEN 1 ELSE 0 END) AS net_orders FROM demo_orders WHERE order_time >= '2026-10-01' AND order_time < '2026-11-01';

-- Q10 2026年10月退款金额率和退款订单率分别是多少？
SELECT SUM(refund_amount)*1.0/NULLIF(SUM(paid_amount),0) AS refund_amount_rate,SUM(CASE WHEN refund_amount>0 THEN 1 ELSE 0 END)*1.0/NULLIF(SUM(CASE WHEN paid_amount>0 THEN 1 ELSE 0 END),0) AS refund_order_rate FROM demo_orders WHERE order_time >= '2026-10-01' AND order_time < '2026-11-01';

-- Q11 2026年10月净销售客单价是多少？
SELECT SUM(net_revenue)/100.0/NULLIF(SUM(CASE WHEN net_revenue>0 THEN 1 ELSE 0 END),0) AS net_aov FROM demo_orders WHERE order_time >= '2026-10-01' AND order_time < '2026-11-01';

-- Q12 截至2026年10月31日，哪些注册客户从未下单？一共有多少名？
SELECT c.customer_id,c.customer_name FROM demo_customers c LEFT JOIN demo_orders o ON c.customer_id=o.customer_id WHERE o.order_id IS NULL ORDER BY c.customer_id;

-- Q13 2026年10月成交客户中，有至少2笔净成交订单的客户占比是多少？
SELECT COUNT(*) AS buyers,SUM(CASE WHEN n>=2 THEN 1 ELSE 0 END) AS repeat_buyers,SUM(CASE WHEN n>=2 THEN 1 ELSE 0 END)*1.0/COUNT(*) AS repeat_rate FROM (SELECT customer_id,COUNT(*) n FROM demo_orders WHERE order_time >= '2026-10-01' AND order_time < '2026-11-01' AND net_revenue>0 GROUP BY customer_id);

-- Q14 2026年10月净销售额最高的10名客户是谁？列出客户地区与等级。
SELECT c.customer_id,c.customer_name,c.region,c.member_level,SUM(o.net_revenue)/100.0 AS net_revenue FROM demo_orders o JOIN demo_customers c ON o.customer_id=c.customer_id WHERE order_time >= '2026-10-01' AND order_time < '2026-11-01' GROUP BY c.customer_id,c.customer_name,c.region,c.member_level ORDER BY net_revenue DESC,c.customer_id LIMIT 10;

-- Q15 对比2026年10月12日至18日，10月19日至25日净销售额为什么下降？先按销售渠道拆解，再看客户地区。
SELECT CASE WHEN order_time<'2026-10-19' THEN '对比期' ELSE '当前期' END AS period,o.sales_channel,c.region,SUM(o.net_revenue)/100.0 AS net_revenue FROM demo_orders o JOIN demo_customers c ON o.customer_id=c.customer_id WHERE order_time>='2026-10-12' AND order_time<'2026-10-26' GROUP BY 1,2,3 ORDER BY 1,2,3;

-- Q16 10月19日至25日，华东短视频商城的净销售额比10月12日至18日下降多少？
SELECT CASE WHEN order_time<'2026-10-19' THEN '对比期' ELSE '当前期' END AS period,COUNT(*) AS order_count,SUM(paid_amount)/100.0 AS paid_gmv,SUM(refund_amount)/100.0 AS refunds,SUM(net_revenue)/100.0 AS net_revenue,SUM(gross_profit)/100.0 AS gross_profit,SUM(gross_profit)*1.0/NULLIF(SUM(net_revenue),0) AS gross_margin,SUM(CASE WHEN net_revenue>0 THEN 1 ELSE 0 END) AS net_order_count FROM demo_orders o JOIN demo_customers c ON o.customer_id=c.customer_id WHERE order_time>='2026-10-12' AND order_time<'2026-10-26' AND c.region='华东' AND sales_channel='短视频商城' GROUP BY 1;

-- Q17 10月5日至11日居家焕新周，相比9月28日至10月4日，净销售额和毛利率如何变化？增收是否同时增利？
SELECT CASE WHEN order_time<'2026-10-05' THEN '对比期' ELSE '当前期' END AS period,COUNT(*) AS order_count,SUM(paid_amount)/100.0 AS paid_gmv,SUM(refund_amount)/100.0 AS refunds,SUM(net_revenue)/100.0 AS net_revenue,SUM(gross_profit)/100.0 AS gross_profit,SUM(gross_profit)*1.0/NULLIF(SUM(net_revenue),0) AS gross_margin,SUM(CASE WHEN net_revenue>0 THEN 1 ELSE 0 END) AS net_order_count FROM demo_orders WHERE order_time>='2026-09-28' AND order_time<'2026-10-12' GROUP BY 1;

-- Q18 同一促销对比期内，各商品品类净销售额与毛利额分别变化多少？
SELECT CASE WHEN order_time<'2026-10-05' THEN '对比期' ELSE '当前期' END AS period,category,SUM(net_revenue)/100.0 AS net_revenue,SUM(gross_profit)/100.0 AS gross_profit FROM demo_orders WHERE order_time>='2026-09-28' AND order_time<'2026-10-12' GROUP BY 1,2 ORDER BY 2,1;

-- Q19 检测2026年10月每日订单数的异常点，并画图。
SELECT SUBSTR(order_time,1,10) AS day,COUNT(*) AS orders FROM demo_orders WHERE order_time >= '2026-10-01' AND order_time < '2026-11-01' GROUP BY 1 ORDER BY 1;

-- Q20 2026年10月28日订单数最高时，秋日闪购订单占多少？
SELECT campaign,COUNT(*) AS orders,SUM(net_revenue)/100.0 AS net_revenue FROM demo_orders WHERE order_time>='2026-10-28' AND order_time<'2026-10-29' GROUP BY campaign;

-- Q21 2026年9月15日至21日，哪个渠道和品类组合退款金额率最高？至少有10笔成交单。
SELECT sales_channel,category,SUM(refund_amount)/100.0 AS refunds,SUM(paid_amount)/100.0 AS paid_gmv,SUM(refund_amount)*1.0/NULLIF(SUM(paid_amount),0) AS refund_rate,SUM(CASE WHEN paid_amount>0 THEN 1 ELSE 0 END) AS paid_orders FROM demo_orders WHERE order_time>='2026-09-15' AND order_time<'2026-09-22' GROUP BY 1,2 HAVING paid_orders>=10 ORDER BY refund_rate DESC;

-- Q22 根据2026年5月1日至9月27日的每日订单数，预测随后7天订单数并给出区间。
SELECT SUBSTR(order_time,1,10) AS day,COUNT(*) AS orders FROM demo_orders WHERE order_time>='2026-05-01' AND order_time<'2026-09-28' GROUP BY 1 ORDER BY 1;

-- Q23 用2026年10月销售额和毛利率做四象限，比较各品类、各渠道。
SELECT category,sales_channel,SUM(net_revenue)/100.0 AS net_revenue,SUM(gross_profit)*1.0/NULLIF(SUM(net_revenue),0) AS gross_margin FROM demo_orders WHERE order_time >= '2026-10-01' AND order_time < '2026-11-01' GROUP BY 1,2;

-- Q24 2026年11月订单金额是多少？
SELECT COUNT(*) AS orders,COALESCE(SUM(net_revenue),0)/100.0 AS net_revenue FROM demo_orders WHERE order_time>='2026-11-01' AND order_time<'2026-12-01';

