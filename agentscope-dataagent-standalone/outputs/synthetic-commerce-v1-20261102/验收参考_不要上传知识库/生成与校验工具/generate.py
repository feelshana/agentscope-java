"""Deterministic synthetic retail facts; no real data files or databases are read."""
from __future__ import annotations

import csv
import hashlib
import json
import random
import sqlite3
from collections import Counter, defaultdict
from datetime import date, datetime, timedelta
from pathlib import Path

ROOT = Path(__file__).resolve().parent
OUT = ROOT / "outputs" / "synthetic-commerce-v1"
ADMIN = OUT / "验收参考_不要上传知识库"
WORK = ROOT / "work"
for directory in (OUT, ADMIN, WORK):
    directory.mkdir(parents=True, exist_ok=True)
SEED = 20261102
rng = random.Random(SEED)
START, END = date(2026, 5, 1), date(2026, 10, 31)
REGIONS = ["华东", "华南", "华北", "西部"]
CHANNELS = ["短视频商城", "自营App", "微信小程序", "品牌官网", "线下门店"]
CATEGORIES = ["居家生活", "食品饮料", "个护清洁", "数码配件", "运动户外", "厨房小电"]
PRODUCTS = [
    ("收纳组合", 99, 62), ("纯棉床品", 299, 185), ("轻柔毛巾", 59, 36), ("桌面照明", 159, 98),
    ("挂耳咖啡", 89, 42), ("坚果礼盒", 129, 67), ("谷物早餐", 69, 31), ("冷萃茶包", 59, 26),
    ("洗护套装", 159, 72), ("家居清洁", 79, 36), ("口腔护理", 99, 45), ("旅行洗漱包", 69, 30),
    ("无线耳机", 199, 115), ("快充套装", 129, 68), ("移动电源", 149, 83), ("便携支架", 59, 25),
    ("瑜伽垫", 139, 66), ("运动水杯", 89, 39), ("轻便背包", 229, 118), ("训练弹力带", 69, 28),
    ("空气炸锅", 499, 320), ("便携榨汁杯", 199, 118), ("电热水壶", 169, 98), ("早餐烤盘", 299, 178),
]
ORD_FIELDS = [
    ("order_id", "订单编号"), ("customer_id", "客户编号"), ("order_time", "下单时间"),
    ("product_id", "商品编号"), ("product_name", "商品名称"), ("category", "商品品类"),
    ("sales_channel", "销售渠道"), ("campaign", "活动名称"), ("order_status", "订单状态"),
    ("quantity", "购买数量"), ("unit_price", "商品单价"), ("gross_amount", "商品原价金额"),
    ("discount_amount", "优惠金额"), ("paid_amount", "实付金额"), ("refund_amount", "退款金额"),
    ("retained_cost", "留存商品成本"), ("net_revenue", "净销售额"), ("gross_profit", "毛利额"),
    ("refunded_quantity", "退款数量"),
]
CUS_FIELDS = [
    ("customer_id", "客户编号"), ("customer_name", "虚拟客户名称"), ("registered_at", "注册时间"),
    ("region", "客户地区"), ("member_level", "客户等级"), ("acquisition_channel", "获客渠道"),
]
MONEY = {"unit_price", "gross_amount", "discount_amount", "paid_amount", "refund_amount", "retained_cost", "net_revenue", "gross_profit"}
customers = []
for i in range(2000):
    registered = (date(2026, 1, 1) + timedelta(days=rng.randrange(120))) if i < 1800 else (date(2026, 10, 1) + timedelta(days=rng.randrange(31)))
    customers.append(dict(
        customer_id=f"C{i+1:05d}", customer_name=f"演示客户{i+1:04d}", registered_at=f"{registered} 09:00:00",
        region=rng.choices(REGIONS, [40, 25, 20, 15])[0],
        member_level=rng.choices(["普通会员", "银卡会员", "金卡会员"], [55, 30, 15])[0],
        acquisition_channel=rng.choices(["内容投放", "自然搜索", "好友推荐", "线下引流"], [40, 25, 25, 10])[0],
    ))
# An independent name seed keeps all order facts and business metrics unchanged.
name_rng = random.Random(2026110201)
surnames = list("王李张刘陈杨黄赵吴周徐孙马朱胡郭何高林罗郑梁谢宋唐许韩冯邓曹彭曾肖田董袁潘于蒋蔡余杜叶程魏苏吕丁任沈姚卢姜崔钟谭陆汪范金石廖贾夏韦付方白邹孟熊秦邱江尹薛阎段雷侯龙史陶黎贺顾毛郝龚邵万覃武钱戴严莫孔向汤")
one = list("伟芳娜敏静磊强洋勇艳杰娟涛明霞平刚颖飞超婷瑞琳鹏欣宁")
first = list("思子文嘉雨佳明晓宇诗若安浩泽梓俊亦欣景启")
last = list("涵宁然轩远悦辰彤琳航萱哲晨妍瑜晴言睿瑶凡")
names = {f"C{i:05d}": name_rng.choice(surnames) + (name_rng.choice(one) if name_rng.random() < .25 else name_rng.choice(first) + name_rng.choice(last)) for i in range(1, 2001)}
for i in range(2, 2001, 100): names[f"C{i:05d}"] = names[f"C{i-1:05d}"]
names.update({"C00001":"张明", "C00002":"张明", "C00003":"李思远", "C00004":"王欣然", "C00005":"陈雨晴"})
for customer in customers: customer["customer_name"] = names[customer["customer_id"]]
assert len(set(names.values())) == 1915

by_customer = {r["customer_id"]: r for r in customers}
west = [r for r in customers[:1800] if r["region"] == "西部"]
serial = 0

def generate_order(day: date, *, product_index=None, customer=None, channel=None, campaign="日常销售", discount=None):
    global serial
    serial += 1
    customer = customer or rng.choice(customers[:1800])
    product_index = rng.randrange(len(PRODUCTS)) if product_index is None else product_index
    name, price, cost = PRODUCTS[product_index]
    quantity = rng.choices([1, 2, 3, 4], [64, 23, 10, 3])[0]
    status = rng.choices(["已完成", "已取消", "已退款", "部分退款"], [89, 5, 4, 2])[0]
    if status == "部分退款":
        quantity = max(2, quantity)
    if date(2026, 9, 15) <= day <= date(2026, 9, 21) and product_index // 4 == 3 and channel == "短视频商城":
        status = rng.choices(["已完成", "已取消", "已退款", "部分退款"], [59, 5, 30, 6])[0]
        if status == "部分退款": quantity = max(2, quantity)
    unit_price, unit_cost = price * 100, cost * 100
    discount_pct = rng.choices([0, 5, 10], [50, 35, 15])[0] if discount is None else discount
    paid_unit = unit_price * (100 - discount_pct) // 100
    refunded = quantity if status == "已退款" else (1 if status == "部分退款" else 0)
    paid = 0 if status == "已取消" else paid_unit * quantity
    refund = 0 if status == "已取消" else paid_unit * refunded
    retained_cost = 0 if status == "已取消" else unit_cost * (quantity - refunded)
    return dict(
        order_id=f"D{serial:08d}", customer_id=customer["customer_id"],
        order_time=f"{day} {rng.randrange(8,23):02d}:{rng.randrange(60):02d}:{rng.randrange(60):02d}",
        product_id=f"P{product_index+1:03d}", product_name=name, category=CATEGORIES[product_index//4],
        sales_channel=channel or rng.choices(CHANNELS, [35,25,20,15,5])[0], campaign=campaign,
        order_status=status, quantity=quantity, unit_price=unit_price, gross_amount=unit_price*quantity,
        discount_amount=0 if status == "已取消" else unit_price*quantity-paid,
        paid_amount=paid, refund_amount=refund, retained_cost=retained_cost,
        net_revenue=paid-refund, gross_profit=paid-refund-retained_cost, refunded_quantity=refunded,
    )

def clone_order(row, day, *, discount_pct=None, campaign=None):
    global serial
    serial += 1
    row = dict(row, order_id=f"D{serial:08d}", order_time=str(day)+row["order_time"][10:])
    if campaign is not None: row["campaign"] = campaign
    if discount_pct is not None and row["order_status"] != "已取消":
        paid_unit = row["unit_price"] * (100-discount_pct)//100
        row["paid_amount"] = paid_unit*row["quantity"]
        row["refund_amount"] = paid_unit*row["refunded_quantity"]
        row["discount_amount"] = row["gross_amount"]-row["paid_amount"]
        row["net_revenue"] = row["paid_amount"]-row["refund_amount"]
        row["gross_profit"] = row["net_revenue"]-row["retained_cost"]
    return row

orders = []
daily_rows = defaultdict(list)
days = [START+timedelta(days=i) for i in range((END-START).days+1)]
for day in days:
    if date(2026,10,5) <= day <= date(2026,10,11):
        for old in daily_rows[day-timedelta(days=7)]:
            home = old["category"] == "居家生活"
            orders.append(clone_order(old,day,discount_pct=30 if home else None,campaign="居家焕新周" if home else None))
            if home:
                orders.append(clone_order(old,day,discount_pct=30,campaign="居家焕新周"))
        continue
    if date(2026,10,19) <= day <= date(2026,10,25):
        for j,old in enumerate(daily_rows[day-timedelta(days=7)]):
            target = by_customer[old["customer_id"]]["region"] == "华东" and old["sales_channel"] == "短视频商城"
            if target and j % 10 < 7: continue
            orders.append(clone_order(old,day))
        for _ in range(5):
            orders.append(generate_order(day, customer=rng.choice(west),channel="自营App"))
        continue
    weekly = [1.00,0.96,0.98,1.02,1.10,1.24,1.18][day.weekday()]
    count = round((220+(day-START).days*0.24)*weekly+rng.uniform(-9,9))
    if day == date(2026,10,28): count *= 4
    rows = []
    for _ in range(count):
        # Channel is decided before status so the refund scenario has a genuine denominator.
        channel = rng.choices(CHANNELS,[35,25,20,15,5])[0]
        rows.append(generate_order(day,channel=channel,campaign="秋日闪购" if day==date(2026,10,28) else "日常销售"))
    daily_rows[day] = rows
    orders.extend(rows)

# Keep designed comparison weeks and outlier untouched while matching the exact fact count.
protected = lambda r: r["order_time"][:10] >= "2026-09-28"
remove = len(orders)-48000
assert remove >= 0, len(orders)
candidates = [i for i,r in enumerate(orders) if not protected(r)]
drop = set(rng.sample(candidates,remove))
orders = [r for i,r in enumerate(orders) if i not in drop]
# Guarantee every existing customer has a fact; the 200 new leads intentionally have none.
covered = {r["customer_id"] for r in orders}
missing = [c for c in customers[:1800] if c["customer_id"] not in covered]
for c,r in zip(missing,(r for r in orders if r["order_time"][:10]<"2026-09-01")):
    r["customer_id"] = c["customer_id"]
# Strata across months, category, channel and status improve the platform's first-1000 sample.
rng.shuffle(orders)
assert len(orders) == 48000 and len(customers) == 2000
assert len({r["order_id"] for r in orders}) == 48000
assert len(by_customer) == 2000
assert {r["order_time"][:10] for r in orders} == set(map(str,days))
assert len({r["customer_id"] for r in orders}) == 1800
for row in orders:
    assert row["customer_id"] in by_customer
    assert by_customer[row["customer_id"]]["registered_at"] <= row["order_time"]
    assert row["gross_amount"] == row["quantity"]*row["unit_price"]
    assert row["paid_amount"] >= row["refund_amount"] >= 0
    assert row["net_revenue"] == row["paid_amount"]-row["refund_amount"]
    assert row["gross_profit"] == row["net_revenue"]-row["retained_cost"]
    assert row["order_status"] == "已取消" or row["gross_amount"]-row["discount_amount"] == row["paid_amount"]
    assert row["order_status"] != "已取消" or row["paid_amount"] == row["refund_amount"] == row["retained_cost"] == 0
assert len({r['order_time'][:7] for r in orders[:1000]}) == 6

def write_csv(path,fields,rows):
    with path.open('w',encoding='utf-8-sig',newline='') as f:
        w=csv.DictWriter(f,fieldnames=fields);w.writeheader();w.writerows(rows)

write_csv(WORK/'orders_cents.csv',[k for k,_ in ORD_FIELDS],orders)
write_csv(WORK/'customers.csv',[k for k,_ in CUS_FIELDS],customers)
payload = {
    'seed':SEED,
    'tables':[
        {'name':'演示订单','file':'演示订单.xlsx','fields':ORD_FIELDS,'rows':[[r[k]/100 if k in MONEY else r[k] for k,_ in ORD_FIELDS] for r in orders], 'dates':[2], 'money':[i for i,(k,_) in enumerate(ORD_FIELDS) if k in MONEY]},
        {'name':'演示客户','file':'演示客户.xlsx','fields':CUS_FIELDS,'rows':[[r[k] for k,_ in CUS_FIELDS] for r in customers], 'dates':[2], 'money':[]},
    ]
}
(WORK/'workbook-data.json').write_text(json.dumps(payload,ensure_ascii=False,separators=(',',':')),encoding='utf-8')

conn=sqlite3.connect(':memory:');conn.row_factory=sqlite3.Row
def db_table(name,fields,rows):
    cols=[k for k,_ in fields]
    conn.execute('CREATE TABLE '+name+' ('+','.join(k+(' INTEGER' if k in MONEY or k in ('quantity','refunded_quantity') else ' TEXT') for k in cols)+')')
    conn.executemany('INSERT INTO '+name+' VALUES ('+','.join('?' for _ in cols)+')',[[r[k] for k in cols] for r in rows])
db_table('demo_orders',ORD_FIELDS,orders);db_table('demo_customers',CUS_FIELDS,customers)
def query(sql): return [dict(r) for r in conn.execute(sql)]
TOTAL = "COUNT(*) AS order_count,SUM(paid_amount)/100.0 AS paid_gmv,SUM(refund_amount)/100.0 AS refunds,SUM(net_revenue)/100.0 AS net_revenue,SUM(gross_profit)/100.0 AS gross_profit,SUM(gross_profit)*1.0/NULLIF(SUM(net_revenue),0) AS gross_margin,SUM(CASE WHEN net_revenue>0 THEN 1 ELSE 0 END) AS net_order_count"
october = "order_time >= '2026-10-01' AND order_time < '2026-11-01'"
questions=[]
def q(id,level,question,sql,note=''):
    rows=query(sql) if sql else None;questions.append(dict(id=id,level=level,question=question,sql_cents=sql,expected=rows,note=note));return rows
q('Q01','入门','2026年5月1日至10月31日一共有多少笔订单、多少名注册客户？',"SELECT (SELECT COUNT(*) FROM demo_orders) AS orders,(SELECT COUNT(*) FROM demo_customers) AS customers")
q('Q02','入门','2026年10月实付GMV、退款金额、净销售额和毛利额各是多少？',f'SELECT {TOTAL} FROM demo_orders WHERE {october}')
q('Q03','趋势图','按月画出2026年5月至10月的净销售额和毛利额趋势。',f'SELECT SUBSTR(order_time,1,7) AS month,{TOTAL} FROM demo_orders GROUP BY 1 ORDER BY 1')
q('Q04','趋势图','2026年10月每天有多少笔订单？画折线图。','SELECT SUBSTR(order_time,1,10) AS day,COUNT(*) AS orders FROM demo_orders WHERE '+october+' GROUP BY 1 ORDER BY 1')
q('Q05','分类汇总','2026年10月各销售渠道的净销售额排名和占比是多少？',f'SELECT sales_channel,SUM(net_revenue)/100.0 AS net_revenue,SUM(net_revenue)*1.0/(SELECT SUM(net_revenue) FROM demo_orders WHERE {october}) AS share FROM demo_orders WHERE {october} GROUP BY 1 ORDER BY net_revenue DESC')
q('Q06','关联汇总','2026年10月各客户地区的净销售额、成交客户数和毛利率是多少？','SELECT c.region,SUM(o.net_revenue)/100.0 AS net_revenue,COUNT(DISTINCT CASE WHEN o.net_revenue>0 THEN o.customer_id END) AS buyers,SUM(o.gross_profit)*1.0/NULLIF(SUM(o.net_revenue),0) AS gross_margin FROM demo_orders o JOIN demo_customers c ON o.customer_id=c.customer_id WHERE '+october+' GROUP BY 1 ORDER BY net_revenue DESC')
q('Q07','关联汇总','2026年10月普通会员、银卡会员和金卡会员分别贡献了多少净销售额？','SELECT c.member_level,SUM(o.net_revenue)/100.0 AS net_revenue FROM demo_orders o JOIN demo_customers c ON o.customer_id=c.customer_id WHERE '+october+' GROUP BY 1 ORDER BY net_revenue DESC','等级为2026-10-31快照，不代表下单时等级。')
q('Q08','排名','2026年10月净销售额最高的5款商品是什么？','SELECT product_id,product_name,SUM(net_revenue)/100.0 AS net_revenue FROM demo_orders WHERE '+october+' GROUP BY 1,2 ORDER BY net_revenue DESC,product_id LIMIT 5')
q('Q09','口径','2026年10月订单数、成交订单数和净成交订单数分别是多少？','SELECT COUNT(*) AS all_orders,SUM(CASE WHEN paid_amount>0 THEN 1 ELSE 0 END) AS paid_orders,SUM(CASE WHEN net_revenue>0 THEN 1 ELSE 0 END) AS net_orders FROM demo_orders WHERE '+october)
q('Q10','口径','2026年10月退款金额率和退款订单率分别是多少？','SELECT SUM(refund_amount)*1.0/NULLIF(SUM(paid_amount),0) AS refund_amount_rate,SUM(CASE WHEN refund_amount>0 THEN 1 ELSE 0 END)*1.0/NULLIF(SUM(CASE WHEN paid_amount>0 THEN 1 ELSE 0 END),0) AS refund_order_rate FROM demo_orders WHERE '+october)
q('Q11','口径','2026年10月净销售客单价是多少？','SELECT SUM(net_revenue)/100.0/NULLIF(SUM(CASE WHEN net_revenue>0 THEN 1 ELSE 0 END),0) AS net_aov FROM demo_orders WHERE '+october,'分母为净销售额>0的订单数。')
q('Q12','左关联','截至2026年10月31日，哪些注册客户从未下单？一共有多少名？','SELECT c.customer_id,c.customer_name FROM demo_customers c LEFT JOIN demo_orders o ON c.customer_id=o.customer_id WHERE o.order_id IS NULL ORDER BY c.customer_id','应为200名，C01801至C02000；这里只统计完全没有订单，不含只有取消单。')
q('Q13','复购','2026年10月成交客户中，有至少2笔净成交订单的客户占比是多少？',"SELECT COUNT(*) AS buyers,SUM(CASE WHEN n>=2 THEN 1 ELSE 0 END) AS repeat_buyers,SUM(CASE WHEN n>=2 THEN 1 ELSE 0 END)*1.0/COUNT(*) AS repeat_rate FROM (SELECT customer_id,COUNT(*) n FROM demo_orders WHERE "+october+" AND net_revenue>0 GROUP BY customer_id)")
q('Q14','关联排名','2026年10月净销售额最高的10名客户是谁？列出客户地区与等级。','SELECT c.customer_id,c.customer_name,c.region,c.member_level,SUM(o.net_revenue)/100.0 AS net_revenue FROM demo_orders o JOIN demo_customers c ON o.customer_id=c.customer_id WHERE '+october+' GROUP BY c.customer_id,c.customer_name,c.region,c.member_level ORDER BY net_revenue DESC,c.customer_id LIMIT 10')
period = "CASE WHEN order_time<'2026-10-19' THEN '对比期' ELSE '当前期' END"
window = "order_time>='2026-10-12' AND order_time<'2026-10-26'"
q('Q15','指标归因','对比2026年10月12日至18日，10月19日至25日净销售额为什么下降？先按销售渠道拆解，再看客户地区。',f'SELECT {period} AS period,o.sales_channel,c.region,SUM(o.net_revenue)/100.0 AS net_revenue FROM demo_orders o JOIN demo_customers c ON o.customer_id=c.customer_id WHERE {window} GROUP BY 1,2,3 ORDER BY 1,2,3','选择指标归因，确认净销售额、两期日期及维度；贡献是统计拆解，不等于因果识别。')
q('Q16','交叉分析','10月19日至25日，华东短视频商城的净销售额比10月12日至18日下降多少？',f"SELECT {period} AS period,{TOTAL} FROM demo_orders o JOIN demo_customers c ON o.customer_id=c.customer_id WHERE {window} AND c.region='华东' AND sales_channel='短视频商城' GROUP BY 1")
promo = q('Q17','经营分析','10月5日至11日居家焕新周，相比9月28日至10月4日，净销售额和毛利率如何变化？增收是否同时增利？',"SELECT CASE WHEN order_time<'2026-10-05' THEN '对比期' ELSE '当前期' END AS period,"+TOTAL+" FROM demo_orders WHERE order_time>='2026-09-28' AND order_time<'2026-10-12' GROUP BY 1",'活动名称可证明活动订单归属，不能仅凭同期变化估计活动真实增量效果。')
q('Q18','经营下钻','同一促销对比期内，各商品品类净销售额与毛利额分别变化多少？',"SELECT CASE WHEN order_time<'2026-10-05' THEN '对比期' ELSE '当前期' END AS period,category,SUM(net_revenue)/100.0 AS net_revenue,SUM(gross_profit)/100.0 AS gross_profit FROM demo_orders WHERE order_time>='2026-09-28' AND order_time<'2026-10-12' GROUP BY 1,2 ORDER BY 2,1")
q('Q19','异常检测','检测2026年10月每日订单数的异常点，并画图。','SELECT SUBSTR(order_time,1,10) AS day,COUNT(*) AS orders FROM demo_orders WHERE '+october+' GROUP BY 1 ORDER BY 1','选择异常检测；是否异常、阈值和数量以项目固定脚本输出为准。')
q('Q20','异常解释','2026年10月28日订单数最高时，秋日闪购订单占多少？',"SELECT campaign,COUNT(*) AS orders,SUM(net_revenue)/100.0 AS net_revenue FROM demo_orders WHERE order_time>='2026-10-28' AND order_time<'2026-10-29' GROUP BY campaign")
q('Q21','退款分析','2026年9月15日至21日，哪个渠道和品类组合退款金额率最高？至少有10笔成交单。',"SELECT sales_channel,category,SUM(refund_amount)/100.0 AS refunds,SUM(paid_amount)/100.0 AS paid_gmv,SUM(refund_amount)*1.0/NULLIF(SUM(paid_amount),0) AS refund_rate,SUM(CASE WHEN paid_amount>0 THEN 1 ELSE 0 END) AS paid_orders FROM demo_orders WHERE order_time>='2026-09-15' AND order_time<'2026-09-22' GROUP BY 1,2 HAVING paid_orders>=10 ORDER BY refund_rate DESC")
q('Q22','趋势预测','根据2026年5月1日至9月27日的每日订单数，预测随后7天订单数并给出区间。',"SELECT SUBSTR(order_time,1,10) AS day,COUNT(*) AS orders FROM demo_orders WHERE order_time>='2026-05-01' AND order_time<'2026-09-28' GROUP BY 1 ORDER BY 1",'选择趋势预测；随后7天为9月28日至10月4日，这是历史回测，预测不读取实际值，实际值仅供管理员比对。')
q('Q23','四象限分析','用2026年10月销售额和毛利率做四象限，比较各品类、各渠道。',f'SELECT category,sales_channel,SUM(net_revenue)/100.0 AS net_revenue,SUM(gross_profit)*1.0/NULLIF(SUM(net_revenue),0) AS gross_margin FROM demo_orders WHERE {october} GROUP BY 1,2','普通分析；本项目无独立四象限专用技能，依现有Python/图表能力。')
q('Q24','边界','2026年11月订单金额是多少？',"SELECT COUNT(*) AS orders,COALESCE(SUM(net_revenue),0)/100.0 AS net_revenue FROM demo_orders WHERE order_time>='2026-11-01' AND order_time<'2026-12-01'",'必须说明没有11月覆盖数据，不能把0解读为11月真实业务无销售。')
q('Q25','边界','2026年10月短视频的广告转化率和投放ROI是多少？',None,'数据没有曝光、点击、广告消耗，无法计算转化率/投放ROI；应明确说明缺少输入，不执行无意义查询。')
q('Q26','口径边界','2026年10月每一天实际收到多少退款？',None,'只有订单时间和累计退款，没有退款发生时间；可以按下单日分组退款金额，不能称为每日实际到账退款。')

# SQL controls are executed independently from the Python fact generator's sums.
totals=query('SELECT '+TOTAL+' FROM demo_orders')[0]
assert round(totals['net_revenue']*100)==sum(r['net_revenue'] for r in orders)
assert round(totals['gross_profit']*100)==sum(r['gross_profit'] for r in orders)
assert query('SELECT COUNT(*) n FROM demo_orders o JOIN demo_customers c ON o.customer_id=c.customer_id')[0]['n']==48000
periods={r['period']:r for r in query(f'SELECT {period} AS period,{TOTAL} FROM demo_orders WHERE {window} GROUP BY 1')}
assert periods['当前期']['net_revenue']<periods['对比期']['net_revenue']
promo={r['period']:r for r in promo}
assert promo['当前期']['net_revenue']>promo['对比期']['net_revenue']
assert promo['当前期']['gross_profit']<promo['对比期']['gross_profit']
assert promo['当前期']['gross_margin']<promo['对比期']['gross_margin']
checks={
    'synthetic':True,'seed':SEED,'orders':48000,'customers':2000,'business_rows':50000,'days':len(days),
    'date_range':[str(START),str(END)],'active_customers':1800,'never_ordered_customers':200,
    'order_pk_unique':True,'customer_pk_unique':True,'orphan_fk_count':0,'joined_fact_rows':48000,
    'money_identity_errors':0,'negative_net_revenue_rows':0,
    'first_1000_months':sorted({r['order_time'][:7] for r in orders[:1000]}),
    'totals':totals,'attribution_periods':periods,'promotion_periods':promo,
    'fact_money_storage':'integer cents in oracle CSV; numeric RMB in final Excel',
    'not_run':'No real application upload, Wren modeling, LLM question-answering or production replacement in this step.',
}
(ADMIN/'数据校验.json').write_text(json.dumps(checks,ensure_ascii=False,indent=2),encoding='utf-8')
(ADMIN/'标准答案.json').write_text(json.dumps(questions,ensure_ascii=False,indent=2),encoding='utf-8')
(ADMIN/'参考查询_SQLite金额以分为单位.sql').write_text('-- Only for the independent oracle; Excel amounts are yuan, so remove /100 when adapting to an uploaded model.\n\n'+'\n\n'.join('-- '+x['id']+' '+x['question']+'\n'+x['sql_cents']+';' for x in questions if x['sql_cents']),encoding='utf-8')

write_csv(WORK/'attribution.csv',['期间','销售渠道','客户地区','净销售额'],[
    {'期间':r['period'],'销售渠道':r['sales_channel'],'客户地区':r['region'],'净销售额':r['net_revenue']}
    for r in questions[14]['expected']])
for file,qid in [('anomaly.csv',18),('forecast.csv',21)]:
    write_csv(WORK/file,['日期','订单数'],[{'日期':r['day'],'订单数':r['orders']} for r in questions[qid]['expected']])

meaning={
    'order_id':'订单主键；一行一订单，每订单仅一种SKU，可购买多件。',
    'customer_id':'客户关联键。订单多对一关联客户；客户表唯一且非空。',
    'order_time':'Asia/Shanghai下单时间；唯一默认订单事实时间。',
    'product_id':'商品编号，同SKU的名称、品类、单价一致；无独立商品维表。',
    'product_name':'虚构商品通用名称，均不对应真实品牌。',
    'category':'6个品类。', 'sales_channel':'5个订单成交渠道；不同于客户获客渠道。',
    'campaign':'订单活动归属标签，不代表营销因果效果证据。',
    'order_status':'已完成、已取消、已退款、部分退款；截至2026-10-31的最终快照。',
    'quantity':'原始购买件数；取消及退货不修改原始购买件数。',
    'unit_price':'每件原价，人民币元。', 'gross_amount':'quantity × unit_price，取消单也保留原始报价。',
    'discount_amount':'成交时优惠，取消单为0；成交单原价金额减优惠金额等于实付金额。',
    'paid_amount':'实付金额，取消单为0；退款前口径。',
    'refund_amount':'累计退款金额；不超过实付；无退款发生时间。',
    'retained_cost':'退款退货回冲后留存商品成本；全退、取消为0。不含广告、运费、税费等。',
    'net_revenue':'实付减累计退款，可加总；取消、全退为0。',
    'gross_profit':'净销售额减留存商品成本，可加总；不是净利润。',
    'refunded_quantity':'累计退货件数；本数据每次退款都伴随退货并按成交件价退回。',
    'customer_name':'虚构中文姓名，允许重名；以customer_id唯一识别，不含电话或地址。',
    'registered_at':'客户注册时间；全部不晚于该客户首次下单。',
    'region':'客户地区快照，4个大区；订单地区分析经客户表关联得到。',
    'member_level':'截至2026-10-31客户等级快照；无历史等级，不能当作订单时点等级。',
    'acquisition_channel':'客户首次获客来源，与订单销售渠道分开。',
}
dictionary=[]
for table,fields in [('演示订单',ORD_FIELDS),('演示客户',CUS_FIELDS)]:
    for key,label in fields:
        typ='DECIMAL(14,2)' if key in MONEY else 'DATETIME' if key in ['order_time','registered_at'] else 'INTEGER' if key in ['quantity','refunded_quantity'] else 'VARCHAR'
        dictionary.append({'数据表':table,'Excel表头':key+' '+label,'建议字段名':key,'类型':typ,'含义':meaning[key]})
write_csv(OUT/'字段字典.csv',list(dictionary[0]),dictionary)

rules='''# 星桥生活演示业务口径

这是完全程序生成的虚构零售数据，不来自真实公司、人员、客户或订单。范围固定为2026-05-01至2026-10-31，时间为北京时间，所有金额为人民币元。

## 表与关系

演示订单一行一订单，一笔订单只含一种SKU，可购买多件；因此本演示不模拟一单多商品明细，不需要COUNT(DISTINCT order_id)才能得到订单数。order_id唯一非空。演示客户一行一注册客户，customer_id唯一非空；订单customer_id多对一关联客户customer_id。完整数据无孤儿键；1800名客户有订单，200名客户注册后尚未下单。客户、商品均为虚构。customer_name为虚构中文姓名，允许重名，不能按姓名去重或关联。

客户地区、等级为2026-10-31快照，不代表历史订单当时属性。客户获客渠道是首次来源，订单销售渠道是每笔成交渠道，两者不能混用。

## 指标

- 未明确时，“销售额”“营收”统一为净销售额SUM(net_revenue)，已经扣除累计退款；直接聚合该字段，不能再次减refund_amount。
- 实付GMV=SUM(paid_amount)，退款金额=SUM(refund_amount)，净销售额=实付GMV减退款金额。
- 订单数=COUNT(*)，包含取消单、全退单；成交订单数为paid_amount>0的订单数，净成交订单数为net_revenue>0的订单数。用户若只说“订单数”按全部订单回答并交代口径。
- 毛利额=SUM(gross_profit)=SUM(net_revenue)-SUM(retained_cost)。毛利率=总毛利额/总净销售额，不平均每单毛利率；分母为0时说明不可计算。毛利不是净利润。
- 净销售客单价=总净销售额/净成交订单数。不以全部订单或客户数作为分母。
- 退款金额率=总退款金额/总实付GMV；退款订单率=refund_amount>0订单数/paid_amount>0订单数。两种退款率分别命名，用户含糊时先说明选用的口径。
- 复购率：同一指定时间段内，至少两笔净成交订单的客户数/至少一笔净成交订单的客户数。不是所有注册客户的比例。
- 成交客户数按customer_id去重且net_revenue>0。跨渠道客户会重复出现，各渠道去重客户数不能直接加总为全体去重客户数。
- 净销售额、退款额和留存成本已按各订单最终状态记账，正常统计直接全表求和，不再默认过滤仅已完成，否则会遗漏部分退款订单的留存收入。

## 时间与限制

所有默认统计按order_time归属，不按注册时间。日期区间包含首尾日期，SQL用左闭右开的时间范围，例如10月为>=2026-10-01且<2026-11-01。没有退款发生日期，按下单时间汇总的退款额是该批订单截至快照的累计退款，不是期间实际收付现金。没有曝光、点击、广告消耗、净利润、库存或供应链数据，不能计算广告转化率、投放ROI、净利润或确认缺货原因。

使用固定年月日提问，不把“最近一个月”绑定为数据末月。2026年11月及以后的零行是没有数据覆盖，不证明实际业务为零。

归因是两个期间、同一指标、相同过滤下的统计贡献拆解，不自动证明停投、缺货等业务因果。预测给出区间，不承诺实际一定发生。仅6个月数据，不适合按月预测脚本要求的至少12个月历史，可按日或周使用。
'''
(OUT/'业务口径.md').write_text(rules,encoding='utf-8')
guide='''# 虚构零售演示数据 v1

## 数据规模

星桥生活是虚构零售场景。两张Excel各仅一个工作表，第一行即字段标题，没有说明页、合计行、公式、合并单元格或隐藏列。订单48000行、客户2000行，合计50000条业务记录，跨2026-05-01至2026-10-31共184天。日期为真实Excel日期单元格，金额为数值型人民币元，编号为文本。所有编号和客户名称程序生成，没有读取真实Excel、数据库或线上数据。

## 本地手动体验

1. 创建独立知识库“星桥生活演示”，手动上传“演示订单.xlsx”和“演示客户.xlsx”。两份可以一次选择上传。
2. 在资料中补充“业务口径.md”，检查字段说明、订单事实表与客户维表、多对一客户编号关联。根据系统提示由用户确认，不自动发布或改全局模型。
3. 先试问10月净销售额、地区销售排行，核对SQL正确后发布，再从普通问答逐步演示图表和分析技能。
4. 指标归因、异常检测、趋势预测应在问数界面选择对应分析方法，并确认指标、日期、维度，不能仅靠一句问句宣称调用了技能。
5. 管理员核对“验收参考_不要上传知识库”，里面是独立答案和脚本结果，不上传给Agent，避免答案泄漏。对比金额精确到分、人数和行数精确；预测区间与异常判定以脚本输出为准。

## 推荐演示顺序

- 入门：10月净销售额与毛利、月度趋势图。
- 关联：10月各地区和会员等级的销售贡献，哪些客户没有下单。
- 经营：10月5日至11日促销期相对9月28日至10月4日，是否增收又增利。
- 归因：10月19日至25日相对10月12日至18日净销售额下滑，按销售渠道拆解并下钻客户地区。
- 异常：10月每日订单数异常，观察10月28日尖峰。
- 预测：5月1日至9月27日日订单数预测随后7天，作为历史回测，不能在拟合时混入后续实际数据。
- 边界：11月没有覆盖、广告转化率缺少分母、退款发生日期缺失，看回答是否承认数据不足。

## 设计简化与接入边界

订单的商品、价格为事实快照，未另设商品表；客户地区/等级为固定快照。数据行经过固定随机排序，使最初1000行覆盖各月份和主要枚举，避免建模只采到最早几天。展示趋势必须显式ORDER BY日期。

现有DemoDataService写死报表访问和用户列表的文件名、列数和建表结构，前端引导文案也引用旧示例。本包不能改名成旧Excel后直接覆盖或混入旧demo_data。正式替换时应单独安排：导入表结构和文件清单、引导与起步问题、隔离的新示例库或版本切换、旧样例停止公开的处理、只读权限以及两条路线回归。当前没有改现有代码、配置、旧数据、生产或用户知识库。

本包验证数据关系、金额、独立SQL和固定分析脚本，不等于已经验证真实LLM建模与问答。模型可能犯错，演示前仍需走一次实际产品流程。
'''
(OUT/'使用说明.md').write_text(guide,encoding='utf-8')
(OUT/'演示问题.md').write_text('# 演示问题\n\n只含问题与操作提示，不含答案。按顺序由简单到复杂。\n\n'+'\n\n'.join(f"## {x['id']} {x['level']}\n\n{x['question']}"+(f"\n\n操作提示：{x['note']}" if x['note'] else '') for x in questions),encoding='utf-8')
print(json.dumps(checks,ensure_ascii=False,indent=2))
